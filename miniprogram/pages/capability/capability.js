// 能力清单页（memory/skills/mcp/plugins 四合一参数化；iOS RemoteCapabilityListPage
// 同构）。数据：utils/capability-store.js + utils/capability.js 读面（只读，
// 启停/卸载/安装写面不接——越界即失守）。
// 入参：?kind=memory|skills|mcp|plugins
const store = require('../../utils/capability-store.js');

const TITLES = {
  memory: '记忆',
  skills: '技能',
  mcp: 'MCP',
  plugins: '插件',
};

Page({
  data: {
    kind: 'memory',
    title: '记忆',
    statusBarHeight: 0,
    status: 'idle',
    rows: [],
    error: '',
  },

  onLoad(query) {
    const kind = (query && query.kind) || 'memory';
    let statusBarHeight = 0;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = info.statusBarHeight || 0;
    } catch (err) { statusBarHeight = 0; }
    this.setData({ kind: kind, title: TITLES[kind] || kind, statusBarHeight: statusBarHeight });
    this.unsubscribe = store.subscribe((state) => this.render(state));
    this.render(store.getState());
    store.refresh(kind);
  },

  onShow() {
    store.refresh(this.data.kind);
  },

  onUnload() {
    if (this.unsubscribe) this.unsubscribe();
  },

  render(state) {
    this.setData({
      status: state.status,
      rows: state.rows || [],
      error: state.error || '',
    });
  },

  onRetry() {
    store.refresh(this.data.kind);
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/settings/settings' }); } });
  },
});
