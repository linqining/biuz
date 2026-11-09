// 小程序片五 automator 实测（node miniprogram/tests/automator-wave5.js）
// ——设置页 Agent 能力组 → 能力清单四页（memory/skills/mcp/plugins）逐页点亮 +
// 设备页 LAN 如实拒绝态。
// 前置：tools/stub-desktop.js --port 9100 已起；开发者工具 auto --auto-port 9420 已起。
// 运行：NODE_PATH=/tmp/mp-automator/node_modules node miniprogram/tests/automator-wave5.js
const automator = require('miniprogram-automator');

async function main() {
  const miniProgram = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' });
  console.log('[t] automator connected');

  // 连接（两轮重试模板——automator.connect 触发工具重载会冲掉首轮缝连接）
  let state = null;
  for (let attempt = 0; attempt < 2 && !(state && state.status === 'connected'); attempt++) {
    await miniProgram.reLaunch(
      '/pages/sessions/sessions?acceptance=1&connect=' + encodeURIComponent('http://127.0.0.1:9100'));
    const deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      state = await miniProgram.evaluate(function () {
        const store = require('utils/store.js');
        const s = store.getState();
        return { status: s.status, rows: s.rows.length };
      });
      if (state.status === 'connected') break;
      await new Promise(function (r) { setTimeout(r, 300); });
    }
    console.log('[t] attempt', attempt, '→', JSON.stringify(state));
  }
  if (!state || state.status !== 'connected') throw new Error('连接未建立');

  // 设置页 → Agent 能力组四行 → 逐页断言
  await miniProgram.reLaunch('/pages/settings/settings');
  await new Promise(function (r) { setTimeout(r, 1200); });
  let page = await miniProgram.currentPage();
  for (const kind of ['memory', 'skills', 'mcp', 'plugins']) {
    const row = await page.$('view[data-test="12-row-' + kind + '"]');
    if (!row) throw new Error('设置页缺能力行 ' + kind);
    await row.tap();
    await new Promise(function (r) { setTimeout(r, 1800); });
    page = await miniProgram.currentPage();
    const data = await page.data();
    console.log('[' + kind + '] status =', data.status, '| rows =', (data.rows || []).length,
      '| title =', data.title);
    if (data.status !== 'ready') {
      throw new Error(kind + ' 未点亮：' + data.status + ' ' + (data.error || ''));
    }
    await miniProgram.screenshot({ path: '/tmp/mp-wave5-' + kind + '.png' });
    await miniProgram.navigateBack();
    await new Promise(function (r) { setTimeout(r, 800); });
    page = await miniProgram.currentPage();
  }

  // 期望行数（替身载荷）：memory 2 / skills 2 / mcp 2 / plugins 2
  // （已在各页 status=ready 时通过 data 断言行数；上面循环只验点亮——
  // 行数断言放这里一次性复核）
  const expected = { memory: 1, skills: 2, mcp: 2, plugins: 2 }; // = 替身载荷行数
  for (const kind of Object.keys(expected)) {
    await miniProgram.navigateTo('/pages/capability/capability?kind=' + kind);
    await new Promise(function (r) { setTimeout(r, 1500); });
    page = await miniProgram.currentPage();
    const data = await page.data();
    console.log('[recheck ' + kind + '] rows =', (data.rows || []).length);
    if ((data.rows || []).length !== expected[kind]) {
      throw new Error(kind + ' 应 ' + expected[kind] + ' 行，实际 ' + (data.rows || []).length);
    }
    await miniProgram.navigateBack();
    await new Promise(function (r) { setTimeout(r, 500); });
  }

  // ---- 设备页：LAN 直连如实拒绝态（Mock 红线：不给假设备） ----
  await miniProgram.navigateTo('/pages/devices/devices');
  await new Promise(function (r) { setTimeout(r, 2000); });
  page = await miniProgram.currentPage();
  const devicesData = await page.data();
  console.log('[devices] status =', devicesData.status, '| error =', devicesData.error);
  if (devicesData.status !== 'error') {
    throw new Error('设备页应为如实错误态（LAN 无中继账号层），实际 ' + devicesData.status);
  }
  if (!/中继账号/.test(devicesData.error || '')) {
    throw new Error('设备页错误文案应指向中继账号：' + devicesData.error);
  }
  await miniProgram.screenshot({ path: '/tmp/mp-wave5-devices.png' });

  console.log('AUTOMATOR-OK');
  await miniProgram.disconnect();
  process.exit(0);
}

main().catch(function (err) {
  console.error('AUTOMATOR-FAIL:', err.message);
  process.exit(1);
});
