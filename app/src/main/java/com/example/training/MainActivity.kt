package com.example.training

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// Page < card < field: three steps of gray only. Gold is reserved for the main action and the selected state.
private val SteelBlack = Color(0xFF0D0F11)
private val PanelBlack = Color(0xFF16191C)
private val FieldGray = Color(0xFF1F2327)
private val IronGray = Color(0xFF2C3136)
private val AshText = Color(0xFFE6E8EA)
private val SubText = Color(0xFFB9C0C4)
private val MutedText = Color(0xFF8B949A)
private val IronGold = Color(0xFFDFA742)
private val OnGold = Color(0xFF1A1204)
private val GoldTint = Color(0xFF2B2418)
private val WarningRed = Color(0xFFD0604E)
private val CautionAmber = Color(0xFFE0B04F)
private val ReadyGreen = Color(0xFF61A66B)
private val PopBlue = Color(0xFF5CA8FF)
private val PopCoral = Color(0xFFFF7A64)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrainingTheme {
                TrainingApp(this)
            }
        }
    }
}

data class WorkoutSet(
    val id: Long,
    val date: String,
    val exercise: String,
    val bodyPart: String,
    val weight: Double,
    val reps: Int,
    val sets: Int,
    val memo: String,
    val rpe: Int = 7,
    val soreness: Int = 0,
    val jointPain: Int = 0,
    val sleep: String = "OK",
    val condition: String = "OK"
) {
    val volume: Double get() = weight * reps * sets
    val load: Double
        get() {
            val effort = if (rpe <= 0) 1.0 else 0.6 + (rpe.coerceIn(1, 10) / 10.0)
            val sleepPenalty = when (sleep) {
                "BAD", "悪い" -> 1.15
                "GOOD", "良い" -> 0.95
                else -> 1.0
            }
            val conditionPenalty = when (condition) {
                "BAD", "悪い" -> 1.15
                "GOOD", "良い" -> 0.95
                else -> 1.0
            }
            val symptomPenalty = 1.0 + soreness.coerceIn(0, 5) * 0.04 + jointPain.coerceIn(0, 5) * 0.08
            return volume * effort * sleepPenalty * conditionPenalty * symptomPenalty
        }
}

private data class FatigueStatus(
    val bodyPart: String,
    val score: Int,
    val level: FatigueLevel,
    val daysSince: Int?,
    val recentLoad: Double,
    val advice: String
)

private data class WeeklyLoad(
    val acute: Double,
    val chronicWeekly: Double,
    val ratio: Double,
    val monotony: Double,
    val weekBefore: Double,
    val twoWeeksBefore: Double
)

private data class VolumePoint(
    val label: String,
    val total: Double,
    val sortKey: Long
)

private enum class FatigueLevel(val label: String, val color: Color) {
    Ready("攻めてOK", ReadyGreen),
    Caution("軽め", CautionAmber),
    Stop("避ける", WarningRed)
}

private enum class TrainingTab(val label: String, val icon: ActionIcon) {
    Log("記録", ActionIcon.Barbell),
    Volume("グラフ", ActionIcon.Chart),
    History("履歴", ActionIcon.Calendar),
    Timer("タイマー", ActionIcon.Timer),
    Coach("コーチ", ActionIcon.Chat)
}

@Composable
private fun TrainingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = SteelBlack,
            onBackground = AshText,
            surface = PanelBlack,
            onSurface = AshText,
            surfaceVariant = FieldGray,
            onSurfaceVariant = SubText,
            surfaceContainerLowest = SteelBlack,
            surfaceContainerLow = PanelBlack,
            surfaceContainer = PanelBlack,
            surfaceContainerHigh = FieldGray,
            surfaceContainerHighest = FieldGray,
            primary = IronGold,
            onPrimary = OnGold,
            primaryContainer = Color(0xFF3A2F1A),
            onPrimaryContainer = Color(0xFFF3DDB2),
            secondary = SubText,
            onSecondary = SteelBlack,
            secondaryContainer = GoldTint,
            onSecondaryContainer = IronGold,
            outline = IronGray,
            outlineVariant = IronGray,
            error = WarningRed
        ),
        content = content
    )
}

@Composable
private fun TrainingApp(context: Context) {
    val coachController = rememberCoachController(context)
    val workoutTimer = rememberWorkoutTimer(onFinished = { vibrateRestDone(context) })
    var selectedTabName by rememberSaveable { mutableStateOf(TrainingTab.Log.name) }
    val selectedTab = runCatching { TrainingTab.valueOf(selectedTabName) }.getOrDefault(TrainingTab.Log)
    var entries by remember { mutableStateOf(loadWorkoutSets(context)) }
    var customExercises by remember { mutableStateOf(loadCustomExercises(context)) }
    var hiddenExercises by remember { mutableStateOf(loadHiddenExercises(context)) }
    val exerciseOptions = remember(customExercises, hiddenExercises) {
        (exercisePresets() + customExercises)
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { it in hiddenExercises }
    }
    val volumeExerciseOptions = remember(entries, exerciseOptions) {
        (entries.map { it.exercise } + exerciseOptions)
            .filter { it.isNotBlank() }
            .distinct()
    }
    var selectedDate by rememberSaveable { mutableStateOf(todayText()) }

    fun updateEntries(next: List<WorkoutSet>) {
        saveWorkoutChanges(context, entries, next)
        entries = next.sortedByDescending { it.id }
    }

    fun addCustomExercise(name: String) {
        val cleaned = name.trim()
        if (cleaned.isBlank() || cleaned in exerciseOptions) return
        val nextHidden = hiddenExercises.filterNot { it == cleaned }
        val nextCustom = (customExercises + cleaned).distinct()
        hiddenExercises = nextHidden
        customExercises = nextCustom
        saveHiddenExercises(context, nextHidden)
        saveCustomExercises(context, nextCustom)
    }

    fun deleteExercise(name: String) {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return
        val nextCustom = customExercises.filterNot { it == cleaned }
        val nextHidden = (hiddenExercises + cleaned).distinct()
        customExercises = nextCustom
        hiddenExercises = nextHidden
        saveCustomExercises(context, nextCustom)
        saveHiddenExercises(context, nextHidden)
    }

    Scaffold(
        containerColor = SteelBlack,
        bottomBar = {
            Column {
                NavigationBar(containerColor = Color(0xFF0A0B0D), tonalElevation = 0.dp) {
                    TrainingTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTabName = tab.name },
                            icon = {
                                IconGlyph(
                                    kind = tab.icon,
                                    color = if (selected) IronGold else MutedText,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text(tab.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = IronGold,
                                unselectedTextColor = MutedText,
                                indicatorColor = GoldTint
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SteelBlack)
                .padding(padding)
        ) {
            when (selectedTab) {
                TrainingTab.Log -> LogScreen(
                    entries = entries,
                    exerciseOptions = exerciseOptions,
                    selectedDate = selectedDate,
                    onSelectedDateChange = { selectedDate = it },
                    onAdd = { updateEntries(listOf(it) + entries) },
                    onUpdate = { edited -> updateEntries(entries.map { if (it.id == edited.id) edited else it }) },
                    onDelete = { target -> updateEntries(entries.filterNot { it.id == target.id }) },
                    onAddExercise = ::addCustomExercise,
                    onDeleteExercise = ::deleteExercise
                )
                TrainingTab.Volume -> VolumeScreen(entries, volumeExerciseOptions)
                TrainingTab.History -> HistoryScreen(entries)
                TrainingTab.Timer -> WorkoutTimerScreen(workoutTimer)
                TrainingTab.Coach -> CoachScreen(coachController, entries, selectedDate, exerciseOptions)
            }
        }
    }
}

@Composable
private fun LogScreen(
    entries: List<WorkoutSet>,
    exerciseOptions: List<String>,
    selectedDate: String,
    onSelectedDateChange: (String) -> Unit,
    onAdd: (WorkoutSet) -> Unit,
    onUpdate: (WorkoutSet) -> Unit,
    onDelete: (WorkoutSet) -> Unit,
    onAddExercise: (String) -> Unit,
    onDeleteExercise: (String) -> Unit
) {
    var editing by remember(selectedDate) { mutableStateOf<WorkoutSet?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkoutSet?>(null) }
    var calendarOpen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val dayEntries = remember(entries, selectedDate) {
        entries.filter { it.date == selectedDate }.sortedBy { it.id }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LogHeader(
            selectedDate = selectedDate,
            onOpenCalendar = { calendarOpen = true },
            onToday = { onSelectedDateChange(todayText()) }
        )
        WeekStrip(selectedDate = selectedDate, entries = entries, onSelect = onSelectedDateChange)
        SetEntryCard(
            date = selectedDate,
            entries = entries,
            dayEntries = dayEntries,
            exerciseOptions = exerciseOptions,
            editing = editing,
            onAddExercise = onAddExercise,
            onDeleteExercise = onDeleteExercise,
            onCancelEdit = { editing = null },
            onSave = { saved ->
                if (editing == null) onAdd(saved) else onUpdate(saved)
                editing = null
            }
        )
        DayLog(
            dayEntries = dayEntries,
            editingId = editing?.id,
            onEdit = {
                editing = it
                scope.launch { scrollState.animateScrollTo(0) }
            },
            onDelete = { pendingDelete = it }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("このセットを削除しますか？") },
            text = { Text("${target.exercise}　${setLabel(target)}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
                        if (editing?.id == target.id) editing = null
                        pendingDelete = null
                    }
                ) {
                    Text("削除", color = WarningRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
            containerColor = PanelBlack,
            titleContentColor = AshText,
            textContentColor = SubText
        )
    }
    if (calendarOpen) {
        MonthCalendarDialog(
            entries = entries,
            selectedDate = selectedDate,
            onSelect = onSelectedDateChange,
            onDismiss = { calendarOpen = false }
        )
    }
}

@Composable
private fun LogHeader(selectedDate: String, onOpenCalendar: () -> Unit, onToday: () -> Unit) {
    val isToday = selectedDate == todayText()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(dateHeadline(selectedDate), color = MutedText, fontSize = 13.sp)
            Text(
                if (isToday) "今日の記録" else "この日の記録",
                color = AshText,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (!isToday) {
            TextButton(onClick = onToday) { Text("今日へ") }
        }
        ActionIconButton(kind = ActionIcon.Calendar, onClick = onOpenCalendar, tone = SubText)
    }
}

@Composable
private fun WeekStrip(selectedDate: String, entries: List<WorkoutSet>, onSelect: (String) -> Unit) {
    val days = remember(selectedDate) { weekDates(selectedDate) }
    val trainedDates = remember(entries) { entries.map { it.date }.toSet() }
    val today = todayText()
    var dragTotal by remember { mutableStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(selectedDate) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        when {
                            dragTotal < -60f -> onSelect(shiftDateText(selectedDate, 7))
                            dragTotal > 60f -> onSelect(shiftDateText(selectedDate, -7))
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f }
                ) { change, amount ->
                    change.consume()
                    dragTotal += amount
                }
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        days.forEachIndexed { index, date ->
            val selected = date == selectedDate
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) IronGold else Color.Transparent)
                    .clickable { onSelect(date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    WeekdayLabels[index],
                    fontSize = 11.sp,
                    color = if (selected) OnGold else weekdayColor(index)
                )
                Text(
                    date.substringAfterLast("."),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        selected -> OnGold
                        date == today -> IronGold
                        else -> AshText
                    }
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                date !in trainedDates -> Color.Transparent
                                selected -> OnGold
                                else -> IronGold
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun SetEntryCard(
    date: String,
    entries: List<WorkoutSet>,
    dayEntries: List<WorkoutSet>,
    exerciseOptions: List<String>,
    editing: WorkoutSet?,
    onAddExercise: (String) -> Unit,
    onDeleteExercise: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSave: (WorkoutSet) -> Unit
) {
    val dayLatest = remember(dayEntries) { dayEntries.maxByOrNull { it.id } }
    val fallbackExercise = exerciseOptions.firstOrNull() ?: "ベンチプレス"
    fun recentSet(name: String): WorkoutSet? = latestSetOf(entries, name, date)

    var exercise by remember(date, editing?.id) {
        mutableStateOf(editing?.exercise ?: dayLatest?.exercise?.takeIf { it in exerciseOptions } ?: fallbackExercise)
    }
    val template = remember(entries, exercise, date, editing) { editing ?: recentSet(exercise) }
    val conditionTemplate = editing ?: dayLatest
    var weight by remember(date, editing?.id) { mutableStateOf(template?.weight) }
    var reps by remember(date, editing?.id) { mutableStateOf(template?.reps) }
    var bodyPart by remember(date, editing?.id) { mutableStateOf(template?.bodyPart ?: exerciseBodyPart(exercise)) }
    var memo by remember(date, editing?.id) { mutableStateOf(editing?.memo ?: "") }
    var rpe by remember(date, editing?.id) { mutableStateOf(conditionTemplate?.rpe ?: 7) }
    var soreness by remember(date, editing?.id) { mutableStateOf(conditionTemplate?.soreness ?: 0) }
    var jointPain by remember(date, editing?.id) { mutableStateOf(conditionTemplate?.jointPain ?: 0) }
    var sleep by remember(date, editing?.id) { mutableStateOf(normalizeConditionLabel(conditionTemplate?.sleep ?: "OK")) }
    var condition by remember(date, editing?.id) { mutableStateOf(normalizeConditionLabel(conditionTemplate?.condition ?: "OK")) }
    var detailsOpen by remember(editing?.id) { mutableStateOf(false) }
    val previous = remember(entries, exercise, date) { previousSession(entries, exercise, date) }
    val readyToSave = weight != null && (reps ?: 0) > 0

    fun selectExercise(name: String) {
        exercise = name
        val recent = recentSet(name)
        bodyPart = recent?.bodyPart ?: exerciseBodyPart(name)
        // While editing, keep the numbers: changing the name is usually a correction.
        if (editing == null) {
            weight = recent?.weight
            reps = recent?.reps
        }
    }

    SectionPanel {
        if (editing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "セットを編集中",
                    color = IronGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancelEdit) { Text("キャンセル", color = SubText) }
            }
        }
        ExercisePicker(
            selected = exercise,
            options = exerciseOptions,
            onSelect = ::selectExercise,
            onAddExercise = { name ->
                onAddExercise(name)
                selectExercise(name)
            },
            onDeleteExercise = { name ->
                onDeleteExercise(name)
                if (exercise == name) selectExercise(exerciseOptions.firstOrNull { it != name } ?: "ベンチプレス")
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StepperField(
                label = "重量",
                value = weight?.clean() ?: "--",
                unit = "kg",
                initialValue = weight?.clean().orEmpty(),
                allowDecimal = true,
                modifier = Modifier.weight(1f),
                onMinus = { weight = ((weight ?: 0.0) - 2.5).coerceAtLeast(0.0) },
                onPlus = { weight = (weight ?: 0.0) + 2.5 },
                onInput = { text -> text.toDoubleOrNull()?.let { weight = it } }
            )
            StepperField(
                label = "回数",
                value = reps?.toString() ?: "--",
                unit = "回",
                initialValue = reps?.toString().orEmpty(),
                allowDecimal = false,
                modifier = Modifier.weight(1f),
                onMinus = { reps = ((reps ?: 1) - 1).coerceAtLeast(1) },
                onPlus = { reps = (reps ?: 0) + 1 },
                onInput = { text -> text.toIntOrNull()?.takeIf { it > 0 }?.let { reps = it } }
            )
        }
        previous?.let { (previousDate, sets) ->
            Text(
                "前回 ${compactDateLabel(previousDate)}　" + sets.joinToString("　") { setLabel(it) },
                color = MutedText,
                fontSize = 13.sp
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { detailsOpen = !detailsOpen }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("詳細", color = SubText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                "RPE ${rpeLabel(rpe)} ・ 体調 ${conditionDisplay(condition)} ・ 睡眠 ${conditionDisplay(sleep)}" +
                    if (soreness + jointPain > 0) " ・ 痛みあり" else "",
                color = MutedText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            IconGlyph(
                ActionIcon.Chevron,
                color = MutedText,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (detailsOpen) 180f else 0f)
            )
        }
        if (detailsOpen) {
            CompactOptionPicker("部位", bodyPart, bodyParts()) { bodyPart = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CompactOptionPicker("RPE", rpeLabel(rpe), listOf("-", "6", "7", "8", "9", "10"), Modifier.weight(1f)) {
                    rpe = it.toIntOrNull() ?: 0
                }
                CompactOptionPicker("筋肉痛", soreness.toString(), ZeroToFive, Modifier.weight(1f)) {
                    soreness = it.toIntOrNull() ?: 0
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CompactOptionPicker("関節痛", jointPain.toString(), ZeroToFive, Modifier.weight(1f)) {
                    jointPain = it.toIntOrNull() ?: 0
                }
                CompactOptionPicker("体調", conditionDisplay(condition), ConditionDisplayOptions, Modifier.weight(1f)) {
                    condition = normalizeConditionLabel(it)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CompactOptionPicker("睡眠", conditionDisplay(sleep), ConditionDisplayOptions, Modifier.weight(1f)) {
                    sleep = normalizeConditionLabel(it)
                }
                Spacer(Modifier.weight(1f))
            }
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("メモ") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            enabled = readyToSave,
            onClick = {
                val savedWeight = weight ?: return@Button
                val savedReps = reps ?: return@Button
                onSave(
                    WorkoutSet(
                        id = editing?.id ?: System.currentTimeMillis(),
                        date = date,
                        exercise = exercise,
                        bodyPart = bodyPart,
                        weight = savedWeight,
                        reps = savedReps,
                        sets = editing?.sets ?: 1,
                        memo = memo,
                        rpe = rpe,
                        soreness = soreness,
                        jointPain = jointPain,
                        sleep = sleep,
                        condition = condition
                    )
                )
                if (editing == null) memo = ""
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = IronGold,
                contentColor = OnGold,
                disabledContainerColor = FieldGray,
                disabledContentColor = MutedText
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                if (editing == null) "セットを記録" else "変更を保存",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DayLog(
    dayEntries: List<WorkoutSet>,
    editingId: Long?,
    onEdit: (WorkoutSet) -> Unit,
    onDelete: (WorkoutSet) -> Unit
) {
    if (dayEntries.isEmpty()) {
        Text(
            "まだ記録がありません。重量と回数を決めて「セットを記録」を押してください。",
            color = MutedText,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        return
    }
    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("この日の記録", color = AshText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(
            "${dayEntries.sumOf { it.sets }}セット ・ ${dayEntries.sumOf { it.volume }.toInt()} kg",
            color = MutedText,
            fontSize = 13.sp
        )
    }
    dayEntries.groupBy { it.exercise }.forEach { (name, sets) ->
        SectionPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = AshText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${sets.sumOf { it.sets }}セット", color = MutedText, fontSize = 12.sp)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sets.forEach { set ->
                    val active = set.id == editingId
                    Text(
                        setLabel(set),
                        color = if (active) OnGold else AshText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) IronGold else FieldGray)
                            .combinedClickable(onClick = { onEdit(set) }, onLongClick = { onDelete(set) })
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            sets.filter { it.memo.isNotBlank() }.forEach {
                Text("${setLabel(it)}：${it.memo}", color = MutedText, fontSize = 12.sp)
            }
        }
    }
    Text(
        "タップで編集 ・ 長押しで削除",
        color = MutedText,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StepperField(
    label: String,
    value: String,
    unit: String,
    initialValue: String,
    allowDecimal: Boolean,
    modifier: Modifier = Modifier,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onInput: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(FieldGray)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = MutedText, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RoundStepButton("−", "${label}を減らす", onMinus)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "${label}を入力" }
                    .clickable { open = true }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(value, color = AshText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(unit, color = MutedText, fontSize = 11.sp)
            }
            RoundStepButton("+", "${label}を増やす", onPlus)
        }
    }
    if (open) {
        NumberPadDialog(
            title = label,
            initialValue = initialValue,
            allowDecimal = allowDecimal,
            onDismiss = { open = false },
            onDone = {
                onInput(it)
                open = false
            }
        )
    }
}

@Composable
private fun RoundStepButton(symbol: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(IronGray)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = AshText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MonthCalendarDialog(
    entries: List<WorkoutSet>,
    selectedDate: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var monthOffset by remember { mutableStateOf(monthOffsetOf(selectedDate)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { monthOffset -= 1 }) { Text("‹", fontSize = 22.sp, color = SubText) }
                    Text(
                        monthTitle(monthOffset),
                        color = AshText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { monthOffset += 1 }) { Text("›", fontSize = 22.sp, color = SubText) }
                }
                CalendarGrid(
                    cells = monthCells(monthOffset),
                    entries = entries,
                    selectedDate = selectedDate,
                    onSelect = {
                        onSelect(it)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
        containerColor = PanelBlack,
        textContentColor = AshText
    )
}

@Composable
private fun CalendarGrid(
    cells: List<String?>,
    entries: List<WorkoutSet>,
    selectedDate: String,
    onSelect: (String) -> Unit
) {
    val trainedDates = remember(entries) { entries.map { it.date }.toSet() }
    val today = todayText()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            WeekdayLabels.forEachIndexed { index, label ->
                Text(
                    label,
                    color = weekdayColor(index),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                week.forEach { date ->
                    val selected = date == selectedDate
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) IronGold else Color.Transparent)
                            .clickable(enabled = date != null) { date?.let(onSelect) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (date != null) {
                            Text(
                                date.substringAfterLast("."),
                                color = when {
                                    selected -> OnGold
                                    date == today -> IronGold
                                    else -> AshText
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Box(
                                Modifier
                                    .padding(top = 3.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            date !in trainedDates -> Color.Transparent
                                            selected -> OnGold
                                            else -> IronGold
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val ManageExercisesLabel = "＋ 種目を追加・削除"

@Composable
private fun ExercisePicker(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onAddExercise: ((String) -> Unit)? = null,
    onDeleteExercise: ((String) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var newExercise by remember { mutableStateOf("") }
    val menuOptions = remember(options, onAddExercise) {
        if (onAddExercise != null) options + ManageExercisesLabel else options
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(FieldGray)
                .semantics { contentDescription = "種目を選択" }
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected, color = AshText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconGlyph(ActionIcon.Chevron, color = SubText, modifier = Modifier.size(16.dp))
        }
        ScrollableDropdownMenu(
            expanded = expanded,
            selected = selected,
            options = menuOptions,
            onDismiss = { expanded = false },
            onSelect = {
                expanded = false
                if (it == ManageExercisesLabel && onAddExercise != null) {
                    managing = true
                } else {
                    onSelect(it)
                }
            }
        )
    }
    if (managing) {
        AlertDialog(
            onDismissRequest = { managing = false },
            title = { Text("種目を追加・削除") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newExercise,
                            onValueChange = { newExercise = it },
                            label = { Text("新しい種目名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        ActionIconButton(
                            kind = ActionIcon.Add,
                            onClick = {
                                val cleaned = newExercise.trim()
                                if (cleaned.isNotBlank()) {
                                    onAddExercise?.invoke(cleaned)
                                    newExercise = ""
                                }
                            }
                        )
                    }
                    HorizontalDivider(color = IronGray)
                    Column(
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        options.forEach { option ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(option, color = AshText, modifier = Modifier.weight(1f))
                                ActionIconButton(
                                    kind = ActionIcon.Delete,
                                    tone = PopCoral,
                                    onClick = {
                                        managing = false
                                        pendingDelete = option
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { managing = false }) { Text("完了") }
            },
            containerColor = PanelBlack,
            titleContentColor = AshText,
            textContentColor = AshText
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("種目を削除しますか？") },
            text = { Text("$target をリストから外します。過去の記録は残ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteExercise?.invoke(target)
                        pendingDelete = null
                    }
                ) {
                    Text("削除", color = WarningRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
            containerColor = PanelBlack,
            titleContentColor = AshText,
            textContentColor = SubText
        )
    }
}

@Composable
private fun CompactOptionPicker(
    label: String,
    selected: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(FieldGray)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = MutedText, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(selected, color = AshText, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            IconGlyph(ActionIcon.Chevron, color = MutedText, modifier = Modifier.size(12.dp))
        }
        TrackingDropdownMenu(
            expanded = expanded,
            selected = selected,
            options = options,
            onDismiss = { expanded = false },
            onSelect = {
                onSelect(it)
                expanded = false
            }
        )
    }
}

@Composable
private fun ScrollableDropdownMenu(
    expanded: Boolean,
    selected: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    if (!expanded) return
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
        ) {
            options.forEach { option ->
                DropdownRow(option, active = option == selected, onClick = { onSelect(option) })
            }
        }
    }
}

@Composable
private fun TrackingDropdownMenu(
    expanded: Boolean,
    selected: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    if (!expanded) return
    var trackingOption by remember(expanded) { mutableStateOf<String?>(null) }
    val itemHeight = 44.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(200.dp)
                .pointerInput(options, expanded) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun optionAt(y: Float): String? {
                            val index = (y / itemHeightPx).toInt().coerceIn(0, options.lastIndex)
                            return options.getOrNull(index)
                        }
                        trackingOption = optionAt(down.position.y)
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            trackingOption = optionAt(change.position.y)
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            change.consume()
                        }
                        if (released) trackingOption?.let(onSelect)
                        trackingOption = null
                    }
                }
        ) {
            options.forEach { option ->
                DropdownRow(
                    option,
                    active = option == (trackingOption ?: selected),
                    onClick = { onSelect(option) },
                    height = itemHeight
                )
            }
        }
    }
}

@Composable
private fun DropdownRow(option: String, active: Boolean, onClick: () -> Unit, height: androidx.compose.ui.unit.Dp = 44.dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(if (active) GoldTint else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            option,
            color = if (active) IronGold else AshText,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NumberPadDialog(
    title: String,
    initialValue: String,
    allowDecimal: Boolean,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    var input by remember(initialValue) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    input.ifBlank { initialValue.ifBlank { "0" } },
                    color = if (input.isBlank()) MutedText else IronGold,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(FieldGray)
                        .padding(14.dp)
                )
                listOf(
                    listOf("7", "8", "9"),
                    listOf("4", "5", "6"),
                    listOf("1", "2", "3"),
                    if (allowDecimal) listOf(".", "0", "⌫") else listOf("", "0", "⌫")
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (key.isBlank()) Color.Transparent else FieldGray)
                                    .clickable(enabled = key.isNotBlank()) {
                                        input = when (key) {
                                            "⌫" -> input.dropLast(1)
                                            "." -> if (input.contains(".")) input else input.ifBlank { "0" } + "."
                                            else -> if (input == "0") key else input + key
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(key, color = AshText, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // An untouched pad keeps the current value instead of writing 0.
            TextButton(onClick = { if (input.isBlank()) onDismiss() else onDone(input) }) { Text("決定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
        containerColor = PanelBlack,
        titleContentColor = AshText,
        textContentColor = AshText
    )
}

@Composable
private fun SectionPanel(modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = PanelBlack,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun StatPanel(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = PanelBlack,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = MutedText, fontSize = 12.sp)
            Text(value, color = AshText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private enum class ActionIcon {
    Add,
    Edit,
    Delete,
    Chevron,
    Calendar,
    Barbell,
    Chart,
    Heart,
    Timer,
    Chat
}

@Composable
private fun ActionIconButton(
    kind: ActionIcon,
    onClick: () -> Unit,
    tone: Color = IronGold
) {
    val description = when (kind) {
        ActionIcon.Calendar -> "カレンダー"
        ActionIcon.Add -> "種目を追加"
        ActionIcon.Delete -> "種目を削除"
        else -> kind.name
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FieldGray)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        IconGlyph(kind = kind, color = tone, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun IconGlyph(kind: ActionIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.09f).coerceAtLeast(2.5f)
        val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            ActionIcon.Add -> {
                drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.78f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), stroke, StrokeCap.Round)
            }
            ActionIcon.Edit -> {
                drawLine(color, Offset(w * 0.25f, h * 0.75f), Offset(w * 0.72f, h * 0.28f), stroke * 1.6f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.18f, h * 0.84f), Offset(w * 0.3f, h * 0.8f), stroke, StrokeCap.Round)
            }
            ActionIcon.Delete -> {
                drawRoundRect(color, Offset(w * 0.28f, h * 0.34f), Size(w * 0.44f, h * 0.48f), CornerRadius(w * 0.06f), style = line)
                drawLine(color, Offset(w * 0.2f, h * 0.26f), Offset(w * 0.8f, h * 0.26f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.42f, h * 0.16f), Offset(w * 0.58f, h * 0.16f), stroke, StrokeCap.Round)
            }
            ActionIcon.Chevron -> {
                drawLine(color, Offset(w * 0.25f, h * 0.38f), Offset(w * 0.50f, h * 0.62f), stroke * 1.3f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.75f, h * 0.38f), Offset(w * 0.50f, h * 0.62f), stroke * 1.3f, StrokeCap.Round)
            }
            ActionIcon.Calendar -> {
                drawRoundRect(color, Offset(w * 0.15f, h * 0.22f), Size(w * 0.7f, h * 0.64f), CornerRadius(w * 0.1f), style = line)
                drawLine(color, Offset(w * 0.15f, h * 0.42f), Offset(w * 0.85f, h * 0.42f), stroke)
                drawLine(color, Offset(w * 0.35f, h * 0.12f), Offset(w * 0.35f, h * 0.3f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.65f, h * 0.12f), Offset(w * 0.65f, h * 0.3f), stroke, StrokeCap.Round)
            }
            ActionIcon.Barbell -> {
                drawLine(color, Offset(w * 0.06f, h * 0.5f), Offset(w * 0.94f, h * 0.5f), stroke, StrokeCap.Round)
                drawRoundRect(color, Offset(w * 0.2f, h * 0.26f), Size(w * 0.1f, h * 0.48f), CornerRadius(w * 0.03f))
                drawRoundRect(color, Offset(w * 0.7f, h * 0.26f), Size(w * 0.1f, h * 0.48f), CornerRadius(w * 0.03f))
                drawRoundRect(color, Offset(w * 0.11f, h * 0.36f), Size(w * 0.07f, h * 0.28f), CornerRadius(w * 0.03f))
                drawRoundRect(color, Offset(w * 0.82f, h * 0.36f), Size(w * 0.07f, h * 0.28f), CornerRadius(w * 0.03f))
            }
            ActionIcon.Chart -> {
                val bar = w * 0.13f
                drawLine(color, Offset(w * 0.22f, h * 0.84f), Offset(w * 0.22f, h * 0.56f), bar, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.84f), Offset(w * 0.5f, h * 0.2f), bar, StrokeCap.Round)
                drawLine(color, Offset(w * 0.78f, h * 0.84f), Offset(w * 0.78f, h * 0.42f), bar, StrokeCap.Round)
            }
            ActionIcon.Heart -> {
                val heart = Path().apply {
                    moveTo(w * 0.5f, h * 0.84f)
                    cubicTo(w * 0.1f, h * 0.56f, w * 0.04f, h * 0.3f, w * 0.26f, h * 0.2f)
                    cubicTo(w * 0.38f, h * 0.15f, w * 0.47f, h * 0.22f, w * 0.5f, h * 0.32f)
                    cubicTo(w * 0.53f, h * 0.22f, w * 0.62f, h * 0.15f, w * 0.74f, h * 0.2f)
                    cubicTo(w * 0.96f, h * 0.3f, w * 0.9f, h * 0.56f, w * 0.5f, h * 0.84f)
                    close()
                }
                drawPath(heart, color, style = line)
            }
            ActionIcon.Timer -> {
                drawCircle(color, radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.56f), style = line)
                drawLine(color, Offset(w * 0.38f, h * 0.09f), Offset(w * 0.62f, h * 0.09f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.09f), Offset(w * 0.5f, h * 0.22f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.56f), Offset(w * 0.5f, h * 0.35f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.56f), Offset(w * 0.65f, h * 0.63f), stroke, StrokeCap.Round)
            }
            ActionIcon.Chat -> {
                val bubble = Path().apply {
                    moveTo(w * 0.22f, h * 0.18f)
                    lineTo(w * 0.78f, h * 0.18f)
                    quadraticTo(w * 0.9f, h * 0.18f, w * 0.9f, h * 0.3f)
                    lineTo(w * 0.9f, h * 0.58f)
                    quadraticTo(w * 0.9f, h * 0.7f, w * 0.78f, h * 0.7f)
                    lineTo(w * 0.44f, h * 0.7f)
                    lineTo(w * 0.26f, h * 0.86f)
                    lineTo(w * 0.28f, h * 0.7f)
                    lineTo(w * 0.22f, h * 0.7f)
                    quadraticTo(w * 0.1f, h * 0.7f, w * 0.1f, h * 0.58f)
                    lineTo(w * 0.1f, h * 0.3f)
                    quadraticTo(w * 0.1f, h * 0.18f, w * 0.22f, h * 0.18f)
                    close()
                }
                drawPath(bubble, color, style = line)
            }
        }
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            Text(
                option,
                color = if (active) OnGold else SubText,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) IronGold else FieldGray)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

private val WeekdayLabels = listOf("日", "月", "火", "水", "木", "金", "土")
private val ZeroToFive = listOf("0", "1", "2", "3", "4", "5")
private val ConditionDisplayOptions = listOf("良い", "普通", "悪い")

private fun weekdayColor(index: Int): Color = when (index) {
    0 -> PopCoral
    6 -> PopBlue
    else -> MutedText
}

private fun conditionDisplay(value: String): String = when (normalizeConditionLabel(value)) {
    "GOOD" -> "良い"
    "BAD" -> "悪い"
    else -> "普通"
}

private fun setLabel(entry: WorkoutSet): String {
    val base = if (entry.weight == 0.0) "自重×${entry.reps}" else "${entry.weight.clean()}×${entry.reps}"
    return if (entry.sets > 1) "$base ×${entry.sets}" else base
}

/** Latest set of [exercise] on or before [date]; used to prefill weight and reps. */
private fun latestSetOf(entries: List<WorkoutSet>, exercise: String, date: String): WorkoutSet? {
    val limit = dateSortKey(date)
    val dateKeys = mutableMapOf<String, Long>()
    fun key(entry: WorkoutSet) = dateKeys.getOrPut(entry.date) { dateSortKey(entry.date) }
    return entries
        .asSequence()
        .filter { it.exercise == exercise && key(it) <= limit }
        .maxWithOrNull(compareBy<WorkoutSet> { key(it) }.thenBy { it.id })
}

/** The most recent earlier day this exercise was done, with that day's sets in order. */
private fun previousSession(entries: List<WorkoutSet>, exercise: String, date: String): Pair<String, List<WorkoutSet>>? {
    val limit = dateSortKey(date)
    val dateKeys = mutableMapOf<String, Long>()
    fun key(entry: WorkoutSet) = dateKeys.getOrPut(entry.date) { dateSortKey(entry.date) }
    val previousDate = entries
        .asSequence()
        .filter { it.exercise == exercise && key(it) < limit }
        .maxByOrNull { key(it) }
        ?.date ?: return null
    return previousDate to entries.filter { it.exercise == exercise && it.date == previousDate }.sortedBy { it.id }
}

private fun vibrateRestDone(context: Context) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        val pattern = longArrayOf(0, 300, 150, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}

private fun dateHeadline(dateText: String): String {
    val parser = SimpleDateFormat("yyyy.M.d", Locale.JAPAN).apply { isLenient = false }
    val date = runCatching { parser.parse(dateText) }.getOrNull() ?: return dateText
    val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
    val pattern = if (year == Calendar.getInstance().get(Calendar.YEAR)) "M月d日（E）" else "yyyy年M月d日（E）"
    return SimpleDateFormat(pattern, Locale.JAPAN).format(date)
}

/** Sunday-to-Saturday dates of the week containing [dateText]. */
private fun weekDates(dateText: String): List<String> {
    val parser = SimpleDateFormat("yyyy.M.d", Locale.JAPAN).apply { isLenient = false }
    val date = runCatching { parser.parse(dateText) }.getOrNull() ?: Calendar.getInstance().time
    val calendar = Calendar.getInstance().apply {
        time = date
        add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
    }
    return List(7) {
        val text = parser.format(calendar.time)
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        text
    }
}

private fun monthOffsetOf(dateText: String): Int {
    val parser = SimpleDateFormat("yyyy.M.d", Locale.JAPAN).apply { isLenient = false }
    val date = runCatching { parser.parse(dateText) }.getOrNull() ?: return 0
    val target = Calendar.getInstance().apply { time = date }
    val now = Calendar.getInstance()
    return (target.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 12 + target.get(Calendar.MONTH) - now.get(Calendar.MONTH)
}

private fun monthTitle(offset: Int): String {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.add(Calendar.MONTH, offset)
    return SimpleDateFormat("yyyy年M月", Locale.JAPAN).format(calendar.time)
}

@Composable
private fun VolumeScreen(entries: List<WorkoutSet>, exerciseOptions: List<String>) {
    val bodyPartTotals = remember(entries) { entries.groupBy { it.bodyPart }
        .mapValues { it.value.sumOf { set -> set.volume } }
        .toList()
        .sortedByDescending { it.second } }
    val exerciseTotals = remember(entries) { entries.groupBy { it.exercise }
        .mapValues { it.value.sumOf { set -> set.volume } }
        .toList()
        .sortedByDescending { it.second } }
    val totalVolume = remember(entries) { entries.sumOf { it.volume } }
    val trainingDays = remember(entries) { entries.map { it.date }.toSet().size }
    var volumeMode by remember { mutableStateOf("全体") }
    var periodMode by remember { mutableStateOf("日") }
    var selectedBodyPart by remember(entries) { mutableStateOf(bodyPartTotals.firstOrNull()?.first ?: "胸") }
    var selectedExercise by remember(entries) { mutableStateOf(exerciseTotals.firstOrNull()?.first ?: "ベンチプレス") }
    val filteredEntries = remember(entries, volumeMode, selectedBodyPart, selectedExercise) { when (volumeMode) {
        "部位" -> entries.filter { it.bodyPart == selectedBodyPart }
        "種目" -> entries.filter { it.exercise == selectedExercise }
        else -> entries
    } }
    val chartTitle = when (volumeMode) {
        "部位" -> "${selectedBodyPart} の${periodMode}別ボリューム"
        "種目" -> "${selectedExercise} の${periodMode}別ボリューム"
        else -> "全体の${periodMode}別ボリューム"
    }
    val volumePoints = remember(filteredEntries, periodMode) { buildVolumeSeries(filteredEntries, periodMode) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPanel("総ボリューム", "${totalVolume.toInt()} kg", Modifier.weight(1f))
            StatPanel("トレーニング日数", "$trainingDays", Modifier.weight(1f))
        }
        SectionPanel {
            ChipRow(listOf("日", "週", "月"), periodMode) { periodMode = it }
            ChipRow(listOf("全体", "部位", "種目"), volumeMode) { volumeMode = it }
            when (volumeMode) {
                "部位" -> ChipRow(bodyPartTotals.map { it.first }.ifEmpty { bodyParts() }, selectedBodyPart) {
                    selectedBodyPart = it
                }
                "種目" -> ExercisePicker(
                    selected = selectedExercise,
                    options = exerciseTotals.map { it.first }.ifEmpty { exerciseOptions },
                    onSelect = { selectedExercise = it }
                )
            }
            Text(chartTitle, color = IronGold, fontWeight = FontWeight.SemiBold)
            PeriodVolumeChart(
                points = volumePoints,
                emptyText = "この条件の記録がありません"
            )
        }
    }
}

private data class HistoryDay(val date: String, val records: List<WorkoutSet>, val totalSets: Int)

@Composable
private fun HistoryScreen(entries: List<WorkoutSet>) {
    var filterMode by rememberSaveable { mutableStateOf("全種目") }
    var selectedExercise by rememberSaveable { mutableStateOf<String?>(null) }
    val exercises = remember(entries) { entries.map { it.exercise }.distinct().sorted() }
    val activeExercise = selectedExercise?.takeIf { it in exercises } ?: exercises.firstOrNull()
    val historyDays = remember(entries, filterMode, activeExercise) {
        val filtered = if (filterMode == "全種目") entries else entries.filter { it.exercise == activeExercise }
        filtered.groupBy { it.date }.entries
            .map { (date, records) -> dateSortKey(date) to HistoryDay(date, records.sortedBy { it.id }, records.sumOf { it.sets }) }
            .sortedByDescending { it.first }
            .map { it.second }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(filterMode, activeExercise) { if (historyDays.isNotEmpty()) listState.scrollToItem(0) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("トレーニング履歴", color = AshText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp))
        SectionPanel {
            ChipRow(listOf("全種目", "種目別"), filterMode) { filterMode = it }
            if (filterMode == "種目別" && activeExercise != null) {
                ExercisePicker(selected = activeExercise, options = exercises, onSelect = { selectedExercise = it })
            }
            Text("${historyDays.size}日分の記録 ・ 新しい日付から表示", color = MutedText, fontSize = 12.sp)
        }
        if (historyDays.isEmpty()) {
            Text("まだトレーニングの記録がありません。", color = MutedText, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("training-history-list"),
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                historyDays.forEach { day ->
                    item(key = "day:${day.date}", contentType = "date") {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp).testTag("history-day-${day.date}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(dateHeadline(day.date), color = IronGold, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${day.totalSets}セット", color = MutedText, fontSize = 12.sp)
                        }
                    }
                    items(day.records, key = { "record:${it.id}" }, contentType = { "record" }) { entry ->
                        HistoryRecordRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordRow(entry: WorkoutSet) {
    Surface(color = PanelBlack, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("history-record-${entry.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.exercise, color = AshText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val weight = if (entry.weight == 0.0) "自重" else "${entry.weight.clean()} kg"
            Text("$weight × ${entry.reps}回 × ${entry.sets}セット", color = AshText, fontSize = 15.sp)
            Text("RPE ${rpeLabel(entry.rpe)}", color = MutedText, fontSize = 12.sp)
            if (entry.memo.isNotBlank()) Text(entry.memo, color = SubText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PeriodVolumeChart(points: List<VolumePoint>, emptyText: String) {
    if (points.isEmpty()) {
        Text(emptyText, color = MutedText)
        return
    }
    val maxTotal = max(1.0, points.maxOfOrNull { it.total } ?: 1.0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        points.forEach { point ->
            val total = point.total
            val ratio = (total / maxTotal).toFloat().coerceIn(0.06f, 1f)
            Column(
                modifier = Modifier
                    .width(52.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (total > 0.0) {
                    Text(total.toInt().toString(), color = IronGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight((1f - ratio).coerceAtLeast(0.01f)))
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .weight(ratio)
                        .clip(RoundedCornerShape(7.dp))
                        .background(IronGold)
                )
                Text(point.label, color = MutedText, fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun FatigueRow(status: FatigueStatus) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.bodyPart, color = AshText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    status.daysSince?.let { "前回から${it}日 / 7日負荷 ${status.recentLoad.toInt()}" } ?: "記録なし",
                    color = MutedText,
                    fontSize = 12.sp
                )
            }
            Text(status.level.label, color = status.level.color, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
        ) {
            drawRoundRect(color = IronGray, cornerRadius = CornerRadius(10f, 10f), size = size)
            drawRoundRect(
                color = status.level.color,
                cornerRadius = CornerRadius(10f, 10f),
                size = Size(size.width * (status.score / 100f).coerceIn(0f, 1f), size.height)
            )
        }
        Text(status.advice, color = AshText, fontSize = 13.sp)
        HorizontalDivider(color = IronGray.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp))
    }
}

private fun buildFatigueStatuses(entries: List<WorkoutSet>): List<FatigueStatus> {
    val weekly = buildWeeklyLoad(entries)
    return bodyParts().map { part ->
        val partEntries = entries.filter { it.bodyPart == part }
        val recent = partEntries.filter { daysAgo(it.date) in 0..6 }
        val chronic = partEntries.filter { daysAgo(it.date) in 0..27 }
        val decayedLoad = partEntries.sumOf { entry ->
            val days = daysAgo(entry.date)
            if (days < 0 || days > 21) 0.0 else entry.load * 0.55.pow(days / 3.0)
        }
        val recentLoad = recent.sumOf { it.load }
        val chronicWeekly = max(1.0, chronic.sumOf { it.load } / 4.0)
        val daysSince = partEntries.mapNotNull { daysAgo(it.date).takeIf { days -> days >= 0 } }.minOrNull()
        val latest = partEntries.filter { daysAgo(it.date) in 0..5 }.maxByOrNull { it.id }
        val symptomScore = (latest?.soreness ?: 0) * 7 + (latest?.jointPain ?: 0) * 11
        val globalPressure = if (weekly.ratio > 1.5) 12 else if (weekly.ratio > 1.2) 6 else 0
        val score = ((decayedLoad / chronicWeekly) * 58 + symptomScore + globalPressure).toInt().coerceIn(0, 100)
        val level = when {
            (latest?.jointPain ?: 0) >= 3 || score >= 75 -> FatigueLevel.Stop
            score >= 50 || (daysSince != null && daysSince <= 1 && recentLoad > 0.0) -> FatigueLevel.Caution
            else -> FatigueLevel.Ready
        }
        FatigueStatus(
            bodyPart = part,
            score = score,
            level = level,
            daysSince = daysSince,
            recentLoad = recentLoad,
            advice = bodyPartAdvice(level, daysSince, latest)
        )
    }
}

private fun buildWeeklyLoad(entries: List<WorkoutSet>): WeeklyLoad {
    val acute = entries.filter { daysAgo(it.date) in 0..6 }.sumOf { it.load }
    val chronicWeekly = max(1.0, entries.filter { daysAgo(it.date) in 0..27 }.sumOf { it.load } / 4.0)
    val dailyLoads = (0..6).map { day -> entries.filter { daysAgo(it.date) == day }.sumOf { it.load } }
    val mean = dailyLoads.average().takeIf { !it.isNaN() } ?: 0.0
    val variance = dailyLoads.map { (it - mean) * (it - mean) }.average().takeIf { !it.isNaN() } ?: 0.0
    val sd = sqrt(variance)
    val monotony = if (sd < 1.0) 0.0 else mean / sd
    val weekBefore = entries.filter { daysAgo(it.date) in 7..13 }.sumOf { it.load }
    val twoWeeksBefore = entries.filter { daysAgo(it.date) in 14..20 }.sumOf { it.load }
    return WeeklyLoad(acute, chronicWeekly, acute / chronicWeekly, monotony, weekBefore, twoWeeksBefore)
}

private fun weeklyAdvice(weekly: WeeklyLoad): String {
    val lastTwoWereHigh = weekly.weekBefore > weekly.chronicWeekly * 1.2 &&
        weekly.twoWeeksBefore > weekly.chronicWeekly * 1.2
    return when {
        weekly.ratio >= 1.5 -> "今週は増えすぎです。全体ボリュームを30〜40%落として、重いセットは絞りましょう。"
        weekly.ratio >= 1.2 -> "今週はやや高めです。疲労がある部位は20〜30%軽くするのが良さそうです。"
        lastTwoWereHigh -> "先週と先々週が重めでした。今週は回復週として70%くらいに抑える判断が合います。"
        weekly.monotony >= 2.0 && weekly.acute > weekly.chronicWeekly -> "負荷が単調です。休みか軽い日を1日入れると回復しやすくなります。"
        weekly.ratio < 0.65 && weekly.acute > 0.0 -> "今週はかなり軽めです。調子が良ければ少し戻しても大丈夫です。"
        else -> "今週の負荷は普通です。部位別の疲労だけ見ながら進めましょう。"
    }
}

private fun weeklyAdviceColor(weekly: WeeklyLoad): Color {
    return when {
        weekly.ratio >= 1.5 -> WarningRed
        weekly.ratio >= 1.2 || weekly.monotony >= 2.0 -> CautionAmber
        else -> ReadyGreen
    }
}

private fun todayRecommendation(statuses: List<FatigueStatus>, weekly: WeeklyLoad): String {
    val stops = statuses.filter { it.level == FatigueLevel.Stop }
    val ready = statuses.filter { it.level == FatigueLevel.Ready && it.recentLoad > 0.0 }
    val fresh = statuses.filter { it.level == FatigueLevel.Ready && it.recentLoad <= 0.0 }
    val avoidText = if (stops.isNotEmpty()) {
        "避けたい部位: ${stops.joinToString("、") { it.bodyPart }}。"
    } else {
        "強く避けたい部位はありません。"
    }
    val target = (fresh + ready).firstOrNull()?.bodyPart ?: "全身を軽め"
    val volumeText = if (weekly.ratio >= 1.2) "今日は全体ボリュームを少し抑えめに。" else "フォームが崩れない範囲で通常通り。"
    return "$avoidText おすすめは $target。$volumeText 関節痛が3以上なら、その部位は重量を追わない判断にします。"
}

private fun bodyPartAdvice(level: FatigueLevel, daysSince: Int?, latest: WorkoutSet?): String {
    return when (level) {
        FatigueLevel.Stop -> {
            if ((latest?.jointPain ?: 0) >= 3) {
                "関節痛が強めです。今日はこの部位を避けるか、痛みのない軽い可動域だけにしましょう。"
            } else {
                "疲労が残っています。今日はこの部位を避けるか、セット数を半分くらいに落としましょう。"
            }
        }
        FatigueLevel.Caution -> {
            val dayText = daysSince?.let { "前回から${it}日です。" }.orEmpty()
            "$dayText 重く攻めるより、ボリュームを20〜30%減らす日が合いそうです。"
        }
        FatigueLevel.Ready -> "強いブレーキは不要です。ウォームアップで違和感がなければ通常通りで大丈夫です。"
    }
}

private fun bodyParts(): List<String> = listOf("胸", "背中", "脚", "肩", "腕", "腹", "その他")

private fun exercisePresets(): List<String> = listOf(
    "ベンチプレス",
    "インクラインDBプレス",
    "懸垂",
    "ローイング",
    "スクワット",
    "ブルガリアンスクワット",
    "ルーマニアンDL",
    "ダンベルルーマニアンDL",
    "ショルダープレス",
    "オーバーヘッドプレス",
    "サイドレイズ",
    "フェイスプル",
    "アームカール",
    "EZバーアームカール",
    "ハンマーカール",
    "トライセプス",
    "アブローラー"
)

private fun exerciseBodyPart(exercise: String): String {
    return when (exercise) {
        "ベンチプレス", "インクラインDBプレス", "ダンベルフライ" -> "胸"
        "ラットプルダウン", "懸垂", "ローイング", "ルーマニアンDL", "ダンベルルーマニアンDL" -> "背中"
        "スクワット", "ブルガリアンスクワット", "レッグプレス" -> "脚"
        "ショルダープレス", "オーバーヘッドプレス", "サイドレイズ", "フェイスプル" -> "肩"
        "アームカール", "EZバーアームカール", "ハンマーカール", "トライセプス" -> "腕"
        "クランチ", "アブローラー" -> "腹"
        else -> "その他"
    }
}

private fun normalizeConditionLabel(value: String): String {
    return when (value) {
        "良い" -> "GOOD"
        "悪い" -> "BAD"
        "普通" -> "OK"
        "GOOD", "BAD" -> value
        else -> "OK"
    }
}

private fun todayText(): String {
    val formatter = SimpleDateFormat("yyyy.M.d", Locale.JAPAN)
    return formatter.format(Calendar.getInstance().time)
}

private fun monthCells(offset: Int): List<String?> {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.add(Calendar.MONTH, offset)
    val formatter = SimpleDateFormat("yyyy.M.d", Locale.JAPAN)
    val blanks = calendar.get(Calendar.DAY_OF_WEEK) - 1
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = MutableList<String?>(blanks) { null }
    repeat(daysInMonth) { index ->
        calendar.set(Calendar.DAY_OF_MONTH, index + 1)
        cells += formatter.format(calendar.time)
    }
    while (cells.size % 7 != 0) cells += null
    return cells
}

private fun buildVolumeSeries(entries: List<WorkoutSet>, periodMode: String): List<VolumePoint> {
    val parser = SimpleDateFormat("yyyy.M.d", Locale.JAPAN)
    parser.isLenient = false
    return entries
        .groupBy { it.date }
        .mapNotNull { (dateText, dayEntries) ->
            val date = runCatching { parser.parse(dateText) }.getOrNull() ?: return@mapNotNull null
            val calendar = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = when (periodMode) {
                "月" -> calendar.apply { set(Calendar.DAY_OF_MONTH, 1) }
                "週" -> calendar.apply { add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY)) }
                else -> calendar
            }
            val key = start.timeInMillis
            val label = when (periodMode) {
                "月" -> "${start.get(Calendar.YEAR)}/${start.get(Calendar.MONTH) + 1}"
                "週" -> {
                    val end = Calendar.getInstance().apply {
                        timeInMillis = key
                        add(Calendar.DAY_OF_MONTH, 6)
                    }
                    "${start.get(Calendar.MONTH) + 1}/${start.get(Calendar.DAY_OF_MONTH)}-${end.get(Calendar.MONTH) + 1}/${end.get(Calendar.DAY_OF_MONTH)}"
                }
                else -> "${start.get(Calendar.MONTH) + 1}/${start.get(Calendar.DAY_OF_MONTH)}"
            }
            Triple(key, label, dayEntries.sumOf { it.volume })
        }
        .groupBy { it.first to it.second }
        .map { (period, rows) ->
            VolumePoint(
                label = period.second,
                total = rows.sumOf { it.third },
                sortKey = period.first
            )
        }
        .sortedBy { it.sortKey }
        .takeLast(18)
}

private fun compactDateLabel(dateText: String): String {
    val parts = dateText.split(".")
    return if (parts.size >= 3) "${parts[1]}/${parts[2]}" else dateText
}

private fun dateSortKey(dateText: String): Long {
    val parser = SimpleDateFormat("yyyy.M.d", Locale.JAPAN)
    parser.isLenient = false
    return runCatching { parser.parse(dateText)?.time ?: Long.MIN_VALUE }.getOrDefault(Long.MIN_VALUE)
}

private fun shiftDateText(dateText: String, days: Int): String {
    val parser = SimpleDateFormat("yyyy.M.d", Locale.JAPAN)
    parser.isLenient = false
    val formatter = SimpleDateFormat("yyyy.M.d", Locale.JAPAN)
    return runCatching {
        val date = parser.parse(dateText) ?: return@runCatching dateText
        val calendar = Calendar.getInstance().apply {
            time = date
            add(Calendar.DAY_OF_MONTH, days)
        }
        formatter.format(calendar.time)
    }.getOrDefault(dateText)
}

private fun daysAgo(dateText: String): Int {
    val parser = SimpleDateFormat("yyyy.M.d", Locale.JAPAN)
    parser.isLenient = false
    return runCatching {
        val date = parser.parse(dateText) ?: return@runCatching Int.MAX_VALUE
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        TimeUnit.MILLISECONDS.toDays(today.timeInMillis - target.timeInMillis).toInt()
    }.getOrDefault(Int.MAX_VALUE)
}

private fun Double.clean(): String {
    return if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(this)
}

private fun rpeLabel(rpe: Int): String = if (rpe <= 0) "-" else rpe.toString()

/** Commit only the changed records; an unrelated historical row is never rewritten. */
private fun saveWorkoutChanges(context: Context, previous: List<WorkoutSet>, next: List<WorkoutSet>) {
    val previousById = previous.associateBy { it.id }
    val nextIds = next.mapTo(mutableSetOf()) { it.id }
    val removedIds = previousById.keys - nextIds
    val changed = next.filter { previousById[it.id] != it }
    if (removedIds.isEmpty() && changed.isEmpty()) return
    TrainingDbHelper(context).use { helper ->
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            removedIds.forEach { id -> db.delete("workout_sets", "id = ?", arrayOf(id.toString())) }
            changed.forEach { check(db.insertWorkoutSet(it) != -1L) { "Workout record could not be saved" } }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

private const val LegacyPrefsName = "training_store"
private const val LegacyWorkoutSetsKey = "workout_sets"
private const val LegacyCustomExercisesKey = "custom_exercises"
private const val LegacyHiddenExercisesKey = "hidden_exercises"
private const val LegacySqliteMigratedKey = "sqlite_migrated_v1"

private class TrainingDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "training.db", null, 1) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS workout_sets (
                id INTEGER PRIMARY KEY,
                date TEXT NOT NULL,
                exercise TEXT NOT NULL,
                body_part TEXT NOT NULL,
                weight REAL NOT NULL,
                reps INTEGER NOT NULL,
                sets INTEGER NOT NULL,
                memo TEXT NOT NULL,
                rpe INTEGER NOT NULL,
                soreness INTEGER NOT NULL,
                joint_pain INTEGER NOT NULL,
                sleep TEXT NOT NULL,
                condition TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS custom_exercises (
                name TEXT PRIMARY KEY NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hidden_exercises (
                name TEXT PRIMARY KEY NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            migrateLegacyJsonIfNeeded(appContext, db)
        }
    }
}

private fun trainingDb(context: Context): SQLiteDatabase {
    return TrainingDbHelper(context).writableDatabase
}

private fun migrateLegacyJsonIfNeeded(context: Context, db: SQLiteDatabase) {
    val prefs = context.getSharedPreferences(LegacyPrefsName, Context.MODE_PRIVATE)
    if (prefs.getBoolean(LegacySqliteMigratedKey, false)) return
    if (hasAnySqliteRows(db)) {
        prefs.edit().putBoolean(LegacySqliteMigratedKey, true).apply()
        return
    }

    val workoutSets = parseLegacyWorkoutSets(prefs.getString(LegacyWorkoutSetsKey, "[]").orEmpty())
    val customExercises = parseLegacyStringArray(prefs.getString(LegacyCustomExercisesKey, "[]").orEmpty())
    val hiddenExercises = parseLegacyStringArray(prefs.getString(LegacyHiddenExercisesKey, "[]").orEmpty())

    db.beginTransaction()
    try {
        workoutSets.forEach { db.insertWorkoutSet(it) }
        customExercises.forEach { db.insertExerciseName("custom_exercises", it) }
        hiddenExercises.forEach { db.insertExerciseName("hidden_exercises", it) }
        db.setTransactionSuccessful()
        prefs.edit().putBoolean(LegacySqliteMigratedKey, true).apply()
    } finally {
        db.endTransaction()
    }
}

private fun hasAnySqliteRows(db: SQLiteDatabase): Boolean {
    return listOf("workout_sets", "custom_exercises", "hidden_exercises").any { table ->
        db.rawQuery("SELECT 1 FROM $table LIMIT 1", emptyArray()).use { cursor -> cursor.moveToFirst() }
    }
}

private fun parseLegacyStringArray(raw: String): List<String> {
    return runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index -> array.optString(index) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())
}

private fun parseLegacyWorkoutSets(raw: String): List<WorkoutSet> {
    return runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            WorkoutSet(
                id = item.optLong("id"),
                date = item.optString("date"),
                exercise = item.optString("exercise"),
                bodyPart = item.optString("bodyPart"),
                weight = item.optDouble("weight"),
                reps = item.optInt("reps"),
                sets = item.optInt("sets"),
                memo = item.optString("memo"),
                rpe = item.optInt("rpe", 7),
                soreness = item.optInt("soreness", 0),
                jointPain = item.optInt("jointPain", 0),
                sleep = normalizeConditionLabel(item.optString("sleep", "OK")),
                condition = normalizeConditionLabel(item.optString("condition", "OK"))
            )
        }
    }.getOrDefault(emptyList())
}

private fun SQLiteDatabase.insertWorkoutSet(entry: WorkoutSet): Long {
    val values = ContentValues().apply {
        put("id", entry.id)
        put("date", entry.date)
        put("exercise", entry.exercise)
        put("body_part", entry.bodyPart)
        put("weight", entry.weight)
        put("reps", entry.reps)
        put("sets", entry.sets)
        put("memo", entry.memo)
        put("rpe", entry.rpe)
        put("soreness", entry.soreness)
        put("joint_pain", entry.jointPain)
        put("sleep", entry.sleep)
        put("condition", entry.condition)
    }
    return insertWithOnConflict("workout_sets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
}

private fun SQLiteDatabase.insertExerciseName(table: String, name: String) {
    val cleaned = name.trim()
    if (cleaned.isBlank()) return
    val values = ContentValues().apply { put("name", cleaned) }
    insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
}

private fun loadCustomExercises(context: Context): List<String> {
    val db = trainingDb(context)
    return db.query("custom_exercises", arrayOf("name"), null, null, null, null, "name COLLATE NOCASE")
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
}

private fun saveCustomExercises(context: Context, exercises: List<String>) {
    val db = trainingDb(context)
    db.beginTransaction()
    try {
        db.delete("custom_exercises", null, null)
        exercises.distinct().forEach { db.insertExerciseName("custom_exercises", it) }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun loadHiddenExercises(context: Context): List<String> {
    val db = trainingDb(context)
    return db.query("hidden_exercises", arrayOf("name"), null, null, null, null, "name COLLATE NOCASE")
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
}

private fun saveHiddenExercises(context: Context, exercises: List<String>) {
    val db = trainingDb(context)
    db.beginTransaction()
    try {
        db.delete("hidden_exercises", null, null)
        exercises.distinct().forEach { db.insertExerciseName("hidden_exercises", it) }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun loadWorkoutSets(context: Context): List<WorkoutSet> {
    val db = trainingDb(context)
    return db.query("workout_sets", null, null, null, null, null, "id DESC")
        .use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val dateIndex = cursor.getColumnIndexOrThrow("date")
            val exerciseIndex = cursor.getColumnIndexOrThrow("exercise")
            val bodyPartIndex = cursor.getColumnIndexOrThrow("body_part")
            val weightIndex = cursor.getColumnIndexOrThrow("weight")
            val repsIndex = cursor.getColumnIndexOrThrow("reps")
            val setsIndex = cursor.getColumnIndexOrThrow("sets")
            val memoIndex = cursor.getColumnIndexOrThrow("memo")
            val rpeIndex = cursor.getColumnIndexOrThrow("rpe")
            val sorenessIndex = cursor.getColumnIndexOrThrow("soreness")
            val jointPainIndex = cursor.getColumnIndexOrThrow("joint_pain")
            val sleepIndex = cursor.getColumnIndexOrThrow("sleep")
            val conditionIndex = cursor.getColumnIndexOrThrow("condition")
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WorkoutSet(
                            id = cursor.getLong(idIndex),
                            date = cursor.getString(dateIndex),
                            exercise = cursor.getString(exerciseIndex),
                            bodyPart = cursor.getString(bodyPartIndex),
                            weight = cursor.getDouble(weightIndex),
                            reps = cursor.getInt(repsIndex),
                            sets = cursor.getInt(setsIndex),
                            memo = cursor.getString(memoIndex),
                            rpe = cursor.getInt(rpeIndex),
                            soreness = cursor.getInt(sorenessIndex),
                            jointPain = cursor.getInt(jointPainIndex),
                            sleep = normalizeConditionLabel(cursor.getString(sleepIndex)),
                            condition = normalizeConditionLabel(cursor.getString(conditionIndex))
                        )
                    )
                }
            }
        }
}

private fun saveWorkoutSets(context: Context, entries: List<WorkoutSet>) {
    val db = trainingDb(context)
    db.beginTransaction()
    try {
        db.delete("workout_sets", null, null)
        entries.forEach { db.insertWorkoutSet(it) }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}
