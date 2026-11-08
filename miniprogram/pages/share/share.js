// 009-分享落地
// web 源：packages/web/src/share/ConversationShareLandingPage.tsx:294
Page({
  data: {
    pageKey: "009-分享落地",
  },

  onLoad(query) {
    // 测试缝：?acceptance=1 时按 query 里的参数直达内部状态（仅测试用途）
    this.acceptanceQuery = query || {};
  },
});
