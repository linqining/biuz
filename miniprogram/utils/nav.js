// APP-DIFF[D005] platform: 微信无浏览器历史，web 的 history.pushState/popstate 映射为
// 页面栈纪律（上限 10 层，超限 reLaunch）+ 各页 wx.navigateBack 自定义返回（fail 回落 reLaunch）。
// 页面栈纪律：微信上限 10 层。超过阈值改用 reLaunch，避免静默失败。
// 对应 web 侧 history.pushState 的语义（WebRemoteControlMobileShell.tsx:28-38）。
const MAX_DEPTH = 10;

function push(url) {
  const pages = getCurrentPages();
  if (pages.length >= MAX_DEPTH - 1) {
    wx.reLaunch({ url });
    return;
  }
  wx.navigateTo({ url });
}

// 测试缝：仅开发者工具/automator 生效，正式用户路径不可达。
function acceptanceRoute(key, routes) {
  const url = routes[key];
  if (!url) {
    console.warn("[BiuZ] unknown acceptance key", key);
    return false;
  }
  wx.reLaunch({ url });
  return true;
}

module.exports = { push, acceptanceRoute, MAX_DEPTH };
