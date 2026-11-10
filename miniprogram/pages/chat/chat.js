// 011-会话对话
//
// web 权威源：`packages/ui/src/v4/V4ChatPane.tsx` / `SessionPane.tsx`（手机壳 chat 页）；
// Android 对位 `ui/screen/ChatScreen.kt`（消息流 + composer + 常驻交互卡区）。
//
// 本轮落地：顶栏 / 消息流（用户气泡 + 助手块渲染 + 工具卡 + 思考块）/ composer / 空错态。
// 未接：RPC 数据源（连接层接入后 setSource 即亮）、行级动作（复制/赞踩）、审批卡。
const model = require('../../utils/chat-model.js');
const store = require('../../utils/chat-store.js');

Page({
  data: {
    sessionId: '',
    status: 'idle',
    title: '任务会话',
    rows: [],
    error: '',
    projectName: '',
    scrollTarget: '',
    draft: '',
    statusBarHeight: 0,
  },

  onLoad(query) {
    const sessionId = (query && query.sessionId) || '';
    let statusBarHeight = 0;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      statusBarHeight = info.statusBarHeight || 0;
    } catch (err) {
      statusBarHeight = 0;
    }
    this.setData({
      sessionId: sessionId,
      statusBarHeight: statusBarHeight,
      // 项目行数据（web 第二行「📁 项目名」）；连接层接入后按会话归属填充
      projectName: (query && query.projectName) || '',
    });
    this.unsubscribe = store.subscribe((state) => this.render(state));
    this.render(store.getState());
  },

  onShow() {
    if (this.data.sessionId) store.refresh(this.data.sessionId);
  },

  onUnload() {
    if (this.unsubscribe) this.unsubscribe();
  },

  render(state) {
    const now = Date.now();
    const rows = (state.messages || []).map(function (raw) {
      return model.decorateMessage(model.normalizeMessage(raw), now);
    });
    this.setData({
      status: state.status,
      rows: rows,
      error: state.error || '',
      title: model.headerTitle({ title: state.title }),
    });
  },

  /** P-020 行级反馈（赞/踩/取消）；RPC 经 connection.setAssistantFeedback。 */
  onFeedback(event) {
    const id = event.currentTarget.dataset.id;
    const value = event.currentTarget.dataset.value === 'true';
    const conn = require('../../utils/connection.js');
    const msg = this.data.rows ? this.data.rows.find(function (r) { return r.id === id; }) : null;
    if (!msg || !msg.entityId) {
      wx.showToast({ title: '该消息不可反馈', icon: 'none' });
      return;
    }
    const rowId = parseInt(id.replace('row-', ''), 10) || 0;
    const sessionId = this.data.sessionId || '';
    conn.setAssistantFeedback(sessionId, rowId, msg.entityId, value)
      .then(function (error) {
        if (error) wx.showToast({ title: error, icon: 'none' });
      })
      .catch(function (err) {
        wx.showToast({ title: (err && err.message) || '反馈失败', icon: 'none' });
      });
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },

  onDraftInput(event) {
    this.setData({ draft: event.detail.value });
  },

  onCopy(event) {
    const id = event.currentTarget.dataset.id;
    const target = (this.data.rows || []).filter(function (row) { return row.id === id; })[0];
    if (!target) return;
    wx.setClipboardData({ data: target.text || '' });
  },

  onSend() {
    // 写链未接入：如实提示，不伪造已发送
    wx.showToast({ title: '发送需要连接桌面端', icon: 'none' });
  },
});
