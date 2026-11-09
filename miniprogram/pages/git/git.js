// 029-Diff 审查（屏 08）
//
// web 权威源：`packages/ui/src/GitPane.tsx`；移动端对位 Android `FilesScreen` 的
// DiffReviewView 与 iOS `DiffReviewView`。分段「未暂存/已暂存/本次会话」，文件卡可展开逐行。
// 写面（stage/unstage）需连接层，未接入时如实提示。
const layout = require('../../utils/layout.js');
const model = require('../../utils/diff-model.js');
const store = require('../../utils/diff-store.js');

Page({
  data: {
    statusBarHeight: 20,
    status: 'idle',
    sections: [],
    totals: { files: 0, add: 0, del: 0 },
    expanded: {},
    error: '',
  },

  onLoad() {
    this.setData({ statusBarHeight: layout.statusBarHeight() });
    this.unsubscribe = store.subscribe((state) => this.render(state));
    this.render(store.getState());
  },

  onShow() { store.refresh(); },
  onUnload() { if (this.unsubscribe) this.unsubscribe(); },

  render(state) {
    const expanded = this.data.expanded;
    const sections = model.groupBySegment(state.files).map(function (section) {
      return Object.assign({}, section, {
        files: section.files.map(function (file) {
          return Object.assign({}, file, {
            name: model.baseName(file.path),
            expanded: Boolean(expanded[file.id]),
            visibleLines: expanded[file.id] ? file.lines : [],
          });
        }),
      });
    });
    this.setData({
      status: state.status,
      sections: sections,
      totals: model.totals(state.files),
      error: state.error || '',
    });
  },

  onToggleFile(event) {
    const id = event.currentTarget.dataset.id;
    const expanded = Object.assign({}, this.data.expanded);
    if (expanded[id]) delete expanded[id];
    else expanded[id] = true;
    this.setData({ expanded: expanded });
    this.render(store.getState());
  },

  onSegmentAction(event) {
    // stage/unstage 属写面：需要连接层，未接入如实提示（不做本地假成功）
    wx.showToast({ title: '需要连接桌面端', icon: 'none' });
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },
});
