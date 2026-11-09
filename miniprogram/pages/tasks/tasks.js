// 038-模型轨迹（任务输出）
//
// web 权威源：`packages/ui/src/ModelTrajectoryPane.tsx`；iOS 对位 `TaskOutputView`
// （reasoning + toolCall 轨迹 + 元数据 + token 用量）。移动端只读，无写面。
const layout = require('../../utils/layout.js');
const model = require('../../utils/trajectory-model.js');
const store = require('../../utils/trajectory-store.js');

Page({
  data: {
    statusBarHeight: 20,
    status: 'idle',
    taskTitle: '',
    entries: [],
    stats: { steps: 0, tools: 0 },
    error: '',
  },

  onLoad(query) {
    this.taskId = (query && query.taskId) || '';
    this.setData({ statusBarHeight: layout.statusBarHeight() });
    this.unsubscribe = store.subscribe((state) => this.render(state));
    this.render(store.getState());
  },

  onShow() { store.refresh(this.taskId); },
  onUnload() { if (this.unsubscribe) this.unsubscribe(); },

  render(state) {
    const entries = model.normalizeTrajectory(state.entries).map(function (entry) {
      return Object.assign({}, entry, { summary: model.toolSummary(entry) });
    });
    this.setData({
      status: state.status,
      taskTitle: state.taskTitle || '',
      entries: entries,
      stats: model.stats(entries),
      error: state.error || '',
    });
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },
});
