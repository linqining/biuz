// APP-DIFF[D003] platform: web 用 dvh 视口高度（含地址栏收放）；微信自定义导航
// （navigationStyle: custom）需自己让出状态栏，统一走 wx.getWindowInfo() 取 statusBarHeight。
//
// 布局工具：自定义导航（navigationStyle: custom）需要自己让出状态栏。
//
// 为什么不用 CSS env(safe-area-inset-top)：微信开发者工具/部分机型里该值为 0，
// 页面内容会顶到状态栏（时间/电量）下面。统一走 wx.getWindowInfo()。

let cached = null;

/** 状态栏高度（px）。读取失败回落 20px（iOS 常见值），不让内容压到状态栏。 */
function statusBarHeight() {
  if (cached !== null) return cached;
  let height = 0;
  try {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    height = info.statusBarHeight || 0;
  } catch (err) {
    height = 0;
  }
  cached = height > 0 ? height : 20;
  return cached;
}

module.exports = { statusBarHeight: statusBarHeight };
