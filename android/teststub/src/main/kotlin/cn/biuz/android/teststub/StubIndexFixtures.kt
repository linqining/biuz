package cn.biuz.android.teststub

import cn.biuz.android.protocol.JSONValue

/**
 * bootstrap.tasks / sessions-index 注水夹具（M2 store 层验收面）。
 * 行形状上游裁决（【上游裁决 2026-10-09 /tmp/zcode-upstream】）：
 * - bootstrap.tasks 行 = zcodeTaskMetaSchema 子集（validation.ts:1205）：taskId/title/
 *   workspacePath 必填、workspaceIdentity?、createdAt/updatedAt epoch 毫秒、
 *   status ∈ running|completed|error（validation.ts:1037）；行**无 lastActivityAt**、
 *   无 phase——跨工作区行的活性只有 status 一个来源；
 * - sessions-index 行 = sessionSummarySchema 子集（sessions-index.ts:29-53）：
 *   sessionId/workspaceId 必填、phase（snapshot.ts:28-37 词表）、lastActivityAt；
 *   **relay 下发的行实测只带 workspaceId 不带 workspacePath**（2026-10-05 全键取证）
 *   ——客户端合并器对无 workspacePath 行禁拿 workspaceId 当分组键（交接乙-6②）。
 *
 * 跨工作区幽灵行样本（乙-6/戊-1，上游 bug 至今存留）：同一会话被桌面
 * seedTaskMetaIfMissing 按订阅工作区写多行 task-index——[ghostBootstrapTaskRows]
 * 产出「真实区行 + 幽灵区行」双行对，合并器必须允许权威行纠正。
 */
object StubIndexFixtures {

    /**
     * bootstrap.tasks 行（zcodeTaskMetaSchema 子集）。
     * [workspacePath] 传 null = 行缺归属键（乙-6②红线样本；workspaceId-only 行不进 workspacePath 集合）。
     */
    fun bootstrapTaskRow(
        taskId: String,
        title: String,
        workspacePath: String?,
        updatedAtMs: Long,
        workspaceIdentity: String? = null,
        workspaceId: String? = null,
        status: String? = null,
        createdAtMs: Long? = null,
    ): JSONValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["taskId"] = JSONValue.Str(taskId)
        fields["title"] = JSONValue.Str(title)
        if (workspacePath != null) fields["workspacePath"] = JSONValue.Str(workspacePath)
        if (workspaceIdentity != null) fields["workspaceIdentity"] = JSONValue.Str(workspaceIdentity)
        if (workspaceId != null) fields["workspaceId"] = JSONValue.Str(workspaceId)
        if (status != null) fields["status"] = JSONValue.Str(status)
        fields["createdAt"] = stubNum(createdAtMs ?: updatedAtMs)
        fields["updatedAt"] = stubNum(updatedAtMs)
        return JSONValue.Obj(fields)
    }

    /**
     * 跨工作区幽灵行对（乙-6）：同一会话（同 taskId/sessionId）在真实区 [realWorkspace]
     * 与幽灵区 [ghostWorkspace] 各落一行 task-index。真实区行携带更新的 updatedAt
     * （权威行后到也必须赢——「只补缺不纠正」会让幽灵归属永久冻结）。
     */
    fun ghostBootstrapTaskRows(
        sessionId: String,
        title: String,
        realWorkspace: String,
        ghostWorkspace: String,
        updatedAtMs: Long,
        status: String = "running",
    ): List<JSONValue> = listOf(
        // 幽灵区行（先到，旧时间戳——上游 seedTaskMetaIfMissing 的残留态）
        bootstrapTaskRow(
            taskId = sessionId,
            title = title,
            workspacePath = ghostWorkspace,
            updatedAtMs = updatedAtMs - 60_000L,
            status = status,
        ),
        // 真实区行（后到，新时间戳）
        bootstrapTaskRow(
            taskId = sessionId,
            title = title,
            workspacePath = realWorkspace,
            updatedAtMs = updatedAtMs,
            status = status,
        ),
    )

    /**
     * sessions-index 行（sessionSummarySchema 子集）。
     * [workspacePath] 传 null = relay 形态行（只带 workspaceId，2026-10-05 全键取证）。
     * [phase] 传 null = 行缺 phase 键（phaseExplicit=false 形态——双源合成判据面）。
     * [lastActivityAtMs] 与 [lastActivityIso] 二选一（毫秒数/ISO 双形态，中继快照实测）。
     */
    fun sessionsIndexRow(
        sessionId: String,
        title: String,
        phase: String?,
        lastActivityAtMs: Long? = null,
        lastActivityIso: String? = null,
        workspacePath: String? = null,
        workspaceId: String? = null,
        taskStatus: String? = null,
        lastAssistantPreview: String? = null,
        pendingPermissionCount: Int? = null,
        pendingUserInputCount: Int? = null,
    ): JSONValue {
        val fields = LinkedHashMap<String, JSONValue>()
        fields["sessionId"] = JSONValue.Str(sessionId)
        fields["workspaceId"] = JSONValue.Str(workspaceId ?: workspacePath ?: sessionId)
        fields["title"] = JSONValue.Str(title)
        if (phase != null) fields["phase"] = JSONValue.Str(phase)
        when {
            lastActivityAtMs != null -> fields["lastActivityAt"] = stubNum(lastActivityAtMs)
            lastActivityIso != null -> fields["lastActivityAt"] = JSONValue.Str(lastActivityIso)
        }
        if (workspacePath != null) fields["workspacePath"] = JSONValue.Str(workspacePath)
        if (taskStatus != null) fields["taskStatus"] = JSONValue.Str(taskStatus)
        if (lastAssistantPreview != null) fields["lastAssistantPreview"] = JSONValue.Str(lastAssistantPreview)
        if (pendingPermissionCount != null || pendingUserInputCount != null) {
            fields["pendingInteractionSummary"] = stubObj(
                "permissionCount" to (pendingPermissionCount?.let { JSONValue.Int(it) }),
                "userInputCount" to (pendingUserInputCount?.let { JSONValue.Int(it) }),
            )
        }
        return JSONValue.Obj(fields)
    }

    /** sessions-index 快照整体注水体（sessionsIndexSnapshotSchema 骨架：logEpoch + sessions）。 */
    fun sessionsIndexSnapshot(
        logEpoch: String,
        sessions: List<JSONValue>,
    ): JSONValue = stubObj(
        "logEpoch" to JSONValue.Str(logEpoch),
        "sessions" to JSONValue.Arr(sessions),
    )
}
