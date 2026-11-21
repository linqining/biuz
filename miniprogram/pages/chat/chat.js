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
    // 审批卡（盖章子轮 2026-11-22）：pendingInteractions 投影 + 落印态（id → true=朱印/false=墨印）
    cards: [],
    stamps: {},
  },

  /** 本地已决议遮罩（停驻期满后卡离场；投影回流自然对账）。 */
  resolvedIds: {},

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
    if (this.data.sessionId) {
      store.refresh(this.data.sessionId);
      // 审批卡数据面：帧流挂载 + 会话订阅（幂等；未连接/失败由连接层如实回执）
      const conn = require('../../utils/connection.js');
      conn.subscribeConversation(this.data.sessionId);
    }
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
    this.renderCards(state);
  },

  /** 审批卡渲染：投影解析后的规整卡 + 本地决议遮罩（停驻期满移除）。 */
  renderCards(state) {
    const resolved = this.resolvedIds || {};
    const cards = (state.pendingInteractions || []).filter(function (c) {
      return !resolved[c.id];
    }).map(function (c) {
      return {
        id: c.id,
        kind: c.kind,
        title: c.title || '待确认',
        command: c.command,
        impact: c.impact,
        options: c.options,
        questions: c.questions,
        plan: c.plan,
        scope: c.scope || 'once',
        error: c.error || '',
      };
    });
    this.setData({ cards: cards });
  },

  /** 权限范围 chips（一次/始终；§7.2 拼接词表 allow|reject + Once|Always）。 */
  onScope(event) {
    const id = event.currentTarget.dataset.id;
    const scope = event.currentTarget.dataset.scope;
    const cards = this.data.cards.map(function (c) {
      return c.id === id ? Object.assign({}, c, { scope: scope }) : c;
    });
    this.setData({ cards: cards });
  },

  /** 印向推导（motion-spec：reject*/decline → 墨印「不准」，其余 → 朱印「照办」）。 */
  permissionOptionId(card, approved) {
    const family = approved ? 'allow' : 'reject';
    const want = family + (card.scope === 'always' ? 'Always' : 'Once');
    // 服务端原生 optionId 优先（按钮即选项；web 归一化 dQ 同子串口径）
    const hit = (card.options || []).filter(function (o) {
      const hay = (o.id + ' ' + o.label).toLowerCase();
      if (approved) return hay.indexOf('allow') >= 0 || hay.indexOf('approve') >= 0;
      return hay.indexOf('reject') >= 0 || hay.indexOf('deny') >= 0;
    });
    if (hit.length) {
      const scoped = hit.filter(function (o) { return o.id === want; });
      return (scoped[0] || hit[0]).id;
    }
    return want; // 服务端 options 缺席：客户端拼规范 id（§7.2 宽容口径）
  },

  /** 决议公共路径：resolveInteraction → 权威成功落印停驻 700ms 收卡；失败错误行。 */
  resolveCard(id, answer, approved) {
    const that = this;
    const conn = require('../../utils/connection.js');
    conn.resolveInteraction(this.data.sessionId, id, answer).then(function (res) {
      if (!res.ok) {
        const cards = that.data.cards.map(function (c) {
          return c.id === id ? Object.assign({}, c, { error: res.failure.text }) : c;
        });
        that.setData({ cards: cards });
        return;
      }
      // 落印（U-5：印是权威成功专属）+ 停驻 700ms 收卡
      const stamps = Object.assign({}, that.data.stamps);
      stamps[id] = approved;
      that.setData({ stamps: stamps });
      if (!wx.__reduceMotionStub) wx.vibrateShort({ type: 'medium' });
      setTimeout(function () {
        that.resolvedIds[id] = true;
        const rest = Object.assign({}, that.data.stamps);
        delete rest[id];
        that.setData({ stamps: rest });
        that.renderCards(store.getState());
      }, 700);
    });
  },

  onPermissionApprove(event) {
    const id = event.currentTarget.dataset.id;
    const card = this.data.cards.filter(function (c) { return c.id === id; })[0];
    if (!card || this.data.stamps[id] !== undefined) return;
    this.resolveCard(id, { optionId: this.permissionOptionId(card, true) }, true);
  },

  onPermissionReject(event) {
    const id = event.currentTarget.dataset.id;
    const card = this.data.cards.filter(function (c) { return c.id === id; })[0];
    if (!card || this.data.stamps[id] !== undefined) return;
    this.resolveCard(id, { optionId: this.permissionOptionId(card, false) }, false);
  },

  onPlanAccept(event) {
    const id = event.currentTarget.dataset.id;
    if (this.data.stamps[id] !== undefined) return;
    this.resolveCard(id, { action: 'accept' }, true);
  },

  onPlanDecline(event) {
    const id = event.currentTarget.dataset.id;
    if (this.data.stamps[id] !== undefined) return;
    this.resolveCard(id, { action: 'decline' }, false);
  },

  /** 提问族选项：点选即发（{optionId}；A-3 提问族分形）。 */
  onQuestionOption(event) {
    const id = event.currentTarget.dataset.id;
    const optionId = event.currentTarget.dataset.option;
    if (this.data.stamps[id] !== undefined) return;
    this.resolveCard(id, { optionId: optionId }, true);
  },

  /** 提问族自由文本（{freeText}）。 */
  onQuestionSubmit(event) {
    const id = event.currentTarget.dataset.id;
    if (this.data.stamps[id] !== undefined) return;
    const text = (this.data.questionDraft || '').trim();
    if (!text) {
      wx.showToast({ title: '请先填写回答', icon: 'none' });
      return;
    }
    this.resolveCard(id, { freeText: text }, true);
  },

  onQuestionInput(event) {
    this.setData({ questionDraft: event.detail.value });
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
