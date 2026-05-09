/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.translator

import top.xjunz.tasker.BuildConfig
import top.xjunz.tasker.R
import top.xjunz.tasker.ai.agent.AiAgentAction
import top.xjunz.tasker.ai.agent.AiUiTarget
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Do
import top.xjunz.tasker.engine.applet.base.Flow
import top.xjunz.tasker.engine.applet.base.RootFlow
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.ktx.str
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.applet.option.registry.BootstrapOptionRegistry
import top.xjunz.tasker.task.applet.option.registry.GlobalActionRegistry
import top.xjunz.tasker.task.applet.option.registry.UiObjectActionRegistry
import top.xjunz.tasker.task.applet.option.registry.UiObjectCriterionRegistry
import top.xjunz.tasker.task.editor.AppletReferenceEditor
import top.xjunz.tasker.task.inspector.shared.NodeToActionAssembler
import java.util.concurrent.atomic.AtomicLong

/**
 * 把 AI agent 输出的 [AiAgentAction] 翻译成一棵**最小的临时单步 [XTask]**，由调用方通过
 * `LocalTaskManager.addOneshotTaskIfAbsent(task)` + `currentService.scheduleOneshotTask(task, callback)`
 * 派给执行端进程执行。
 *
 * **架构对照**（见 `aidoc/16-ai-inspector-capability.md` §13.8）：
 * - agent 的"动作执行"复用 AutoTask 现有的 Applet 管道，**不再**自己写 `performAction`。
 * - 跨进程问题、节点定位、a11y / Shizuku 双模式适配全部由现有 task 管道解决。
 * - 输出的 [XTask] 保留 [Applet] 序列；将来"agent 跑完保存为可重放任务"只需把多个临时 task merge 成大 task。
 *
 * 不支持的动作（[AiAgentAction.Done] / [GiveUp] / [Unknown] / [Wait] / [Scroll]）返回 null：
 * - Done / GiveUp / Unknown：session 终止，不应执行；
 * - Wait：session 内部 delay 即可，不必派任务；
 * - Scroll：ScrollMetrics 是 packed long，需要额外编码工作，留 follow-up。
 *
 * 三个支持动作：launch_app / click+long_click / set_text，对应业务里 90% 的用例。
 */
object AiActionToTask {

    /**
     * 给临时 task 一个全局唯一 checksum：用 `nanoTime` 当种子保证不同步并发不冲突。
     * 这个值不参与持久化校验（agent 临时 task 跑完即丢），只为 PrivilegedTaskManager 的
     * `requireTask(checksum)` 索引提供唯一键。
     */
    private val checksumCounter = AtomicLong(System.nanoTime())

    /**
     * @return 翻译成功的 [XTask]；不支持的动作类型返回 null。
     */
    fun translate(action: AiAgentAction): XTask? {
        AppletOptionFactory.preloadIfNeeded()
        val factory = AppletOptionFactory
        val root = factory.flowRegistry.rootFlow.yield() as RootFlow
        // preloadFlow 必须出现并显式 setReferent，给 containsUiObject 提供 "current_top_app" 与
        // "current_window" 这两个 root 输入；否则 containsUiObject 会拿不到 root → applet 抛异常。
        // 这两个 referent 名字与 inspector "AUTO_CLICK" 路径完全一致（FlowEditorViewModel.QUICK_TASK_CREATOR_AUTO_CLICK）。
        val preloadFlow = factory.flowRegistry.preloadFlow.yield()
        root.add(preloadFlow)
        val rootEditor = AppletReferenceEditor(false)
        rootEditor.setReferent(preloadFlow, 0, R.string.current_top_app.str)
        rootEditor.setReferent(preloadFlow, 3, R.string.current_window.str)

        val doFlow = factory.flowRegistry.doFlow.yield() as Do
        // Do 默认 relation=REL_AND，需要上一个 sibling isSuccessful=true 才跑。
        // 但 preloadFlow children 为空，applyFlow 直接 return 保留入口 isSuccessful=false（task 初值），
        // 导致 Do 被 Flow.applyFlow:41 的 relation gate 跳过。
        // 强制 Do.relation=REL_ANYWAY 让它无条件跑。Do 的 setter 继承自 ControlFlow，仅允许 REL_ANYWAY。
        doFlow.relation = Applet.REL_ANYWAY
        root.add(doFlow)

        val (titleRaw, ok) = when (action) {
            is AiAgentAction.Click -> "AI: click" to addUiObjectAction(doFlow, factory, action.target, UiObjectActionKind.Click)
            is AiAgentAction.LongClick -> "AI: long_click" to addUiObjectAction(doFlow, factory, action.target, UiObjectActionKind.LongClick)
            is AiAgentAction.SetText -> "AI: set_text" to addUiObjectAction(doFlow, factory, action.target, UiObjectActionKind.SetText, action.text)
            is AiAgentAction.GlobalBack -> "AI: global_back" to addGlobal(doFlow, factory, GlobalActionKind.Back)
            is AiAgentAction.GlobalHome -> "AI: global_home" to addGlobal(doFlow, factory, GlobalActionKind.Home)
            // LaunchApp 不走 task 管道——ApplicationActionRegistry.launchApp 内部硬写 true 返回，
            // 误导 AI。改在 AiAgentExecutor.launchApp 主进程直接 startActivity + 轮询前台 pkg。
            is AiAgentAction.LaunchApp,
            is AiAgentAction.Wait,
            is AiAgentAction.Scroll,
            is AiAgentAction.Done,
            is AiAgentAction.GiveUp,
            is AiAgentAction.Unknown -> return null
        }
        if (!ok) return null

        return XTask().apply {
            metadata = XTask.Metadata(
                title = titleRaw,
                taskType = XTask.TYPE_ONESHOT,
                description = "agent 单步临时任务，跑完即丢",
                checksum = nextChecksum()
            ).apply { version = BuildConfig.VERSION_CODE }
            flow = root
        }
    }

    private fun nextChecksum(): Long = checksumCounter.incrementAndGet()

    // ---------- 单步翻译实现 ----------

    private enum class GlobalActionKind { Back, Home }

    private fun addGlobal(doFlow: Flow, factory: AppletOptionFactory, kind: GlobalActionKind): Boolean {
        return runCatching {
            val registry = factory.requireRegistryById(BootstrapOptionRegistry.ID_GLOBAL_ACTION_REGISTRY)
                    as GlobalActionRegistry
            val applet = when (kind) {
                GlobalActionKind.Back -> registry.pressBack.yield()
                GlobalActionKind.Home -> registry.pressHome.yield()
            }
            doFlow.add(applet)
        }.isSuccess
    }

    private enum class UiObjectActionKind { Click, LongClick, SetText }

    /**
     * 包出 `Do → containsUiObject(criteria) + Action(reference="matched_ui_object")` 结构。
     * 完全对齐用户在 inspector 里"挑节点 → 自动点击"生成的同款 Applet 树。
     */
    private fun addUiObjectAction(
        doFlow: Flow,
        factory: AppletOptionFactory,
        target: AiUiTarget,
        kind: UiObjectActionKind,
        text: String? = null
    ): Boolean {
        if (target.isEmpty) return false
        val criterionRegistry = factory.uiObjectRegistry
        val criteria = buildCriteria(target, criterionRegistry)
        if (criteria.isEmpty()) return false
        val editor = AppletReferenceEditor(false)
        val containsUiObject = NodeToActionAssembler.wrapAsContainsUiObject(criteria, editor)
        doFlow.add(containsUiObject)

        val actionRegistry = factory.requireRegistryById(BootstrapOptionRegistry.ID_UI_OBJECT_ACTION_REGISTRY)
                as UiObjectActionRegistry
        val actionApplet = when (kind) {
            UiObjectActionKind.Click -> actionRegistry.click.yield()
            UiObjectActionKind.LongClick -> actionRegistry.longClick.yield()
            UiObjectActionKind.SetText -> {
                if (text == null) return false
                // setText: ref 槽 0 = AccessibilityNodeInfo，unary 槽 1 = String
                actionRegistry.setText.yield(1 to text)
            }
        }
        // ref 槽 0 = 命中节点；从 containsUiObject 暴露的 referent "matched_ui_object" 拿
        editor.setReference(actionApplet, 0, R.string.matched_ui_object.str)
        doFlow.add(actionApplet)
        return true
    }

    /**
     * 从 [AiUiTarget] 各字段构造一组 [UiObjectCriterionRegistry] Criterion Applet。
     * 多字段是 AND 关系（`containsUiObject` 内默认 REL_AND），匹配越准。
     *
     * 跟 inspector 的 [top.xjunz.tasker.task.inspector.shared.NodeCriteriaExtractor] 抽出来的同源条件一致。
     */
    private fun buildCriteria(target: AiUiTarget, registry: UiObjectCriterionRegistry): List<Applet> {
        val out = mutableListOf<Applet>()
        target.viewId?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.withId.yieldWithFirstValue(it))
        }
        target.textEquals?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.textEquals.yieldWithFirstValue(it))
        }
        target.textContains?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.textContains.yieldWithFirstValue(it))
        }
        target.contentDescEquals?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.contentDesc.yieldWithFirstValue(it))
        }
        // contentDescContains: registry.contentDesc 默认是包含语义；如未来加专门 contains 选项再切换
        target.className?.takeIf { it.isNotBlank() }?.let {
            out.add(registry.isType.yieldWithFirstValue(it))
        }
        return out
    }
}
