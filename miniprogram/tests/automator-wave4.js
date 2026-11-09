// 小程序片四 automator 实测（node miniprogram/tests/automator-wave4.js）
// ——chat/files/git 三页数据源点亮 + 渲染断言。
// 前置：tools/stub-desktop.js --port 9100 已起；开发者工具 auto --auto-port 9420 已起。
// 运行：NODE_PATH=/tmp/mp-automator/node_modules node miniprogram/tests/automator-wave4.js
const automator = require('miniprogram-automator');

async function main() {
  const miniProgram = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' });
  console.log('[t] automator connected');

  // 先经 sessions 页 acceptance 缝建立连接（各页数据源随 connectDesktop 接线）
  // automator.connect 触发工具重载——首轮 acceptance 缝可能被冲掉（实测竞态）
  // ——至多两轮：每轮 reLaunch 缝 URL + 轮询 connected
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
  if (!state || state.status !== 'connected') throw new Error('连接未建立：' + JSON.stringify(state));

  // ---- chat 页：readSessionMessages → 消息渲染 ----
  // navigateTo（保持运行时；reLaunch 在本环境偶发触发重载把 store 冲掉——实测）
  await miniProgram.navigateTo('/pages/chat/chat?sessionId=sess-stub-1');
  await new Promise(function (r) { setTimeout(r, 2000); });
  let page = await miniProgram.currentPage();
  let data = await page.data();
  console.log('[chat] status =', data.status, '| rows =', (data.rows || []).length,
    '| title =', data.title);
  if (data.status !== 'ready') throw new Error('chat 未点亮：' + data.status + ' ' + (data.error || ''));
  if ((data.rows || []).length !== 3) throw new Error('chat 应 3 条消息，实际 ' +
    (data.rows || []).length);
  const kinds = data.rows.map(function (m) { return m.role + '/' + m.kind; });
  console.log('[chat] kinds =', JSON.stringify(kinds));
  await miniProgram.screenshot({ path: '/tmp/mp-wave4-chat.png' });

  // ---- files 页：file.readdir 递归 → 树渲染 ----
  await miniProgram.navigateBack();
  await new Promise(function (r) { setTimeout(r, 800); });
  await miniProgram.navigateTo('/pages/files/files');
  await new Promise(function (r) { setTimeout(r, 2000); });
  // 首刷可能竞态（连接重建期）——轮询至树非空（last-good 语义：重试即填）
  let filesState = null;
  const filesDeadline = Date.now() + 8000;
  while (Date.now() < filesDeadline) {
    page = await miniProgram.currentPage();
    data = await page.data();
    filesState = data;
    if (data.status === 'ready' && (data.tree || []).length > 0) break;
    // 镜像页面 onShow 重试面：空 last-good 时重触发读面
    await miniProgram.evaluate(function () {
      require('utils/files-store.js').refresh();
    });
    await new Promise(function (r) { setTimeout(r, 400); });
  }
  console.log('[files] status =', filesState.status, '| rows(扁平) =', (filesState.rows || []).length,
    '| workspacePath =', filesState.workspacePath);
  if (filesState.status !== 'ready') throw new Error('files 未点亮：' + filesState.status);
  // 页面 setData 键 = rows（flatten 后扁平树）而非 tree——含 src 目录 + README.md + app.ts
  const flatRows = filesState.rows || [];
  if (!flatRows.length) throw new Error('files 扁平树为空');
  // flatten 行形态 = {node, depth, expandable, expanded}——名字在 node.name
  const flatNames = flatRows.map(function (r) { return r.node && r.node.name; });
  console.log('[files] flat names =', JSON.stringify(flatNames));
  if (flatNames.indexOf('README.md') < 0) throw new Error('files 树缺 README.md');
  if (data.workspacePath !== '/Users/e2e/zcode-workspace') {
    throw new Error('workspacePath 注入失败: ' + data.workspacePath);
  }
  await miniProgram.screenshot({ path: '/tmp/mp-wave4-files.png' });

  // ---- git 页：git.getChanges → diff files 渲染 ----
  await miniProgram.navigateBack();
  await new Promise(function (r) { setTimeout(r, 800); });
  await miniProgram.navigateTo('/pages/git/git');
  await new Promise(function (r) { setTimeout(r, 2000); });
  let gitState = null;
  const gitDeadline = Date.now() + 8000;
  while (Date.now() < gitDeadline) {
    page = await miniProgram.currentPage();
    data = await page.data();
    gitState = data;
    if (data.status === 'ready' && (data.files || []).length > 0) break;
    await miniProgram.evaluate(function () {
      require('utils/diff-store.js').refresh();
    });
    await new Promise(function (r) { setTimeout(r, 400); });
  }
  console.log('[git] status =', gitState.status, '| sections =', (gitState.sections || []).length);
  if (gitState.status !== 'ready') throw new Error('git 未点亮：' + gitState.status);
  // 页面 setData 键 = sections（groupBySegment 分组）；组内文件数合计应 2
  let gitFileCount = 0;
  const gitPaths = [];
  (gitState.sections || []).forEach(function (s) {
    (s.files || []).forEach(function (f) {
      gitFileCount += 1;
      gitPaths.push(f.path || (f.file && f.file.path));
    });
  });
  console.log('[git] paths =', JSON.stringify(gitPaths));
  if (gitFileCount !== 2) throw new Error('git 应 2 条变更，实际 ' + gitFileCount);
  await miniProgram.screenshot({ path: '/tmp/mp-wave4-git.png' });

  console.log('AUTOMATOR-OK');
  await miniProgram.disconnect();
  process.exit(0);
}

main().catch(function (err) {
  console.error('AUTOMATOR-FAIL:', err.message);
  process.exit(1);
});
