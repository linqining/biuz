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
    showAllPinned: false,
    pinnedOverflow: 0,
    groupColors: {},
    statusBarHeight: 0,
    versionNotice: '',
  },

  /** G33 徽标点按 = 暂停本次自动决议（snoozeInteractionAutoResolution；幂等 noop）。 */
  onRowSnooze(event) {
    const id = event.detail && event.detail.id;
    if (!id) return;
    const conn = require('../../utils/connection.js');
    conn.snoozeInteractionAutoResolution(id, this._pendingInteractionId(id))
      .then(function (res) {
        if (!res.ok) {
          wx.showToast({ title: (res.failure && res.failure.text) || '稍后失败', icon: 'none' });
        }
      });
  },

  /** snooze 定位键（行轻量投影 interactionId；缺席不发）。 */
  _pendingInteractionId(sessionId) {
    const state = store.getState();
    const row = (state.rows || []).filter(function (r) { return String(r.id) === String(sessionId); })[0];
    return row && row.pendingInteractionId;
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
    // 测试缝（片六）：?acceptance=1&relayConnect=1&relayLink=<enc>&jwt=<enc>
    // 直发中继 V2 连接（绕原生弹窗；localhost/127.0.0.1 自动降级 wss→ws
    // ——开发者工具无 TLS；仅 automator 用途）
    if (this.acceptanceQuery.acceptance && this.acceptanceQuery.relayConnect) {
      const connection = require('../../utils/connection.js');
      const self = this;
      const rawLink = decodeURIComponent(this.acceptanceQuery.relayLink || '');
      const jwt = decodeURIComponent(this.acceptanceQuery.jwt || '');
      // localhost wss→ws 降级（开发者工具测试用；parseRelayLink 产 wss://）
      const parsed = require('../../utils/relay-v2.js').parseRelayLink(rawLink);
      if (parsed && (parsed.wssUrl.indexOf('wss://127.0.0.1') === 0 ||
                     parsed.wssUrl.indexOf('wss://localhost') === 0)) {
        parsed.wssUrl = parsed.wssUrl.replace('wss://', 'ws://');
        // 开发者工具无 TLS：httpOrigin 也降级 https→http（token/设备目录请求用）
        parsed.httpOrigin = parsed.httpOrigin.replace('https://', 'http://');
      }
      // 开发者工具：https/wss → http/ws 降级（127.0.0.1 无 TLS；urlCheck 已关）
      const devHttp = function (url, jwt2, bodyObj) {
        const httpUrl = url.replace('https://127.0.0.1', 'http://127.0.0.1')
                           .replace('https://localhost', 'http://localhost');
        const wt = require('../../utils/wx-transport.js');
        return wt.wxHttpPost(httpUrl, jwt2, bodyObj);
      };
      const devGetJson = function (url, jwt2) {
        const httpUrl = url.replace('https://127.0.0.1', 'http://127.0.0.1')
                           .replace('https://localhost', 'http://localhost');
        const wt = require('../../utils/wx-transport.js');
        return wt.wxHttpGetJson(httpUrl, jwt2);
      };
      connection.connectRelayDesktop({
        relayLink: parsed || rawLink,
        jwt: jwt,
        httpPost: devHttp,
        httpGetJson: devGetJson,
      }).then(function () { self.render(require('../../utils/store.js').getState()); })
        .catch(function (err) {
          wx.navigateTo({
            url: '/pages/connecting/connecting?mode=failure&reason=transport&detail=' +
              encodeURIComponent((err && err.message) || '中继连接失败'),
          });
        });
    }
    // 测试缝（片三）：?acceptance=1&connect=<baseUrl> 直发连接（绕原生弹窗，
    // 仅 automator 用途）——连接过程/结果态由 store 与 connecting/failure 页承载
    if (this.acceptanceQuery.acceptance && this.acceptanceQuery.connect) {
      const connection = require('../../utils/connection.js');
      const self = this;
      // 微信 onLoad query 不自动解码（实测 reLaunch 编码串原样到达）——显式 decode
      const connectUrl = decodeURIComponent(this.acceptanceQuery.connect);
      connection.connectDesktop({ baseUrl: connectUrl })
        .then(function () { self.render(store.getState()); })
        .catch(function (err) {
          wx.navigateTo({
            url: '/pages/connecting/connecting?mode=failure&reason=transport&detail=' +
              encodeURIComponent((err && err.message) || '连接失败'),
          });
        });
    }
  },

  onShow() {
    store.refresh();
  },

  // 片三：连接桌面端（地址弹窗输入 + connecting 页承载过程态 + 失败页如实上屏）
  onConnectDesktop() {
    const connection = require('../../utils/connection.js');
    const last = connection.lastAddress();
    wx.showModal({
      title: '连接桌面端',
      editable: true,
      placeholderText: (last && last.baseUrl) || 'http://192.168.x.x:9100',
      content: (last && last.baseUrl) || '',
      success: (res) => {
        if (!res.confirm) return;
        const baseUrl = (res.content || '').trim();
        if (!baseUrl) return;
        connection.connectDesktop({ baseUrl: baseUrl }).then(() => {
          // 连接成功：回会话页（本页即 tab 根，刷新由 store 数据驱动）
          this.render(store.getState());
        }).catch((err) => {
          // 失败：005 失败页如实上屏（reason=transport 文案族；detail=原文）
          wx.navigateTo({
            url: '/pages/connecting/connecting?mode=failure&reason=transport&detail=' +
              encodeURIComponent((err && err.message) || '连接失败'),
          });
        });
      },
    });
  },

  onUnload() {
    if (this.unsubscribe) this.unsubscribe();
  },

  render(state) {
    const sections = model.buildView(state.rows, this.data.view, this.data.collapsedKeys, this.data.showAllPinned);
    const pinnedOverflow = model.pinnedOverflowCount
      ? model.pinnedOverflowCount(state.rows || [], this.data.showAllPinned)
      : 0;
    if (this.data.view === 'grouped' && state.status === 'connected') {
      this.refreshTaskGroups();
    }
    this.setData({
      status: state.status,
      error: state.error || '',
      versionNotice: state.versionNotice || '',
      sections: model.decorateSections(sections),
      pinnedOverflow: pinnedOverflow,
      deviceName: state.deviceName || '未连接设备',
      accountName: state.accountName || '',
      accountInitial: (state.accountName || '').slice(0, 1),
    });
  },

  /** G36 GROUPED 视图拉分组结构（色点数据源；未连接/失败静默空）。 */
  refreshTaskGroups() {
    if (this.data.view !== 'grouped') return;
    const conn = require('../../utils/connection.js');
    conn.readTaskGroups().then((structure) => {
      if (!structure) return;
      const groupColors = {};
      structure.groups.forEach(function (g) { groupColors[g.title] = g.color; });
      this._groupIds = {};
      structure.groups.forEach(function (g) { this._groupIds[g.title] = g.id; }, this);
      this.setData({ groupColors: groupColors });
    });
  },

  /** G36 组色循环（点击组头 = 下一档色；save 后更新本地映射）。 */
  onGroupHeaderTap(event) {
    const title = event.currentTarget.dataset.title;
    if (!title) return;
    const palette = ['gray', 'red', 'orange', 'yellow', 'green', 'blue', 'purple'];
    const current = this.data.groupColors[title] || 'gray';
    const next = palette[(palette.indexOf(current) + 1) % palette.length];
    const conn = require('../../utils/connection.js');
    conn.saveTaskGroupColor(this._groupIds[title] || '', next).then((res) => {
      if (res.ok) {
        const groupColors = Object.assign({}, this.data.groupColors);
        groupColors[title] = next;
        this.setData({ groupColors: groupColors });
      } else {
        wx.showToast({ title: res.text || '保存失败', icon: 'none' });
      }
    });
  },

  /** G36 组置顶（长按组头 = move 组节点到顶层 index 0）。 */
  onGroupHeaderLongPress(event) {
    const title = event.currentTarget.dataset.title;
    if (!title) return;
    const groupId = (this._groupIds || {})[title];
    if (!groupId) return;
    const conn = require('../../utils/connection.js');
    conn.pinTaskGroupToTop(groupId).then((res) => {
      if (res.ok) {
        wx.showToast({ title: '已置顶', icon: 'none' });
        this.refreshTaskGroups();
        this.render(store.getState());
      } else {
        wx.showToast({ title: res.text || '置顶失败', icon: 'none' });
      }
    });
  },

  /** G34 置顶翻页（web taskList.showMore/showLess 同语义）。 */
  onTogglePinnedMore() {
    this.setData({ showAllPinned: !this.data.showAllPinned });
    this.render(store.getState());
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

  /** G38 统一搜索入口。 */
  onOpenCommandCenter() {
    wx.navigateTo({ url: '/pages/command-center/command-center' });
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
    // APP-DIFF[D006] platform: web 靠 hover 显现行内文件/归档按钮；触屏无 hover，
    // 长按行（session-row bind:longpress）触发 wx.showActionSheet 菜单
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

  /** 008：重新加载（连接层接入后用新版本重连；此处先清提示） */
  onVersionReload() {
    store.confirmVersionReload();
  },
});
