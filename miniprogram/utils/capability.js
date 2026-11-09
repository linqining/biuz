// 能力清单读面（memory/skills/mcp/plugins）—— iOS RemoteCapabilityListPage /
// Android RemoteCapabilities.kt 的第三端同构（形状证据同源，均为【实证·上游仓 /
// 真机回执】）：
// - memory：频道 `memory`（channels.ts:184 Memory="memory"），listProjectMemories
//   无参；回执 {workspaces|memories}[]，条目 {id,label,updatedAt,files[]}；
// - skills：zcode-agent.getSkillReferenceCatalog + workspace 信封；宽容
//   skills[] | catalog.skills[] | entries[]；条目 {name|title, description|summary, enabled?}；
// - mcp：zcode-agent.listMcpServerStatuses {workspacePath}；**真实形状是 statuses
//   map**（{statuses:{id:{status,transport,toolCount}}}，桌面 3.17.2 真机实测，
//   Android 侧同轮修正）；数组形态保留宽容分支；
// - plugins：zcode-agent.listPlugins + workspace 信封；{plugins|items}[]，
//   条目 {name|pluginName, marketplace?, version?}。
// 写面（启停/卸载/安装）一律不接：移动端只读（ReadOnlyGate 同口径越界即失守）。

/** workspace 信封（web 恒携 workspacePath，identity 在场才带——Android workspaceScopeFields 同构）。 */
function workspaceScopeFields(workspace) {
  const fields = {};
  if (workspace && workspace.path) fields.workspacePath = workspace.path;
  if (workspace && workspace.workspaceIdentity) fields.workspaceIdentity = workspace.workspaceIdentity;
  return fields;
}

/** 读面失败分类：未连接是引导不是失败（U-7），与读失败分开叙事。 */
function capabilityError(kind, err) {
  const message = (err && err.message) || String(err);
  return new Error(message);
}

/**
 * 一次能力读（connection.js 的 client 面）。
 * @param client rpc 客户端；@param workspace {path, workspaceIdentity}?；@param kind
 * @returns Promise<{rows: [{id,title,subtitle,badge?}]}>；失败 reject（UI 分类上屏）。
 */
function loadCapability(client, workspace, kind) {
  if (!client) return Promise.reject(new Error('未连接桌面端'));
  const scope = workspaceScopeFields(workspace);
  const read = {
    memory: readMemories,
    skills: readSkills,
    mcp: readMcp,
    plugins: readPlugins,
  }[kind];
  if (!read) return Promise.reject(new Error('未知能力类型 ' + kind));
  return read(client, scope);
}

function readMemories(client) {
  return client.call('memory', 'listProjectMemories', []).then(function (value) {
    const items = (value && (value.workspaces || value.memories)) || [];
    return {
      rows: (Array.isArray(items) ? items : []).map(function (item) {
        if (!item || typeof item !== 'object') return null;
        const label = item.label || item.id;
        if (!label) return null;
        const fileCount = Array.isArray(item.files) ? item.files.length : 0;
        const updated = item.updatedAt
          ? formatShortDate(item.updatedAt)
          : '';
        return {
          id: label,
          title: label,
          subtitle: fileCount + ' 个记忆文件' + (updated ? ' · 更新于 ' + updated : ''),
          badge: null,
        };
      }).filter(function (row) { return row !== null; }),
    };
  });
}

function readSkills(client, scope) {
  return client
    .call('zcode-agent', 'getSkillReferenceCatalog', [scope])
    .then(function (value) {
      const items = (value && (value.skills
        || (value.catalog && value.catalog.skills)
        || value.entries)) || (Array.isArray(value) ? value : []);
      return {
        rows: (Array.isArray(items) ? items : []).map(function (item) {
          if (!item || typeof item !== 'object') return null;
          const name = item.name || item.title;
          if (!name) return null;
          return {
            id: name,
            title: name,
            subtitle: item.description || item.summary || '技能目录条目',
            badge: item.enabled == null ? null : (item.enabled ? '已启用' : '已停用'),
          };
        }).filter(function (row) { return row !== null; }),
      };
    });
}

function readMcp(client, scope) {
  return client
    .call('zcode-agent', 'listMcpServerStatuses', [scope])
    .then(function (value) {
      // 真实形状：statuses map（键形态如 plugin:documents:documents）
      if (value && value.statuses && typeof value.statuses === 'object'
          && !Array.isArray(value.statuses)) {
        return {
          rows: Object.keys(value.statuses).map(function (rawId) {
            const d = value.statuses[rawId] || {};
            return {
              id: rawId,
              title: displayMcpName(rawId),
              subtitle: [d.transport, d.toolCount != null ? d.toolCount + ' 个工具' : null]
                .filter(Boolean).join(' · ') || 'MCP 服务器',
              badge: mcpStatusLabel(d.status),
            };
          }),
        };
      }
      // 数组宽容分支（旧形状）
      const items = (value && (value.servers || value.mcpServers)) || (Array.isArray(value) ? value : []);
      return {
        rows: (Array.isArray(items) ? items : []).map(function (item) {
          if (!item || typeof item !== 'object') return null;
          const name = item.name || item.server || item.id;
          if (!name) return null;
          return {
            id: name,
            title: name,
            subtitle: item.command || item.transport || 'MCP 服务器',
            badge: mcpStatusLabel(item.status || item.state),
          };
        }).filter(function (row) { return row !== null; }),
      };
    });
}

function readPlugins(client, scope) {
  return client
    .call('zcode-agent', 'listPlugins', [scope])
    .then(function (value) {
      const items = (value && (value.plugins || value.items)) || (Array.isArray(value) ? value : []);
      return {
        rows: (Array.isArray(items) ? items : []).map(function (item) {
          if (!item || typeof item !== 'object') return null;
          const name = item.name || item.pluginName || item.id;
          if (!name) return null;
          return {
            id: name,
            title: name,
            subtitle: item.description || item.marketplace || '插件',
            badge: item.version || null,
          };
        }).filter(function (row) { return row !== null; }),
      };
    });
}

/** 桌面回执状态词 → 中文（未知词原样透传——Android mcpStatusLabel 同口径）。 */
function mcpStatusLabel(raw) {
  if (!raw) return null;
  const lower = String(raw).toLowerCase();
  if (lower === 'connected' || lower === 'ready' || lower === 'ok') return '已连接';
  if (lower === 'disabled' || lower === 'stopped') return '已停用';
  if (lower === 'failed' || lower === 'error') return '异常';
  return raw;
}

/** plugin:documents:documents → documents（Android displayMcpName 同构）。 */
function displayMcpName(rawId) {
  const segments = String(rawId).split(':').filter(function (s) { return s; });
  if (!segments.length) return rawId;
  if (segments[0] === 'plugin' && segments.length >= 3) return segments[segments.length - 1];
  return rawId;
}

function formatShortDate(ms) {
  const d = new Date(Number(ms));
  if (isNaN(d.getTime())) return '';
  return (d.getMonth() + 1) + '月' + d.getDate() + '日';
}

module.exports = {
  loadCapability: loadCapability,
  parseServerInfoHelper: null, // 占位：无
  mcpStatusLabel: mcpStatusLabel,
  displayMcpName: displayMcpName,
  formatShortDate: formatShortDate,
};
