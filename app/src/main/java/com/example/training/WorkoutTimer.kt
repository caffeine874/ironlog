package com.example.training

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/** UI-only countdown state. It never opens the workout or coach stores. */
internal class WorkoutTimerState private constructor(
    initialDuration: Int,
    initialRemaining: Long,
    initialDeadline: Long,
    initialRunning: Boolean,
    initialStarted: Boolean
) {
    constructor() : this(120, 120_000L, 0L, false, false)

    var durationSeconds by mutableStateOf(initialDuration)
        private set
    var remainingMillis by mutableStateOf(initialRemaining)
        private set
    var deadlineElapsedMillis by mutableStateOf(initialDeadline)
        private set
    var isRunning by mutableStateOf(initialRunning)
        private set
    private var hasStarted by mutableStateOf(initialStarted)

    internal var onFinished: () -> Unit = {}
    val isPaused: Boolean get() = hasStarted && !isRunning && remainingMillis > 0L
    val canSetDuration: Boolean get() = !isRunning && !isPaused
    val remainingSeconds: Long get() = (remainingMillis.coerceAtLeast(0L) + 999L) / 1000L
    val remainingText: String get() = String.format(Locale.ROOT, "%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)
    val statusText: String get() = when {
        isRunning -> "カウント中"
        isPaused -> "一時停止中"
        remainingMillis == 0L && hasStarted -> "終了"
        else -> "準備完了"
    }

    fun setDuration(seconds: Int) {
        if (!canSetDuration || seconds !in 1..5999) return
        durationSeconds = seconds
        remainingMillis = seconds * 1000L
        deadlineElapsedMillis = 0L
        hasStarted = false
    }

    fun start() {
        if (isRunning) return
        if (!isPaused) remainingMillis = durationSeconds * 1000L
        deadlineElapsedMillis = SystemClock.elapsedRealtime() + remainingMillis
        hasStarted = true
        isRunning = true
    }

    fun pause() {
        if (!isRunning) return
        refresh()
        if (isRunning) {
            isRunning = false
            deadlineElapsedMillis = 0L
        }
    }

    fun reset() {
        isRunning = false
        hasStarted = false
        deadlineElapsedMillis = 0L
        remainingMillis = durationSeconds * 1000L
    }

    internal fun refresh() {
        if (!isRunning) return
        remainingMillis = (deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (remainingMillis == 0L) {
            // Change state before invoking the callback, so pause/recomposition cannot finish twice.
            isRunning = false
            deadlineElapsedMillis = 0L
            onFinished()
        }
    }

    companion object {
        val Saver = listSaver<WorkoutTimerState, Any>(
            save = { listOf(it.durationSeconds, it.remainingMillis, it.deadlineElapsedMillis,
                it.isRunning, it.hasStarted, SystemClock.elapsedRealtime()) },
            restore = { values ->
                val sameBoot = SystemClock.elapsedRealtime() >= (values[5] as Long)
                WorkoutTimerState(values[0] as Int, values[1] as Long,
                    if (sameBoot) values[2] as Long else 0L,
                    sameBoot && (values[3] as Boolean), values[4] as Boolean)
            }
        )
    }
}

/** Remember this at TrainingApp level, above its tab switch. */
@Composable
internal fun rememberWorkoutTimer(onFinished: () -> Unit): WorkoutTimerState {
    val state = rememberSaveable(saver = WorkoutTimerState.Saver) { WorkoutTimerState() }
    val latestFinished by rememberUpdatedState(onFinished)
    state.onFinished = { latestFinished() }
    LaunchedEffect(state.isRunning, state.deadlineElapsedMillis) {
        while (state.isRunning) {
            state.refresh()
            if (state.isRunning) delay(minOf(1000L, state.remainingMillis.coerceAtLeast(1L)))
        }
    }
    return state
}

@Composable
internal fun WorkoutTimerScreen(state: WorkoutTimerState) {
    var minutes by rememberSaveable(state.durationSeconds) { mutableStateOf((state.durationSeconds / 60).toString()) }
    var seconds by rememberSaveable(state.durationSeconds) { mutableStateOf((state.durationSeconds % 60).toString()) }
    val minuteValue = minutes.toIntOrNull()
    val secondValue = seconds.toIntOrNull()
    val validDuration = minuteValue != null && minuteValue in 0..99 && secondValue != null && secondValue in 0..59 &&
        minuteValue * 60 + secondValue > 0

    fun applyDuration() {
        if (validDuration) state.setDuration(minuteValue!! * 60 + secondValue!!)
    }

    fun updateDuration(minuteInput: String, secondInput: String) {
        val enteredMinutes = minuteInput.toIntOrNull() ?: return
        val enteredSeconds = secondInput.toIntOrNull() ?: return
        if (enteredMinutes !in 0..99 || enteredSeconds !in 0..59) return
        val total = enteredMinutes * 60 + enteredSeconds
        if (total > 0 && total != state.durationSeconds) state.setDuration(total)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("workout-timer-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("休憩タイマー", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.statusText, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("workout-timer-status"))
                Text(state.remainingText, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("workout-timer-remaining"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (state.isRunning) state.pause() else {
                                if (!state.isPaused) applyDuration()
                                state.start()
                            }
                        },
                        enabled = state.isRunning || state.isPaused || validDuration,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text(if (state.isRunning) "一時停止" else if (state.isPaused) "再開" else "開始") }
                    OutlinedButton(onClick = {
                        state.reset()
                        minutes = (state.durationSeconds / 60).toString()
                        seconds = (state.durationSeconds % 60).toString()
                    }, modifier = Modifier.weight(1f).height(52.dp)) { Text("リセット") }
                }
            }
        }
        Text("時間を設定", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = minutes, onValueChange = { value ->
                if (value.length <= 2 && value.all { it in '0'..'9' }) {
                    minutes = value
                    updateDuration(value, seconds)
                }
            }, label = { Text("分") }, singleLine = true, enabled = state.canSetDuration,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("workout-timer-minutes"))
            OutlinedTextField(value = seconds, onValueChange = { value ->
                if (value.length <= 2 && value.all { it in '0'..'9' }) {
                    seconds = value
                    updateDuration(minutes, value)
                }
            }, label = { Text("秒") }, singleLine = true, enabled = state.canSetDuration,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("workout-timer-seconds"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 5).forEach { preset ->
                OutlinedButton(onClick = {
                    state.setDuration(preset * 60)
                    minutes = preset.toString()
                    seconds = "0"
                }, enabled = state.canSetDuration, modifier = Modifier.weight(1f)) { Text("${preset}分") }
            }
        }
        if (!validDuration && state.canSetDuration) {
            Text("分は0〜99、秒は0〜59、合計1秒以上にしてください。", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Text(when {
            state.isPaused -> "一時停止しています。「再開」で残り時間から続けます。時間を変えるときはリセットしてください。"
            state.isRunning -> "ほかのタブを開いても計測は続きます。時間を変えるときはリセットしてください。"
            else -> "時間を決めて「開始」を押してください。記録の保存では自動開始しません。"
        },
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}
