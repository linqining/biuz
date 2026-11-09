// 030-文件管理器（文件树）
//
// web 权威源：`packages/ui/src/app-shell/AnimatedSidePanePanel.tsx:1333`（side pane 的
// file-manager / code-viewer）；移动端对位 Android `FilesScreen` 的 FileTreeScreen
// 与 iOS `FileTreeView`。数据面只读（ReadOnlyGate：file 频道默认拒绝直写）。
const layout = require('../../utils/layout.js');
const model = require('../../utils/files-model.js');
const store = require('../../utils/files-store.js');

Page({
  data: {
    statusBarHeight: 20,
    status: 'idle',
    workspacePath: '',
    rows: [],
    error: '',
    expanded: {},
    preview: null,
    previewContent: '',
  },

  onLoad(query) {
    this.acceptanceQuery = query || {};
    this.setData({ statusBarHeight: layout.statusBarHeight() });
    this.unsubscribe = store.subscribe((state) => this.render(state));
    this.render(store.getState());
  },

  onShow() {
    store.refresh();
  },

  onUnload() {
    if (this.unsubscribe) this.unsubscribe();
  },

  render(state) {
    this.setData({
      status: state.status,
      workspacePath: state.workspacePath || '',
      error: state.error || '',
      rows: model.flatten(state.tree, this.data.expanded).map(function (row) {
        return Object.assign({}, row, { sizeText: model.sizeText(row.node) });
      }),
    });
  },

  /** 行点按：目录折叠/展开；文件打开预览（单一入口，避免 WXML 动态事件名）。 */
  onRowTap(event) {
    const path = event.currentTarget.dataset.path;
    const expandable = event.currentTarget.dataset.expandable === true || event.currentTarget.dataset.expandable === 'true';
    if (expandable) {
      this.onToggleRow(event);
      return;
    }
    this.onOpenFile(event);
  },

  onToggleRow(event) {
    const path = event.currentTarget.dataset.path;
    const expandable = event.currentTarget.dataset.expandable;
    if (!expandable) return;
    const expanded = Object.assign({}, this.data.expanded);
    if (expanded[path]) delete expanded[path];
    else expanded[path] = true;
    this.setData({ expanded: expanded });
    this.render(store.getState());
  },

  onOpenFile(event) {
    const path = event.currentTarget.dataset.path;
    const row = (this.data.rows || []).filter(function (item) { return item.node.path === path; })[0];
    if (!row || row.node.isDirectory) return;
    // 预览内容未接入连接层：如实提示，不伪造文件内容
    this.setData({
      preview: { path: path, name: model.baseName(path), language: model.languageOf(path) },
      previewContent: '',
    });
  },

  onClosePreview() {
    this.setData({ preview: null, previewContent: '' });
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },
});
