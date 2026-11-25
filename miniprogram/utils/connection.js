// 连接管理器（应用级）—— 片三：wx 运行时接入 + sessions store 数据源点亮。
//
// 数据流：connectDesktop({baseUrl, token})
//   → rpc-connection.connect（server-info → WS → v4 握手）
//   → store.setIdentity({deviceName, accountName})
//   → store.setSource(读面 fn)（listTaskList → session-model 行形状映射）
//   → store.refresh()
//
// 纪律（AGENTS.md §5-13 / store.js 头注）：未连接/失败如实上屏，不填假数据；
// 读面失败保留 last-good（store.refresh 已按此实现）。address 持久化于
// utils/credential-store.js 的加密容器（baseUrl/token 同容器；原来明文落
// `biuz.desktop`——见 APP-DIFF[D014] 与 PARITY_BACKLOG P-031），支持重连。

const rpcConnection = require('./rpc-connection.js');
const relayV2 = require('./relay-v2.js');
const wxTransport = require('./wx-transport.js');
const credentialStore = require('./credential-store.js');
const store = require('./store.js');
const chatStore = require('./chat-store.js');
const filesStore = require('./files-store.js');
const diffStore = require('./diff-store.js');
const trajectoryStore = require('./trajectory-store.js');
const capabilityStore = require('./capability-store.js');
const capability = require('./capability.js');
const devicesStore = require('./devices-store.js');

let active = null; // { client, serverInfo, workspace, hello, clientId }

function getState() {
  return active;
}

/** 上次连接地址（解不开时按失败原因区分：unavailable/decrypt 都如实返回 null + reason）。 */
function lastAddressState() {
  return credentialStore.readDesktopAddress();
}

function lastAddress() {
  const state = lastAddressState();
  return state.baseUrl ? { baseUrl: state.baseUrl, token: state.token } : null;
}

function saveAddress(baseUrl, token) {
  // 写失败不阻断连接（web：凭据写失败只影响「下次自动重连」），但原因可被诊断面读到
  // P-031 修复：用异步版，先预取平台 CSPRNG 再写；池不足时明确失败而非写弱密钥
  const result = credentialStore.saveDesktopAddressAsync(baseUrl, token || '');
  if (result && typeof result.then === 'function') {
    result.catch(function () { /* 不阻断连接；诊断面另有记录 */ });
  }
  return result;
}

/**
 * 连接桌面并点亮 sessions store 数据源。失败 reject Error（message 直接上屏）。
 */
function connectDesktop(params) {
  const baseUrl = (params && params.baseUrl || '').trim();
  const token = (params && params.token || '').trim();
  if (!baseUrl) return Promise.reject(new Error('请先填写桌面端地址'));
  saveAddress(baseUrl, token);
  // 注入面：单测传假 socketFactory/httpGet/httpPost；运行时用 wx 薄壳
  const socketFactory = (params && params.socketFactory) || wxTransport.wxSocketFactory;
  const httpGet = (params && params.httpGet) || wxTransport.wxHttpGet;
  const httpPost = (params && params.httpPost) || wxTransport.wxHttpPost;
  return rpcConnection.connect({
    baseUrl: baseUrl,
    token: token,
    appVersion: '1.0.0',
    socketFactory: socketFactory,
    httpGet: httpGet,
  }).then(function (result) {
    active = result;
    store.setIdentity({
      deviceName: result.serverInfo.name || '桌面端',
      accountName: result.hello.authUserId || '',
    });
    store.setSource(function () { return fetchTaskRows(result.client); });
    // 片四：chat/files/diff/trajectory 各页数据源同构接线（全部走 client 唯一出口）
    wireChatSource(result);
    wireFilesSource(result);
    wireGitSource(result);
    wireTrajectorySource(result);
    // 片五：能力清单四页（memory/skills/mcp/plugins）共用参数化读面
    capabilityStore.setSource(function (kind) {
      return capability.loadCapability(result.client, result.workspace, kind);
    });
    // 设备页（003）：设备目录来自中继账号服务（GET /api/v1/remote/device，
    // 需 OAuth 账号层）——LAN 直连如实拒绝（Mock 红线：不给假设备）；
    // 真机路径 = 中继 V2 接入波次
    devicesStore.setSource(function () {
      return Promise.reject(new Error('设备列表来自中继账号服务；当前为局域网直连，请用中继链接接入后查看'));
    });
    store.refresh();
    return result;
  });
}

/**
 * 中继 V2 连接（真机数据路径；写面波次⑥——Android RelayV2.kt + fetchToken 直译）：
 * relay link → POST /api/v1/remote/token（Bearer JWT）→ wss /remote/ws2 →
 * auth_init（文本帧，无 role）→ auth_ack paired → 桥内 rpc（二进制 rpc-frame）
 * → sessions 等 store 数据源同 LAN 口径点亮；设备页接中继设备目录
 * （GET /api/v1/remote/device，Bearer JWT——Mock 红线解除：JWT 在场才接）。
 */
/** 读取持久化 JWT（login 页 P-018 写入；connectRelayDesktop 自动回填）。
 *  来源 = 加密容器（原 `biuz.jwt` 明文键，见 APP-DIFF[D014]）；读失败按原因分辨。 */
function getRelayJwtState() {
  return credentialStore.readJwt();
}

function getRelayJwt() {
  const state = getRelayJwtState();
  return state.outcome === credentialStore.OUTCOME_OK ? state.value : '';
}

function connectRelayDesktop(params) {
  // relayLink 双形态：string（→ parseRelayLink）或 parsed object（跳过解析——
  // acceptance 缝 wss→ws 降级后传 parsed 对象）
  const relayLinkInput = params && params.relayLink;
  const parsed = typeof relayLinkInput === 'string'
    ? relayV2.parseRelayLink(relayLinkInput.trim())
    : relayLinkInput;
  // JWT 来源：显式传入 > 持久化（login 页粘贴写入）
  const jwt = (params && params.jwt || '').trim() || getRelayJwt();
  if (!parsed) return Promise.reject(new Error('中继链接无效（需 https + /remote/ + sid）'));
  if (!jwt) return Promise.reject(new Error('云中继需要登录 Z.ai 账号（缺 JWT）'));
  const socketFactory = (params && params.socketFactory) || wxTransport.wxSocketFactory;
  const httpPost = (params && params.httpPost) || wxTransport.wxHttpPost;
  const httpGetJson = (params && params.httpGetJson) || wxTransport.wxHttpGetJson;
  if (params.persist !== false) saveAddress(parsed.httpOrigin, jwt);
  return relayV2.connectRelayV2({
    relayLink: parsed,
    jwt: jwt,
    platform: 'miniprogram',
    appVersion: '1.0.0',
    socketFactory: socketFactory,
    httpPost: httpPost,
  }).then(function (result) {
    active = {
      client: makeRpcSocketClient(result),
      serverInfo: {
        name: parsed.machineName || '云端中继',
        version: parsed.desktopAppVersion || '',
      },
      workspace: { path: parsed.deviceMid || 'relay', workspaceIdentity: parsed.deviceMid },
      hello: { authUserId: '' },
      clientId: '',
      mode: 'relay-v2',
      jwt: jwt,
    };
    store.setIdentity({
      deviceName: active.serverInfo.name,
      accountName: '',
    });
    wireSources(active);
    store.refresh();
    return active;
  });
}

// MARK: - 审批卡数据面（盖章子轮 2026-11-22；取证出处协议文档 §7.1/§9.1.1/§10 +
// iOS RemoteConversationStore.ensureConversationSubscribed 同构）

/** 连接级写面/订阅面上下文（wireSources 装配；断开置 null）。 */
const commandFace = {
  client: null,
  workspacePath: '',
  clientId: '',
  frameStreamKey: '',
  subscriptionId: '',
  watermarkSeq: 0,
};

/** resyncConversationV4（§9.1.2）：base=null 全量重发快照——快照缺
 * pendingInteractions 键时补一次（iOS「重连后审核弹窗不显示」修复同构）。 */
function resyncConversation(sessionId) {
  const face = commandFace;
  if (!face.client || !face.subscriptionId || !face.workspacePath) return;
  face.client
    .call('zcode-agent', 'resyncConversationV4', [{
      subscriptionId: face.subscriptionId,
      base: null,
      workspacePath: face.workspacePath,
    }])
    .catch(function (err) {
      console.error('[朱批] resync 失败（丢帧自愈不可用，如实记录）', err);
    });
}

/**
 * conversation 帧流挂载（幂等；§9.1.1 帧 handler 先于订阅注册【工程纪律 3】）。
 * 事件形状取证：listen('zcode-agent', 'onDynamicConversationFrame',
 * {workspacePath}, handler)——iOS ensureConversationFrameStream 逐字同构；
 * payload = topic 帧流（frame.topic 精确匹配 conversation/<sessionId>）。
 */
function ensureConversationFrames(sessionId) {
  const face = commandFace;
  if (!face.client || !face.workspacePath) return;
  if (face.frameStreamKey === face.workspacePath) return; // 幂等
  face.frameStreamKey = face.workspacePath;
  face.client.listen(
    'zcode-agent',
    'onDynamicConversationFrame',
    { workspacePath: face.workspacePath },
    function (frame) {
      if (!frame || typeof frame !== 'object') return;
      if (frame.topic !== 'conversation/' + sessionId) return; // 帧按 topic 精确路由
      // subscriptionId 过滤（旧订阅迟到帧丢；帧缺 subId 不过滤——iOS 同口径）
      if (face.subscriptionId && frame.subscriptionId
        && frame.subscriptionId !== face.subscriptionId) return;
      // 水位/断档（A9 精简版）：delta 断档不猜，转全量 resync
      if (frame.snapshot == null && typeof frame.toSeq === 'number') {
        if (frame.toSeq <= face.watermarkSeq) return;
        if (typeof frame.fromSeq === 'number' && frame.fromSeq > face.watermarkSeq + 1) {
          resyncConversation(sessionId);
          return;
        }
      }
      face.watermarkSeq = typeof frame.toSeq === 'number' ? frame.toSeq : face.watermarkSeq;
      // pendingInteractions 提取（v1.30③ 双形态宽容：state 嵌套 ∪ 顶层扁平）
      const snap = frame.snapshot && typeof frame.snapshot === 'object' ? frame.snapshot : null;
      const delta = frame.delta && typeof frame.delta === 'object' ? frame.delta : null;
      const source = snap || delta;
      if (!source) return;
      const state = source.state && typeof source.state === 'object' ? source.state : null;
      const raw = (state && state.pendingInteractions) != null
        ? state.pendingInteractions
        : source.pendingInteractions;
      if (raw === undefined || raw === null) {
        // 快照带 state 但缺该键：桌面投影无变化，本地不清（iOS 纪律：不剥既有值）
        if (snap && !delta) resyncConversation(sessionId);
        return;
      }
      const chatModel = require('./chat-model.js');
      chatStore.setPendingInteractions(chatModel.parsePendingInteractions(raw));
    },
  );
}

/**
 * 订阅单会话（§9.1.1）：帧流先挂 → call subscribeConversationV4
 * {topic, sessionId, workspacePath} → subscriptionId 宽容提取（ack 包裹/顶层双形态）。
 * 取不到 subId = 丢帧自愈链不可用（AGENTS §5-7）——如实上屏，不静默。
 */
function subscribeConversation(sessionId) {
  const face = commandFace;
  if (!face.client || !sessionId || !face.workspacePath) {
    return Promise.resolve({ ok: false, failure: { kind: 'undelivered', text: '未连接桌面端' } });
  }
  ensureConversationFrames(sessionId);
  return face.client
    .call('zcode-agent', 'subscribeConversationV4', [{
      topic: 'conversation/' + sessionId,
      sessionId: sessionId,
      workspacePath: face.workspacePath,
    }])
    .then(function (reply) {
      const sub = ackStatusValue(reply);
      const subscriptionId = (reply && typeof reply === 'object')
        ? ((reply.ack && reply.ack.subscriptionId) || reply.subscriptionId)
        : null;
      if (!subscriptionId) {
        return { ok: false, failure: { kind: 'unknown', text: '订阅回执缺 subscriptionId（丢帧自愈链不可用）' } };
      }
      face.subscriptionId = subscriptionId;
      void sub;
      return { ok: true, subscriptionId: subscriptionId };
    })
    .catch(function (err) {
      return { ok: false, failure: { kind: 'undelivered', text: (err && err.message) || String(err) } };
    });
}

/** 宽容 ack 包裹提取（订阅回执面）。 */
function ackStatusValue(reply) {
  if (!reply || typeof reply !== 'object') return null;
  if (reply.ack && typeof reply.ack === 'object') return reply.ack;
  return reply;
}

/**
 * resolveInteraction 写面（§7.2【实证】：payload 恒 {interactionId, answer} 两键，
 * answer 恒为对象三族分形——权限 {optionId}/计划 {action}/提问 {freeText}|{optionId}）。
 * 信封走 sendConversationCommandV4（A1-A3：嵌套 envelope + sessionId 键恒在场 +
 * clientId 逐字相等 + issuedAt 毫秒 int；非 CAS 不带 base*）；ACK A13 判定——
 * 权威成功返回 {ok:true}，失败/未知态如实透出（U-5：写面禁止静默吞错）。
 */
function resolveInteraction(sessionId, interactionId, answer) {
  const face = commandFace;
  if (!face.client) {
    return Promise.resolve({ ok: false, failure: { kind: 'undelivered', text: '未连接桌面端，批准需要在连接后操作' } });
  }
  const rpcCommand = require('./rpc-command.js');
  const args = rpcCommand.makeSendConversationArgs({
    clientId: face.clientId,
    type: 'resolveInteraction',
    payload: { interactionId: interactionId, answer: answer },
    sessionId: sessionId,
    workspacePath: face.workspacePath,
  });
  return face.client
    .call('zcode-agent', 'sendConversationCommandV4', args)
    .then(function (ack) {
      const status = rpcCommand.ackStatus(ack);
      if (rpcCommand.isAuthoritativeSuccess(status)) return { ok: true, status: status };
      if (rpcCommand.isFailureStatus(status)) {
        const detail = (ack && (ack.reasonCode || (ack.ack && ack.ack.reasonCode)))
          || status || '?';
        const message = (ack && (ack.message || (ack.ack && ack.ack.message))) || '';
        return { ok: false, failure: { kind: 'rejected', text: detail + (message ? ' ' + message : '') } };
      }
      return { ok: false, failure: { kind: 'unknown', text: '回执状态缺席（旧桌面），视为未定案' } };
    })
    .catch(function (err) {
      return { ok: false, failure: { kind: 'undelivered', text: (err && err.message) || '命令未送达' } };
    });
}

/**
 * G33 行内倒计时暂停（snoozeInteractionAutoResolution；2026-11-25 与 iOS 同步）。
 * 信封纪律与 resolveInteraction 同构（非 CAS 不带 base*；payload 恒 {interactionId}
 * 单键——command.ts:342-344【实证·上游仓】；首次有效操作永久暂停，重复/迟到幂等 noop）。
 */
function snoozeInteractionAutoResolution(sessionId, interactionId) {
  const face = commandFace;
  if (!face.client) {
    return Promise.resolve({ ok: false, failure: { kind: 'undelivered', text: '未连接桌面端' } });
  }
  const rpcCommand = require('./rpc-command.js');
  const args = rpcCommand.makeSendConversationArgs({
    clientId: face.clientId,
    type: 'snoozeInteractionAutoResolution',
    payload: { interactionId: interactionId },
    sessionId: sessionId,
    workspacePath: face.workspacePath,
  });
  return face.client
    .call('zcode-agent', 'sendConversationCommandV4', args)
    .then(function (ack) {
      const status = rpcCommand.ackStatus(ack);
      if (rpcCommand.isAuthoritativeSuccess(status)) return { ok: true, status: status };
      if (rpcCommand.isFailureStatus(status)) {
        const detail = (ack && (ack.reasonCode || (ack.ack && ack.ack.reasonCode))) || status || '?';
        return { ok: false, failure: { kind: 'rejected', text: detail } };
      }
      return { ok: false, failure: { kind: 'unknown', text: '回执状态缺席（旧桌面）' } };
    })
    .catch(function (err) {
      return { ok: false, failure: { kind: 'undelivered', text: (err && err.message) || '命令未送达' } };
    });
}

/**
 * G15 @ 提及文件检索（file.searchWorkspaceFiles；2026-11-25 与 iOS RemoteFileStore
 * .searchFiles :204-222 同构：{rootPath, query, limit} → [{name,path,type}]）。
 * 未连接/失败如实空。
 */
function searchWorkspaceFiles(query, limit) {
  const face = commandFace;
  if (!face.client) return Promise.resolve([]);
  const trimmed = String(query || '').trim();
  if (!trimmed) return Promise.resolve([]);
  const args = [{ rootPath: face.workspacePath, query: trimmed, limit: limit || 50 }];
  return face.client.call('file', 'searchWorkspaceFiles', args).then(function (value) {
    const entries = Array.isArray(value) ? value : [];
    return entries.map(function (entry) {
      if (!entry || typeof entry !== 'object') return null;
      const name = entry.name;
      const path = entry.path;
      if (!name || !path) return null;
      return { id: String(path), name: String(name), path: String(path) };
    }).filter(function (row) { return !!row; });
  }).catch(function () { return []; });
}

/**
 * G25 工作流 run 查询/保存/再次运行（2026-11-25 与 iOS/Android 同步）。
 * wire 取证【实证·上游仓 + 协议文档 §9.1.11】：
 * - conversationWorkflowRunsV4 {workspacePath, sessionId, limit} → 宽容
 *   {runs} | {result:{runs}} | 单 run（iOS RemoteConversationStore :3594-3599 同宽容链）；
 * - saveSavedWorkflowFromRun {workspacePath, runId, name, meta:{description}, scope}
 *   → ok:true {name,scope,overwritten} | ok:false {reason}（zcode-protocol/index.ts:3599-3644；
 *   target_exists 仅在 UI 已见过覆盖警告后带 overwrite:true 重发——覆盖是用户决定）；
 * - 再次运行 = startSavedWorkflow 信封命令 {name, scope?}（command.ts:236-240）。
 */
function workflowRunsFor(sessionId) {
  const face = commandFace;
  if (!face.client) return Promise.resolve([]);
  const args = [{
    workspacePath: face.workspacePath,
    sessionId: sessionId,
    limit: 20,
  }];
  return face.client.call('zcode-agent', 'conversationWorkflowRunsV4', args)
    .then(function (value) {
      const runs = (value && value.runs) ||
        (value && value.result && value.result.runs) ||
        (value && value.result && value.result.workflowRun) || null;
      const list = Array.isArray(runs) ? runs : (runs ? [runs] : []);
      return list.map(parseWorkflowRun).filter(function (r) { return !!r; });
    }).catch(function () { return []; });
}

/** run 负载宽容解析（iOS parseWorkflowRun 投影子集：G25 卡消费面字段）。 */
function parseWorkflowRun(json) {
  const d = json && typeof json === 'object' ? json : null;
  if (!d) return null;
  const id = d.runId || d.workflowRunId || d.id;
  if (!id) return null;
  const concurrency = (d.concurrency && typeof d.concurrency === 'object') ? d.concurrency : {};
  return {
    id: String(id),
    name: d.name || d.workflowName || d.title || '工作流',
    rawStatus: d.status || 'pending',
    isLive: d.status === 'pending' || d.status === 'running',
    stopReason: d.stopReason || null,
    error: d.error || null,
    resultPreview: d.resultPreview || null,
    resumedFrom: d.resumedFrom || null,
    supersededBy: d.supersededBy || null,
    spentTokens: (d.usage && d.usage.spentTokens) || null,
    usageNodesUsed: (d.usage && d.usage.nodesUsed) || null,
    phaseCount: (d.phases && d.phases.length) || (d.phaseNames && d.phaseNames.length) || null,
    // G25 阶段链结构化（phases[].{name,status}——web phasesOf 同口径；phaseNames
    // 旧形态退化为名称列表 status 由 run 状态继承）
    phases: (Array.isArray(d.phases) && d.phases.length > 0)
      ? d.phases.map(function (p) {
          if (!p || typeof p !== 'object' || !p.name) return null;
          return { name: String(p.name), status: String(p.status || 'pending') };
        }).filter(function (p) { return !!p; })
      : (Array.isArray(d.phaseNames)
        ? d.phaseNames.map(function (n) {
            return n ? { name: String(n), status: d.status || 'pending' } : null;
          }).filter(function (p) { return !!p; })
        : []),
    artifactsCount: (d.artifacts && d.artifacts.length) || 0,
    concurrencyLimit: concurrency.limit || null,
    concurrencyCap: concurrency.cap || null,
    concurrencyCooldownMs: concurrency.cooldownMs || null,
    failureCode: d.failureCode || null,
    workId: d.workId || String(id),
  };
}

/** G25 从 run 保存工作流（返回 {ok, text?, name?, scope?, overwritten?}）。 */
function saveWorkflowRun(sessionId, runId, name, description, scope, overwrite) {
  const face = commandFace;
  if (!face.client) {
    return Promise.resolve({ ok: false, text: '未连接桌面端' });
  }
  const fields = {
    workspacePath: face.workspacePath,
    runId: runId,
    name: name,
    meta: { description: description || '' },
    scope: scope || 'project',
  };
  if (overwrite) fields.overwrite = true;
  return face.client.call('zcode-agent', 'saveSavedWorkflowFromRun', [fields])
    .then(function (value) {
      const d = value || {};
      if (d.ok === true) {
        return { ok: true, name: d.name || name, scope: d.scope || scope,
                 overwritten: d.overwritten === true };
      }
      const reason = d.reason || 'unknown';
      if (reason === 'target_exists') return { ok: false, targetExists: true };
      return { ok: false, text: d.detail || reason };
    }).catch(function (err) {
      return { ok: false, text: (err && err.message) || '连接中断或超时' };
    });
}

/** G25 已存判定（workflows/forRun {workspace, runId} → entry {name,scope}；
 * entry 缺席 = 未保存——判定推导得来，用户删档后自动翻回「保存」。iOS
 * savedWorkflowForRun :5390 同 wire。失败不挡保存——上游「探测失败不该挡住保存，
 * agent 仍会用 target_exists 给出最终答案」同口径。） */
function savedWorkflowForRun(sessionId, runId) {
  const face = commandFace;
  if (!face.client) return Promise.resolve(null);
  const args = [{
    workspacePath: face.workspacePath,
    sessionId: sessionId,
    runId: runId,
  }];
  return face.client.call('zcode-agent', 'findSavedWorkflowForRun', args)
    .then(function (value) {
      const entry = value && value.entry;
      if (!entry || !entry.name) return null;
      return { name: String(entry.name), scope: String(entry.scope || 'project') };
    }).catch(function () { return null; });
}

/** G25 再次运行（startSavedWorkflow 信封命令 {name, scope?}；非 CAS 直发）。 */
function rerunWorkflow(name, scope) {
  const face = commandFace;
  if (!face.client) {
    return Promise.resolve({ ok: false, text: '未连接桌面端' });
  }
  const rpcCommand = require('./rpc-command.js');
  const payload = { name: name };
  if (scope) payload.scope = scope;
  const args = rpcCommand.makeSendConversationArgs({
    clientId: face.clientId,
    type: 'startSavedWorkflow',
    payload: payload,
    sessionId: null, // 无会话上下文——桌面创建新会话（iOS :4585 同口径）
    workspacePath: face.workspacePath,
  });
  return face.client.call('zcode-agent', 'sendConversationCommandV4', args)
    .then(function (ack) {
      const status = rpcCommand.ackStatus(ack);
      if (rpcCommand.isAuthoritativeSuccess(status)) return { ok: true };
      return { ok: false, text: rpcCommand.isFailureStatus(status)
        ? (status || '桌面端拒绝') : '回执状态缺席（旧桌面）' };
    }).catch(function (err) {
      return { ok: false, text: (err && err.message) || '命令未送达' };
    });
}

/**
 * G15 $ 技能候选（skills 频道 list；2026-11-25 wire 取证【实证·上游仓
 * skills/skills.ts:43-45 createServiceDescriptor(ServiceChannels.Skills)——wire
 * 方法名 = 接口方法名，task-groups read/save 先例同形态】params
 * {workspacePath, workspaceIdentity?} → SkillsListResult {skills:[SkillSummary
 * {id,name,description,…}], capability, diagnostics}。$ 触发语义归一（¥/￥→$
 * 只归一触发不改输入字符——promptInputTriggers.ts:31-36 同规）。
 */
function listSkills() {
  const face = commandFace;
  if (!face.client) return Promise.resolve([]);
  const args = [{ workspacePath: face.workspacePath }];
  return face.client.call('skills', 'list', args).then(function (value) {
    const skills = (value && value.skills) || [];
    return skills.map(function (s) {
      if (!s || typeof s !== 'object' || !s.id) return null;
      return {
        id: String(s.id),
        name: String(s.name || s.id),
        description: String(s.description || ''),
      };
    }).filter(function (row) { return !!row; });
  }).catch(function () { return []; });
}

/**
 * G42 前置 文件文本读取（file.readTextFile；2026-11-25 wire 取证【实证·上游仓
 * FileTextSlice 回执 {path, content, offset, bytesRead, totalBytes}——Android
 * RemoteFileStore.readTextSlice :347-361 同构】）。截断提示由调用方按 totalBytes 判。
 */
function readFileText(path, offset, length) {
  const face = commandFace;
  if (!face.client) return Promise.resolve(null);
  const fields = { path: path };
  if (offset > 0) fields.offset = offset;
  if (length) fields.length = length;
  return face.client.call('file', 'readTextFile', [fields]).then(function (value) {
    const d = value || {};
    if (typeof d.content !== 'string') return null;
    return {
      content: d.content,
      totalBytes: Number(d.totalBytes || 0),
      bytesRead: Number(d.bytesRead || 0),
    };
  }).catch(function () { return null; });
}

/**
 * G38 命令中心检索（2026-11-25 与 iOS CommandCenterSheet 同构）：
 * - 会话/任务 = zcode-task listTaskList {kind:"timeline", workspaceScopes, sortBy,
 *   limit:50, search}（wire 取证见 iOS searchSessions :3527-3550——键名 `search`
 *   非 searchQuery，2026-10-08 §11.1#7）；
 * - 文件 = file.searchWorkspaceFiles {rootPath, query, limit}（iOS :204-222 同构）。
 * 返回 {sessions, files}；未连接/失败如实空（写面禁静默的读面镜像）。
 */
function commandCenterSearch(query) {
  const face = commandFace;
  if (!face.client) {
    return Promise.resolve({ sessions: [], files: [] });
  }
  const trimmed = String(query || '').trim();
  if (!trimmed) return Promise.resolve({ sessions: [], files: [] });
  const listTaskArgs = [{
    kind: 'timeline',
    workspaceScopes: [{ workspacePath: face.workspacePath }],
    sortBy: 'updated',
    limit: 50,
    search: trimmed,
  }];
  const fileArgs = [{
    rootPath: face.workspacePath,
    query: trimmed,
    limit: 20,
  }];
  return Promise.all([
    face.client.call('zcode-task', 'listTaskList', listTaskArgs).catch(function () { return null; }),
    face.client.call('file', 'searchWorkspaceFiles', fileArgs).catch(function () { return null; }),
  ]).then(function (results) {
    const taskValue = results[0];
    const fileValue = results[1];
    const items = (taskValue && taskValue.items) || [];
    const sessions = items.map(function (item) {
      if (!item || typeof item !== 'object') return null;
      const id = item.sessionId || item.id || item.taskId;
      if (!id) return null;
      return {
        id: String(id),
        title: item.title || '未命名会话',
        projectName: item.projectName || null,
        updatedAt: Number(item.updatedAt || 0),
      };
    }).filter(function (row) { return !!row; });
    const fileEntries = (fileValue && Array.isArray(fileValue)) ? fileValue : [];
    const files = fileEntries.map(function (entry) {
      if (!entry || typeof entry !== 'object') return null;
      const name = entry.name;
      const path = entry.path;
      if (!name || !path) return null;
      return {
        id: String(path),
        name: String(name),
        path: String(path),
        isDirectory: entry.type === 'directory',
      };
    }).filter(function (row) { return !!row; });
    return { sessions: sessions, files: files };
  });
}

/**
 * G36 分组组色/置顶（task-groups 频道 read/save/move；2026-11-25 与 iOS/Android 同步）。
 * wire 取证【实证·上游仓 task-groups/contract.ts:4-33 + index.ts:31-34 + repository.ts:189-202】：
 * - read 回执 {groups:[{id,title,color,…}], members:[…], order:[…]}；
 * - save {id, color} 更新组色（七档词表）；
 * - move 组节点 {node:{type:"group",groupId}, sourceGroupId:null, groupId:null} 无
 *   anchor = 顶层 index 0 = 置顶（组只能在顶层重排）。
 */
function readTaskGroups() {
  const face = commandFace;
  if (!face.client) return Promise.resolve(null);
  const args = [{ workspacePath: face.workspacePath }];
  return face.client.call('task-groups', 'read', args).then(function (value) {
    const d = value || {};
    const groups = (d.groups || []).map(function (g) {
      return { id: g.id, title: g.title, color: g.color || null };
    }).filter(function (g) { return g.id; });
    const members = (d.members || []).map(function (m) {
      return { groupId: m.groupId, taskId: m.taskId };
    }).filter(function (m) { return m.groupId && m.taskId; });
    return { groups: groups, members: members };
  }).catch(function () { return null; });
}

function saveTaskGroupColor(groupId, color) {
  const face = commandFace;
  if (!face.client) return Promise.resolve({ ok: false, text: '未连接桌面端' });
  const args = [{
    workspacePath: face.workspacePath,
    id: groupId,
    color: color,
  }];
  return face.client.call('task-groups', 'save', args).then(function () {
    return { ok: true };
  }).catch(function (err) {
    return { ok: false, text: (err && err.message) || '保存失败' };
  });
}

function pinTaskGroupToTop(groupId) {
  const face = commandFace;
  if (!face.client) return Promise.resolve({ ok: false, text: '未连接桌面端' });
  const args = [{
    workspacePath: face.workspacePath,
    node: { type: 'group', groupId: groupId },
    sourceGroupId: null,
    groupId: null,
  }];
  return face.client.call('task-groups', 'move', args).then(function () {
    return { ok: true };
  }).catch(function (err) {
    return { ok: false, text: (err && err.message) || '置顶失败' };
  });
}

/** 断开时清写面上下文（store 断开路径统一调用）。 */
function disposeCommandFace() {
  commandFace.client = null;
  commandFace.workspacePath = '';
  commandFace.clientId = '';
  commandFace.frameStreamKey = '';
  commandFace.subscriptionId = '';
  commandFace.watermarkSeq = 0;
}

/** 桥内 rpc 客户端包装（auth 配对后的二进制 rpc-frame 面）。 */
function makeRpcSocketClient(result) {
  const rpcClient = require('./rpc-client.js');
  return rpcClient.createRpcClient({
    send: function (bytes) { result.socket.sendBinary(bytes); },
    onMessage: function (cb) { result.socket.onBinary(cb); },
    onClose: function (cb) { result.socket.onClosed(cb); },
  });
}

/** store 数据源装配（LAN/中继共用——同一 RPC 面）。 */
function wireSources(result) {
  const client = result.client;
  const workspacePath = result.workspace && result.workspace.path;
  // 盖章子轮（2026-11-22）：审批卡数据面上下文——clientId 为握手注册值（A2：
  // 信封 clientId 必须逐字相等），workspace 为连接主工作区（单 workspace 场景
  // 即会话归属；多区归属寻址待片四后续波次）
  commandFace.client = client;
  commandFace.workspacePath = workspacePath || '';
  commandFace.clientId = result.clientId || '';
  store.setSource(function () { return fetchTaskRows(client); });
  chatStore.setSource(function (sessionId) {
    return client
      .call('zcode-agent', 'readSessionMessages', [{ sessionId: sessionId, limit: 200 }])
      .then(function (value) { return mapChatMessages(value, sessionId); });
  });
  filesStore.setSource(function () {
    const root = workspacePath;
    return readDirRecursiveClient(client, root, 0).then(function (tree) {
      return { tree: tree, workspacePath: root };
    });
  });
  diffStore.setSource(function () {
    return client
      .call('git', 'getChanges', [{ workspacePath: workspacePath }])
      .then(function (value) { return mapGitChanges(value); });
  });
  trajectoryStore.setSource(function (sessionId) {
    return client
      .call('zcode-agent', 'readSessionMessages', [{ sessionId: sessionId, limit: 200 }])
      .then(function (value) {
        const mapped = mapChatMessages(value, sessionId);
        return { entries: mapped.messages, taskTitle: sessionId };
      });
  });
  capabilityStore.setSource(function (kind) {
    return capability.loadCapability(client, result.workspace, kind);
  });
  devicesStore.setSource(function () {
    // 中继设备目录（GET /api/v1/remote/device，Bearer JWT——Android
    // RelayDeviceCatalog.parse 直译）；jwt 缺席如实拒绝
    const jwt = (result.jwt || '').trim();
    if (!jwt) return Promise.reject(new Error('设备列表来自中继账号服务；当前未携带登录凭据'));
    const origin = result.httpOrigin || 'https://zcode.z.ai';
    const httpGetJson = (result.httpGetJson) || wxTransport.wxHttpGetJson;
    return httpGetJson(origin + '/api/v1/remote/device', jwt).then(function (response) {
      if (response.status !== 200) throw new Error('设备目录 HTTP ' + response.status);
      const json = response.body || {};
      const rows = json.data || [];
      return {
        devices: rows.map(function (row) {
          const meta = row.meta || {};
          return {
            deviceSid: row.device_sid, deviceMid: row.device_mid,
            online: row.online === true, name: meta.name || null,
            platform: meta.platform || null, version: meta.version || null,
          };
        }),
        account: null,
      };
    });
  });
}

/**
 * 行级反馈（P-020；Android RemoteConversationStore.setAssistantFeedback 同参）：
 * `zcode-agent.setAssistantFeedback` — payload {target:{rowId, entityId}, feedback:
 * "like"|"dislike"|null}。entityId 缺席如实拒（Android client.rowCursorUnavailable
 * 同口径）。返回 null=成功；非 null=可上屏错误文案。
 */
function setAssistantFeedback(sessionId, rowId, entityId, value) {
  const conn = active && active.client;
  if (!conn) return Promise.reject(new Error('未连接桌面端'));
  if (!entityId) return Promise.reject(new Error('该消息不可反馈（缺少行游标）'));
  const payload = {
    target: { rowId: rowId, entityId: entityId },
    feedback: value === null ? null : (value ? 'like' : 'dislike'),
  };
  return conn
    .call('zcode-agent', 'setAssistantFeedback', [payload])
    .then(function (reply) {
      // 回执六态词表（accepted|rejected|stale|duplicate|noop|failed）——此前直接
      // `.then(() => null)` 吞掉 ack，桌面拒收也当成功（用户点完什么都没发生）
      var status = reply && reply.status;
      if (!status || status === 'accepted' || status === 'noop' || status === 'duplicate') {
        return null;
      }
      var reason = (reply && (reply.message || reply.reasonCode)) || status;
      return '反馈被拒 · ' + String(reason).substring(0, 120);
    })
    .catch(function (err) {
      return '反馈失败 · ' + ((err && err.message) || String(err)).substring(0, 120);
    });
}

function disconnect() {
  if (active && active.client && active.client.close) {
    active.client.close();
  }
  active = null;
  disposeCommandFace();
  chatStore.setPendingInteractions([]);
  store.setSource(null);
  store.refresh();
}

/**
 * listTaskList 回执 → session-model 行形状（normalizeRow 消费键）。
 * 宽容映射：sessionId|id 二键、pinned|isPinned 二键、workspacePath 末段兜底
 * projectName；认不出的条目跳过（不整份失败）。
 */
function mapTaskRows(value) {
  const items = (value && value.items) || [];
  return items
    .map(function (item) {
      if (!item || typeof item !== 'object') return null;
      const id = item.sessionId || item.id;
      if (!id) return null;
      let projectName = item.projectName || null;
      if (!projectName && item.workspacePath) {
        const segments = String(item.workspacePath).split('/').filter(Boolean);
        projectName = segments.length ? segments[segments.length - 1] : null;
      }
      // G33 轻量投影宽容读（zcode-task-types-core.ts:262-268【实证·上游仓】形状；
      // listTaskList 行是否携带未取证——缺席/词表外即 null，徽标不渲染，不造假面）
      let lightId = null;
      let lightKind = null;
      let lightDeadline = null;
      let lightVisibleAt = null;
      let lightSnoozedAt = null;
      const light = (item.pendingInteraction && typeof item.pendingInteraction === 'object')
        ? item.pendingInteraction : null;
      if (light && light.interactionId && (light.kind === 'permission' || light.kind === 'userInput')) {
        lightId = String(light.interactionId);
        lightKind = light.kind;
        const auto = (light.autoResolution && typeof light.autoResolution === 'object')
          ? light.autoResolution : null;
        if (auto) {
          lightDeadline = Number(auto.deadlineAt) || null;
          lightVisibleAt = Number(auto.visibleAt) || null;
          lightSnoozedAt = Number(auto.snoozedAt) || null;
        }
      }
      // G40 残面（2026-11-25 与 iOS/Android 同步）：cron/off-peak/worktree/远端设备
      // 四族字段宽容读（zcode-task-types.ts:56-64 + worktreeLabel.ts + 
      // taskSourceAttribution.ts:19-24【实证·上游仓】；缺席 = null 不渲染）
      return {
        id: String(id),
        title: item.title || '未命名会话',
        summary: item.summary || item.preview || '',
        status: item.status || 'idle',
        updatedAt: Number(item.updatedAt || 0),
        isPinned: item.pinned === true || item.isPinned === true,
        unread: item.unread === true || item.unreadCount > 0,
        projectName: projectName,
        groupName: item.groupName || null,
        workflowActivity: item.workflowActivity || null,
        pendingInteractionId: lightId,
        pendingInteractionKind: lightKind,
        pendingInteractionDeadline: lightDeadline,
        pendingInteractionVisibleAt: lightVisibleAt,
        pendingInteractionSnoozedAt: lightSnoozedAt,
        isCron: !!(item.cronAutomationId || item.automationId),
        isOffPeak: !!item.offPeakTaskId,
        worktreeRoot: item.worktreeRoot || item.worktree_root || null,
        serverDisplayName: item.serverDisplayName || null,
      };
    })
    .filter(function (row) { return row !== null; });
}

// ---- 片四：各页数据源接线（形状证据见各映射函数头注） ----

/** 递归读目录（client 直收版——中继/局域网共用；深度 3 层）。 */
function readDirRecursiveClient(client, dirPath, depth) {
  if (depth > 3) return Promise.resolve([]);
  return client
    .call('file', 'readdir', [{ path: dirPath }])
    .then(function (value) {
      const entries = (value && (value.entries || value)) || [];
      if (!Array.isArray(entries)) return [];
      return Promise.all(
        entries.map(function (entry) {
          if (!entry || typeof entry !== 'object') return null;
          const name = entry.name || entry.fileName || '';
          if (!name) return null;
          const entryPath = entry.path || dirPath + '/' + name;
          const isDirectory = entry.isDirectory === true || entry.directory === true
            || entry.kind === 'directory';
          const node = {
            id: entryPath,
            name: name,
            path: entryPath,
            isDirectory: isDirectory,
            size: entry.size == null ? null : Number(entry.size),
            children: null,
          };
          if (!isDirectory) return node;
          return readDirRecursiveClient(client, entryPath, depth + 1)
            .then(function (children) { node.children = children; return node; });
        }),
      ).then(function (nodes) { return nodes.filter(function (n) { return n !== null; }); });
    })
    .catch(function () { return []; });
}

/**
 * 会话页数据源：`zcode-agent.readSessionMessages {sessionId, limit?}` →
 * ZCodeMessageWithParts[]（zcode-protocol-legacy-types.ts:458-463 strict：
 * {info:{role: user|assistant,…}, parts:[{type: text|reasoning|tool…, text?}]})。
 * 映射到 chat-store {title, messages:[{role, kind, text}]}（chat-model
 * normalizeMessage 消费键：role user|agent、kind tool|thinking|text）。
 */
function wireChatSource(result) {
  chatStore.setSource(function (sessionId) {
    return result.client
      .call('zcode-agent', 'readSessionMessages', [
        { sessionId: sessionId, limit: 200 },
      ])
      .then(function (value) { return mapChatMessages(value, sessionId); });
  });
}

function mapChatMessages(value, sessionId) {
  const messages = (Array.isArray(value) ? value : [])
    .map(function (msg) {
      if (!msg || typeof msg !== 'object' || !msg.parts) return null;
      const role = msg.info && msg.info.role === 'user' ? 'user' : 'agent';
      const texts = { text: [], thinking: [] };
      let toolText = null;
      (msg.parts || []).forEach(function (part) {
        if (!part || typeof part !== 'object') return;
        if (part.type === 'text' && part.text) texts.text.push(part.text);
        else if (part.type === 'reasoning' && part.text) texts.thinking.push(part.text);
        else if (part.type === 'tool_use' || part.type === 'tool') {
          toolText = part.name || part.toolName || '工具调用';
        }
      });
      if (toolText) {
        return { role: role, kind: 'tool', text: toolText };
      }
      if (texts.thinking.length) {
        return { role: role, kind: 'thinking', text: texts.thinking.join('\n\n') };
      }
      if (!texts.text.length) return null; // 无可见内容的行不渲染（web 同口径）
      return { role: role, kind: 'text', text: texts.text.join('\n\n') };
    })
    .filter(function (row) { return row !== null; });
  return { title: sessionId, messages: messages };
}

/**
 * 文件页数据源：`file.readdir {path, includeHidden?}` → FileEntry[] 递归
 * （Android RemoteFileStore.fileTree 同法：深度限制 3 层、跳过隐藏目录）。
 * FileEntry 字段宽容读取（name/path/directory|isDirectory/size）。
 */
function wireFilesSource(result) {
  filesStore.setSource(function () {
    const root = result.workspace.path;
    return readDirRecursive(result, root, 0).then(function (tree) {
      return { tree: tree, workspacePath: root };
    });
  });
}

function readDirRecursive(result, dirPath, depth) {
  if (depth > 3) return Promise.resolve([]);
  return result.client
    .call('file', 'readdir', [{ path: dirPath }])
    .then(function (value) {
      const entries = (value && (value.entries || value)) || [];
      if (!Array.isArray(entries)) return [];
      return Promise.all(
        entries.map(function (entry) {
          if (!entry || typeof entry !== 'object') return null;
          const name = entry.name || entry.fileName || '';
          if (!name) return null;
          const entryPath = entry.path || dirPath + '/' + name;
          const isDirectory = entry.isDirectory === true || entry.directory === true
            || entry.kind === 'directory';
          const node = {
            id: entryPath,
            name: name,
            path: entryPath,
            isDirectory: isDirectory,
            size: entry.size == null ? null : Number(entry.size),
            children: null,
          };
          if (!isDirectory) return node;
          return readDirRecursive(result, entryPath, depth + 1)
            .then(function (children) { node.children = children; return node; });
        }),
      ).then(function (nodes) { return nodes.filter(function (n) { return n !== null; }); });
    })
    // 读失败如实上抛（files-store last-good + 页面重试面承接——读面失败
    // 不折叠成空态，§5-12 同口径）；depth 递归上限见函数头。
}

/**
 * Diff 页数据源：`git.getChanges {workspacePath}`（Android RemoteFileStore:449
 * 同参同命令）→ 变更条目 {path, status} 映射 diff-store files。
 */
function wireGitSource(result) {
  diffStore.setSource(function () {
    return result.client
      .call('git', 'getChanges', [{ workspacePath: result.workspace.path }])
      .then(function (value) { return mapGitChanges(value); });
  });
}

function mapGitChanges(value) {
  const items = (value && (value.changes || value.files || value)) || [];
  if (!Array.isArray(items)) return [];
  return items
    .map(function (item) {
      if (!item || typeof item !== 'object') return null;
      const path = item.path || item.filePath || item.file;
      if (!path) return null;
      return {
        path: String(path),
        status: item.status || item.state || 'modified',
        additions: item.additions == null ? null : Number(item.additions),
        deletions: item.deletions == null ? null : Number(item.deletions),
      };
    })
    .filter(function (row) { return row !== null; });
}

/**
 * 任务轨迹页数据源：readSessionMessages 复用（思考/工具/回复三分类——
 * Android TaskOutputScreen 行投影同口径），taskTitle 由调用方（会话行）持有，
 * 此处回传 sessionId。
 */
function wireTrajectorySource(result) {
  trajectoryStore.setSource(function (sessionId) {
    return result.client
      .call('zcode-agent', 'readSessionMessages', [{ sessionId: sessionId, limit: 200 }])
      .then(function (value) {
        const mapped = mapChatMessages(value, sessionId);
        return { entries: mapped.messages, taskTitle: sessionId };
      });
  });
}

/**
 * 会话/任务全量拉取（2026-11-25 与 iOS/Android 同裁决「会话项目不全」根修）：
 * 优先 host-scope（`workspaceScopes:"host"` 由存储 owner 决定全部范围，行自带
 * workspacePath 作分组键）——此前用 `{scope:'all'}`，桌面只回当前窗口 tabs，
 * 没开着的项目整块缺席。老桌面不认 host 形态 → 回落 `{scope:'all'}`。
 */
function fetchTaskRows(client) {
  const legacy = function () {
    return client
      .call('zcode-task', 'listTaskList', [{ scope: 'all' }])
      .then(function (value) { return mapTaskRows(value); });
  };
  return client
    .call('zcode-task', 'listTaskList', [{ kind: 'timeline', workspaceScopes: 'host', sortBy: 'updated' }])
    .then(function (value) {
      var items = (value && value.items) || [];
      if (!items.length) return legacy();
      return mapTaskRows(value);
    })
    .catch(function () { return legacy(); });
}

module.exports = {
  connectDesktop: connectDesktop,
  connectRelayDesktop: connectRelayDesktop,
  getRelayJwt: getRelayJwt,
  setAssistantFeedback: setAssistantFeedback,
  connectRelayDesktop: connectRelayDesktop,
  mapChatMessages: mapChatMessages,
  mapGitChanges: mapGitChanges,
  disconnect: disconnect,
  getState: getState,
  lastAddress: lastAddress,
  mapTaskRows: mapTaskRows,
  subscribeConversation: subscribeConversation,
  resolveInteraction: resolveInteraction,
  snoozeInteractionAutoResolution: snoozeInteractionAutoResolution,
  commandCenterSearch: commandCenterSearch,
  searchWorkspaceFiles: searchWorkspaceFiles,
  readFileText: readFileText,
  listSkills: listSkills,
  readTaskGroups: readTaskGroups,
  workflowRunsFor: workflowRunsFor,
  savedWorkflowForRun: savedWorkflowForRun,
  saveWorkflowRun: saveWorkflowRun,
  rerunWorkflow: rerunWorkflow,
  saveTaskGroupColor: saveTaskGroupColor,
  pinTaskGroupToTop: pinTaskGroupToTop,
  disposeCommandFace: disposeCommandFace,
};
