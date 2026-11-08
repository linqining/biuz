#!/usr/bin/env python3
# capture.py — web-to-app 统一浏览器采证 CLI（skill: web-to-app）
#
# 自包含 CDP 客户端（python3 + websocket-client，不依赖 node）。
# 仿真规格默认 iPhone 390x844@3x + iOS 17.5 UA + 触控 + 屏幕竖屏补丁，
# 全部可在 config/web-to-app.yaml 的 capture.* 调整（改配置即生效）。
#
# 常用：
#   python3 capture.py chrome                          # 确保调试 Chrome 在跑（默认 9224）
#   python3 capture.py status                          # 探活 + 登录态
#   python3 capture.py pages                           # 列页面索引 key
#   python3 capture.py shot 001-首页                   # 按 key 采证（已部署站）
#   python3 capture.py shot /items --base local        # 按路由采本地 dev server
#   python3 capture.py shot 004-画布页 --dom-stable    # canvas/WebGL 页走 DOM 锚稳定
#
# 输出：<项目根>/.capture/runs/<YYYYMMDD-HHMMSS>-<name>/（截图 + meta.json + images-final.json，可选 dom.html）
# 交互驱动（点击后截图等）可 import 本文件的 CDP 类，见 references/interaction-and-motion.md。
import argparse
import base64
import json
import shutil
import subprocess
import sys
import threading
import time
import queue
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import skill_common as common  # noqa: E402

try:
    import websocket  # websocket-client
except ImportError:
    websocket = None  # 只在需要连 CDP 的子命令里报错，pages/chrome 不受影响

CFG = common.load_config()
CAP = CFG["capture"]
PORT = CAP["port"]
PROFILE = CAP["profile"] or Path(".capture/chrome-profile")
RUNS = CAP["runs"] or Path(".capture/runs")
VW, VH, DPR = CAP["width"], CAP["height"], CAP["dpr"]
INDEX = CFG["page_index"]

BASES: dict[str, str | None] = {"deployed": CFG["deployed_base"], "local": CFG["local_dev_base"]}
DEFAULT_UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
              "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1")
UA = CAP["ua"] or DEFAULT_UA
LOGIN_HINTS = CAP["login_hints"]

# rem 适配站点常按 screen.* 算根字号，宽窗口下会算错 —— 把 screen 伪造为仿真视口尺寸，
# 保证「窗口再宽，页面也按手机竖屏排版」。不用 rem 的站点无副作用。
SCREEN_PATCH = """(() => {
  const fakeOrientation = { type: 'portrait-primary', angle: 0, onchange: null,
    addEventListener() {}, removeEventListener() {}, dispatchEvent() { return true; },
    lock() { return Promise.resolve(); }, unlock() {} };
  const def = (obj, prop, getter) => { try { Object.defineProperty(obj, prop, { get: getter, configurable: true }); } catch (e) {} };
  def(screen, 'orientation', () => fakeOrientation);
  def(screen, 'width', () => %(w)d); def(screen, 'height', () => %(h)d);
  def(screen, 'availWidth', () => %(w)d); def(screen, 'availHeight', () => %(h)d);
  try { Object.defineProperty(window, 'orientation', { value: 0, configurable: true }); } catch (e) {}
})();""" % {"w": VW, "h": VH}


def _need_websocket():
    if websocket is None:
        sys.exit("缺依赖：websocket-client（pip3 install --user websocket-client）")


def http_json(path, timeout=5):
    with urllib.request.urlopen(f'http://127.0.0.1:{PORT}{path}', timeout=timeout) as r:
        return json.loads(r.read())


def chrome_alive():
    try:
        return http_json('/json/version').get('Browser', '?')
    except Exception:
        return None


def _launch_chrome():
    """拉起独立调试 Chrome（持久登录态 profile，不与日常浏览器抢会话）。"""
    PROFILE.mkdir(parents=True, exist_ok=True)
    cmd = None
    if sys.platform == 'darwin':
        cmd = ['open', '-na', 'Google Chrome', '--args',
               f'--remote-debugging-port={PORT}',
               f'--user-data-dir={PROFILE}',
               '--no-first-run', '--no-default-browser-check']
    else:
        for binname in ('google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser'):
            exe = shutil.which(binname)
            if exe:
                cmd = [exe, f'--remote-debugging-port={PORT}', f'--user-data-dir={PROFILE}',
                       '--no-first-run', '--no-default-browser-check']
                break
    if cmd is None:
        print(f'错误：未找到 Chrome/Chromium。请手动启动并带 '
              f'--remote-debugging-port={PORT} --user-data-dir={PROFILE}', file=sys.stderr)
        return 1
    subprocess.Popen(cmd)
    for _ in range(20):
        time.sleep(1)
        if chrome_alive():
            print(f'已拉起调试 Chrome（{PORT}，profile={PROFILE}）')
            print(f'注意：登录态保存在该 profile；首次使用需在弹出的窗口里登录 {BASES["deployed"] or "目标站点"}')
            return 0
    print(f'错误：20s 内 {PORT} 未就绪（Chrome 未安装或被策略拦截？）', file=sys.stderr)
    return 1


def cmd_chrome(args):
    b = chrome_alive()
    if b:
        print(f'Chrome 调试实例已在 {PORT} 端口运行（{b}）')
        return 0
    return _launch_chrome()


class CDP:
    def __init__(self, url_filter=None):
        _need_websocket()
        pages = [t for t in http_json('/json/list')
                 if t.get('type') == 'page' and not t['url'].startswith('devtools')]
        if not pages:
            raise RuntimeError(f'{PORT} 上没有可用的 page target（先开一个标签页）')
        page = next((p for p in pages if url_filter and url_filter in p['url']), pages[0])
        self.page = page
        self.ws = websocket.create_connection(page['webSocketDebuggerUrl'], timeout=30,
                                              max_size=None, suppress_origin=True)
        self.mid = 0
        self.pending = {}
        self._lock = threading.Lock()
        self._run = True
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while self._run:
            try:
                raw = self.ws.recv()
                if not raw:
                    break
                m = json.loads(raw)
                if 'id' in m:
                    with self._lock:
                        cb = self.pending.pop(m['id'], None)
                    if cb:
                        cb(m)
            except Exception:
                break

    def send(self, method, params=None, timeout=30):
        with self._lock:
            self.mid += 1
            i = self.mid
            fut = queue.Queue(1)
            self.pending[i] = fut.put
        self.ws.send(json.dumps({'id': i, 'method': method, 'params': params or {}}))
        try:
            m = fut.get(timeout=timeout)
        except queue.Empty:
            raise TimeoutError(method)
        if 'error' in m:
            raise RuntimeError('%s: %s' % (method, json.dumps(m['error'])[:300]))
        return m.get('result', {})

    def eval_js(self, expr, timeout=30):
        r = self.send('Runtime.evaluate',
                      {'expression': expr, 'returnByValue': True, 'awaitPromise': True},
                      timeout=timeout)
        if 'exceptionDetails' in r:
            d = r['exceptionDetails']
            raise RuntimeError('eval: ' + ((d.get('exception') or {}).get('description')
                                           or d.get('text') or '')[:300])
        return r.get('result', {}).get('value')

    def emulate(self):
        self.send('Emulation.setDeviceMetricsOverride',
                  {'width': VW, 'height': VH, 'deviceScaleFactor': DPR, 'mobile': True})
        ua_params = {'userAgent': UA}
        if UA == DEFAULT_UA:
            ua_params.update({'platform': 'iPhone', 'platformVersion': '17.5.0'})
        self.send('Emulation.setUserAgentOverride', ua_params)
        self.send('Emulation.setTouchEmulationEnabled', {'enabled': True, 'maxTouchPoints': 5})
        self.send('Page.enable')
        self.send('Page.addScriptToEvaluateOnNewDocument', {'source': SCREEN_PATCH})

    def navigate(self, url):
        try:
            self.send('Page.navigate', {'url': url})
        except Exception:
            pass

    def shot(self, path):
        r = self.send('Page.captureScreenshot', {'format': 'png'})
        Path(path).write_bytes(base64.b64decode(r['data']))
        return path

    def click_text(self, text):
        """DOM 级点击（el.click()）：对视口外元素与主流框架 handler 比 CDP 触摸/鼠标注入可靠。"""
        r = self.eval_js(f"""(() => {{
          const els = Array.from(document.querySelectorAll('button, a, [role=button], input, div, span'));
          const el = els.find(e => e.innerText && e.innerText.trim().includes({json.dumps(text)}));
          if (!el) return 'NOT_FOUND';
          el.click(); return 'CLICKED';
        }})()""")
        return r

    def close(self):
        self._run = False
        try:
            self.ws.close()
        except Exception:
            pass

    def state(self):
        guest_js = 'null'
        if LOGIN_HINTS:
            guest_js = (f'new RegExp({json.dumps(LOGIN_HINTS)}, "i")'
                        f'.test((document.body && document.body.innerText || "").slice(0, 3000))')
        return json.loads(self.eval_js(
            'JSON.stringify({'
            ' u: location.href,'
            ' iw: innerWidth, cw: document.documentElement.clientWidth,'
            ' cv: (function(){try{return document.querySelectorAll("canvas").length}catch(e){return -1}})(),'
            ' len: (document.body && document.body.innerText || "").length,'
            f' guest: {guest_js},'
            ' conn: /Connecting|Refresh/i.test(document.body && document.body.innerText || "")'
            '})'))


def _slow_scroll(c):
    """慢速滚动到底触发 loading=lazy 再回顶。必须 behavior:'instant'：
    html/body 常见 scroll-behavior:smooth，普通 scrollTo 会变平滑动画，
    120ms 一档的节奏下实际位置永远到不了底，lazy 图一张都触发不了。"""
    c.eval_js('''(() => new Promise(res => {
      const d = document.documentElement;
      const step = Math.max(300, Math.round(innerHeight * 0.8));
      let y = 0;
      const t = setInterval(() => {
        y += step;
        if (y >= d.scrollHeight - innerHeight) {
          clearInterval(t);
          window.scrollTo({top: 0, behavior: 'instant'});
          setTimeout(res, 300);
        } else {
          window.scrollTo({top: y, behavior: 'instant'});
        }
      }, 120);
    }))()''', timeout=15)


def settle(c, budget_s=30, min_text=120):
    """渲染稳定三重门：
    ① 网络静默 + in-flight 检测：最近 900ms（performance.now 时钟）无完成资源，
       且无未完成的在途资源（responseEnd===0，限近 15s 发起的）；
    ② 慢速整页滚动到底再回顶，触发 loading=lazy；
    ③ 图片就绪门：document.images 逐一校验 complete && naturalWidth > 0 ——
       pending / complete 但 naturalWidth===0（坏图）都不放行；坏图原地不会自愈，
       计数连续多轮纹丝不动且网络静默时提前放行，交给截图前 broken 审计兜底。
    全程受 budget_s 约束，超时放行。文本少的页面（canvas 游戏/工具页）用 --min-text 降。
    时钟注意：resource timing 是 performance.now() 相对毫秒，不能与 Date.now() 混用
    （差值≈1.79e12，静默判断会恒为真）。跨域资源无 Timing-Allow-Origin 时
    responseStart 恒为 0，不能当 in-flight 依据；responseEnd 跨域完成后有值，
    ===0 才是真未完成 —— 加 15s 新鲜度窗排除埋点等永不结束的 beacon。"""
    t0 = time.time()
    deadline = t0 + budget_s
    # ①阶段只分到 60% 预算：常轮询的页面（行情流/聊天）永远到不了静默，
    # 必须给 ③图片就绪门留出保底窗口。
    gates_js = ("JSON.stringify({"
                "n: performance.getEntriesByType('resource')"
                ".filter(e => (e.responseEnd === 0 && performance.now() - e.startTime < 15000)"
                " || performance.now() - e.responseEnd < 900).length,"
                "p: Array.from(document.images).filter(i => i.src && !i.complete).length,"
                "b: Array.from(document.images).filter(i => i.src && i.complete"
                " && i.naturalWidth === 0).length})")

    def gates():
        d = json.loads(c.eval_js(gates_js))
        return d['n'], d['p'], d['b']

    quiet = 0
    while time.time() < t0 + budget_s * 0.6:
        time.sleep(0.7)
        n, p, _b = gates()
        quiet = quiet + 1 if (n == 0 and p == 0) else 0
        if quiet >= 2 and time.time() - t0 > 2.5:
            if c.state().get('len', 0) >= min_text:
                break
            quiet = 0
    # ② 首轮慢速滚动
    try:
        _slow_scroll(c)
    except Exception:
        pass  # 个别页面禁止滚动/超时，不阻塞采集
    # ③ 滚动后 lazy 资源开始加载 —— 静默 + 图片就绪复合门，直到 budget 用尽。
    #    网络静默但仍有未完成图片时，最多补两轮滚动：数据驱动的 section 在首滚
    #    之后才挂载 lazy img，不在视口内永远不触发加载。
    extra_scrolls = 2
    quiet = 0
    stall_prev, stall = None, 0
    while time.time() < deadline:
        time.sleep(0.7)
        n, p, b = gates()
        if n == 0 and p > 0 and quiet >= 2 and extra_scrolls > 0:
            extra_scrolls -= 1
            quiet = 0
            try:
                # 补一刀：横向 swiper 里被裁掉的 lazy slide 靠滚动永远触发不了，翻成 eager 强制起载
                c.eval_js("Array.from(document.images)"
                          ".forEach(i => { if (i.src && !i.complete) i.loading = 'eager'; }); 'ok'")
                _slow_scroll(c)
            except Exception:
                pass
            continue
        quiet = quiet + 1 if n == 0 else 0
        if p == 0 and b == 0:
            if quiet >= 2:
                break
        else:
            stall = stall + 1 if (p, b) == stall_prev else 0
            stall_prev = (p, b)
            if quiet >= 2 and stall >= 5:
                break  # 网络静默且就绪计数纹丝不动：本轮等待无效，坏图留给重采
    time.sleep(0.8)


# ---- 截图稳定门（settle 之后、正式截图之前）--------------------------------
# settle 只保证网络/图片就绪，防不住行情流/动画导致的像素抖动，也不防仿真丢失造成的
# 截图尺寸漂移（"缩放比例不对"的截图正是这种）。
# 像素门：连拍 probe 帧（默认 3，--stable-frames 可调，间隔 600ms），相邻帧差异像素
#   占比 >0.1% 视为未稳定，等待重试；总预算 15s 耗尽仍不收敛 → 取差异最小的一帧当
#   视口截图，stability="unstable"。
# DOM 锚门：canvas/WebGL 页节点常驻重绘，像素比对必不收敛 —— 对根元素 + body 直接
#   子元素 getBoundingClientRect 快照，连续两次一致即拍，stability="dom-stable"。
#   --dom-stable 直接走此门；像素门超时且页面存在 <canvas> 时自动切换。
STABLE_INTERVAL_S = 0.6
STABLE_BUDGET_S = 15
STABLE_MAX_RATIO = 0.001

DOM_SNAP_JS = '''(() => JSON.stringify({
  y: scrollY, sh: document.documentElement.scrollHeight,
  rects: [document.documentElement, document.body]
    .concat(Array.from(document.body.children))
    .map(el => {
      if (!el || !el.getBoundingClientRect) return '-';
      const r = el.getBoundingClientRect();
      return [r.x, r.y, r.width, r.height].map(v => Math.round(v * 10) / 10).join(',');
    })
}))()'''


def _require_pil(where):
    try:
        import PIL  # noqa: F401
    except ImportError:
        sys.exit(f'缺依赖：Pillow（pip3 install --user Pillow）—— {where}必需'
                 f'（canvas/WebGL 页可加 --dom-stable 免像素比对）')


def _png_diff(p1, p2):
    """两张 PNG 的像素 diff：返回 (差异占比, 差异 bbox, 尺寸是否不一致)。
    任一 RGB 通道非零即算差异像素；不用 convert('L') 直方图是因为单通道低位差
    （如 R=1）加权后会舍入成 0，会漏计。尺寸不一致按完全不稳处理（占比 1.0）。"""
    from PIL import Image, ImageChops
    a, b = Image.open(p1), Image.open(p2)
    if a.size != b.size:
        return 1.0, None, True
    d = ImageChops.difference(a.convert('RGB'), b.convert('RGB'))
    mask = d.point(lambda v: 255 if v else 0)  # 逐通道 0/255 化再统计，低位差不丢
    total = a.size[0] * a.size[1]
    changed = sum(mask.convert('L').histogram()[1:])
    return changed / total, mask.getbbox(), False


def _canvas_count(c):
    try:
        return int(c.eval_js("document.querySelectorAll('canvas').length") or 0)
    except Exception:
        return 0


def _dom_gate(c, t0):
    """DOM 锚框稳定：关键元素 rect 快照连续两次一致即收敛。返回 (是否收敛, 快照次数)。"""
    prev, snaps = None, 0
    while time.time() - t0 < STABLE_BUDGET_S:
        cur = c.eval_js(DOM_SNAP_JS)
        snaps += 1
        if prev is not None and cur == prev:
            return True, snaps
        prev = cur
        time.sleep(STABLE_INTERVAL_S)
    return False, snaps


def stability_gate(c, final_png, frames_n=3, force_dom=False):
    """截图稳定门。返回 (stability, probe)，并全权负责把视口截图写入 final_png：
    - stable     像素门收敛，正式截图；
    - dom-stable DOM 锚门收敛（canvas 页自动切换或 --dom-stable 强制）；
    - unstable   15s 预算内未收敛 —— 把差异最小的 probe 帧复制为 final_png。
    probe 帧落 final_png 同目录 .probe-frames/，用完即删，不进采集产物。"""
    tmp = Path(final_png).parent / '.probe-frames'
    tmp.mkdir(parents=True, exist_ok=True)
    start = time.time()
    try:
        if force_dom:
            ok, snaps = _dom_gate(c, time.time())
            c.shot(final_png)
            return ('dom-stable' if ok else 'unstable',
                    {'mode': 'dom', 'snapshots': snaps, 'converged': ok})
        _require_pil('像素稳定门')
        best_ratio, best_frame, last_ratios, size_mismatch = None, None, [], False
        while time.time() < start + STABLE_BUDGET_S:
            paths = []
            for i in range(frames_n):
                p = tmp / f'f{i}.png'
                c.shot(p)
                paths.append(p)
                if i < frames_n - 1:
                    time.sleep(STABLE_INTERVAL_S)
            ratios = []
            for i in range(frames_n - 1):
                r, _, sm = _png_diff(paths[i], paths[i + 1])
                size_mismatch = size_mismatch or sm
                ratios.append(r)
                if best_ratio is None or r < best_ratio:
                    best_ratio = r
                    best_frame = tmp / 'best.png'
                    shutil.copyfile(paths[i + 1], best_frame)  # 差异最小帧留作兜底截图
            last_ratios = ratios
            if max(ratios) <= STABLE_MAX_RATIO:
                meta = {'mode': 'pixel', 'frames': frames_n, 'intervalMs': 600,
                        'ratios': ratios, 'best': best_ratio, 'converged': True}
                if size_mismatch:
                    meta['sizeMismatch'] = True
                c.shot(final_png)  # 收敛即拍（与末帧 probe 同为静帧）
                return 'stable', meta
        # 像素门超时：canvas/WebGL 页常驻重绘必不收敛 → 自动切 DOM 锚门
        if _canvas_count(c) > 0:
            ok, snaps = _dom_gate(c, time.time())
            if ok:
                meta = {'mode': 'pixel→dom', 'frames': frames_n, 'intervalMs': 600,
                        'ratios': last_ratios, 'best': best_ratio, 'converged': False,
                        'domSnapshots': snaps, 'domConverged': True}
                if size_mismatch:
                    meta['sizeMismatch'] = True
                c.shot(final_png)
                return 'dom-stable', meta
        meta = {'mode': 'pixel', 'frames': frames_n, 'intervalMs': 600,
                'ratios': last_ratios, 'best': best_ratio, 'converged': False,
                'promoted': '差异最小 probe 帧'}
        if size_mismatch:
            meta['sizeMismatch'] = True
        shutil.copyfile(best_frame, final_png)  # 超时兜底：取差异最小的一帧
        return 'unstable', meta
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# 截图前 broken 审计：逐 img/背景图收集 rect/尺寸/完成态，broken 列表非空触发整页重采
IMAGES_AUDIT_JS = r'''(() => {
  const map = new Map();
  const r1 = n => Math.round(n * 10) / 10;
  const push = (src, el, kind, nw, nh, complete) => {
    if (!src || !src.startsWith || src.startsWith('data:')) return;
    const key = kind + '|' + src;
    let e = map.get(key);
    const r = el.getBoundingClientRect();
    if (!e) {
      let chain = '', p = el, i = 0;
      while (p && p.nodeType === 1 && i < 5) {
        let seg = p.tagName.toLowerCase();
        if (p.classList.length) seg += '.' + Array.from(p.classList).slice(0, 3).join('.');
        chain = seg + (chain ? ' < ' + chain : '');
        p = p.parentElement; i++;
      }
      e = { src, kind, count: 0, naturalWidth: nw, naturalHeight: nh, complete, cls: el.getAttribute('class') || undefined, chain, rect: { x: r1(r.x + scrollX), y: r1(r.y + scrollY), w: r1(r.width), h: r1(r.height) }, sizes: [] };
      map.set(key, e);
    }
    e.count++;
    if (nw > 0 && e.sizes.indexOf(nw + 'x' + nh) < 0) e.sizes.push(nw + 'x' + nh);
    if (nw === 0 && e.naturalWidth === null) { e.naturalWidth = 0; e.naturalHeight = 0; }
    if (nw > 0 && !e.naturalWidth) { e.naturalWidth = nw; e.naturalHeight = nh; }
    if (complete === true) e.complete = true;
  };
  for (const img of document.images) push(img.currentSrc || img.src, img, 'img', img.naturalWidth, img.naturalHeight, img.complete);
  for (const el of document.querySelectorAll('body *')) {
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') continue;
    if (s.backgroundImage && s.backgroundImage !== 'none') {
      for (const m of s.backgroundImage.matchAll(/url\(["']?([^"')]+)["']?\)/g)) {
        const r = el.getBoundingClientRect();
        if (r.width >= 2 && r.height >= 2) push(m[1], el, 'bg', null, null, null);
      }
    }
  }
  const list = Array.from(map.values());
  return JSON.stringify({
    url: location.href, total: list.length,
    broken: list.filter(e => e.kind === 'img' && e.naturalWidth === 0).map(e => e.src),
    list
  });
})()'''


def load_index():
    if not INDEX or not INDEX.is_file():
        sample = common.SKILL_DIR / 'assets' / 'page-index.sample.json'
        sys.exit(f'页面索引不存在：{INDEX}\n'
                 f'先复制 {sample} 到该路径并按项目填写（capture.py pages/shot 依赖它）。')
    return json.loads(INDEX.read_text(encoding='utf-8'))


def resolve_target(selector, base_name):
    """selector = 页面 key / 路由 / 完整 URL → (完整URL, 建议 name)"""
    base = BASES[base_name]
    if not base:
        sys.exit(f'config 缺少 web.{"deployed_base" if base_name == "deployed" else "local_dev_base"}；'
                 f'先在 config/web-to-app.yaml 补上再采证。')
    if selector.startswith('http://') or selector.startswith('https://'):
        url, name = selector, selector.rstrip('/').split('/')[-1] or 'page'
    else:
        idx = load_index()
        ent = None
        for e in idx['pages']:
            app_routes = (e.get('app') or {}).values()
            if selector.lower() in [e['key'].lower()] + [str(v).lower() for v in app_routes]:
                ent = e
                break
        if ent is None:  # 按路由前缀兜底
            for e in idx['pages']:
                if e.get('route') and selector.split('?')[0].rstrip('/') == e['route'].rstrip('/'):
                    ent = e
                    break
        if ent is None:
            keys = '\n  '.join(e['key'] for e in idx['pages'])
            sys.exit(f'未在页面索引中找到 {selector!r}。可用 key：\n  {keys}\n'
                     f'（或直接传路由 /items、完整 URL）')
        url = base + ent['route']
        name = ent['key']
    return url, name


def cmd_status(args):
    _need_websocket()
    b = chrome_alive()
    if not b:
        print(f'Chrome 调试实例未运行 —— 先执行: python3 capture.py chrome')
        return 1
    print(f'Chrome: {b} @ 127.0.0.1:{PORT}')
    try:
        pages = [t for t in http_json('/json/list') if t.get('type') == 'page']
        for p in pages:
            print(f"  target: {p['url'][:100]}")
        c = CDP()
        s = c.state()
        print(f"  当前页: {s['u']}")
        if s.get('guest') is None:
            login = '未知（config capture.login_hints 未配置）'
        else:
            login = '游客（未登录）' if s['guest'] else '已登录'
        print(f"  登录态: {login} ｜ 文本量 {s['len']} ｜ canvas {s['cv']}"
              f" ｜ viewport {s['iw']}x（应为 {VW}）")
        c.close()
    except Exception as e:
        print(f'  连接 target 失败: {e}')
    return 0


def cmd_pages(args):
    idx = load_index()
    platforms = CFG['platforms']
    header = f"{'key':<24} {'route':<34}" + ''.join(f' {p:<14}' for p in platforms) + ' canvas'
    print(header)
    for e in idx['pages']:
        app = e.get('app') or {}
        row = f"{e['key']:<24} {e.get('route', '-'):<34}"
        for p in platforms:
            row += f" {str(app.get(p, '-')):<14}"
        row += f" {e.get('canvas', '')}"
        print(row)
    print(f'base: deployed={BASES["deployed"]} ｜ local={BASES["local"]}')


def cmd_shot(args):
    _need_websocket()
    if not chrome_alive():
        sys.exit(f'Chrome 调试实例未运行 —— 先执行: python3 capture.py chrome')
    url, name = resolve_target(args.target, args.base)
    out = RUNS / f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{common.slugify(name, 'page')}"
    out.mkdir(parents=True, exist_ok=True)

    host = urllib.parse.urlparse(BASES[args.base]).netloc
    c = CDP(url_filter=host)

    def capture_once():
        """一次完整采集：导航 → settle → viewport 校验 → 图片审计（截图前）。
        返回 (state, images)。images = images-final.json 数据。"""
        c.emulate()
        c.navigate(url)
        settle(c, min_text=args.min_text)
        s = c.state()
        # viewport 校验：误差 >1px 重仿真重导航一次（防止仿真丢失导致整页缩放漂移）
        if abs(s['iw'] - VW) > 1 or abs(s['cw'] - VW) > 1:
            c.emulate()
            c.navigate(url)
            settle(c, min_text=args.min_text)
            s = c.state()
        imgs = json.loads(c.eval_js(IMAGES_AUDIT_JS))
        return s, imgs

    try:
        s, imgs = capture_once()
        status = 'OK'
        if imgs['broken']:
            # broken 非空 → 整页自动重采一次（不无限重试）；仍 broken 则标 UNVERIFIED 继续
            print(f"  broken 审计：{len(imgs['broken'])} 张坏图 → 整页自动重采一次")
            s, imgs = capture_once()
            if imgs['broken']:
                status = 'UNVERIFIED'
        # 视口截图前先过稳定门：连拍比对收敛才拍；canvas 页像素不收敛自动切 DOM 锚门
        vp = out / '01-viewport.png'
        stability, probe = stability_gate(c, vp, frames_n=max(2, args.stable_frames),
                                          force_dom=args.dom_stable)
        files = [vp]
        if args.scrolls > 0:
            height = c.eval_js('document.documentElement.scrollHeight')
            for k in range(1, args.scrolls + 1):
                y = VH * k
                if y >= height - 50:
                    break
                c.eval_js(f'window.scrollTo({{top: {y}, behavior: "instant"}}); "ok"')  # smooth 站须 instant
                time.sleep(0.9)
                files.append(c.shot(out / f'{k + 1:02d}-scroll{k}.png'))
            c.eval_js('window.scrollTo({top: 0, behavior: "instant"}); "ok"')
            time.sleep(0.5)
        if args.dom:
            (out / 'dom.html').write_text(
                c.eval_js('document.documentElement.outerHTML'), encoding='utf-8')
        (out / 'images-final.json').write_text(
            json.dumps(imgs, ensure_ascii=False), encoding='utf-8')
        meta = {'url': s['u'], 'requested': url, 'base': args.base,
                'guest': s.get('guest'), 'textLen': s['len'], 'canvasCount': s['cv'],
                'viewport': [s['iw'], s['cw']], 'connecting': s['conn'],
                'broken': len(imgs['broken']), 'status': status,
                'stability': stability, 'probe': probe,
                'time': datetime.now().isoformat(timespec='seconds'),
                'spec': f'{VW}x{VH}@{DPR} + touch + screen-patch'}
        (out / 'meta.json').write_text(json.dumps(meta, ensure_ascii=False, indent=1))
        print(f"采集完成 → {out}")
        for f in files:
            print(f'  {f.name}')
        print(f"  图片审计：total {imgs['total']} ｜ broken {len(imgs['broken'])}"
              f" ｜ status {status}")
        if probe.get('domSnapshots') or probe.get('mode') == 'dom':
            print(f"  稳定门禁：{stability}"
                  f"（DOM 锚快照 {probe.get('domSnapshots', probe.get('snapshots'))} 次）")
        else:
            rr = ' / '.join(f'{r * 100:.3f}%' for r in probe.get('ratios') or [])
            print(f"  稳定门禁：{stability}（相邻帧差 {rr or 'n/a'}"
                  f"{'，best ' + format(probe.get('best') * 100, '.3f') + '%' if probe.get('best') is not None else ''}）")
        if probe.get('sizeMismatch'):
            print('  警告：probe 帧尺寸不一致 —— 设备像素比/缩放仿真漂移，本图不可作基线，请重采')
        if stability == 'unstable':
            print('  警告：画面 15s 内未稳定 —— 已取差异最小的一帧（probe.best），建议稍后重采')
        if s.get('guest') is True:
            print('  注意：当前为游客态 —— 需登录态的页面请在调试 Chrome 里先登录')
        if s['conn']:
            print('  警告：页面文本含 Connecting/Refresh，可能未连上实时通道，建议重采')
        if status == 'UNVERIFIED':
            print('  UNVERIFIED：重采后仍有 broken 图片 —— 见 images-final.json 的 broken 列表，建议人工排查')
        return 0
    finally:
        c.close()


def main():
    p = argparse.ArgumentParser(description='web-to-app 浏览器采证 CLI（详见 skill web-to-app）')
    sub = p.add_subparsers(dest='cmd', required=True)
    sub.add_parser('chrome', help=f'拉起 {PORT} 调试 Chrome（持久登录 profile）')
    sub.add_parser('status', help='探活 + 当前页 + 登录态')
    sub.add_parser('pages', help='列出页面索引')
    s = sub.add_parser('shot', help='采集页面截图')
    s.add_argument('target', help='页面 key（001-首页）/ 路由（/items）/ 完整 URL')
    s.add_argument('--base', choices=list(BASES), default='deployed',
                   help='deployed=已部署站（默认） local=本地 dev server')
    s.add_argument('--scrolls', type=int, default=0, help='额外滚动截图屏数')
    s.add_argument('--dom', action='store_true', help='落 dom.html（渲染后 outerHTML）')
    s.add_argument('--min-text', type=int, default=120, help='渲染稳定的最少文本量（canvas 页可降）')
    s.add_argument('--stable-frames', type=int, default=3,
                   help='截图稳定门连拍帧数（默认 3，间隔 600ms；≥2）')
    s.add_argument('--dom-stable', action='store_true',
                   help='跳过像素比对，直接用 DOM 锚框稳定（canvas/WebGL 页推荐；'
                        '像素门 15s 超时且页面有 canvas 时也会自动切换）')
    args = p.parse_args()
    sys.exit({'chrome': cmd_chrome, 'status': cmd_status,
              'pages': cmd_pages, 'shot': cmd_shot}[args.cmd](args))


if __name__ == '__main__':
    main()
