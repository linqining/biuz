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
    previewSettings: { fontSizePx: 12, showLineNumbers: true, wrapLongLines: false },
    previewLines: [],
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
    // G42 前置（2026-11-25）：预览内容接线 file.readTextFile（有界 256KB 首页，
    // totalBytes 判截断如实提示；未连接/失败如实空态——AGENTS §5-13 禁假数据）
    this.setData({
      preview: {
        path: path,
        name: model.baseName(path),
        language: model.languageOf(path),
        loading: true,
        truncated: false,
      },
      previewContent: '',
      previewSettings: this.readPreviewSettings(),
    });
    const conn = require('../../utils/connection.js');
    conn.readFileText(path, 0, 262144).then((page) => {
      if (!page) {
        this.setData({ preview: Object.assign({}, this.data.preview, { loading: false }) });
        return;
      }
      const truncated = page.totalBytes > page.content.length;
      this.setData({
        previewContent: page.content,
        previewLines: page.content.split('\n'),
        preview: Object.assign({}, this.data.preview, {
          loading: false,
          truncated: truncated,
        }),
      });
    });
  },

  /** G42 残面：代码预览三键行内控制（字号循环 12→20→12 / 行号 / 换行；持久
   * zcode-code-preview-settings 同词面）。 */
  /** G42 残面：预览偏好读（zcode-code-preview-settings 同词面；默认 12/on/off）。 */
  readPreviewSettings() {
    try {
      const parsed = wx.getStorageSync('zcode-code-preview-settings') || {};
      return {
        fontSizePx: typeof parsed.fontSizePx === 'number'
          ? Math.min(20, Math.max(12, parsed.fontSizePx)) : 12,
        showLineNumbers: parsed.showLineNumbers !== false,
        wrapLongLines: parsed.wrapLongLines === true,
      };
    } catch (err) {
      return { fontSizePx: 12, showLineNumbers: true, wrapLongLines: false };
    }
  },

  onPreviewFontSize() {
    const s = this.data.previewSettings;
    const next = s.fontSizePx >= 20 ? 12 : s.fontSizePx + 1;
    const settings = Object.assign({}, s, { fontSizePx: next });
    this.setData({ previewSettings: settings });
    wx.setStorageSync('zcode-code-preview-settings', settings);
  },

  onPreviewToggleLineNumbers() {
    const settings = Object.assign({}, this.data.previewSettings, {
      showLineNumbers: !this.data.previewSettings.showLineNumbers,
    });
    this.setData({ previewSettings: settings });
    wx.setStorageSync('zcode-code-preview-settings', settings);
  },

  onPreviewToggleWrap() {
    const settings = Object.assign({}, this.data.previewSettings, {
      wrapLongLines: !this.data.previewSettings.wrapLongLines,
    });
    this.setData({ previewSettings: settings });
    wx.setStorageSync('zcode-code-preview-settings', settings);
  },

  onClosePreview() {
    this.setData({ preview: null, previewContent: '' });
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },
});
