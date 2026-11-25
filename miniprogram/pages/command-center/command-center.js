// G38 命令中心统一搜索（web CommandCenterDialog.tsx 移动端同构，2026-11-25
// 与 iOS CommandCenterSheet/Android CommandCenterScreen 同构）：三 scope
// （全部/会话/文件——命令 scope 由 composer 斜杠承载）+ 400ms 防抖 +
// 历史 chips（wx storage `zcode-command-center-search-history:<wsPath>` 同词面 limit 20）。
const connection = require('../../utils/connection.js');

const HISTORY_KEY_PREFIX = 'zcode-command-center-search-history:';
const HISTORY_LIMIT = 20;

Page({
  data: {
    query: '',
    scope: 'all', // all | conversations | files
    searching: false,
    sessions: [],
    files: [],
    history: [],
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },

  onLoad() {
    this.setData({ history: this.readHistory() });
  },

  historyKey() {
    const ws = connection.getState().deviceName || 'none';
    return HISTORY_KEY_PREFIX + ws;
  },

  readHistory() {
    try {
      const raw = wx.getStorageSync(this.historyKey());
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.slice(0, HISTORY_LIMIT) : [];
    } catch (err) {
      return [];
    }
  },

  recordHistory(scope, queryText) {
    const entries = this.readHistory()
      .filter(function (e) { return e.scope !== scope || e.query !== queryText; });
    entries.unshift({ scope: scope, query: queryText, updatedAt: Date.now() });
    try {
      wx.setStorageSync(this.historyKey(), JSON.stringify(entries.slice(0, HISTORY_LIMIT)));
    } catch (err) {
      // 存储失败静默（历史为便利面）
    }
    this.setData({ history: this.readHistory() });
  },

  onQueryInput(event) {
    const value = event.detail.value;
    this.setData({ query: value, searching: value.trim().length > 0 });
    if (this._searchJob) clearTimeout(this._searchJob);
    const trimmed = value.trim();
    if (!trimmed) {
      this.setData({ sessions: [], files: [], searching: false });
      return;
    }
    this._searchJob = setTimeout(() => this.runSearch(trimmed), 400);
  },

  runSearch(trimmed) {
    connection.commandCenterSearch(trimmed).then((result) => {
      this.setData({
        sessions: result.sessions,
        files: result.files,
        searching: false,
      });
    }).catch(() => {
      this.setData({ sessions: [], files: [], searching: false });
    });
  },

  onScope(event) {
    this.setData({ scope: event.currentTarget.dataset.scope });
  },

  onHistoryTap(event) {
    const entry = event.currentTarget.dataset.entry;
    if (!entry) return;
    this.setData({
      query: entry.query,
      scope: entry.scope !== 'all' ? entry.scope : this.data.scope,
    });
    this.runSearch(entry.query);
  },

  onOpenSession(event) {
    const id = event.currentTarget.dataset.id;
    if (!id) return;
    this.recordHistory(this.data.scope, this.data.query.trim());
    wx.navigateTo({ url: '/pages/chat/chat?sessionId=' + encodeURIComponent(id) });
  },

  onPickFile() {
    // 文件命中记录历史（深度导航留文件页——小程序文件页已有检索）
    if (this.data.query.trim()) {
      this.recordHistory(this.data.scope, this.data.query.trim());
    }
  },
});
