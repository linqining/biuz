// BiuZ 小程序入口。
// 与 web 手机壳（ZCode/packages/ui/src/WebRemoteControlMobileShell.tsx）同构：
// 只有 home（pages/sessions）与 chat（pages/chat）两级主壳，设置/文件等为二级页。
// 冷启动不做假数据装配：未连接一律空态，连上桌面后由 RPC 回执填充。
App({
  globalData: {
    // 连接态由 utils/connection.js 管理；此处仅保存跨页只读快照
    connection: null,
  },

  onLaunch() {
    // 页面栈上限 10 层（微信限制）：超过后 reLaunch 而非 navigateTo，见 utils/nav.js
  },

  onError(err) {
    // 兜底：不静默吞错，交给 error-line 组件上屏（web 侧同口径）
    console.error("[BiuZ] app error", err);
  },
});
