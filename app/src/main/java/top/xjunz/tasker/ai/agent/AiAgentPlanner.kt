/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import top.xjunz.tasker.Preferences
import top.xjunz.tasker.ai.AiJson
import top.xjunz.tasker.ai.model.AiCapability
import top.xjunz.tasker.ai.provider.AiProviderFactory

/**
 * Agent loop 的"嘴和脑子"：负责构造两类 prompt、调用 AI、解析模型输出。
 *
 * - [planSession]: 会话开始前问 AI："你打算在哪个 App 里做什么、需要哪些能力、估计几步？"
 *   输出供任务级授权对话框展示给用户。
 *
 * - [nextAction]: 进入 agent loop 后每一步问 AI："看了当前屏幕和过去历史，下一步该做什么？"
 *   AI 必须在固定动作 schema 内做选择，本地代码直接执行。
 */
object AiAgentPlanner {

    /**
     * 当前 agent 第一阶段对外开放的能力。模型在 prompt 里被告知只能用这些 type，
     * 任何超出的 type 都会被 [AiAgentAction.fromDto] 兜底成 Unknown。
     */
    private val OPEN_ACTIONS = listOf(
        AiAgentActionType.LAUNCH_APP,
        AiAgentActionType.CLICK,
        AiAgentActionType.LONG_CLICK,
        AiAgentActionType.SET_TEXT,
        AiAgentActionType.WAIT,
        AiAgentActionType.SCROLL,
        AiAgentActionType.GLOBAL_BACK,
        AiAgentActionType.GLOBAL_HOME,
        AiAgentActionType.DONE,
        AiAgentActionType.GIVE_UP
    )

    /** prompt 里附带的最近 N 条历史摘要，超出部分丢弃；防止 prompt 越来越长。 */
    private const val HISTORY_TAIL = 8

    suspend fun planSession(userGoal: String): AiAgentSessionPlanResult? {
        val provider = AiProviderFactory.createConfiguredProvider()
        if (provider == null) {
            AiAgentLog.w("planSession", "provider 未配置，跳过 agent 流程")
            return null
        }
        val prompt = buildSessionPlanPrompt(userGoal)
        AiAgentLog.i("planSession", "userGoal=\"$userGoal\" → 开始请求 plan")
        AiAgentLog.d("planSession.prompt", prompt)
        var rawResponse: String? = null
        return runCatching {
            withTimeout(Preferences.aiRequestTimeoutMillis.toLong()) {
                val response = provider.complete(prompt)
                rawResponse = response.text
                AiAgentLog.d("planSession.response", response.text)
                val dto = AiJson.decodeFromString<AiAgentSessionPlanDto>(extractJson(response.text))
                val capabilities = parseCapabilities(dto.requiredCapabilities).ifEmpty {
                    setOf(AiCapability.ReadNodeTree, AiCapability.ClickUi, AiCapability.InputText)
                }
                val plan = AiAgentSessionPlan(
                    targetAppPackage = dto.targetAppPackage?.trim()?.takeIf { it.isNotBlank() },
                    targetAppLabel = dto.targetAppLabel?.trim()?.takeIf { it.isNotBlank() },
                    summary = dto.planSummary?.trim()?.takeIf { it.isNotBlank() } ?: userGoal,
                    estimatedSteps = (dto.estimatedSteps ?: 6).coerceIn(1, 50),
                    capabilities = capabilities,
                    confidence = dto.confidence.coerceIn(0f, 1f)
                )
                AiAgentLog.i(
                    "planSession",
                    "解析成功：app=${plan.targetAppPackage}(${plan.targetAppLabel}) " +
                            "estSteps=${plan.estimatedSteps} caps=${plan.capabilities} " +
                            "isExecutable=${plan.isExecutable} conf=${plan.confidence}"
                )
                AiAgentSessionPlanResult(
                    plan = plan,
                    prompt = prompt,
                    rawResponse = response.text
                )
            }
        }.getOrElse { error ->
            AiAgentLog.e(
                "planSession",
                "请求或解析失败：${error.message ?: error::class.simpleName}",
                error
            )
            AiAgentSessionPlanResult(
                plan = null,
                prompt = prompt,
                rawResponse = rawResponse,
                providerError = error.message ?: error::class.simpleName
            )
        }
    }

    /**
     * @param plan 会话开场时由 [planSession] 产出的初始规划。会被原样塞进 prompt 让 AI 自检"是否
     *   还在原计划路径上"。允许为 null（适用于绕过 planSession 直接 agent 跑的场景），prompt 此时
     *   会退化成"无原计划"的提示，行为兼容旧实现。
     */
    suspend fun nextAction(
        userGoal: String,
        history: List<AiAgentStepRecord>,
        snapshot: AiUiSnapshot?,
        plan: AiAgentSessionPlan? = null,
        maxSteps: Int = AiTaskScope.DEFAULT_MAX_STEPS,
        /**
         * 当前页面"试过且没反应"的 target 描述列表（人话格式，比如 `id=com.xxx:id/btn,text=发送`）。
         * 由 [AiAgentSession] 维护并传入；planner 负责把它放进 prompt 顶部的"禁选区"，让 AI 显式
         * 看见"这些 target 已经试过没反应——绝对禁止再选"。
         */
        deadTargets: List<String> = emptyList()
    ): AiAgentNextActionResult {
        val provider = AiProviderFactory.createConfiguredProvider()
        if (provider == null) {
            AiAgentLog.w("nextAction", "provider 未配置，返回 Unknown")
            return AiAgentNextActionResult(
                action = AiAgentAction.Unknown("AI Provider 未配置或未启用"),
                prompt = "",
                rawResponse = null,
                providerError = "provider_unconfigured"
            )
        }
        val prompt = buildNextActionPrompt(userGoal, history, snapshot, plan, maxSteps, deadTargets)
        AiAgentLog.i(
            "nextAction",
            "step=${history.size} pkg=${snapshot?.packageName} nodes=${snapshot?.nodes?.size ?: 0} → 请求"
        )
        AiAgentLog.d("nextAction.prompt", prompt)
        var rawResponse: String? = null
        return runCatching {
            withTimeout(Preferences.aiRequestTimeoutMillis.toLong()) {
                val response = provider.complete(prompt)
                rawResponse = response.text
                AiAgentLog.d("nextAction.response", response.text)
                val dto = AiJson.decodeFromString<AiAgentActionDto>(extractJson(response.text))
                val action = AiAgentAction.fromDto(dto)
                AiAgentLog.i(
                    "nextAction",
                    "解析成功 → ${describeAction(action)} [plan=${action.planStatus ?: "?"}] // ${action.thought.orEmpty()}"
                )
                AiAgentNextActionResult(
                    action = action,
                    prompt = prompt,
                    rawResponse = response.text
                )
            }
        }.getOrElse { error ->
            AiAgentLog.e(
                "nextAction",
                "请求或解析失败：${error.message ?: error::class.simpleName}",
                error
            )
            AiAgentNextActionResult(
                action = AiAgentAction.Unknown("解析模型输出失败：${error.message}"),
                prompt = prompt,
                rawResponse = rawResponse,
                providerError = error.message ?: error::class.simpleName
            )
        }
    }

    // ---------- prompt 构造 ----------

    private fun buildSessionPlanPrompt(userGoal: String): String {
        return """
你是 Android 自动化应用「AutoTask」的任务规划助手。
用户给出了一段自然语言目标，你需要做一次"开场规划"，告诉用户和系统：你打算在哪个 App 里完成它，需要哪些能力，预计几步，可能涉及的关键操作是什么。

只输出严格 JSON（不要 markdown，不要解释），schema：
{
  "target_app_package": "<目标 App 的包名，如 com.tencent.mm；不确定就留空字符串>",
  "target_app_label": "<目标 App 的中文/英文名，方便给用户看>",
  "plan_summary": "<2-3 句话说明你打算怎么做>",
  "estimated_steps": <预计步数整数，1-50>,
  "required_capabilities": [
    "ReadNodeTree", "ClickUi", "InputText", "LaunchIntent"
  ],
  "confidence": 0.0
}

可选 capability 取值（取你真正会用到的，越少越好）：
- ReadNodeTree: 读取屏幕节点树（agent 模式下默认就要）
- ClickUi: 点击按钮 / 列表项 / 入口
- InputText: 在输入框里写字
- LaunchIntent: 启动某个 App
- ExecuteExistingTask: 执行用户已有任务（agent 模式不需要）
- TakeScreenshot: 截屏（暂不开放）
- RunShell / ManageFiles / ForceStopApp: 高风险，**不要**返回

注意：
- 如果用户目标根本不需要在 App 内做 UI 操作（例如只是问问题），返回 estimated_steps=0、target_app_package="" 即可，agent 模式不会被启动。
- 包名要尽量准确，用户授权时会按这个限制 agent 只能在该 App 里操作。

用户目标：
$userGoal
        """.trimIndent()
    }

    private fun buildNextActionPrompt(
        userGoal: String,
        history: List<AiAgentStepRecord>,
        snapshot: AiUiSnapshot?,
        plan: AiAgentSessionPlan?,
        maxSteps: Int,
        deadTargets: List<String>
    ): String {
        val historySection = buildHistorySection(history)
        val snapshotSection = buildSnapshotSection(snapshot)
        val planSection = buildPlanSection(plan, history.size, maxSteps)
        // 黑名单段：把"试过且没反应"的 target 摆在 prompt 最显眼位置，比 history 里夹杂的 ⚠ 更硬核。
        val blacklistSection = if (deadTargets.isEmpty()) "" else buildString {
            appendLine()
            appendLine("⛔ 禁选目标（这些 target 在当前界面已经被尝试过且没有任何反应——再选一次也是浪费）：")
            deadTargets.forEach { appendLine("  - $it") }
            appendLine("**绝对禁止**输出 target 等价于上面任何一项的动作；必须换其他节点 / scroll / wait / give_up。")
        }.trimEnd()
        return """
你是 Android 自动化应用「AutoTask」内嵌的 UI agent，正在分步骤帮用户完成一个目标。
你将看到：用户目标、开场时你自己出的会话规划、过去几步执行结果、当前屏幕的可交互节点。
**你只输出"下一步要做什么"的一个动作，严格 JSON，不要 markdown，不要解释**。

目标：
$userGoal
${if (blacklistSection.isEmpty()) "" else "\n$blacklistSection\n"}
$planSection

$historySection

$snapshotSection

可用动作（type 字段必须是这些之一，多余字段会被忽略）：
${OPEN_ACTIONS.joinToString(", ")}

各动作字段约定（每个动作都**必须**带上 plan_status，详见后面"自检"部分）：
- launch_app: { "type":"launch_app", "packageName":"<pkg>", "thought":"...", "plan_status":"on_track|adjusted|off_track|unknown" }
- click / long_click: { "type":"click", "target": <AiUiTarget>, "thought":"...", "plan_status":"..." }
   · **target 不要求 [C]**——执行器会自动选最合适的方式触发：节点带 [C] 走标准 a11y 点击，节点 [-] 但 parent 是 [C] 走 parent 容器，纯 [-] 节点走坐标触摸（点 bounds 中心）。三种方式可靠性都很高。
   · 选 target 时**优先**选**有 text 或 contentDesc 的节点**（人也能看懂的入口）——这远比"必须 [C]"重要。例如 desc="发送" 的 [-] View 比同区域无 text 的 [C] View 更可能是真发送按钮。
- set_text: { "type":"set_text", "target": <AiUiTarget>, "text":"<要写入的文本>", "thought":"...", "plan_status":"..." }
   · **target 必须命中带 [E] 的节点**（editable=true，即真正的输入框）。
   · 当前屏幕**没有任何 [E] 节点 = 此页没有输入框**——必须先 click 进入正确页面，禁止直接 set_text。
- scroll: { "type":"scroll", "target": <AiUiTarget 可空>, "text":"down|up|left|right", "thought":"...", "plan_status":"..." }
- wait: { "type":"wait", "seconds": <1-30>, "thought":"...", "plan_status":"..." }
- global_back / global_home: { "type":"global_back", "thought":"...", "plan_status":"..." }
- done: { "type":"done", "summary":"<向用户说明已经达成什么>", "thought":"...", "plan_status":"..." }
- give_up: { "type":"give_up", "reason":"<为什么放弃>", "thought":"...", "plan_status":"..." }

AiUiTarget 是定位条件，至少要给一个非空字段：
{
  "viewId": "<resource id 全名，如 com.tencent.mm:id/send_btn>",
  "textEquals": "<完整匹配的可见文本>",
  "textContains": "<可见文本中出现的子串>",
  "contentDescEquals": "<contentDescription 完整匹配>",
  "contentDescContains": "<contentDescription 子串>",
  "className": "<节点类名末段，例如 Button、EditText>",
  "matchIndex": <同时多个匹配时取第几个，默认 0>
}

行为准则：
0. **【最高优先级】绝不操作 AutoTask 自己**：当 "当前屏幕" 显示 packageName = `top.xjunz.tasker` 时（这就是你正在跑的 AutoTask App 自己），**绝对不要**输出 click / set_text / long_click / scroll 任何会触动它界面的动作；那只会让你和你正在执行的指令本身打架。此时只能：(a) 输出 launch_app(目标 App 包名) 切到目标 App；(b) 如果用户目标本来就要在 AutoTask 里完成，直接 give_up 让用户手动操作。
1. 如果目标已经达成，立刻输出 done。
2. 如果当前界面与目标无关（例如还在桌面而 App 没启动），先 launch_app；不要在桌面上找 App 图标点击。
3. **绝对不要在已经进入目标 App 后再次 launch_app 同一 pkg**：如果"当前屏幕"显示的 packageName 已经等于你想 launch 的目标，直接基于现有节点决定下一步（点击 / 输入 / 滚动 / wait / done）；硬要再次 launch 会被系统短路并视为偏轨。
4. 启动后界面没切过来（current pkg 仍是桌面 / launcher），优先用 wait(2-3s) 多看一两轮再判断；不要立刻又发 launch_app。
5. **target 锁定原则**：能用 textEquals / contentDescEquals 唯一锁定的就用它（人话语义最准）；textEquals 锁不住时再加 className（"按钮 + 文本 X"）；viewId 在大型 RN/Compose App 里经常多节点共享同 id（如 `com.deepseek.chat:id/edit_text` 可能挂多处），单用容易匹错——**有 viewId 时建议同时给 textEquals/textContains 联合锁定**。
6. 不要操作快照里没有列出的节点；如果你想要的入口不在快照里，先 scroll 或 wait 再看。
7. **silent-fail 后的重试规则（重要）**：上一步 click/set_text 后**屏幕节点签名没变化**，系统会在该步 message 里追加 ⚠。**第一次** silent fail 允许你**再试一次**同一 target（给动画延迟一次机会，比如启动期 click 经常被吞），系统会等待并重新抓快照。**第二次**仍然 silent fail 该 target 就被永久拉黑（顶部"禁选目标"会列出来），此时**必须**换别的 target / scroll / wait / give_up。绝对禁止重复输出已经在禁选区的 target。
8. **节点 flag 是参考不是硬约束**（仅 set_text 例外，必须 [E]）：每个节点旁的 `[CLEFS]` 含义——C=可点击 / L=可长按 / **E=可编辑（输入框）** / F=可获取焦点 / S=可滚动 / k 已勾选 K 可勾选未选中 / D 已禁用。click 不要求 [C]：很多 App（特别是 React Native / Compose 自绘）把 clickable 标在父容器上，子节点显示 [-]，但子节点有 text/desc 用户语义清晰——这种情况**优先选有 text/desc 的子节点**，executor 会自动用坐标触摸或回溯到 parent。
9. 单次只做一步。每一步都要简短填 thought 解释为什么这样选。
10. 如果遇到登录 / 支付 / 安全验证 / 输入密码 等敏感界面，立刻 give_up，让用户接管。
11. 你最多有 $maxSteps 步预算（系统会强制中止），珍惜每一步。

【自检：plan_status 必填】
每一步都要根据"原会话规划"和"过去步骤实际走向"自评本步在轨度，必须是以下四个之一：
- on_track: 本步与原 plan 一致，按预期推进。
- adjusted: 本步在原 plan 大方向内做了局部调整（例如换了入口、换了 target 字段），但目标方向不变。
- off_track: 本步偏离了原 plan，正在尝试纠正回去（例如发现自己进了广告页，先 global_back）。
- unknown: 没有原 plan 可对照，或情况复杂无法判断。

如果连续两步都是 off_track 且没有看到回到正轨的迹象，强烈建议直接 give_up 让用户接管，而不要硬撑。

只输出一个动作的 JSON 对象，不要数组、不要其他字段。
        """.trimIndent()
    }

    private fun buildPlanSection(plan: AiAgentSessionPlan?, doneSteps: Int, maxSteps: Int): String {
        if (plan == null) {
            return "原会话规划：（本会话未经过 planSession，没有原计划可对照，自评 plan_status 用 unknown）"
        }
        val targetApp = plan.targetAppLabel ?: plan.targetAppPackage ?: "未指定"
        val capabilities = if (plan.capabilities.isEmpty()) "（未声明）"
        else plan.capabilities.joinToString(", ") { it.name }
        return buildString {
            appendLine("原会话规划（你在开场时自己给出的方向，请对照它做 plan_status 自检）：")
            appendLine("- 目标 App: $targetApp")
            appendLine("- 计划摘要: ${plan.summary}")
            appendLine("- 估计步数: ${plan.estimatedSteps}（已用 $doneSteps / 上限 $maxSteps）")
            appendLine("- 计划用到的能力: $capabilities")
        }.trimEnd()
    }

    private fun buildHistorySection(history: List<AiAgentStepRecord>): String {
        if (history.isEmpty()) return "过去步骤：（这是第一步）"
        val tail = if (history.size <= HISTORY_TAIL) history else history.takeLast(HISTORY_TAIL)
        return buildString {
            appendLine("过去步骤（最近 ${tail.size} 步，最旧 → 最新）：")
            tail.forEach { rec ->
                val pkg = rec.snapshotPackage?.let { " @$it" } ?: ""
                val ok = if (rec.result.ok) "OK" else "FAIL"
                val matched = rec.result.matchedNodeSummary?.let { " on $it" } ?: ""
                val msg = rec.result.message?.let { " | $it" } ?: ""
                val ps = rec.action.planStatus?.takeIf { it.isNotBlank() }?.let { " [plan=$it]" } ?: ""
                val intervention = formatIntervention(rec.userIntervention)
                val thought = rec.action.thought?.takeIf { it.isNotBlank() }?.let { " // $it" } ?: ""
                appendLine("- #${rec.index}$pkg ${describeAction(rec.action)}$ps$intervention → $ok$matched$msg$thought")
            }
            // 用户介入累计统计（让 AI 感知"我前几步被用户改了几次方向"）
            val interventions = history.mapNotNull { it.userIntervention }
            val replaced = interventions.count { it is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Replaced }
            if (replaced > 0) {
                appendLine("（累计：用户换节点 $replaced 步——请反思你的选择是否经常偏离用户意图）")
            }
        }.trimEnd()
    }

    private fun formatIntervention(d: top.xjunz.tasker.ai.agent.overlay.AiAgentDecision?): String {
        if (d == null) return ""
        return when (d) {
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.ApprovedManual -> " [用户同意]"
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.ApprovedAuto -> ""
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Terminate -> {
                val r = if (d.reason.isNullOrBlank()) "" else "(${d.reason})"
                " [用户终止${r}]"
            }
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Replaced -> {
                val hint = d.replacementHint?.let { "($it)" }.orEmpty()
                " [用户换为${hint}]"
            }
            is top.xjunz.tasker.ai.agent.overlay.AiAgentDecision.Skipped -> ""
        }
    }

    private fun buildSnapshotSection(snapshot: AiUiSnapshot?): String {
        if (snapshot == null) {
            return "当前屏幕：（无法读取节点树，请先尝试 launch_app 或 wait）"
        }
        val header = buildString {
            append("当前屏幕")
            snapshot.packageName?.let { append(" @$it") }
            snapshot.activityName?.let { append("/$it") }
            append(" (${snapshot.screenWidth}x${snapshot.screenHeight})")
        }
        if (snapshot.nodes.isEmpty()) {
            return "$header：（节点列表为空，可能屏幕还在加载）"
        }
        // 头部统计——给 AI 一眼判断"此页能不能 set_text / 能不能 click"的依据。
        // editable=0 是关键信号：set_text 直接禁用，必须先 click 进入有输入框的页面。
        val clickableCnt = snapshot.nodes.count { it.clickable }
        val editableCnt = snapshot.nodes.count { it.editable }
        val scrollableCnt = snapshot.nodes.count { it.scrollable }
        return buildString {
            appendLine(header)
            appendLine(
                "节点 ${snapshot.nodes.size} 个（可点击 $clickableCnt / 可编辑 $editableCnt / 可滚动 $scrollableCnt）"
            )
            appendLine(
                "flag 含义：C=可点击 L=可长按 **E=可编辑（输入框）** F=可聚焦 S=可滚动 k/K=勾选状态 D=已禁用"
            )
            if (editableCnt == 0) {
                appendLine("⚠ 当前屏幕**没有任何 [E] 节点**——禁止使用 set_text，应先 click 进入有输入框的页面。")
            }
            appendLine("节点列表：")
            snapshot.nodes.forEach { n ->
                appendLine(formatNode(n))
            }
        }.trimEnd()
    }

    private fun formatNode(n: AiUiNode): String {
        val flags = buildString {
            if (n.clickable) append('C')
            if (n.longClickable) append('L')
            if (n.editable) append('E')
            if (n.scrollable) append('S')
            if (n.checkable) append(if (n.checked) 'k' else 'K')
            if (!n.enabled) append('D')
            if (n.focused) append('F')
        }.ifEmpty { "-" }
        val text = n.text?.let { " text=\"$it\"" } ?: ""
        val cd = n.contentDesc?.let { " desc=\"$it\"" } ?: ""
        val viewId = n.viewId?.let { " id=$it" } ?: ""
        val parent = n.parentId?.let { " parent=#$it" } ?: ""
        return "  #${n.id} [${n.className}] [$flags]$viewId$text$cd $parent bounds=(${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom})"
    }

    private fun describeAction(action: AiAgentAction): String = when (action) {
        is AiAgentAction.LaunchApp -> "launch_app(${action.packageName})"
        is AiAgentAction.Click -> "click(${formatTarget(action.target)})"
        is AiAgentAction.LongClick -> "long_click(${formatTarget(action.target)})"
        is AiAgentAction.SetText -> "set_text(${formatTarget(action.target)}, \"${action.text.take(40)}\")"
        is AiAgentAction.Wait -> "wait(${action.seconds}s)"
        is AiAgentAction.Scroll -> "scroll(${action.direction})"
        is AiAgentAction.GlobalBack -> "global_back"
        is AiAgentAction.GlobalHome -> "global_home"
        is AiAgentAction.Done -> "done(${action.summary.take(40)})"
        is AiAgentAction.GiveUp -> "give_up(${action.reason.take(40)})"
        is AiAgentAction.Unknown -> "unknown(${action.raw.take(40)})"
    }

    private fun formatTarget(t: AiUiTarget): String = buildString {
        val parts = mutableListOf<String>()
        t.viewId?.let { parts.add("id=$it") }
        t.textEquals?.let { parts.add("text=$it") }
        t.textContains?.let { parts.add("text~$it") }
        t.contentDescEquals?.let { parts.add("desc=$it") }
        t.contentDescContains?.let { parts.add("desc~$it") }
        t.className?.let { parts.add("cls=$it") }
        if (t.matchIndex > 0) parts.add("idx=${t.matchIndex}")
        append(parts.joinToString(",").take(120))
    }

    private fun parseCapabilities(raw: List<String>?): Set<AiCapability> {
        if (raw.isNullOrEmpty()) return emptySet()
        val result = mutableSetOf<AiCapability>()
        for (item in raw) {
            val trimmed = item.trim()
            val match = AiCapability.entries.firstOrNull {
                it.name.equals(trimmed, ignoreCase = true)
            }
            if (match != null) result.add(match)
        }
        return result
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end >= start) text.substring(start, end + 1) else text
    }

    /**
     * 调试用：能把 history 序列化成给人看的字符串，但目前 [buildHistorySection] 已经够用，
     * 这个保留作为后续扩展接口（例如把整段 history 用 JSON 灌给 prompt）。
     */
    @Suppress("unused")
    fun encodeHistoryForDebug(history: List<AiAgentStepRecord>): String {
        return AiJson.encodeToString(history.map {
            AgentHistoryDebugDto(
                index = it.index,
                actionType = it.action::class.simpleName.orEmpty(),
                ok = it.result.ok,
                message = it.result.message
            )
        })
    }

    @Serializable
    private data class AgentHistoryDebugDto(
        val index: Int,
        val actionType: String,
        val ok: Boolean,
        val message: String?
    )
}

@Serializable
data class AiAgentSessionPlanDto(
    @SerialName("target_app_package") val targetAppPackage: String? = null,
    @SerialName("target_app_label") val targetAppLabel: String? = null,
    @SerialName("plan_summary") val planSummary: String? = null,
    @SerialName("estimated_steps") val estimatedSteps: Int? = null,
    @SerialName("required_capabilities") val requiredCapabilities: List<String>? = null,
    val confidence: Float = 0f
)

data class AiAgentSessionPlan(
    val targetAppPackage: String?,
    val targetAppLabel: String?,
    val summary: String,
    val estimatedSteps: Int,
    val capabilities: Set<AiCapability>,
    val confidence: Float
) {
    val isExecutable: Boolean
        get() = estimatedSteps > 0 && (capabilities.isNotEmpty()) &&
                (capabilities.any {
                    it == AiCapability.ClickUi || it == AiCapability.InputText ||
                            it == AiCapability.LaunchIntent || it == AiCapability.ReadNodeTree
                })
}

data class AiAgentSessionPlanResult(
    val plan: AiAgentSessionPlan?,
    val prompt: String,
    val rawResponse: String?,
    val providerError: String? = null
)

data class AiAgentNextActionResult(
    val action: AiAgentAction,
    val prompt: String,
    val rawResponse: String?,
    val providerError: String? = null
)
