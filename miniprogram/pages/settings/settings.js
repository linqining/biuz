// 013-设置
// web 源：packages/ui/src/SettingsPage.tsx:1928-2242
Page({
  data: {
    pageKey: "013-设置",
  },

  onLoad(query) {
    // 测试缝：?acceptance=1 时按 query 里的参数直达内部状态（仅测试用途）
    this.acceptanceQuery = query || {};
  },
});
