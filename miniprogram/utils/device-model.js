// 003-设备列表的纯逻辑层 —— 分组 / 描述文案 / 重名消歧。
//
// web 权威源：packages/web/src/web-remote-control/ui/webRemoteControlDeviceListPage.tsx
//   :19-52   文案表（zh-CN 逐字照抄）
//   :71-78   SectionHeader（标题 + 计数）
//   :94-111  describeDevice（平台 · 版本 / 离线；同组重名才追加 ID 前 8 位）
//   :113-130 DeviceSection（在线/离线两组，空组不渲染）
//
// 不依赖 wx API，可在 Node 里直接跑单测。

const COPY = {
  title: '选择电脑',
  switchAccount: '切换',
  switchAccountFallback: '切换账号',
  refresh: '刷新',
  loading: '正在加载设备…',
  online: '在线',
  offline: '离线',
  unnamed: '未命名设备',
  notFound: '没看到你的电脑？在电脑端 ZCode 登录同一账号并开启远程控制，然后刷新。',
  emptyTitle: '暂无设备',
  emptyDescription: '在电脑端登录同一账号并开启 Web 远程控制，然后刷新列表。',
  error: '无法加载设备，请检查网络后刷新。',
};

const PROVIDER_LABELS = { zai: 'Z.ai', bigmodel: 'BigModel' };

/** 平台 → 展示名（web getDevicePlatformLabel；未识别时回退空串不下判断）。 */
const PLATFORM_LABELS = {
  darwin: 'macOS',
  macos: 'macOS',
  win32: 'Windows',
  windows: 'Windows',
  linux: 'Linux',
};

function platformLabel(platform) {
  if (!platform) return '';
  return PLATFORM_LABELS[String(platform).toLowerCase()] || String(platform);
}

function connectionsText(count) {
  return count + ' 个连接';
}

/**
 * 设备描述行：在线 → 「平台 · ZCode 版本」；离线 → 「平台 · 离线」。
 * 同组重名时追加 `ID <mid 前 8 位>`（前 8 位不参与身份判断，仅用于肉眼区分）。
 */
function describeDevice(device, showId) {
  const platform = platformLabel(device.platform);
  const connections = device.onlineConnections || [];
  const online = connections.length > 0;
  const single = connections.length === 1 ? connections[0] : null;
  const version = single && single.meta ? String(single.meta.version || '').trim() : '';
  const parts = (online ? [platform, version ? 'ZCode ' + version : ''] : [platform, COPY.offline])
    .filter(function (part) { return Boolean(part); });
  if (!parts.length) parts.push(online ? COPY.online : COPY.offline);
  if (showId && device.deviceMid) parts.push('ID ' + String(device.deviceMid).slice(0, 8));
  return parts.join(' · ');
}

/**
 * 构建渲染分区：[{ key, label, count, devices: [{...device, nameText, descText, connectionsText}] }]
 * 在线组恒在前；空组不渲染（web DeviceSection `if (devices.length === 0) return null`）。
 */
function buildSections(devices) {
  const list = devices || [];
  const online = list.filter(function (d) { return (d.onlineConnections || []).length > 0; });
  const offline = list.filter(function (d) { return (d.onlineConnections || []).length === 0; });

  const nameCounts = {};
  list.forEach(function (d) {
    const name = d.name || COPY.unnamed;
    nameCounts[name] = (nameCounts[name] || 0) + 1;
  });

  function decorate(group) {
    return group.map(function (d) {
      const name = d.name || COPY.unnamed;
      const dup = (nameCounts[name] || 0) > 1;
      const count = (d.onlineConnections || []).length;
      return Object.assign({}, d, {
        nameText: name,
        descText: describeDevice(d, dup),
        connectionsText: count > 1 ? connectionsText(count) : '',
        online: count > 0,
      });
    });
  }

  const sections = [];
  if (online.length) sections.push({ key: 'online', label: COPY.online, count: online.length, devices: decorate(online) });
  if (offline.length) sections.push({ key: 'offline', label: COPY.offline, count: offline.length, devices: decorate(offline) });
  return sections;
}

/** 账号行文案：`displayName · Provider`；无账号时用 fallback 文案。 */
function accountText(account) {
  if (!account) return '';
  const provider = PROVIDER_LABELS[account.provider] || '';
  return provider ? account.displayName + ' · ' + provider : account.displayName;
}

/* ---- 006 多实例选择（web webRemoteControlConnectionSheet.tsx:19-60） ---- */

const SHEET_COPY = {
  sheetDescription: function (count) { return '这台电脑上有 ' + count + ' 个 ZCode 在线，选择要连接的一个'; },
  startedAgo: function (relative) { return relative + '启动'; },
  cancel: '取消',
};

/**
 * 相对时间（web 用 Intl.RelativeTimeFormat numeric:auto）。
 * <1 分钟→刚刚；<1 小时→N 分钟前；<24 小时→N 小时前；<30 天→N 天前；更早→M月D日。
 */
function formatStartedAgo(createdAtSeconds, nowMs) {
  const now = nowMs === undefined ? Date.now() : nowMs;
  const diffMs = now - createdAtSeconds * 1000;
  const abs = Math.abs(diffMs);
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (abs < minute) return '刚刚';
  if (abs < hour) return Math.round(abs / minute) + ' 分钟前';
  if (abs < day) return Math.round(abs / hour) + ' 小时前';
  if (abs < 30 * day) return Math.round(abs / day) + ' 天前';
  const d = new Date(createdAtSeconds * 1000);
  return (d.getMonth() + 1) + '月' + d.getDate() + '日';
}

/** 多实例选择行：版本 + 启动时间。 */
function connectionRows(device, nowMs) {
  const connections = (device && device.onlineConnections) || [];
  return connections.map(function (conn, index) {
    const version = conn.meta && conn.meta.version ? String(conn.meta.version).trim() : '';
    const startedAgo = conn.meta && conn.meta.createdAt
      ? formatStartedAgo(Number(conn.meta.createdAt), nowMs)
      : '';
    return {
      sid: conn.device_sid,
      index: index,
      title: version ? 'ZCode ' + version : 'ZCode',
      subtitle: startedAgo ? SHEET_COPY.startedAgo(startedAgo) : '',
    };
  });
}

/** 是否需要在点选设备时弹多实例选择（同组在线连接 > 1）。 */
function needsConnectionSheet(device) {
  return ((device && device.onlineConnections) || []).length > 1;
}

module.exports = {
  COPY: COPY,
  PROVIDER_LABELS: PROVIDER_LABELS,
  SHEET_COPY: SHEET_COPY,
  platformLabel: platformLabel,
  connectionsText: connectionsText,
  describeDevice: describeDevice,
  buildSections: buildSections,
  accountText: accountText,
  formatStartedAgo: formatStartedAgo,
  connectionRows: connectionRows,
  needsConnectionSheet: needsConnectionSheet,
};
