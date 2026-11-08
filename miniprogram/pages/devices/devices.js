// 003-设备列表
//
// web 权威源：packages/web/src/web-remote-control/ui/webRemoteControlDeviceListPage.tsx
//   :19-52 文案；页头（品牌 +「选择电脑」+ 刷新）；账号行（头像 + displayName · Provider + 切换）；
//   分组/空态/加载/错误分支。
//
// 数据：utils/devices-store.js（五态）。未连接时如实空态，不造设备（AGENTS.md §5-13）。
const model = require('../../utils/device-model.js');
const store = require('../../utils/devices-store.js');
const nav = require('../../utils/nav.js');

Page({
  data: {
    copy: model.COPY,
    status: 'idle',
    sections: [],
    accountText: '',
    accountInitial: '',
    error: '',
    statusBarHeight: 0,
    // 006 多实例选择弹层（同组在线 > 1 时）
    sheetVisible: false,
    sheetTitle: '',
    sheetDescription: '',
    sheetRows: [],
    sheetCopy: model.SHEET_COPY,
  },

  onLoad(query) {
    this.acceptanceQuery = query || {};
    let statusBarHeight = 0;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = info.statusBarHeight || 0;
    } catch (err) {
      statusBarHeight = 0;
    }
    this.setData({ statusBarHeight: statusBarHeight });
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
    const account = state.account;
    this.devices = state.devices || [];
    this.setData({
      status: state.status,
      sections: model.buildSections(state.devices),
      accountText: model.accountText(account),
      accountInitial: account && account.displayName ? String(account.displayName).slice(0, 1) : '',
      error: state.error || '',
    });
  },

  onRefresh() {
    store.refresh();
  },

  // 选中设备：单连接直接进连接中页；多连接弹选择层（web openDevice 同口径）
  onSelectDevice(event) {
    const mid = event.currentTarget.dataset.mid;
    const device = (this.devices || []).filter(function (d) { return d.deviceMid === mid; })[0];
    if (!device) return;
    const connections = device.onlineConnections || [];
    if (!connections.length) return;
    if (!model.needsConnectionSheet(device)) {
      this.openConnection(connections[0].device_sid);
      return;
    }
    this.setData({
      sheetVisible: true,
      sheetTitle: device.name || model.COPY.unnamed,
      sheetDescription: model.SHEET_COPY.sheetDescription(connections.length),
      sheetRows: model.connectionRows(device),
    });
  },

  onSelectConnection(event) {
    const sid = event.currentTarget.dataset.sid;
    this.setData({ sheetVisible: false });
    if (sid) this.openConnection(sid);
  },

  onCloseSheet() {
    this.setData({ sheetVisible: false });
  },

  openConnection(sid) {
    nav.push('/pages/connecting/connecting?sid=' + encodeURIComponent(sid));
  },

  onSwitchAccount() {
    // 切换账号需要登出 + 重走 OAuth（W2 后续轮次接入）；未接入前如实提示
    wx.showToast({ title: '切换账号待接入', icon: 'none' });
  },
});
