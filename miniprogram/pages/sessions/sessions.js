// 010-任务主页（home）
//
// web 权威源（线上基线 .capture/runs/20261108-080125-*，结构取自实时 DOM）：
//   设备切换行    sidebar-primary-navigation 顶部 span :: MacBook-Pro-9.local
//   新建任务      div[data-testid=task-new-button]
//   任务列表头    div[data-testid=sidebar-search-toolbar]（标题 + 搜索 + 视图菜单）
//   已置顶        div[data-testid=project-pinned-tasks] > h3「已置顶」> 任务行
//   项目 / 任务   section[data-testid=project-section | conversation-section]
//   底部账号      button[data-testid=login-trigger] + task-settings-button
//
// 数据：来自 utils/store.js 的会话快照。未连接时如实给空态，不填假数据（AGENTS.md §5-13）。
const model = require('../../utils/session-model.js');
const store = require('../../utils/store.js');
const nav = require('../../utils/nav.js');

Page({
  data: {
    status: 'idle',
    error: '',
    sections: [],
    view: 'projects',
    viewLabel: '按项目',
    deviceName: '未连接设备',
    accountName: '',
    accountInitial: '',
    collapsedKeys: [],
    statusBarHeight: 0,
  },

  onLoad(query) {
    this.acceptanceQuery = query || {};
    // 自定义导航（navigationStyle: custom）需自己让出状态栏；env(safe-area-inset-top)
    // 在微信模拟器里取不到状态栏高度，改读系统信息。
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
    // 测试缝：?acceptance=1&view=grouped 直达指定视图（仅测试用途，零用户影响）
    if (this.acceptanceQuery.acceptance && this.acceptanceQuery.view) {
      this.setData({
        view: this.acceptanceQuery.view,
        viewLabel: model.viewLabel(this.acceptanceQuery.view),
      });
      this.render(store.getState());
    }
  },

  onShow() {
    store.refresh();
  },

  onUnload() {
    if (this.unsubscribe) this.unsubscribe();
  },

  render(state) {
    const sections = model.buildView(state.rows, this.data.view, this.data.collapsedKeys);
    this.setData({
      status: state.status,
      error: state.error || '',
      sections: model.decorateSections(sections),
      deviceName: state.deviceName || '未连接设备',
      accountName: state.accountName || '',
      accountInitial: (state.accountName || '').slice(0, 1),
    });
  },

  onToggleSection(event) {
    const key = event.detail.key;
    const keys = this.data.collapsedKeys.slice();
    const at = keys.indexOf(key);
    if (at >= 0) keys.splice(at, 1);
    else keys.push(key);
    this.setData({ collapsedKeys: keys });
    this.render(store.getState());
  },

  onOpenViewMenu() {
    wx.showActionSheet({
      itemList: model.VIEWS.map((v) => v.label),
      success: (res) => {
        const view = model.VIEWS[res.tapIndex];
        if (!view) return;
        this.setData({ view: view.key, viewLabel: view.label });
        this.render(store.getState());
      },
    });
  },

  onOpenRow(event) {
    const id = event.detail.id;
    if (!id) return;
    nav.push('/pages/chat/chat?sessionId=' + encodeURIComponent(id));
  },

  onRowMenu(event) {
    const id = event.detail.id;
    if (!id) return;
    wx.showActionSheet({
      itemList: ['查看文件', '归档'],
      success: (res) => {
        if (res.tapIndex === 0) nav.push('/pages/git/git');
        if (res.tapIndex === 1) store.archive(id);
      },
    });
  },

  onNewTask() {
    if (this.data.status !== 'connected') {
      wx.showToast({ title: '未连接桌面端', icon: 'none' });
      return;
    }
    // 新建任务需要 createSession 写链（W2 接入），此处不伪造草稿
    wx.showToast({ title: '新建任务待接入', icon: 'none' });
  },

  onSwitchDevice() {
    nav.push('/pages/devices/devices');
  },

  onAccount() {
    nav.push('/pages/settings/settings');
  },

  onSettings() {
    nav.push('/pages/settings/settings');
  },

  onRetry() {
    store.refresh();
  },
});
