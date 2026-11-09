// 038-模型轨迹
// web 源：packages/ui/src/ModelTrajectoryPane.tsx
const layout = require('../../utils/layout.js');

Page({
  data: {
    statusBarHeight: 20,
    pageKey: "038-模型轨迹",
  },

  onLoad(query) {
    this.setData({ statusBarHeight: layout.statusBarHeight() });
    // 测试缝：?acceptance=1 时按 query 里的参数直达内部状态（仅测试用途）
    this.acceptanceQuery = query || {};
  },
});
