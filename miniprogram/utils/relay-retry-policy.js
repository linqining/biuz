// 中继重连退避策略（纯策略层）—— web v3.18.1
// `packages/shared/src/web-remote-relay/domain/retry.ts` 的 JS 移植
// （权威来源 commit：c5904686ea83，WebRemoteControlV2RetryPolicy 逐语义对齐）。
//
// 语义对齐（web v3.18.1 新策略）：
// 1. 连接 acknowledged 后的「第一次」重连不走指数曲线，而在 0..spreadMs 打散窗口内
//    一次性随机（spreadNext，仅一次），避免多设备 thundering herd；
// 2. 用户主动 start / 回前台走 reset()：清退避计数与 spread，reset 后不打散；
// 3. 其后回到指数退避 initialMs=1000、maxMs=30000、jitterRatio=0.2（±20%）；
// 4. 常量 RELAY_RECONNECT_SPREAD_MS=1500，对应 web
//    WEB_REMOTE_CONTROL_V2_RECONNECT_SPREAD_MS。
//
// 仅策略层，本提交不接 wx socket 生命周期；不依赖任何 wx API，wx/Node 双环境可直接 require。

/** 连接建立后第一次重连的打散窗口（ms），电脑与手机共用，对齐 web 同名常量。 */
const RELAY_RECONNECT_SPREAD_MS = 1500;

/** 默认退避参数（对齐 web backoff：initialMs 1000 / maxMs 30000 / jitter 0.2）。 */
const RELAY_RETRY_DEFAULTS = {
  initialMs: 1000,
  maxMs: 30000,
  jitterRatio: 0.2,
  spreadMs: RELAY_RECONNECT_SPREAD_MS,
};

/**
 * 创建一个中继重连策略实例（每个 transport 实例独占一个，对齐 web 每实例独占语义）。
 *
 * @param {Object} [options]
 * @param {() => number} [options.random] 随机数注入，默认 Math.random；
 *        确定性单测可固定返回 0 / 0.5 / 1。真值域约定 [0,1)。
 * @param {number} [options.initialMs=1000] 指数退避首档基数（ms）。
 * @param {number} [options.maxMs=30000] 延迟硬封顶（ms），base 与 jitter 后各封一次。
 * @param {number} [options.jitterRatio=0.2] jitter 比例，乘数区间 [1-r, 1+r]（±20%）。
 * @param {number} [options.spreadMs=1500] acknowledged 后首次重连的打散窗口（ms）。
 * @returns {{nextDelay: () => number, acknowledged: () => void, reset: () => void}}
 */
function createRelayRetryPolicy(options) {
  const opts = options || {};
  const random = typeof opts.random === 'function' ? opts.random : Math.random;
  const initialMs = opts.initialMs != null ? opts.initialMs : RELAY_RETRY_DEFAULTS.initialMs;
  const maxMs = opts.maxMs != null ? opts.maxMs : RELAY_RETRY_DEFAULTS.maxMs;
  const jitterRatio = opts.jitterRatio != null ? opts.jitterRatio : RELAY_RETRY_DEFAULTS.jitterRatio;
  const spreadMs = opts.spreadMs != null ? opts.spreadMs : RELAY_RETRY_DEFAULTS.spreadMs;

  // 对齐 web 私有态：failures=指数失败计数，spreadNext=下一次重连是否走一次性打散。
  let failures = 0;
  let spreadNext = false;

  return {
    /**
     * 用户触发的连接（start、回前台）——对齐 web reset()：
     * 清退避计数与 spread 标记，按原曲线尽快重连，不打散。
     */
    reset: function () {
      failures = 0;
      spreadNext = false;
    },

    /**
     * 连接已建立（auth_ack 后）——对齐 web acknowledged()：
     * 清零指数计数，并 arm 一次性 spread：下一次 nextDelay 在整个打散窗口内随机，
     * 只打散这一次，失败后回到原指数曲线。
     */
    acknowledged: function () {
      failures = 0;
      spreadNext = true;
    },

    /**
     * 返回下一次重连延迟（ms）。每次调用只消费一次 random()。
     *
     * 分支一（spreadNext=true，仅 acknowledged 后的第一次）：
     *   web 原文 Math.floor(random() * spreadMs)。Math.random() 真值域为 [0,1)，
     *   floor 结果天然落在整数 0..spreadMs-1；注入式 random 在确定性单测里可能返回
     *   恰好 1，故用 Math.min(spreadMs - 1, …) 守住窗口上界——生产环境 random()<1
     *   时该 clamp 永不触发，与 web 数值完全一致。
     *
     * 分支二（指数退避）：对齐 web
     *   base   = min(maxMs, initialMs * 2^min(failures, 16))，failures 后置自增；
     *   delay  = min(maxMs, round(base * (1 - jitter + random() * jitter * 2)))，
     *   乘数区间 [1-jitterRatio, 1+jitterRatio]，即 ±20%，外层再封 maxMs。
     *
     * @returns {number} 下一次重连延迟（ms）
     */
    nextDelay: function () {
      if (spreadNext) {
        spreadNext = false;
        return Math.min(spreadMs - 1, Math.floor(random() * spreadMs));
      }
      const base = Math.min(maxMs, initialMs * 2 ** Math.min(failures, 16));
      failures += 1;
      return Math.min(
        maxMs,
        Math.round(base * (1 - jitterRatio + random() * jitterRatio * 2)),
      );
    },
  };
}

module.exports = {
  RELAY_RECONNECT_SPREAD_MS: RELAY_RECONNECT_SPREAD_MS,
  RELAY_RETRY_DEFAULTS: RELAY_RETRY_DEFAULTS,
  createRelayRetryPolicy: createRelayRetryPolicy,
};
