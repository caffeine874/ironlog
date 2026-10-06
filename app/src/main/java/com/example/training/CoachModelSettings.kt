package com.example.training

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class CoachPreferences(val model: String? = null, val effort: String? = null, val modelName: String? = null) {
    val label: String get() = "${modelName ?: model ?: "自動"} / ${coachEffortLabel(effort)}"
    fun applyTo(body: JSONObject): JSONObject = body.apply {
        model?.let { put("model", it) }
        effort?.let { put("effort", it) }
    }
}

internal data class CoachModelOption(val id: String, val displayName: String, val efforts: List<String>, val defaultEffort: String?)

internal data class CoachModelCatalog(val models: List<CoachModelOption>, val defaultModel: String?, val defaultEffort: String?) {
    fun selected(id: String?): CoachModelOption? = models.firstOrNull { it.id == (id ?: defaultModel) }
    fun supports(preferences: CoachPreferences): Boolean =
        (preferences.model == null || selected(preferences.model) != null) &&
            (preferences.effort == null || preferences.effort in selected(preferences.model)?.efforts.orEmpty())

    companion object {
        fun parse(json: JSONObject): CoachModelCatalog {
            val array = json.getJSONArray("models")
            val models = (0 until minOf(array.length(), 100)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() && it.length <= 200 } ?: return@mapNotNull null
                val efforts = item.optJSONArray("supportedReasoningEfforts")
                val supported = if (efforts == null) emptyList() else (0 until minOf(efforts.length(), 30))
                    .mapNotNull { efforts.optString(it).takeIf { value -> value.isNotBlank() && value.length <= 40 } }.distinct()
                CoachModelOption(id, item.optString("displayName").ifBlank { id }.take(200), supported,
                    item.optString("defaultReasoningEffort").takeIf { it in supported })
            }.distinctBy { it.id }
            check(models.isNotEmpty()) { "利用できるモデルが見つかりません。ログイン状態を確認してください。" }
            return CoachModelCatalog(models, json.optString("defaultModel").takeIf { it.isNotBlank() },
                json.optString("defaultReasoningEffort").takeIf { it.isNotBlank() })
        }
    }
}

internal fun coachEffortLabel(effort: String?): String = when (effort) {
    null -> "自動"
    "none" -> "推論なし (none)"
    "minimal" -> "最小 (minimal)"
    "low" -> "軽め (low)"
    "medium" -> "標準 (medium)"
    "high" -> "じっくり (high)"
    "xhigh" -> "より深く (xhigh)"
    "max" -> "最大 (max)"
    "ultra" -> "最高 (ultra)"
    else -> effort
}

@Composable
internal fun CoachModelDialog(controller: CoachController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var model by remember { mutableStateOf(controller.preferences.model) }
    var effort by remember { mutableStateOf(controller.preferences.effort) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    val catalog = controller.modelCatalog
    val selected = catalog?.selected(model)
    val busy = controller.busy
    val automaticLabel = "自動（ChatGPTの既定）"
    val modelName = if (model == null) automaticLabel else selected?.displayName ?: model.orEmpty()
    val choicesValid = if (catalog == null) (model == null && effort == null) ||
        (model == controller.preferences.model && effort == controller.preferences.effort)
        else catalog.supports(CoachPreferences(model, effort))
    val choicesAvailable = controller.ready && catalog != null && !busy
    LaunchedEffect(controller, controller.ready, controller.signingIn) {
        if (!controller.signingIn) controller.refreshModels()
    }
    AlertDialog(onDismissRequest = { if (!controller.saving) onDismiss() }, title = { Text("モデル・推論") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!controller.ready) {
                    Text("モデルを選ぶには、ChatGPTにログインしてください。ログイン後に、このアカウントで使えるモデルを読み込みます。")
                    if (controller.signingIn) {
                        Text("ブラウザでログインしたら、IronLogに戻ってください。")
                        TextButton(onClick = { controller.cancelSignIn() }) { Text("ログインをキャンセル") }
                    } else {
                        Button(onClick = { controller.signIn { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            enabled = !busy) { Text("Continue with ChatGPT") }
                    }
                    controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else if (catalog == null && !controller.modelsLoading) {
                    Text("モデル一覧を取得できていません。「一覧を更新」で読み込み直してください。")
                } else {
                    Text("一度選ぶと、次回からも同じ設定で使えます。")
                }
                if (controller.conversation.pending != null) Text(
                    if (controller.pendingRequiresDraft) "以前のPC接続で送信待ちの質問は、入力欄に戻してから送信してください。"
                    else "送信待ちの質問は元の設定で再試行します。変更は次の質問から反映されます。",
                    style = MaterialTheme.typography.bodySmall)
                Text("モデル", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { modelMenu = true }, enabled = choicesAvailable, modifier = Modifier.fillMaxWidth()) {
                        Text(if (catalog == null) if (controller.modelsLoading) "モデルを読み込み中…" else "モデル一覧は未取得" else modelName)
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        DropdownMenuItem(text = { Text(automaticLabel) }, onClick = {
                            model = null
                            if (effort !in catalog?.selected(null)?.efforts.orEmpty()) effort = null
                            modelMenu = false
                        })
                        catalog?.models?.forEach { option ->
                            DropdownMenuItem(text = { Text(option.displayName) }, onClick = {
                                model = option.id
                                if (effort !in option.efforts) effort = null
                                modelMenu = false
                            })
                        }
                    }
                }
                Text("推論の深さ", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { effortMenu = true }, enabled = choicesAvailable, modifier = Modifier.fillMaxWidth()) { Text(coachEffortLabel(effort)) }
                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        DropdownMenuItem(text = { Text("自動") }, onClick = { effort = null; effortMenu = false })
                        selected?.efforts?.forEach { option ->
                            DropdownMenuItem(text = { Text(coachEffortLabel(option)) }, onClick = { effort = option; effortMenu = false })
                        }
                    }
                }
                if (model == null && catalog != null) Text("現在の自動設定：${catalog.selected(null)?.displayName ?: catalog.defaultModel.orEmpty()} / ${coachEffortLabel(catalog.defaultEffort)}",
                    style = MaterialTheme.typography.bodySmall)
                Text("深く考える設定ほど、回答までの時間と利用枠の消費が増える場合があります。", style = MaterialTheme.typography.bodySmall)
                if (controller.modelsLoading) Text("ChatGPTで利用できるモデルを確認しています…")
                if (controller.ready) controller.modelsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!choicesValid && !controller.modelsLoading) Text("前回の選択は現在利用できません。モデルと推論を選び直してください。", color = MaterialTheme.colorScheme.error)
                if (controller.ready) TextButton(onClick = { controller.refreshModels() }, enabled = !busy) { Text("一覧を更新") }
            }
        },
        confirmButton = { TextButton(onClick = {
            val name = if (model == null) null else selected?.displayName ?: controller.preferences.modelName?.takeIf { model == controller.preferences.model }
            controller.savePreferences(CoachPreferences(model, effort, name), onDismiss)
        }, enabled = choicesAvailable && choicesValid) { Text("この設定を保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !controller.saving) { Text("閉じる") } })
}

@Composable
internal fun CoachDailyUseDialog(configured: Boolean, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("毎回の使い方") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (configured) "初回設定は済んでいます。毎回の設定は不要です。" else "初回は「Continue with ChatGPT」からログインし、利用を許可してください。")
            Text("始めるとき", style = MaterialTheme.typography.titleSmall)
            Text("インターネットに接続して、IronLogの「コーチ」で質問を書き、送信します。")
            Text("終わるとき", style = MaterialTheme.typography.titleSmall)
            Text("回答が終わったら、アプリを閉じるだけです。会話と筋トレ記録は端末に残ります。")
            Text("質問を送信したときだけ、必要な筋トレ記録と会話をOpenAIに送ります。ログアウトしても筋トレの記録は使えます。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("わかりました") } })
}
