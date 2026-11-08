// 工作流迷你轨道（home 行内）—— 对位 web：
//   packages/ui/... task-item 内 6x6 圆点 + 6x1 连接线 + 12px 说明文字
//   实测色：运行 #46BF72 / 待操作 #FF8A30 / 未开始 rgba(255,255,255,.35) / 连接线 rgba(255,255,255,.6)
Component({
  properties: {
    // { label: string, states: Array<'done'|'active'|'attention'|'pending'>, extra: string }
    track: { type: Object, value: null },
  },
  data: {
    dotClass: {
      done: 'dot dot--done',
      active: 'dot dot--active',
      attention: 'dot dot--attention',
      pending: 'dot dot--pending',
    },
  },
});
