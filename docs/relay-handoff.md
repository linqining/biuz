# 云中继（remote/v4）对接交接材料

> 2026-10-03 · 由主会话实地侦察产出，供"云中继接入"工作流使用

## 1. 真实配对链接（用户从桌面端 3.14.4"远程控制"复制）

> **v1.5 二次修订掩码注（2026-10-04）**：本节链接原以完整明文留存（sid=d_5BHs…ud5v / hash=juuBpa…o4%3D 全值），独立审阅指出 hash 等同口令级敏感面不应留工作区文档，已按下例掩码（保留前后缀以备形态核对）；完整值仅存于桌面端配对记录与 /tmp 探针材料（后者列评审后清理项，见立项报告 §11 风险 21）。如需复跑验收，请用户在桌面端**重新生成链接**（重新生成即旧凭据失效，亦为唯一已知撤销路径）。

```
https://zcode.z.ai/remote/v4?sid=d_5BHs…（中段掩码）…ud5v&hash=juuBpa…（中段掩码）…o4%3D&t=1791033721496&mid=4499673e-d0f8-49ef-ada8-623c18767c24&name=MacBook-Pro-8.local&app_version=3.14.4
```

- `sid` + `hash`（URL 编码的 base64，=号转 %3D）是桌面会话凭据；`t` 是生成时间戳（可能有过期，先用 curl 探活）；`mid` 机器 id；`name` 机器名。
- **注意时效**：若探测返回跳登录/过期，需请用户在桌面端重新生成链接。

## 2. 已核实事实

1. `GET 该 URL → 200 text/html`（约 18KB）：是 React 网页壳，**不是 WebSocket 端点**；WS upgrade 探测也返回 200 HTML（ESA CDN 边缘，未透传 upgrade）。
2. 页面资源：`/remote/v4/3.14.4/assets/index-BO-TaBle.js`（入口）+ chunk/preload-helper/bundle-mjs/jsx-runtime/react/utils/button 等。**真实的 WS 端点、握手、鉴权方式（sid/hash 如何用）、v4 帧封装都在这些 JS 里**（可能被打包混淆，需在 bundle 里搜 `wss`、`WebSocket(`、`/remote/`、`sid`、`hash`）。
3. 开源仓库 `packages/client/src`：客户端 = WebSocket 承载 VSBuffer/SocketProtocol RPC（`websocket.ts`），`RemoteServiceAccess` 暴露服务代理。桌面如何向云端注册 remote 会话未在开源范围（`packages/desktop/src/main` 为 Electron 管线）。
4. iOS 现状：`ios/ZCodeMobileApp/Sources/Services/RPC/`（ChannelClient/RPCSerialization/V4Wire）+ `Stores/Remote/*` 已实现 v4 帧订阅与只读会话（对替身验证过）；`ConnectURLParser` 目前只认 host:port+token 直连，**不认识中继链接**；`AppSession.connect` 先 HTTP 探测 /api/server-info 再 WS——中继模式需要跳过/改写这两步。
5. 只读边界（v1.3/v1.4 既定）：连接态 execution 类命令在 V4Wire/RPC 出口拦截——中继接入必须复用同一拦截表。

## 3. 实施建议（供对照）

1. 解析分支：`ConnectURLParser` 增加 relay 识别（https + path 前缀 `/remote/`）→ RelayConfig{wssURL(https→wss，query 原样保留), machineName}。
2. 连接路径：`AppSession` 增加 relay 模式——URLSessionWebSocketTask 直连 wssURL（跳过 /api/server-info HTTP 探测），后续走既有 ChannelClient/V4Wire；握手细节以 JS 逆向结论为准。
3. UI：L1 支持粘贴中继链接（剪贴板一键填充已有）＋"云中继"标识；演示模式与局域网直连模式行为不变。
4. 真机验证：`xcrun simctl launch booted cn.biuz.mobile -ZCodeRelayLink "<链接>"`（需在 AppSession.bootstrap 加该调试钩子，模式同 -ZCodeOpenConnectFlow）→ 截图断言真实会话行出现。
