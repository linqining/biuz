// 030-文件管理器
// web 源：packages/ui/src/app-shell/AnimatedSidePanePanel.tsx:1333
Page({
  data: {
    pageKey: "030-文件管理器",
  },

  onLoad(query) {
    // 测试缝：?acceptance=1 时按 query 里的参数直达内部状态（仅测试用途）
    this.acceptanceQuery = query || {};
  },
});
