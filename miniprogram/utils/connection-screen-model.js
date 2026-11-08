// 004-连接中 / 005-连接失败的纯逻辑与文案。
//
// web 权威源：
//   packages/web/src/web-remote-control/ui/webRemoteControlConnectingScreen.tsx:17-70（四段步骤 + 阶段文案）
//   packages/web/src/web-remote-control/domain/webRemoteControlScreenCopy.ts:16-141（12 种失败原因文案）
// 文案逐字照抄 zh-CN 表；en-US 留给 i18n 轮次。

/** 004 连接中：4 段线性步骤 + 各阶段文案。 */
const CONNECTING = {
  connectingTo: function (name) { return '正在连接 ' + name; },
  connectingFallback: '正在连接电脑',
  steps: ['连接中转服务', '设备鉴权', '等待桌面端配对', '同步工作区'],
  stages: {
    connecting: '正在连接中转服务…',
    authenticating: '正在认证设备…',
    waiting: '等待桌面端确认配对…',
    paired: '已配对，正在加载工作区…',
    reconnecting: '连接中断，正在重连…',
    suspended: '页面在后台，等待恢复…',
    other: '正在准备远程控制…',
  },
  reconnectingDescription: '网络或休眠恢复后会自动重连，请稍候。',
  suspendedDescription: '回到前台后会自动恢复连接。',
};

/** 线性阶段 → 4 段进度下标；重连/后台等非线性态不高亮任何段（返回 -1）。 */
const STEP_INDEX = {
  idle: 0,
  connecting: 0,
  authenticating: 1,
  waiting: 2,
  paired: 3,
};

/** 005 连接失败：12 种原因（web 表逐字）。 */
const FAILURE = {
  'device-not-owned': {
    tone: 'warning', badge: '账号不匹配', title: '该设备不属于当前账号',
    description: '请使用与电脑端相同的账号登录。', detailLabel: '失败原因',
    nextSteps: ['确认电脑端当前登录的账号。', '切换为相同账号后重新连接。'], action: '重新连接',
  },
  'session-not-found': {
    tone: 'warning', badge: '链接不可用', title: '访问链接已失效',
    description: '这次 Web 远程控制链接已经不存在，通常是桌面端重新生成了二维码。', detailLabel: 'Relay 返回',
    nextSteps: ['回到桌面端重新打开 Web 远程控制。', '用手机扫描最新二维码。'], action: '重新加载',
  },
  'session-expired': {
    tone: 'warning', badge: '会话结束', title: '本次远程控制已结束',
    description: '当前远程控制会话已经过期或被桌面端关闭。', detailLabel: '结束原因',
    nextSteps: ['在桌面端重新开启 Web 远程控制。', '用新的链接进入当前工作区。'], action: '重新加载',
  },
  'session-conflict': {
    tone: 'warning', badge: '设备接管', title: '已被其他设备接管',
    description: '另一台远程控制设备已经接入，同一时间只能保留一个手机控制端。', detailLabel: 'Relay 返回',
    nextSteps: ['继续使用新接入的设备。', '如果要用本设备控制，请重新扫描桌面端二维码。'], action: '重新连接',
  },
  'workspace-closed': {
    tone: 'neutral', badge: '工作区关闭', title: '当前工作区已关闭',
    description: '桌面端已经关闭了共享工作区，手机端无法继续访问这个 workspace。', detailLabel: '桌面端返回',
    nextSteps: ['在桌面端重新打开目标工作区。', '重新发起 Web 远程控制。'], action: '重新加载',
  },
  'desktop-disconnected': {
    tone: 'danger', badge: '电脑端离线', title: '桌面端已离线',
    description: '电脑端已经断开连接，当前手机页面不能继续控制桌面工作区。', detailLabel: 'Relay 返回',
    nextSteps: ['确认电脑端 ZCode 仍在运行并联网。', '在电脑端重新开启 Web 远程控制后再连接。'], action: '重新连接',
  },
  'invalid-mobile-connection': {
    tone: 'warning', badge: '校验失败', title: '手机连接已失效',
    description: '当前页面的二维码参数无效，请扫描桌面端的最新二维码。', detailLabel: '失败原因',
    nextSteps: ['不要复用旧截图或旧链接。', '回到桌面端扫描最新二维码。'], action: '重新连接',
  },
  'desktop-bootstrap-timeout': {
    tone: 'warning', badge: '响应超时', title: '桌面端响应超时',
    description: '手机端已经连上 relay，但桌面端没有及时返回工作区数据。', detailLabel: '超时详情',
    nextSteps: ['确认桌面端没有休眠或卡在确认弹窗。', '保持电脑和手机网络可用后重试。'], action: '重试',
  },
  'connection-recovery-timeout': {
    tone: 'warning', badge: '恢复超时', title: '连接恢复超时',
    description: '手机端连接没有及时恢复，当前页面暂时无法继续同步远程控制。', detailLabel: '恢复详情',
    nextSteps: ['保持手机网络可用后重试。', '如果仍无法恢复，再回到桌面端重新开启 Web 远程控制。'], action: '重试',
  },
  'relay-unavailable': {
    tone: 'danger', badge: '中转异常', title: '无法连接中转服务',
    description: '手机端无法稳定连接 Web 远程控制中转服务。', detailLabel: '连接详情',
    nextSteps: ['检查手机网络是否可访问外网。', '如果电脑端仍在线，可以稍后刷新重试。'], action: '重试',
  },
  'unsupported-action': {
    tone: 'neutral', badge: '暂不支持', title: '当前动作暂不支持',
    description: 'Web 远程控制只支持访问桌面端已经打开的工作区。', detailLabel: '限制说明',
    nextSteps: ['先在桌面端打开目标工作区。', '再从手机端选择这个工作区。'], action: '重新加载',
  },
  'unexpected-error': {
    tone: 'danger', badge: '未知异常', title: 'Web 远程控制失败',
    description: '打开远程控制页面时发生了未预期错误。', detailLabel: '错误详情',
    nextSteps: ['刷新页面再试一次。', '如果仍然失败，请回到桌面端重新生成二维码。'], action: '重试',
  },
};

const NEXT_STEPS_TITLE = '下一步';
const DEFAULT_REASON = 'unexpected-error';

/** 未知 reason 一律回落到 unexpected-error（web 同样只认这 12 个）。 */
function failureCopy(reason) {
  return FAILURE[reason] || FAILURE[DEFAULT_REASON];
}

/** 4 段进度：返回 { stepIndex, label, description }。 */
function connectingStage(state) {
  const index = Object.prototype.hasOwnProperty.call(STEP_INDEX, state) ? STEP_INDEX[state] : -1;
  switch (state) {
    case 'connecting':
    case 'authenticating':
    case 'waiting':
    case 'paired':
      return { stepIndex: index, label: CONNECTING.stages[state], description: '' };
    case 'reconnecting':
      return { stepIndex: -1, label: CONNECTING.stages.reconnecting, description: CONNECTING.reconnectingDescription };
    case 'suspended':
      return { stepIndex: -1, label: CONNECTING.stages.suspended, description: CONNECTING.suspendedDescription };
    default:
      return { stepIndex: -1, label: CONNECTING.stages.other, description: '' };
  }
}

function connectingTitle(deviceName) {
  return deviceName ? CONNECTING.connectingTo(deviceName) : CONNECTING.connectingFallback;
}

module.exports = {
  CONNECTING: CONNECTING,
  FAILURE: FAILURE,
  NEXT_STEPS_TITLE: NEXT_STEPS_TITLE,
  failureCopy: failureCopy,
  connectingStage: connectingStage,
  connectingTitle: connectingTitle,
};
