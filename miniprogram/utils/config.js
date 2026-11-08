// 运行期配置。域名必须在微信公众平台后台登记为「合法域名」，否则真机请求被拦
// （见 references/platform-differences.md §5：request 域名 + socket 域名分开登记）。
module.exports = {
  // 远控中继（与 iOS/Android 同源，协议事实见 docs/协议接口文档.md）
  RELAY_WS_BASE: "wss://zcode.z.ai",
  RELAY_HTTP_BASE: "https://zcode.z.ai",

  // 页面 key → 路径映射，与 config/page-index.json 的 miniprogram 字段保持一致
  ROUTES: {
    "001-登录": "/pages/login/login",
    "003-设备列表": "/pages/devices/devices",
    "004-连接中": "/pages/connecting/connecting",
    "009-分享落地": "/pages/share/share",
    "010-任务主页": "/pages/sessions/sessions",
    "011-会话对话": "/pages/chat/chat",
    "013-设置": "/pages/settings/settings",
    "029-Diff/Git 面板": "/pages/git/git",
    "030-文件管理器": "/pages/files/files",
    "038-模型轨迹": "/pages/tasks/tasks",
  },
};
