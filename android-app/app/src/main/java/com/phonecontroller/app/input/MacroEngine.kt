package com.phonecontroller.app.input

import com.phonecontroller.app.net.DeltaEvent
import com.phonecontroller.app.profile.MacroConfig
import com.phonecontroller.app.profile.MacroLimits
import com.phonecontroller.app.profile.MacroStepType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Executes validated [MacroConfig]s on the phone and routes every key through
 * [InputTracker], so macro keys obey the same reference counts, release-all
 * and snapshot rules as touch controls.
 *
 * All methods must run on the UI thread (the scope supplied by `PlayScreen` is
 * the Compose scope). This keeps [InputTracker], which is not thread-safe, on a
 * single dispatcher and makes [cancel] release held keys synchronously.
 *
 * Safety:
 * - [MacroConfig.sanitized] caps steps, repeats, delays and total duration.
 * - A runtime timeout is a second line of defence around the whole run.
 * - Every run releases anything it pressed in `finally`, including on cancel.
 * - Starting a run twice for the same cell is rejected; tapping the macro
 *   button while it runs cancels it.
 */
class MacroEngine(
    private val tracker: InputTracker,
    private val emit: (List<DeltaEvent>) -> Unit,
    private val scope: CoroutineScope,
    private val onRunningChanged: (Set<String>) -> Unit = {},
) {
    private val jobs = LinkedHashMap<String, Job>()
    private val runIds = HashMap<String, Long>()
    /** action id -> action for every macro key currently held. */
    private val pressed = LinkedHashMap<String, ControlAction>()
    private var nextRunId = 0L

    fun isRunning(cellId: String): Boolean = jobs.containsKey(cellId)

    val runningIds: Set<String>
        get() = jobs.keys.toSet()

    /**
     * Start [macro] for [cellId]. Returns false for an empty/non-runnable macro
     * or if that cell already has a run in progress.
     */
    fun start(cellId: String, macro: MacroConfig): Boolean {
        if (jobs.containsKey(cellId)) return false
        val plan = macro.sanitized()
        if (!plan.isRunnable()) return false

        val runId = ++nextRunId
        // LAZY: publish the running state before the body can finish, otherwise
        // a zero-delay macro with Main.immediate could complete before the map
        // entries are installed and remain "running" forever.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(MacroLimits.MAX_DURATION_MS.toLong() + 2_000L) {
                    execute(cellId, runId, plan)
                }
            } finally {
                // A cancelled run may have already been replaced by a new one
                // for the same cell; only the current run owns cleanup.
                if (runIds[cellId] == runId) {
                    releaseActionsFor(cellId, runId)
                    runIds.remove(cellId)
                    jobs.remove(cellId)
                    notifyRunningChanged()
                }
            }
        }
        jobs[cellId] = job
        runIds[cellId] = runId
        notifyRunningChanged()
        job.start()
        return true
    }

    /** Stop [cellId] immediately and release every key it still holds. */
    fun cancel(cellId: String) {
        val runId = runIds.remove(cellId) ?: return
        jobs.remove(cellId)?.cancel()
        releaseActionsFor(cellId, runId)
        notifyRunningChanged()
    }

    /** Cancel every macro (disconnect, mode change, profile switch). */
    fun cancelAll() {
        if (jobs.isEmpty() && pressed.isEmpty()) return
        val activeJobs = jobs.values.toList()
        jobs.clear()
        runIds.clear()
        for (job in activeJobs) job.cancel()
        releaseAllActions()
        notifyRunningChanged()
    }

    private suspend fun execute(cellId: String, runId: Long, macro: MacroConfig) {
        for (repeat in 0 until macro.repeatCount) {
            for ((index, step) in macro.steps.withIndex()) {
                val actionId = actionId(cellId, runId, repeat, index)
                when (step.type) {
                    MacroStepType.TAP -> {
                        press(actionId, step.keys)
                        if (step.holdMs > 0) delay(step.holdMs.toLong())
                        release(actionId)
                    }

                    MacroStepType.HOLD -> {
                        press(actionId, step.keys)
                    }

                    MacroStepType.RELEASE -> {
                        releaseMatching(cellId, runId, step.keys)
                    }

                    MacroStepType.WAIT -> Unit
                }
                if (step.delayMs > 0) delay(step.delayMs.toLong())
            }
        }
    }

    private fun press(actionId: String, keys: List<Int>) {
        val action = ControlAction(keys = keys)
        val events = tracker.press(actionId, action)
        if (tracker.isActive(actionId)) pressed[actionId] = action
        emit(events)
    }

    private fun release(actionId: String) {
        if (pressed.remove(actionId) == null) return
        emit(tracker.release(actionId))
    }

    /** Release the newest held macro action whose key set matches exactly. */
    private fun releaseMatching(cellId: String, runId: Long, keys: List<Int>) {
        val prefix = actionPrefix(cellId, runId)
        val actionId = pressed.entries.lastOrNull { (id, action) ->
            id.startsWith(prefix) && action.keys == keys
        }?.key ?: return
        release(actionId)
    }

    private fun releaseActionsFor(cellId: String, runId: Long) {
        val prefix = actionPrefix(cellId, runId)
        val ids = pressed.keys.filter { it.startsWith(prefix) }
        for (id in ids.asReversed()) {
            release(id)
        }
    }

    private fun releaseAllActions() {
        for (id in pressed.keys.toList().asReversed()) {
            release(id)
        }
    }

    private fun notifyRunningChanged() {
        onRunningChanged(runningIds)
    }

    private fun actionPrefix(cellId: String, runId: Long): String =
        "macro_${cellId}_${runId}_"

    private fun actionId(
        cellId: String,
        runId: Long,
        repeat: Int,
        stepIndex: Int,
    ): String = "${actionPrefix(cellId, runId)}${repeat}_$stepIndex"
}
