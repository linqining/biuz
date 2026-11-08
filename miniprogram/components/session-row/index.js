// 任务行 —— 对位 web li[data-testid=task-item-<id>]（实测 360x92，r8px，padding 4/4/4/10，gap8）：
//   ├ 置顶钮 16x16（仅置顶行；web 为可点图钉）
//   ├ 标题行：标题 14px 单行省略 + 相对时间 12px alpha .6
//   │   + 行内动作（web 为 hover 显示的 24x24 文件/归档按钮 —— 移动端改为长按菜单，见 APP-DIFF）
//   └ 工作流迷你轨道（有 running/attention 的会话才渲染）
Component({
  properties: {
    row: { type: Object, value: null },
  },
  methods: {
    onOpen() {
      this.triggerEvent('open', { id: this.data.row && this.data.row.id });
    },
    onLongPress() {
      this.triggerEvent('menu', { id: this.data.row && this.data.row.id });
    },
  },
});
