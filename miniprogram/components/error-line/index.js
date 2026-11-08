// 写面失败必须上屏服务端原文（AGENTS.md §5-12）；读面失败保留 last-good + 失败文案。
Component({
  properties: {
    message: { type: String, value: "" },
    retryable: { type: Boolean, value: false },
  },
  methods: {
    onRetry() {
      this.triggerEvent("retry");
    },
  },
});
