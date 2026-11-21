// 朱批小程序 —— sendConversationCommandV4 信封构造与 ACK 判定（纯函数，可单测）。
//
// 取证出处（协议接口文档 §7.1【实证】+ iOS RemoteConversationStore.sendCommand /
// ConversationCommandFactory 同构；小程序首次接写面信封——2026-11-22 品牌升级子轮）：
//   args = [ { workspacePath, workspaceIdentity?, envelope: {
//              commandId, clientId, type, payload, issuedAt, sessionId } } ]
//
// 逐字段纪律（全部【实证】，违反后果见协议文档 §7.1 表）：
//   A1 信封形态必须嵌套 envelope:{…}，外层扁平 workspacePath（平铺被新版服务端 TypeError）；
//   A1' envelope.sessionId 键恒在场（普通命令=目标会话 id；缺省键被拒 proto.invalidPayload）；
//   A2 envelope.clientId 必须等于握手注册值（clientMismatch）；
//   A3 payload 形状由调用方按 §7.2 词表分形（resolveInteraction = {interactionId, answer}，
//      answer 恒为对象三族分形）；未知键服务端静默剥离——键名禁猜；
//   issuedAt = epoch 毫秒 int（ISO 字符串被拒）；
//   resolveInteraction 非 CAS——baseRevision/baseLogEpoch 不带（CAS 词表外）。
//
// ACK 判定（A13 六态词表 + iOS CommandAck 同口径）：status 缺席（旧桌面）或词表外
// = 「未知态」——读面宽容但**落印只认权威成功**（accepted/duplicate/noop）。

var AckStatuses = ['accepted', 'rejected', 'stale', 'duplicate', 'noop', 'failed'];

/** 命令唯一 id：随机 16 位（web uuid 任意唯一即可；重试不变由调用方保证——小程序首版无重发）。 */
function makeCommandId(random) {
  var rand = random || Math.random;
  var chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  var out = '';
  for (var i = 0; i < 16; i++) {
    out += chars.charAt(Math.floor(rand() * chars.length));
  }
  return 'cmd-' + out;
}

/**
 * 构造 sendConversationCommandV4 位置参数（rpc-client args = [obj]）。
 * @param {Object} opts {clientId, type, payload, sessionId, workspacePath, issuedAtMs?, commandId?}
 * @returns [{workspacePath, envelope}]
 */
function makeSendConversationArgs(opts) {
  var commandId = opts.commandId || makeCommandId();
  var issuedAt = typeof opts.issuedAtMs === 'number'
    ? Math.floor(opts.issuedAtMs)
    : Date.now();
  var envelope = {
    commandId: commandId,
    clientId: opts.clientId,
    type: opts.type,
    payload: opts.payload,
    issuedAt: issuedAt,
    // A1'：sessionId 键恒在场（无会话命令传 null——resolveInteraction 恒有目标会话）
    sessionId: opts.sessionId,
  };
  var arg = { workspacePath: opts.workspacePath, envelope: envelope };
  return [arg];
}

/** ACK 宽容提取：顶层 {status} 或中继桥 {ack:{status}} 双形态（iOS CommandAck 同口径）。 */
function ackStatus(ackValue) {
  if (!ackValue || typeof ackValue !== 'object') return null;
  if (typeof ackValue.status === 'string') return ackValue.status;
  if (ackValue.ack && typeof ackValue.ack.status === 'string') return ackValue.ack.status;
  return null;
}

/** A13 失败判定：词表内非 accepted/duplicate/noop = 失败；缺席/词表外 = 未知态（非失败）。 */
function isFailureStatus(status) {
  if (!status) return false;
  if (AckStatuses.indexOf(status) < 0) return false;
  return status !== 'accepted' && status !== 'duplicate' && status !== 'noop';
}

/** 权威成功（落印门槛）：词表内非失败态。 */
function isAuthoritativeSuccess(status) {
  if (!status) return false;
  if (AckStatuses.indexOf(status) < 0) return false;
  return !isFailureStatus(status);
}

module.exports = {
  AckStatuses: AckStatuses,
  makeCommandId: makeCommandId,
  makeSendConversationArgs: makeSendConversationArgs,
  ackStatus: ackStatus,
  isFailureStatus: isFailureStatus,
  isAuthoritativeSuccess: isAuthoritativeSuccess,
};
