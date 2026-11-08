// 分区标题（已置顶 / 项目 / 任务 / 未分组）—— 对位 web：
//   h3[data-testid=project-pinned-tasks] h3（14px/500/alpha .3，padding 4px 10px）
//   section[data-testid=project-section] 折叠箭头（virtual-project-toggle-all）
Component({
  properties: {
    title: { type: String, value: '' },
    count: { type: Number, value: 0 },
    collapsible: { type: Boolean, value: false },
    collapsed: { type: Boolean, value: false },
    sectionKey: { type: String, value: '' },
  },
  methods: {
    onToggle() {
      if (!this.data.collapsible) return;
      this.triggerEvent('toggle', { key: this.data.sectionKey });
    },
  },
});
