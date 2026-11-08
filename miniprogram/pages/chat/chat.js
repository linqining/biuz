// 011-会话对话
// web 源：packages/ui/src/WebRemoteControlMobileShell.tsx:172-246 + v4/V4ChatPane.tsx:92
Page({
  data: {
    pageKey: "011-会话对话",
  },

  onLoad(query) {
    // 测试缝：?acceptance=1 时按 query 里的参数直达内部状态（仅测试用途）
    this.acceptanceQuery = query || {};
  },
});
