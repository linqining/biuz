// 004/005 连接中 & 连接失败 纯逻辑对拍（node miniprogram/tests/connection-screen.test.js）
// 金标：webRemoteControlConnectingScreen.tsx:17-70 与 webRemoteControlScreenCopy.ts:16-141。
const assert = require('assert');
const model = require('../utils/connection-screen-model.js');

let passed = 0;
function test(name, fn) {
  fn();
  passed += 1;
  console.log('  ok  ' + name);
}

test('12 种失败原因全部有文案', () => {
  const reasons = Object.keys(model.FAILURE);
  assert.strictEqual(reasons.length, 12, '应为 12 种：' + reasons.join(','));
  reasons.forEach((r) => {
    const copy = model.failureCopy(r);
    assert.ok(copy.badge && copy.title && copy.description, r + ' 缺字段');
    assert.ok(copy.nextSteps.length >= 2, r + ' 下一步应至少两步');
    assert.ok(['danger', 'warning', 'neutral'].includes(copy.tone), r + ' tone 非法');
  });
});

test('未知 reason 回落到 unexpected-error', () => {
  assert.strictEqual(model.failureCopy('nope').title, 'Web 远程控制失败');
  assert.strictEqual(model.failureCopy(undefined).badge, '未知异常');
});

test('校验失败文案与线上基线一致', () => {
  const copy = model.failureCopy('invalid-mobile-connection');
  assert.strictEqual(copy.badge, '校验失败');
  assert.strictEqual(copy.title, '手机连接已失效');
  assert.deepStrictEqual(copy.nextSteps, ['不要复用旧截图或旧链接。', '回到桌面端扫描最新二维码。']);
  assert.strictEqual(copy.action, '重新连接');
  assert.strictEqual(model.NEXT_STEPS_TITLE, '下一步');
});

test('连接中四段步骤与 web 逐字一致', () => {
  assert.deepStrictEqual(model.CONNECTING.steps, [
    '连接中转服务', '设备鉴权', '等待桌面端配对', '同步工作区',
  ]);
});

test('阶段 → 4 段进度下标', () => {
  assert.strictEqual(model.connectingStage('connecting').stepIndex, 0);
  assert.strictEqual(model.connectingStage('authenticating').stepIndex, 1);
  assert.strictEqual(model.connectingStage('waiting').stepIndex, 2);
  assert.strictEqual(model.connectingStage('paired').stepIndex, 3);
  assert.strictEqual(model.connectingStage('paired').label, '已配对，正在加载工作区…');
});

test('非线性态不高亮任何一段', () => {
  const re = model.connectingStage('reconnecting');
  assert.strictEqual(re.stepIndex, -1);
  assert.strictEqual(re.label, '连接中断，正在重连…');
  assert.strictEqual(re.description, '网络或休眠恢复后会自动重连，请稍候。');

  const sus = model.connectingStage('suspended');
  assert.strictEqual(sus.stepIndex, -1);
  assert.strictEqual(sus.description, '回到前台后会自动恢复连接。');

  assert.strictEqual(model.connectingStage('weird').stepIndex, -1);
  assert.strictEqual(model.connectingStage('weird').label, '正在准备远程控制…');
});

test('标题：带设备名 / 无设备名', () => {
  assert.strictEqual(model.connectingTitle('MacBook-Pro-9.local'), '正在连接 MacBook-Pro-9.local');
  assert.strictEqual(model.connectingTitle(''), '正在连接电脑');
});

console.log('\n' + passed + ' 项通过');
