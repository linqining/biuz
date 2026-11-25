// 011-会话对话
//
// web 权威源：`packages/ui/src/v4/V4ChatPane.tsx` / `SessionPane.tsx`（手机壳 chat 页）；
// Android 对位 `ui/screen/ChatScreen.kt`（消息流 + composer + 常驻交互卡区）。
//
// 本轮落地：顶栏 / 消息流（用户气泡 + 助手块渲染 + 工具卡 + 思考块）/ composer / 空错态。
// 未接：RPC 数据源（连接层接入后 setSource 即亮）、行级动作（复制/赞踩）、审批卡。
const model = require('../../utils/chat-model.js');
const store = require('../../utils/chat-store.js');

/** G49 滚动记忆键（web workspace::pane::session 的单 pane 简化）。 */
function ChatScrollKey(sessionId) {
  return 'ws::session:' + sessionId;
}

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
    mentionOpen: false,
    mentionScope: 'files', // files | conversations | skills（G15 子面板三 tab）
    mentionQuery: '',
    mentionResults: [],
    mentionSessions: [],
    mentionSkills: [],
    workflowRuns: [],
    savedByRun: {}, // G25 收口：runId → {name,scope}（forRun 判定）
    workflowSaved: null, // {name, scope}
    promptHistoryPointer: null, // G18：null=未开始；0=走到头回空
    workflowNotice: '',
    // G49 滚动恢复目标（null = 不设；恢复后清零避免再次触发跳动）
    scrollTop: null,
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
      // G50 草稿持久化恢复（web composerDraftStore 同构；进程死/切会话不丢）
      draft: sessionId ? this.loadDraft(sessionId) : '',
    });
    this.unsubscribe = store.subscribe((state) => this.render(state));
    this.render(store.getState());
    // G49 滚动位置记忆恢复（进程内 LRU——模块级 Map；web chatSessionScrollMemory
    // 同构：进程死即清，键 ws::session:<id>）
    const scrollMemory = require('../../utils/scroll-memory.js');
    if (sessionId) {
      const saved = scrollMemory.load(ChatScrollKey(sessionId));
      if (saved) {
        const self = this;
        // 行渲染后再设 scroll-top（scroll-view 属性生效需要内容已在）
        wx.nextTick(function () {
          self.setData({ scrollTop: saved });
          // 恢复只生效一次：清零 scroll-top 属性（null 不写）
          setTimeout(function () {
            self.setData({ scrollTop: null });
          }, 300);
        });
      }
    }
  },

  /** G50 草稿存取（wx storage 键 zcode.composer.draft.v1.<sessionId>；正文空白即删键
   * ——web composerDraftStore.ts:389-393 同口径；附件不入草稿【上游裁决 :9-10】）。 */
  loadDraft(sessionId) {
    try {
      return wx.getStorageSync('zcode.composer.draft.v1.' + sessionId) || '';
    } catch (err) {
      return '';
    }
  },

  persistDraft(sessionId, text) {
    if (!sessionId) return;
    try {
      if (!text || !text.trim()) {
        wx.removeStorageSync('zcode.composer.draft.v1.' + sessionId);
      } else {
        wx.setStorageSync('zcode.composer.draft.v1.' + sessionId, text);
      }
    } catch (err) {
      // 存储失败静默（草稿为易失便利，不阻断输入）
    }
  },

  onShow() {
    if (this.data.sessionId) {
      store.refresh(this.data.sessionId);
      // 审批卡数据面：帧流挂载 + 会话订阅（幂等；未连接/失败由连接层如实回执）
      const conn = require('../../utils/connection.js');
      conn.subscribeConversation(this.data.sessionId);
      // G25：工作流 run 投影拉取（一次/次 show；活投影 workflowRuns state 面小程序
      // 未订阅——query 通路 C 是唯一读源，rG-008 同口径）
      conn.workflowRunsFor(this.data.sessionId).then((runs) => {
        // G25 收口：逐 run 拉 forRun 已存判定（entry 缺席 = 未保存；失败不挡保存）
        const conn2 = require('../../utils/connection.js');
        const probes = runs.filter(function (r) { return !r.isLive; }).map(function (r) {
          return conn2.savedWorkflowForRun(this.data.sessionId, r.id).then(function (saved) {
            return { id: r.id, saved: saved };
          });
        }, this);
        Promise.all(probes.length ? probes : []).then((pairs) => {
          const savedByRun = {};
          pairs.forEach(function (p) { if (p.saved) savedByRun[p.id] = p.saved; });
          this.setData({ workflowRuns: runs, savedByRun: savedByRun });
        });
        this.setData({ workflowRuns: runs });
      });
    }
  },

  onUnload() {
    if (this.unsubscribe) this.unsubscribe();
    // G49 离开补终值
    if (this._lastScrollTop != null) {
      require('../../utils/scroll-memory.js').save(ChatScrollKey(this.data.sessionId), this._lastScrollTop);
    }
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
        // G21/G23（2026-11-25 与 iOS 同步）：反馈文本框开关 + 插件卡字段族
        permissionFreeText: c.permissionFreeText === true,
        freeTextEnabled: c.freeTextEnabled === true,
        feedbackDraft: '',
        // G22 多题向导状态（questions.length>1 逐题呈现；drafts 按题序保留——
        // 上游 ElicitationDialog「回退不清已答」同语义；iOS/Android 同构）
        wizardIndex: 0,
        wizardSelections: {},
        wizardTexts: {},
        qnow: null,
        pluginName: c.pluginName,
        pluginDescription: c.pluginDescription,
        pluginReason: c.pluginReason,
        pluginScope: c.pluginScope,
        pluginAction: c.pluginAction,
      };
    });
    // G22 预装饰当前题视图（WXML 表达式不支持方法调用——options.on 在 JS 侧算好）
    const self = this;
    cards.forEach(function (c) { c.qnow = self.wizardView(c); });
    this.setData({ cards: cards });
  },

  /**
   * G22 向导当前题视图（questions.length>1 才有；一次一题 + 上一步/下一步）。
   * iOS UserInputInteractionCard 向导段同构：进度「第 N/M 题」、showText 条件
   * （无选项题恒显 + freeText 开关）、选项 on 徽标（multiSelect 多选）。
   */
  wizardView(card) {
    const qs = card.questions || [];
    if (qs.length < 2) return null;
    const index = Math.min(card.wizardIndex || 0, qs.length - 1);
    const q = qs[index];
    const picked = card.wizardSelections[index] || [];
    return {
      index: index,
      count: qs.length,
      question: q.question,
      multiSelect: q.multiSelect === true,
      showText: !(q.options || []).length || card.freeTextEnabled === true,
      text: card.wizardTexts[index] || '',
      options: (q.options || []).map(function (o) {
        return { id: o.id, label: o.label, on: picked.indexOf(o.id) >= 0 };
      }),
    };
  },

  /** 向导卡片就地更新（drafts 按题序保留——回退不清已答）。 */
  wizardUpdate(id, mutate) {
    const self = this;
    const cards = this.data.cards.map(function (c) {
      if (c.id !== id) return c;
      const next = mutate(Object.assign({}, c));
      next.qnow = self.wizardView(next);
      return next;
    });
    this.setData({ cards: cards });
  },

  /** G22 上一题/下一题（hasPrevious = index>0——上游 ElicitationDialog :686 同判）。 */
  onWizardPrev(event) { this.wizardStep(event.currentTarget.dataset.id, -1); },
  onWizardNext(event) { this.wizardStep(event.currentTarget.dataset.id, 1); },

  wizardStep(id, delta) {
    if (this.data.stamps[id] !== undefined) return;
    this.wizardUpdate(id, function (c) {
      const total = (c.questions || []).length;
      return Object.assign(c, {
        wizardIndex: Math.max(0, Math.min((c.wizardIndex || 0) + delta, total - 1)),
      });
    });
  },

  /** 选项点选存草稿不即发（multiSelect 多选、单选互斥——iOS toggle 同构）。 */
  onWizardToggle(event) {
    const d = event.currentTarget.dataset;
    const id = d.id;
    const index = Number(d.index);
    const optionId = d.option;
    const multi = d.multi === true || d.multi === 'true';
    if (this.data.stamps[id] !== undefined) return;
    this.wizardUpdate(id, function (c) {
      const picked = (c.wizardSelections[index] || []).slice();
      const has = picked.indexOf(optionId) >= 0;
      let next;
      if (multi) {
        next = has ? picked.filter(function (x) { return x !== optionId; }) : picked.concat([optionId]);
      } else {
        next = has ? [] : [optionId];
      }
      const selections = Object.assign({}, c.wizardSelections);
      selections[index] = next;
      return Object.assign(c, { wizardSelections: selections });
    });
  },

  /** 补充输入（回退不清已答：texts 按题序键保留）。 */
  onWizardTextInput(event) {
    const d = event.currentTarget.dataset;
    const id = d.id;
    const index = Number(d.index);
    this.wizardUpdate(id, function (c) {
      const texts = Object.assign({}, c.wizardTexts);
      texts[index] = event.detail.value;
      return Object.assign(c, { wizardTexts: texts });
    });
  },

  /** 跳过 = {action:'cancel'}（iOS skip 同构；盖章随 approved=true 朱印「照办」）。 */
  onWizardSkip(event) {
    const id = event.currentTarget.dataset.id;
    if (this.data.stamps[id] !== undefined) return;
    this.resolveCard(id, { action: 'cancel' }, true);
  },

  /** 提交全部回答（chat-model.questionAnswersAnswer：iOS submitQuestions 信封同构）。 */
  onWizardSubmit(event) {
    const id = event.currentTarget.dataset.id;
    const card = this.data.cards.filter(function (c) { return c.id === id; })[0];
    if (!card || this.data.stamps[id] !== undefined) return;
    const model = require('../../utils/chat-model.js');
    this.resolveCard(
      id,
      model.questionAnswersAnswer(card.questions || [], card.wizardSelections || {}, card.wizardTexts || {}),
      true,
    );
  },

  /** G21 拒绝反馈输入（per-card 草稿；提交时 trim 非空才携带 answer.freeText）。 */
  onFeedbackInput(event) {
    const id = event.currentTarget.dataset.id;
    const cards = this.data.cards.map(function (c) {
      return c.id === id ? Object.assign({}, c, { feedbackDraft: event.detail.value }) : c;
    });
    this.setData({ cards: cards });
  },

  /** G23 插件卡应答（answer 只带 {optionId: "install"|"skip"}——V4InteractionDialogs
   * tsx:357-367 choice 字面量直接作 optionId【实证·上游仓】）。 */
  onPluginRespond(event) {
    const id = event.currentTarget.dataset.id;
    const optionId = event.currentTarget.dataset.option;
    const card = this.data.cards.filter(function (c) { return c.id === id; })[0];
    if (!card || this.data.stamps[id] !== undefined) return;
    this.resolveCard(id, { optionId: optionId }, optionId === 'install');
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

  /** 印向推导（motion-spec：reject / decline → 墨印「不准」，其余 → 朱印「照办」）。 */
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
    // G21：反馈原文 trim 非空才携带（command.ts:317【实证·上游仓】）
    const answer = { optionId: this.permissionOptionId(card, false) };
    const feedback = (card.feedbackDraft || '').trim();
    if (feedback) answer.freeText = feedback;
    this.resolveCard(id, answer, false);
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
    const self = this;
    // 点击即变（乐观落态）+ 失败回滚：与 iOS/Android 同口径——干等回执时
    // 用户观感就是「点了没反应」（2026-11-25 三端统一修）
    const next = msg.feedback === value ? null : value;
    this.setData({
      rows: this.data.rows.map(function (r) {
        return r.id === id ? Object.assign({}, r, { feedback: next }) : r;
      }),
    });
    const rollback = function () {
      self.setData({
        rows: self.data.rows.map(function (r) {
          return r.id === id ? Object.assign({}, r, { feedback: msg.feedback }) : r;
        }),
      });
    };
    conn.setAssistantFeedback(sessionId, rowId, msg.entityId, next)
      .then(function (error) {
        if (error) { rollback(); wx.showToast({ title: error, icon: 'none' }); }
      })
      .catch(function (err) {
        rollback();
        wx.showToast({ title: (err && err.message) || '反馈失败', icon: 'none' });
      });
  },

  onBack() {
    wx.navigateBack({ delta: 1, fail: function () { wx.reLaunch({ url: '/pages/sessions/sessions' }); } });
  },

  /** G49 滚动即记忆（throttle 500ms；onUnload 补终值）。 */
  onListScroll(event) {
    const top = event.detail.scrollTop;
    const now = Date.now();
    if (!this._lastScrollSave || now - this._lastScrollSave > 500) {
      this._lastScrollSave = now;
      require('../../utils/scroll-memory.js').save(ChatScrollKey(this.data.sessionId), top);
    }
    this._lastScrollTop = top;
  },

  /** G15 @/# 提及面板开关（composer 钮；触屏对位——符号自动触发需键盘游标监听）。
   * scope tabs：@文件 / #会话（web $技能/子代理/白板 candidate 数据源未接小程序）。 */
  /** G18 prompt 历史（iOS cyclePromptHistory 同构——全局发送史 cap 50，连续重复
   * 不入账；单向循环走到最早回空草稿重新循环）。 */
  loadPromptHistory() {
    try {
      const raw = wx.getStorageSync('zcode.prompt.history.v1')
      if (!raw) return []
      return String(raw).split('\n').filter(function (line) { return line.length > 0 })
    } catch (err) {
      return []
    }
  },

  recordPromptHistory(text) {
    if (!text || !text.trim()) return
    const history = this.loadPromptHistory()
    if (history[history.length - 1] === text) return
    history.push(text)
    try {
      wx.setStorageSync('zcode.prompt.history.v1', history.slice(-50).join('\n'))
    } catch (err) { /* 存储失败静默 */ }
    this.promptHistoryPointer = null
  },

  onCyclePromptHistory() {
    const history = this.loadPromptHistory()
    if (!history.length) return
    let pointer = this.promptHistoryPointer
    let next
    if (pointer === null) next = history[history.length - 1]
    else if (pointer === 0) next = ''
    else next = history[pointer - 1]
    this.promptHistoryPointer = next === '' ? 0 : (pointer === null ? history.length - 1 : pointer - 1)
    this.setData({ draft: next })
    this.persistDraft(this.data.sessionId, next)
  },

  onMentionOpen() {
    this.setData({
      mentionOpen: true,
      mentionScope: 'files',
      mentionQuery: '',
      mentionResults: [],
      mentionSessions: [],
    });
  },

  onMentionScope(event) {
    const scope = event.currentTarget.dataset.scope;
    this.setData({
      mentionScope: scope,
      mentionQuery: '',
      mentionResults: [],
      mentionSessions: [],
      mentionSkills: [],
    });
    if (scope === 'skills') {
      // $ 技能：拉全量技能清单（本地词表过滤；数据源 = skills.list）
      const conn = require('../../utils/connection.js');
      conn.listSkills().then((skills) => {
        this.setData({ mentionSkills: skills });
      });
    }
  },

  /** G15 #会话候选检索（commandCenterSearch sessions 通道复用）。 */
  fetchMentionSessions(trimmed) {
    const conn = require('../../utils/connection.js');
    conn.commandCenterSearch(trimmed).then((result) => {
      this.setData({ mentionSessions: result.sessions });
    });
  },

  onMentionClose() {
    this.setData({ mentionOpen: false });
  },

  onMentionQuery(event) {
    const value = event.detail.value;
    this.setData({ mentionQuery: value });
    if (this._mentionJob) clearTimeout(this._mentionJob);
    const trimmed = value.trim();
    if (!trimmed) {
      this.setData({ mentionResults: [] });
      return;
    }
    this._mentionJob = setTimeout(() => {
      const conn = require('../../utils/connection.js');
      if (this.data.mentionScope === 'files') {
        conn.searchWorkspaceFiles(trimmed, 50).then((results) => {
          this.setData({ mentionResults: results });
        });
      } else if (this.data.mentionScope === 'skills') {
        // 本地词表过滤（技能量级小；全量已在 onMentionScope 拉取）
        conn.listSkills().then((skills) => {
          const lowered = trimmed.toLowerCase();
          this.setData({
            mentionSkills: skills.filter(function (s) {
              return s.name.toLowerCase().indexOf(lowered) >= 0 ||
                s.description.toLowerCase().indexOf(lowered) >= 0;
            }),
          });
        });
      } else {
        this.fetchMentionSessions(trimmed);
      }
    }, 400);
  },

  /** G25 保存为工作流（wx.showModal 输入名称；target_exists 走覆盖确认——覆盖是
   * 用户决定，不是保存副作用；失败/成功 notice 如实上屏）。 */
  onSaveRun(event) {
    const runId = event.currentTarget.dataset.runid;
    const run = this.data.workflowRuns.filter(function (r) { return r.id === runId; })[0];
    if (!run) return;
    const that = this;
    wx.showModal({
      title: '保存为工作流',
      editable: true,
      placeholderText: '工作流名称',
      success(res) {
        if (!res.confirm) return;
        const name = (res.content || '').trim();
        if (!name) return;
        const conn = require('../../utils/connection.js');
        conn.saveWorkflowRun(runId, name, '', 'project', false).then((outcome) => {
          if (outcome.ok) {
            const savedPatch = {};
            savedPatch['savedByRun.' + runId] = { name: outcome.name, scope: outcome.scope };
            savedPatch.workflowNotice = outcome.overwritten ? '已覆盖保存' : '已保存';
            that.setData(savedPatch);
            that.refreshRuns();
            return;
          }
          if (outcome.targetExists) {
            wx.showModal({
              title: '同名工作流已存在',
              content: '覆盖目标档已有的同名定义？',
              success(res2) {
                if (!res2.confirm) return;
                conn.saveWorkflowRun(runId, name, '', 'project', true).then((outcome2) => {
                  if (outcome2.ok) {
                    that.setData({
                      workflowSaved: { name: outcome2.name, scope: outcome2.scope },
                      workflowNotice: outcome2.overwritten ? '已覆盖保存' : '已保存',
                    });
                    that.refreshRuns();
                  } else {
                    that.setData({ workflowNotice: outcome2.text || '保存失败' });
                  }
                });
              },
            });
            return;
          }
          that.setData({ workflowNotice: outcome.text || '保存失败' });
        });
      },
    });
  },

  /** G25 再次运行（startSavedWorkflow；成功 toast 如实）。 */
  onRerunRun(event) {
    const runId = event.currentTarget.dataset.runid;
    const run = this.data.workflowRuns.filter(function (r) { return r.id === runId; })[0];
    if (!run) return;
    const saved = this.data.savedByRun[runId] || this.data.workflowSaved;
    const name = saved ? saved.name : run.name;
    const scope = saved ? saved.scope : 'project';
    const conn = require('../../utils/connection.js');
    conn.rerunWorkflow(name, scope).then((res) => {
      if (res.ok) {
        this.setData({ workflowNotice: '已发起再次运行' });
      } else {
        this.setData({ workflowNotice: res.text || '再次运行失败' });
      }
    });
  },

  refreshRuns() {
    const conn = require('../../utils/connection.js');
    conn.workflowRunsFor(this.data.sessionId).then((runs) => {
      this.setData({ workflowRuns: runs });
    });
  },

  /** G15 点选候选：文件插 @"path"、会话插 "#" + 标题（draft 引用），并关面板。 */
  onMentionPick(event) {
    const kind = event.currentTarget.dataset.kind;
    let reference = '';
    if (kind === 'session') {
      const id = event.currentTarget.dataset.id;
      const title = event.currentTarget.dataset.title || '';
      if (!id) return;
      reference = '#' + title + ' ';
    } else if (kind === 'skill') {
      const name = event.currentTarget.dataset.name;
      if (!name) return;
      reference = '$' + name + ' ';
    } else {
      const path = event.currentTarget.dataset.path;
      if (!path) return;
      reference = '@"' + path + '" ';
    }
    const current = this.data.draft;
    const next = current.isEmpty ? reference : current + reference;
    this.setData({ draft: next, mentionOpen: false });
    this.persistDraft(this.data.sessionId, next);
  },

  onDraftInput(event) {
    this.setData({ draft: event.detail.value });
    // G50 草稿持久化
    this.persistDraft(this.data.sessionId, event.detail.value);
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
