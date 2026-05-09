/*
 * Copyright (c) 2026 IanVzs. All rights reserved.
 */

package top.xjunz.tasker.ai.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.xjunz.tasker.BuildConfig
import top.xjunz.tasker.ai.translator.AiActionToTask
import top.xjunz.tasker.app
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Do
import top.xjunz.tasker.engine.applet.base.RootFlow
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.service.currentService
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.applet.option.registry.BootstrapOptionRegistry
import top.xjunz.tasker.task.applet.option.registry.ShellCmdActionRegistry
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback
import top.xjunz.tasker.task.runtime.LocalTaskManager

/**
 * Agent 单步动作执行器。
 *
 * **架构对齐**（见 `aidoc/16-ai-inspector-capability.md` §13.8）：
 * 不再自己 `node.performAction(...)`。改成：
 * 1. [AiActionToTask.translate] 把 [AiAgentAction] 翻成临时单步 [XTask]
 * 2. 注册到 [LocalTaskManager.addOneshotTaskIfAbsent]（自动同步给特权进程的 PrivilegedTaskManager）
 * 3. 调 `currentService.scheduleOneshotTask(task, callback)` —— 跨进程派发
 * 4. await callback 拿到 ok/fail
 *
 * 这样：
 * - A11y / Shizuku 双模式自动支持（task 管道本就支持）
 * - 节点定位走 `containsUiObject + Criterion`，跟 inspector 完全一致
 * - 跨进程问题、错误处理、wakeLock、scheduler 全部复用现有能力
 *
 * 不通过 task 派发的动作：
 * - [AiAgentAction.Wait]：本地 delay 即可
 * - [AiAgentAction.Done] / [AiAgentAction.GiveUp] / [AiAgentAction.Unknown]：session 终止前会被
 *   [AiAgentSession] 拦下来，按理不会到这；保险起见仍报 ok=true / no-op。
 * - [AiAgentAction.Scroll]：ScrollMetrics 编码暂未实现，返回明确不支持的失败。
 */
object AiAgentExecutor {

    /** 单个 task 派发后等 callback 的最长时间，避免远端死锁锁住整段 session。 */
    private const val SCHEDULE_TIMEOUT_MILLIS = 30_000L

    /** [launchApp] 轮询前台 pkg 是否切换的间隔。 */
    private const val LAUNCH_POLL_INTERVAL_MILLIS = 200L

    /** [launchApp] 等待 pkg 切换的最长时间。 */
    private const val LAUNCH_MAX_WAIT_MILLIS = 2500L

    suspend fun execute(action: AiAgentAction): AiAgentStepResult {
        val result = when (action) {
            is AiAgentAction.Wait -> {
                delay(action.seconds * 1000L)
                AiAgentStepResult(ok = true, message = "已等待 ${action.seconds}s")
            }

            is AiAgentAction.LaunchApp -> launchApp(action.packageName)

            is AiAgentAction.Scroll -> AiAgentStepResult(
                ok = false,
                message = "scroll 暂未通过 task 管道实现，请用 click 或 wait 替代"
            )

            is AiAgentAction.SetText -> setTextWithFallback(action)

            is AiAgentAction.Done,
            is AiAgentAction.GiveUp,
            is AiAgentAction.Unknown -> AiAgentStepResult(ok = true, message = "no-op")

            else -> dispatchViaTask(action)
        }
        AiAgentLog.i(
            "execute",
            "${action::class.simpleName} → ok=${result.ok}" +
                    (result.message?.let { " | $it" } ?: "")
        )
        return result
    }

    /**
     * setText 三层 fallback。设计动机：
     * - 标准 a11y `ACTION_SET_TEXT` 在 native AOSP EditText 上 100% 灵——但在 React Native /
     *   Compose 等自绘控件（deepseek、微信等）上系统会上报 "perform OK" 但实际**没有任何文本写入**，
     *   是众所周知的兼容性陷阱。
     * - 之前 [CoroutineUiObject.setText] 还有"假成功"bug 把这个问题彻底掩盖了——已修，会真返回 false。
     *
     * Fallback 链（从快到稳）：
     *  1. **a11y SET_TEXT** （走 task 管道）：原生 EditText 命中率最高，1ms 完成；
     *  2. **clipboard + KEYCODE_PASTE** （shell input 注入）：写主进程剪贴板，先 click 让节点聚焦
     *     输入法接管，再注入 PASTE keyevent。绕开 a11y 协议，对 RN/Compose/微信都灵。
     *
     * 任何一步成功就返回；最后一步还失败再 give up。每一步都把诊断细节塞进 result.message，
     * AI 在下一轮 prompt 里能看到"a11y SET_TEXT 失败，已 fallback 到剪贴板粘贴成功" 之类的故事。
     */
    private suspend fun setTextWithFallback(action: AiAgentAction.SetText): AiAgentStepResult {
        // 第一层：原 task 管道（a11y SET_TEXT），普通 EditText 一发命中
        val first = dispatchViaTask(action)
        // **关键修法**：a11y SET_TEXT 在 deepseek/RN 等自绘控件上**系统层返回 true 但实际没写入**——
        // 这种 silent fail 是 Android 框架的已知陷阱，task callback 完全无法识别。
        // 必须执行后立刻抓一次 snapshot 验证 EditText.text 是否真的包含我们的输入，
        // 没有就直接走 paste fallback；不能等下一轮 session loop 的 silent-fail 检测（那时 fallback 已晚）。
        if (first.ok) {
            delay(250)  // 给 UI 反应时间
            if (verifyTextWritten(action)) {
                return first.copy(message = "a11y SET_TEXT 成功（验证 EditText 已包含输入文字）")
            }
            AiAgentLog.w(
                "execute.setText",
                "a11y SET_TEXT 系统层返回 true 但屏幕 EditText 实际未写入文字（silent fail）；启动 paste fallback"
            )
        } else {
            AiAgentLog.w("execute.setText", "a11y SET_TEXT 失败，启动剪贴板+PASTE fallback：${first.message}")
        }

        // 第二层：剪贴板 + KEYCODE_PASTE
        if (!serviceController.isServiceRunning) {
            return AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败，且 AutoTask 服务未运行无法 fallback 到剪贴板粘贴：${first.message}"
            )
        }
        runCatching { writeClipboard(action.text) }.onFailure {
            return AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败，写剪贴板也失败：${it.message}"
            )
        }
        // 先 click 节点让它聚焦——KEYCODE_PASTE 需要焦点在 EditText 上才会触发输入法粘贴
        val focusClick = dispatchViaTask(AiAgentAction.Click(target = action.target, thought = "聚焦输入框为 PASTE 准备"))
        if (!focusClick.ok) {
            return AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败 + 聚焦 click 也失败（${focusClick.message}），请用户手动接管"
            )
        }
        // 给输入法 / 焦点切换留点反应时间
        delay(200)
        // 注入 KEYCODE_PASTE (279) — 走特权进程的 ShellCmdAction（仅 Shizuku 模式可用）
        val pasteResult = dispatchShellViaTask("input keyevent 279")
        return if (pasteResult.ok) {
            AiAgentStepResult(
                ok = true,
                message = "a11y SET_TEXT 不响应（${first.message}），已 fallback 到剪贴板+KEYCODE_PASTE 粘贴"
            )
        } else {
            AiAgentStepResult(
                ok = false,
                message = "a11y SET_TEXT 失败 + KEYCODE_PASTE 注入也失败（${pasteResult.message}）。" +
                        "若使用 a11y 模式则不支持 shell 注入，请改用 Shizuku 模式或手动接管。"
            )
        }
    }

    /**
     * a11y SET_TEXT 之后立刻抓一次 snapshot，看 target 命中节点的 text 是否**真的包含**我们的输入文字。
     *
     * 匹配规则：用 target 在新 snapshot 找候选节点（跟 [AiAgentOverlayController.previewTargetBounds] 同款逻辑），
     * 看节点的 text 是否包含 action.text 前 16 字符。包含 = 系统真的写入了；不包含 = silent fail。
     *
     * 抓不到 snapshot 时**保守认为成功**（避免误判触发不必要的 fallback）。
     */
    private suspend fun verifyTextWritten(action: AiAgentAction.SetText): Boolean {
        val snapshot = ScreenSnapshotProvider.capture() ?: return true
        val target = action.target
        val needle = action.text.take(16)
        if (needle.isBlank()) return true
        val candidate = snapshot.nodes.firstOrNull { node ->
            val viewIdOk = target.viewId.isNullOrBlank() || node.viewId == target.viewId
            val textEqOk = target.textEquals.isNullOrBlank() ||
                    node.text == target.textEquals ||
                    (node.text?.contains(needle) == true)
            val classOk = target.className.isNullOrBlank() ||
                    node.className.substringAfterLast('.').equals(target.className, ignoreCase = true) ||
                    node.className.equals(target.className, ignoreCase = true)
            viewIdOk && classOk && (textEqOk || node.editable)
        } ?: snapshot.nodes.firstOrNull { it.editable }
        val written = candidate?.text?.contains(needle) == true
        AiAgentLog.d(
            "execute.setText.verify",
            "needle=\"$needle\" candidate.text=\"${candidate?.text}\" → written=$written"
        )
        return written
    }

    /**
     * 把文本写到主进程剪贴板。必须在主线程（ClipboardManager 是 main-thread API）。
     */
    private suspend fun writeClipboard(text: String) = withContext(Dispatchers.Main) {
        val cm = app.getSystemService(ClipboardManager::class.java)
            ?: error("ClipboardManager 不可用")
        cm.setPrimaryClip(ClipData.newPlainText("ai_agent_input", text))
    }

    /**
     * 通过 task 管道在特权进程跑一条 shell 命令。复用 [ShellCmdActionRegistry.executeShellCmd]——
     * 这个 applet 已经标 `.shizukuOnly()`，意味着 a11y 模式调用会失败，调用方需要做兜底文案。
     *
     * 用 [AiActionToTask] 的同款 RootFlow + Do(REL_ANYWAY) 模板，避免重写一次踩同样的 relation gate 坑。
     */
    private suspend fun dispatchShellViaTask(cmd: String): AiAgentStepResult {
        if (!serviceController.isServiceRunning) {
            return AiAgentStepResult(ok = false, message = "AutoTask 服务未运行")
        }
        val task = runCatching {
            val factory = AppletOptionFactory
            factory.preloadIfNeeded()
            val root = factory.flowRegistry.rootFlow.yield() as RootFlow
            root.add(factory.flowRegistry.preloadFlow.yield())
            val doFlow = factory.flowRegistry.doFlow.yield() as Do
            doFlow.relation = Applet.REL_ANYWAY
            root.add(doFlow)
            val shellRegistry = factory.requireRegistryById(BootstrapOptionRegistry.ID_SHELL_CMD_ACTION_REGISTRY)
                    as ShellCmdActionRegistry
            doFlow.add(shellRegistry.executeShellCmd.yieldWithFirstValue(cmd))
            XTask().apply {
                metadata = XTask.Metadata(
                    title = "AI: shell",
                    taskType = XTask.TYPE_ONESHOT,
                    description = "agent shell 单步：$cmd",
                    checksum = System.nanoTime()
                ).apply { version = BuildConfig.VERSION_CODE }
                flow = root
            }
        }.getOrElse {
            AiAgentLog.e("execute.shell", "构造 shell task 失败：${it.message}", it)
            return AiAgentStepResult(ok = false, message = "构造 shell task 失败：${it.message}")
        }
        val deferred = CompletableDeferred<Boolean>()
        runCatching {
            LocalTaskManager.addOneshotTaskIfAbsent(task)
            currentService.scheduleOneshotTask(
                task,
                object : ITaskCompletionCallback.Stub() {
                    override fun onTaskCompleted(isSuccessful: Boolean) {
                        deferred.complete(isSuccessful)
                    }
                }
            )
        }.onFailure {
            deferred.complete(false)
            runCatching { LocalTaskManager.removeTask(task) }
            return AiAgentStepResult(ok = false, message = "shell 调度异常：${it.message}")
        }
        val ok = withTimeoutOrNull(SCHEDULE_TIMEOUT_MILLIS) { deferred.await() }
        runCatching { LocalTaskManager.removeTask(task) }
        return when (ok) {
            null -> AiAgentStepResult(ok = false, message = "shell 任务超时")
            true -> AiAgentStepResult(ok = true)
            false -> AiAgentStepResult(ok = false, message = "shell 任务失败（可能 a11y 模式不支持）")
        }
    }

    /**
     * **launch_app 不走 task 管道**——`ApplicationActionRegistry.launchApp` 内部硬写 `true` 返回，
     * 不论 `ActivityManagerBridge.startComponent` 实际是否拉起 App 都报告成功，会误导 AI。
     *
     * 改回主进程的 `app.startActivity(intent)`（之前已经验证过能 work），并且**轮询前台 pkg
     * 是否真的切到目标**——切到了再返回 OK，否则如实告知 AI"已发起但前台仍是 X"。
     */
    private suspend fun launchApp(pkg: String): AiAgentStepResult = withContext(Dispatchers.Main) {
        if (!serviceController.isServiceRunning) {
            return@withContext AiAgentStepResult(
                ok = false,
                message = "AutoTask 服务未启动，无法观察启动结果。请去 AutoTask 主页『启动服务』后重试。"
            )
        }
        val intent = runCatching { app.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: return@withContext AiAgentStepResult(ok = false, message = "未安装或找不到 App: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }.onFailure {
            return@withContext AiAgentStepResult(ok = false, message = "启动失败: ${it.message}")
        }
        // 轮询前台 pkg 是否切到目标
        var observed: String? = currentForegroundPackage()
        if (observed != pkg) {
            val deadline = System.currentTimeMillis() + LAUNCH_MAX_WAIT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                delay(LAUNCH_POLL_INTERVAL_MILLIS)
                observed = currentForegroundPackage()
                if (observed == pkg) break
            }
        }
        if (observed == pkg) {
            AiAgentStepResult(ok = true, message = "已启动并切换到 $pkg")
        } else {
            val pkgHint = observed ?: "未知（a11y 事件还没到，可能 App 已启但事件延迟）"
            AiAgentStepResult(
                ok = true,
                message = "已发起启动 $pkg，但等了 ${LAUNCH_MAX_WAIT_MILLIS}ms 当前前台仍是 $pkgHint，请在下一步用 wait 再观察或换策略"
            )
        }
    }

    private fun currentForegroundPackage(): String? {
        if (!serviceController.isServiceRunning) return null
        return runCatching {
            currentService.getCurrentComponentInfo().packageName
        }.getOrNull()
    }

    /**
     * 把 action 翻成临时单步 XTask 派给执行端进程跑。
     * 任何失败（service 没启动 / 翻译失败 / 调度异常 / 超时）都映射为带 message 的 [AiAgentStepResult]，
     * 上层 [AiAgentSession] 看到失败 message 会塞进 history 喂给 AI 自学。
     */
    private suspend fun dispatchViaTask(action: AiAgentAction): AiAgentStepResult {
        if (!serviceController.isServiceRunning) {
            return AiAgentStepResult(
                ok = false,
                message = "AutoTask 服务未启动，无法执行节点动作。请去 AutoTask 主页『启动服务』后重试。"
            )
        }
        val task = AiActionToTask.translate(action) ?: return AiAgentStepResult(
            ok = false,
            message = "无法把 ${action::class.simpleName} 翻译为 task（action target 可能为空 / 不支持的动作类型）"
        )
        val deferred = CompletableDeferred<Boolean>()
        runCatching {
            LocalTaskManager.addOneshotTaskIfAbsent(task)
            currentService.scheduleOneshotTask(
                task,
                object : ITaskCompletionCallback.Stub() {
                    override fun onTaskCompleted(isSuccessful: Boolean) {
                        deferred.complete(isSuccessful)
                    }
                }
            )
        }.onFailure {
            deferred.complete(false)
            // 调度异常也要清理，避免半挂的临时 task 留在 LocalTaskManager 累积
            runCatching { LocalTaskManager.removeTask(task) }
            AiAgentLog.e("execute", "scheduleOneshotTask 异常：${it.message}", it)
            return AiAgentStepResult(ok = false, message = "调度任务异常：${it.message}")
        }
        val ok = withTimeoutOrNull(SCHEDULE_TIMEOUT_MILLIS) { deferred.await() }
        // 不论成功失败 / 超时，都清掉这个一次性临时 task；它跑完即丢，不需要保留。
        // 不清理会导致 agent 跑久了 LocalTaskManager + PrivilegedTaskManager 都累积上百个临时 task。
        runCatching { LocalTaskManager.removeTask(task) }
        return when (ok) {
            null -> AiAgentStepResult(
                ok = false,
                message = "等待任务完成超时（${SCHEDULE_TIMEOUT_MILLIS}ms），可能远端卡死，请下一步 wait 或 give_up"
            )
            true -> AiAgentStepResult(ok = true, message = "task 执行完成")
            false -> AiAgentStepResult(
                ok = false,
                message = "task 执行返回失败（节点未命中 / 不可点 / 节点拒绝）"
            )
        }
    }
}
