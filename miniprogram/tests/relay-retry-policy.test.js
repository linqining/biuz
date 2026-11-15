// 中继重连退避策略对拍（node --test tests/relay-retry-policy.test.js）
// 权威语义：web v3.18.1 packages/shared/src/web-remote-relay/domain/retry.ts
// （commit c5904686ea83，WebRemoteControlV2RetryPolicy）。
// 确定性向量：全部通过 options.random 注入 0 / 0.5 / 1，不依赖真实 Math.random。
const { test } = require('node:test');
const assert = require('assert');
const retry = require('../utils/relay-retry-policy.js');

/** 固定返回值的 random 替身。 */
function fixedRandom(value) {
  return function () { return value; };
}

test('常量与默认参数对齐 web（spread=1500 / initial=1000 / max=30000 / jitter=0.2）', function () {
  assert.strictEqual(retry.RELAY_RECONNECT_SPREAD_MS, 1500);
  assert.deepStrictEqual(retry.RELAY_RETRY_DEFAULTS, {
    initialMs: 1000,
    maxMs: 30000,
    jitterRatio: 0.2,
    spreadMs: 1500,
  });
});

test('acknowledged 后首次重连：0..1500 打散窗口，random=0/0.5/1 → 0/750/1499', function () {
  // 窗口实现 Math.floor(random*1500)，整数上界 1499（random=1 为注入边界，由 clamp 守住）
  const p0 = retry.createRelayRetryPolicy({ random: fixedRandom(0) });
  p0.acknowledged();
  assert.strictEqual(p0.nextDelay(), 0);

  const pHalf = retry.createRelayRetryPolicy({ random: fixedRandom(0.5) });
  pHalf.acknowledged();
  assert.strictEqual(pHalf.nextDelay(), 750);

  const p1 = retry.createRelayRetryPolicy({ random: fixedRandom(1) });
  p1.acknowledged();
  assert.strictEqual(p1.nextDelay(), 1499);
});

test('spread 只生效一次：首次打散后，第二次 nextDelay 回到指数曲线', function () {
  const p = retry.createRelayRetryPolicy({ random: fixedRandom(0.5) });
  p.acknowledged();
  assert.strictEqual(p.nextDelay(), 750, '第一次：打散窗口随机');
  // 第二次起回到指数曲线 n=0，base=1000，random=0.5 → 乘数 0.8+0.5*0.4=1.0
  assert.strictEqual(p.nextDelay(), 1000, '第二次：指数曲线 n=0 base=1000');
});

test('spread 消费后的指数序列与 jitter（n=0/n=1，random=0 与 1）', function () {
  const p = retry.createRelayRetryPolicy({ random: fixedRandom(0) });
  p.acknowledged();
  assert.strictEqual(p.nextDelay(), 0, 'spread 一次（random=0）');
  assert.strictEqual(p.nextDelay(), 800, '指数 n=0：1000*(1-0.2)=800');
  assert.strictEqual(p.nextDelay(), 1600, '指数 n=1：2000*(1-0.2)=1600');

  const q = retry.createRelayRetryPolicy({ random: fixedRandom(1) });
  q.acknowledged();
  assert.strictEqual(q.nextDelay(), 1499, 'spread 一次（random=1 边界 clamp）');
  assert.strictEqual(q.nextDelay(), 1200, '指数 n=0：1000*(1+0.2)=1200');
  assert.strictEqual(q.nextDelay(), 2400, '指数 n=1：2000*(1+0.2)=2400');
});

test('reset：用户主动重连/回前台不打散，且指数计数归零', function () {
  const p = retry.createRelayRetryPolicy({ random: fixedRandom(0) });
  // 未 acknowledged 直接 reset：不打散，n=0
  p.reset();
  assert.strictEqual(p.nextDelay(), 800, 'reset 后首调走指数曲线而非 spread');

  // 先累积两档指数（n=0、n=1），再 reset，计数应回到 n=0
  assert.strictEqual(p.nextDelay(), 1600, 'reset 后第二次调用为 n=1');
  p.reset();
  assert.strictEqual(p.nextDelay(), 800, '再次 reset 后从 n=0 重新开始');

  // acknowledged 后立刻 reset：spread 被清除，不打散
  const q = retry.createRelayRetryPolicy({ random: fixedRandom(0) });
  q.acknowledged();
  q.reset();
  assert.strictEqual(q.nextDelay(), 800, 'acknowledged 后 reset 应取消待生效 spread');
});

test('jitter ±20% 边界：同档 base 下 random=0/0.5/1 乘数 0.8/1.0/1.2', function () {
  const low = retry.createRelayRetryPolicy({ random: fixedRandom(0) });
  assert.strictEqual(low.nextDelay(), 800, '下界 base*0.8');

  const mid = retry.createRelayRetryPolicy({ random: fixedRandom(0.5) });
  assert.strictEqual(mid.nextDelay(), 1000, '中值 base*1.0');

  const high = retry.createRelayRetryPolicy({ random: fixedRandom(1) });
  assert.strictEqual(high.nextDelay(), 1200, '上界 base*1.2');
});

test('指数延迟封顶 maxMs=30000（random=1：第 6 次起恒为 30000）', function () {
  const p = retry.createRelayRetryPolicy({ random: fixedRandom(1) });
  const delays = [];
  for (let i = 0; i < 20; i += 1) delays.push(p.nextDelay());
  // 基数序列 1000/2000/4000/8000/16000，第 6 档 base=min(30000,32000)=30000
  assert.deepStrictEqual(delays.slice(0, 5), [1200, 2400, 4800, 9600, 19200]);
  assert.strictEqual(delays[5], 30000, 'base 与 jitter 双层封顶后取 30000');
  assert.ok(delays.slice(5).every(function (d) { return d === 30000; }), '后续恒为封顶值');
  assert.ok(delays.every(function (d) { return d <= 30000; }), '任何延迟不得超过 maxMs');
});

test('base 先于 jitter 封顶：random=0 第 6 档为 30000*0.8=24000', function () {
  const p = retry.createRelayRetryPolicy({ random: fixedRandom(0) });
  let last = 0;
  for (let i = 0; i < 6; i += 1) last = p.nextDelay();
  assert.strictEqual(last, 24000, 'base 已封到 30000，再乘下界乘数 0.8');
});

test('未 acknowledged 的全新实例：首次 nextDelay 直接走指数曲线，不打散', function () {
  const p = retry.createRelayRetryPolicy({ random: fixedRandom(0.5) });
  assert.strictEqual(p.nextDelay(), 1000, '无 spread 待生效，n=0 base=1000');
});

test('acknowledged 语义：清指数计数，且 spread 仅 arm 一次（重复 acknowledged 不叠加）', function () {
  const p = retry.createRelayRetryPolicy({ random: fixedRandom(0.5) });
  p.nextDelay(); // n=0 → 1000，failures→1
  p.nextDelay(); // n=1 → 2000，failures→2
  p.acknowledged(); // 计数清零并 arm spread
  p.acknowledged(); // 重复调用不改变一次性语义
  assert.strictEqual(p.nextDelay(), 750, 'ack 后第一次仍是打散');
  assert.strictEqual(p.nextDelay(), 1000, 'spread 只一次；计数从 n=0 重启而非沿用 n=2');
});

test('自定义 options 注入（initialMs/maxMs/jitterRatio/spreadMs）逐档生效', function () {
  const p = retry.createRelayRetryPolicy({
    random: fixedRandom(1),
    initialMs: 100,
    maxMs: 500,
    jitterRatio: 0.5,
    spreadMs: 10,
  });
  p.acknowledged();
  assert.strictEqual(p.nextDelay(), 9, '自定义窗口 floor(1*10) 后 clamp 到 spreadMs-1=9');
  assert.strictEqual(p.nextDelay(), 150, '指数 n=0：100*(1+0.5)=150');
  const capped = [];
  for (let i = 0; i < 8; i += 1) capped.push(p.nextDelay());
  assert.ok(capped.every(function (d) { return d <= 500; }), '自定义 maxMs 封顶生效');
  assert.strictEqual(capped[capped.length - 1], 500);
});

test('默认 Math.random 冒烟：spread 结果为 0..1499 的整数，无需注入亦可工作', function () {
  const p = retry.createRelayRetryPolicy();
  p.acknowledged();
  const d = p.nextDelay();
  assert.ok(Number.isInteger(d), '延迟为整数');
  assert.ok(d >= 0 && d <= 1499, '打散窗口整数域 0..1499，实际=' + d);
});
