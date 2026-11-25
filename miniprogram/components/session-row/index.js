// 任务行 —— 对位 web li[data-testid=task-item-<id>]（实测 360x92，r8px，padding 4/4/4/10，gap8）：
//   ├ 置顶钮 16x16（仅置顶行；web 为可点图钉）
//   ├ 标题行：标题 14px 单行省略 + 相对时间 12px alpha .6
//   │   + 行内动作（web 为 hover 显示的 24x24 文件/归档按钮 —— 移动端改为长按菜单，见 APP-DIFF[D006]）
//   │   + G33 行内待批徽标（web TaskInteractionBadge 同构；userInput 可点暂停）
//   └ 工作流迷你轨道（有 running/attention 的会话才渲染）
const sessionModel = require('../../utils/session-model.js');

Component({
  properties: {
    row: {
      type: Object,
      value: null,
      observer(newRow) {
        this.updateBadge(newRow);
      },
    },
  },
  data: {
    badge: null,
    worktreeLabel: null,
  },
  lifetimes: {
    attached() {
      this.updateBadge(this.data.row);
      this.updateWorktreeLabel(this.data.row);
      // 倒计时环每 5s 重算（行本体不随秒重渲；小程序无 TimelineView，用定时器近似——
      // 5s 粒度对「暂停自动决议」的可点提示足够）
      this._badgeTimer = setInterval(() => this.updateBadge(this.data.row), 5000);
    },
    detached() {
      if (this._badgeTimer) clearInterval(this._badgeTimer);
    },
  },
  methods: {
    updateBadge(row) {
      if (!row || !row.pendingInteractionKind) {
        this.setData({ badge: null });
        return;
      }
      const nowMs = Date.now();
      const isUserInput = row.pendingInteractionKind === 'userInput';
      const progress = sessionModel.countdownProgress
        ? sessionModel.countdownProgress(row, nowMs)
        : null;
      const snoozed = !!row.pendingInteractionSnoozedAt;
      this.setData({
        badge: {
          kind: row.pendingInteractionKind,
          label: row.pendingInteractionKind === 'permission' ? '待批' : '提问',
          canSnooze: isUserInput && !snoozed && !!row.pendingInteractionId,
          progress: progress,
        },
      });
    },
    updateWorktreeLabel(row) {
      const model = require('../../utils/session-model.js');
      this.setData({
        worktreeLabel: model.worktreeLabel ? model.worktreeLabel(row && row.worktreeRoot) : null,
      });
    },

    onBadgeTap() {
      const badge = this.data.badge;
      if (!badge || !badge.canSnooze) return;
      this.triggerEvent('snooze', { id: this.data.row && this.data.row.id });
    },
    onOpen() {
      this.triggerEvent('open', { id: this.data.row && this.data.row.id });
    },
    onLongPress() {
      this.triggerEvent('menu', { id: this.data.row && this.data.row.id });
    },
  },
});
