// 小程序片三 automator 实测（node /tmp/mp-automator/wave3-test.js）
// ——真 wx 运行时：acceptance 缝直连替身桌面 → sessions 页真实数据渲染断言 + 截图。
// 前置：tools/stub-desktop.js --port 9100 已起；开发者工具 auto --auto-port 9420 已起；
// 运行：NODE_PATH=/tmp/mp-automator/node_modules node miniprogram/tests/automator-wave3.js
// （.js 非 .test.js——不进纯 node 套件循环，需开发者工具环境）
const automator = require('miniprogram-automator');

const STORE_PAGE = '/pages/sessions/sessions';

async function main() {
  const miniProgram = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' });
  console.log('[t] automator connected');

  // 重置到会话页并直连替身（acceptance 缝绕原生弹窗）
  await miniProgram.reLaunch(
    STORE_PAGE + '?acceptance=1&connect=' + encodeURIComponent('http://127.0.0.1:9100'));
  await new Promise(function (r) { setTimeout(r, 3000); });

  // 断言：轮询 store（evaluate 直读，时序稳）；渲染断言用页面 sections
  const deadline = Date.now() + 10000;
  let storeState = null;
  while (Date.now() < deadline) {
    storeState = await miniProgram.evaluate(function () {
      const store = require('utils/store.js');
      const s = store.getState();
      return { status: s.status, rows: s.rows.length, deviceName: s.deviceName,
        titles: s.rows.map(function (r) { return r.title; }) };
    });
    if (storeState.status === 'connected' && storeState.rows >= 2) break;
    await new Promise(function (r) { setTimeout(r, 300); });
  }
  console.log('[t] store =', JSON.stringify(storeState));
  if (!storeState || storeState.status !== 'connected') {
    throw new Error('store 未点亮：' + JSON.stringify(storeState));
  }
  if (storeState.deviceName !== 'MP Stub Desktop') {
    throw new Error('deviceName 注入失败: ' + storeState.deviceName);
  }
  if (storeState.rows < 2) throw new Error('应有 2 条会话行');
  if (storeState.titles.indexOf('重构会话持久层') < 0) throw new Error('数据缺「重构会话持久层」');
  const page = await miniProgram.currentPage();
  const data = await page.data();
  const titles = [];
  (data.sections || []).forEach(function (s) {
    (s.items || []).forEach(function (it) { titles.push(it.title); });
  });
  console.log('[t] rendered section titles =', JSON.stringify(titles));
  if (titles.indexOf('重构会话持久层') < 0) throw new Error('渲染缺「重构会话持久层」');
  if (titles.indexOf('生成周报 · 第 40 周') < 0) throw new Error('渲染缺「生成周报 · 第 40 周」');

  // 截图存证
  await miniProgram.screenshot({ path: '/tmp/mp-wave3-sessions.png' });
  console.log('[t] screenshot → /tmp/mp-wave3-sessions.png');

  console.log('AUTOMATOR-OK');
  await miniProgram.disconnect();
  process.exit(0);
}

main().catch(function (err) {
  console.error('AUTOMATOR-FAIL:', err.message);
  process.exit(1);
});
