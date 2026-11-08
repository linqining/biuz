// 029-Diff/Git 面板
// web 源：packages/ui/src/GitPane.tsx
Page({
  data: {
    pageKey: "029-Diff/Git 面板",
  },

  onLoad(query) {
    // 测试缝：?acceptance=1 时按 query 里的参数直达内部状态（仅测试用途）
    this.acceptanceQuery = query || {};
  },
});
