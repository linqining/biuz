# 冷启动：web 项目 → App 首次移植（bootstrap）

> 输入口令："把 XX 网站做成 App / 第一次移植 / 从零开始移植 / 冷启动"。
> 目标：把一个 web 项目完整移植为原生 App。核心思想：**移植不是一口气重写，
> 而是把「每页一闭环」的同步流水线按波次铺到全站**——先建索引和公共地基，
> 再按入口优先级逐页走 9 步流水线（见 `sync-workflow.md`）。

## 0. 前置判定

先弄清三件事（用户没说就问，不要猜）：

1. **web 源**：仓库在哪（`web.repo`）、有没有可访问的已部署站（`web.deployed_base`）、
   能否本地起 dev server（`web.local_dev_base`）。没有源码只有线上站 = 黑盒还原，
   采证只能靠截图+网络，质量降级，要先向用户说明。
2. **目标平台**：iOS（SwiftUI）/ Android（Compose）/ 微信小程序（原生 WXML/WXSS，
   或 Taro/uni-app 跨端框架）是内置样板路径；其他平台（Flutter/RN/鸿蒙等）流程同理，
   工程细节自行适配，填进 `app.platforms`。小程序目标另读
   `platform-differences.md` §5（域名白名单/rpx/页面栈在冷启动就要定）。
3. **App 工程**：已有工程（填 `app.<platform>.dir`）还是全新创建（由你或用户起工程骨架）。

## 1. 建配置

```bash
cp <skill>/assets/config.sample.yaml  config/web-to-app.yaml
cp <skill>/assets/page-index.sample.json  config/page-index.json   # pages.index 的默认路径
```

按项目实际改值：`web.*`（仓库/站点）、`capture.*`（视口按 web 主力机型定，
默认 iPhone 390×844@3x）、`app.*`（平台与工程目录）。

## 2. 页面盘点 → 页面索引

从 web 源码找路由表（框架路由配置 / 目录约定 / 导航 map），列出全部路由，
然后**逐页采证一轮**（`capture.py shot`，见 `capture-pipeline.md`），确认：

- 路由真实可达、需要什么登录态（游客/登录/特定角色）
- 是否 canvas/WebGL 页（`canvas: true`，DOM 采不到画面）
- 带参路由的真实可用参数（详情页 id、筛选参数等——找生产日志或问用户，不造参数）

产出：`config/page-index.json`，每页一条 `{key, route, app: {各端验收缝值}, canvas?, note?}`。
key 命名 `NNN-名称`（编号保证排序与唯一）。**这一步是后续一切流程的地基，
宁慢勿漏**。

## 3. 公共件矩阵（防按页重复实现或全漏）

web 里单实现、多页复用的东西，App 侧也必须单实现。横切盘点并建一张矩阵：

| 公共件 | web 源位置 | App 侧单实现位置 | 状态 |
|---|---|---|---|
| 弹层壳（modal/sheet/toast） | | | TODO |
| 金额/日期/数字格式化 | | | TODO |
| 空态/加载态/错误态组件 | | | TODO |
| 头像/图片组件（占位/圆角/缓存） | | | TODO |
| 网络层与错误处理 | | | TODO |
| 路由/深链表 | | | TODO |
| 设计 token（颜色/字号/间距） | | | TODO |

每移植一页，先查这张矩阵：用到公共件先补公共件，再拼页面。

## 4. 摸清 web 技术栈（决定换算与对接方式）

移植前必须搞清，否则后面全是返工：

- **适配栈**：设计稿基准宽度、rem/px/缩放变量机制（见 `ui-parity.md` §1）
- **状态管理与数据流**：全局 store、请求层、缓存策略
- **协议**：REST/gRPC/WebSocket；接口契约从源码 service 层读，必要时网络采证对账
- **i18n**：语言包在哪、**权威源是哪份**（远端语言包 vs 仓库兜底文件——兜底文件缺键
  ≠ web 无文案，先确认再下结论）
- **动效技术**：CSS/JS 动画库/Lottie/视频（见 `interaction-and-motion.md` §动画）

## 5. 验收缝（每页一个直达入口）

给每页建**仅测试生效、零用户影响**的直达入口，App 实拍不靠真实点击链：

- iOS：启动参数 `-acceptance-route <key>`（tab 页 `-acceptance-tab <n>`）
- Android：深链 `<scheme>://app/<path>` + `--es acceptance_route <key>`
- 微信小程序：页面路径直达（`app.miniprogram` = `pages/xx/xx`）——开发者工具
  「编译模式」自定义启动页面+参数，或 automator `reLaunch(url)`
- 其他平台：等价的启动参数/环境变量

缝值写进 page-index 的 `app` 字段。**seam 只解决"到页即得"，交互链验收必须
补真实入口端到端**（见 `acceptance.md` §测试缝边界）。

## 6. 分波次移植

| 波次 | 内容 | 理由 |
|---|---|---|
| W1 | 主 Tab/首页框架 + 路由骨架 + 公共件第一批 | 一切页面的容器 |
| W2 | 高频核心页（登录/列表/详情主链路） | 用户主路径 |
| W3 | 弹窗/抽屉/设置等次级页 | 补全功能面 |
| W4 | 长尾页、深链带参页、异常态打磨 | 收尾 |

**每页走完整 9 步流水线**（`sync-workflow.md`）：采证→契约→实现→对拍→实拍→登记，
页页清零再进下一页。冷启动期间 `sync_state.py` 的基线推进粒度可以是"一轮波次"
（begin 记 web HEAD，done 时推进），不必每页一次。

## 7. 样板页纪律

**第一个页面（通常 W1 首页）必须走通全流水线作为样板**：产出该页的
brief/file-map/popup-inventory/motion-spec/对拍向量/实拍对比报告，后续页面照抄结构。
样板页偷懒 = 全站返工。

## 8. 收尾验收

全站移植完成后跑一轮全项目验收（`acceptance.md`）：逐页多端对比、多机型矩阵、
差异标记对账、BACKLOG 清点。冷启动结束的标志 = 页面索引全覆盖 + BACKLOG 无 P1。
