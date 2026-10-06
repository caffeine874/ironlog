package com.example.training

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

/** Provider choices are local to this installation; existing relay settings remain in CoachStore. */
internal class CoachProviderSettings(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "coach-provider.json"))

    @Synchronized private fun read(): JSONObject =
        if (file.baseFile.exists() || File("${file.baseFile.path}.bak").exists()) JSONObject(String(file.readFully(), Charsets.UTF_8)) else JSONObject()

    @Synchronized private fun write(json: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (problem: Exception) {
            file.failWrite(output)
            throw problem
        }
    }

    @Synchronized fun usesLegacy(): Boolean = read().optBoolean("legacy", false)
    @Synchronized fun setLegacy(value: Boolean) = write(read().put("legacy", value))
    @Synchronized fun preferences(): CoachPreferences {
        val json = read().optJSONObject("preferences") ?: return CoachPreferences()
        return CoachPreferences(json.optString("model").takeIf { it.isNotBlank() },
            json.optString("effort").takeIf { it.isNotBlank() }, json.optString("modelName").takeIf { it.isNotBlank() })
    }
    @Synchronized fun setPreferences(value: CoachPreferences) = write(read().put("preferences", JSONObject()
        .also { value.model?.let { model -> it.put("model", model) } }
        .also { value.effort?.let { effort -> it.put("effort", effort) } }
        .also { value.modelName?.let { name -> it.put("modelName", name) } }))
}

internal class CoachController(
    private val store: CoachStore,
    private val scope: CoroutineScope,
    private val auth: ChatGptAuth? = null,
    private val providerSettings: CoachProviderSettings? = null,
) {
    var useLegacy by mutableStateOf(auth == null)
        private set
    var account by mutableStateOf<ChatGptAccount?>(null)
        private set
    var signingIn by mutableStateOf(false)
        private set
    val directAvailable: Boolean get() = auth != null
    val busy: Boolean get() = saving || sending || testing || modelsLoading || signingIn
    val ready: Boolean get() = if (useLegacy) connection.configured else account?.planUsageEnabled == true
    val providerLabel: String get() = if (useLegacy) "家のPC" else "ChatGPT"
    var connection by mutableStateOf(CoachConnection())
        private set
    var conversation by mutableStateOf(CoachConversation())
        private set
    var loaded by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var connectionStatus by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf("")
        private set
    var draft by mutableStateOf("")
    var searchWeb by mutableStateOf(false)
        private set
    var preferences by mutableStateOf(CoachPreferences())
        private set
    var modelCatalog by mutableStateOf<CoachModelCatalog?>(null)
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set
    var pendingModelError by mutableStateOf(false)
        private set

    suspend fun load() {
        try {
            val snapshot = withContext(Dispatchers.IO) { Triple(store.readConnection(), store.readConversation(), store.readPreferences()) }
            connection = snapshot.first
            conversation = snapshot.second
            useLegacy = auth == null || withContext(Dispatchers.IO) { providerSettings?.usesLegacy() == true }
            preferences = if (useLegacy) snapshot.third else withContext(Dispatchers.IO) { providerSettings?.preferences() ?: CoachPreferences() }
            account = withContext(Dispatchers.IO) { auth?.account() }
            loaded = true
            if (conversation.pending != null) error = "送信待ちの質問があります。「再試行」で続けられます。"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            this.error = "会話を読み込めませんでした。アプリを開き直してください。筋トレ記録は別に保存されています。"
        }
    }

    fun setLegacy(value: Boolean) {
        if (busy || !loaded || (!value && auth == null) || value == useLegacy) return
        saving = true
        scope.launch {
            try {
                val nextPreferences = withContext(Dispatchers.IO) {
                    val result = if (value) store.readPreferences() else providerSettings?.preferences() ?: CoachPreferences()
                    providerSettings?.setLegacy(value)
                    result
                }
                useLegacy = value
                searchWeb = false
                preferences = nextPreferences
                modelCatalog = null
                modelsError = null
                error = null
                connectionStatus = null
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                error = "接続方法を保存できませんでした。もう一度お試しください。"
            } finally {
                saving = false
            }
        }
    }

    fun signIn(openBrowser: (String) -> Unit) {
        val authentication = auth ?: return
        if (busy || !loaded || useLegacy) return
        signingIn = true
        error = null
        connectionStatus = "ブラウザでログインしてください。完了したらIronLogに戻ってください。"
        scope.launch {
            try {
                account = authentication.signIn(openBrowser)
                connectionStatus = if (account?.planUsageEnabled == true) "ChatGPTに接続しました。質問を書くと相談できます。"
                    else "ChatGPTの利用枠へのアクセスが許可されていません。もう一度ログインして許可してください。"
                modelCatalog = null
                modelsError = null
            } catch (problem: CancellationException) {
                connectionStatus = null
                throw problem
            } catch (problem: Exception) {
                if (problem is ChatGptAuthException && problem.code == "cancelled") {
                    connectionStatus = "ログインをキャンセルしました。"
                    error = null
                } else {
                    connectionStatus = null
                    error = problem.message ?: "ChatGPTにログインできませんでした。"
                }
            } finally {
                signingIn = false
            }
        }
    }

    fun cancelSignIn() {
        if (!signingIn) return
        auth?.cancelSignIn()
    }

    fun signOut() {
        val authentication = auth ?: return
        if (busy) return
        saving = true
        scope.launch {
            try {
                val revoked = authentication.signOutAndRevoke()
                account = authentication.account()
                searchWeb = false
                modelCatalog = null
                modelsError = null
                error = null
                connectionStatus = if (revoked) "ログアウトしました。筋トレ記録と会話は端末に残っています。"
                    else "端末からログアウトしました。接続の解除はChatGPT側で確認してください。"
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                account = authentication.account()
                error = "ログアウト状態を確認できませんでした。アプリを開き直してください。"
            } finally {
                saving = false
            }
        }
    }

    fun saveConnection(next: CoachConnection, onSaved: () -> Unit) {
        if (busy || !useLegacy) return
        saving = true
        scope.launch {
            error = null
            try {
                val checked = next.checked()
                withContext(Dispatchers.IO) { store.writeConnection(checked) }
                connection = checked
                connectionStatus = null
                modelCatalog = null
                modelsError = null
                onSaved()
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                error = problem.message ?: "接続設定を保存できませんでした。"
            } finally {
                saving = false
            }
        }
    }

    fun refreshModels() {
        if (busy) return
        modelCatalog = null
        modelsError = null
        if (!ready) {
            modelsError = if (useLegacy) "接続設定を保存すると、PCで利用できるモデルを選べます。" else "ChatGPTにログインすると、利用できるモデルを選べます。"
            return
        }
        modelsLoading = true
        val requestedConnection = connection
        scope.launch {
            try {
                val available = if (useLegacy) CoachClient.models(requestedConnection) else ChatGptClient(requireNotNull(auth)).models()
                if (connection == requestedConnection) modelCatalog = available
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                if (!useLegacy) account = withContext(Dispatchers.IO) { auth?.account() }
                if (connection == requestedConnection) modelsError = problem.message ?: "モデル一覧を取得できませんでした。接続を確認してください。"
            } finally {
                modelsLoading = false
            }
        }
    }

    fun savePreferences(next: CoachPreferences, onSaved: () -> Unit) {
        if (busy) return
        val unchanged = next.model == preferences.model && next.effort == preferences.effort
        val supported = if (next.model == null && next.effort == null) true
            else if (modelCatalog == null && unchanged) true else modelCatalog?.supports(next) == true
        if (!supported) {
            modelsError = "現在使えるモデルと推論の組み合わせを選んでください。"
            return
        }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (useLegacy) store.writePreferences(next) else providerSettings?.setPreferences(next)
                }
                preferences = next
                modelsError = null
                onSaved()
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                modelsError = "モデルの設定を保存できませんでした。もう一度お試しください。"
            } finally {
                saving = false
            }
        }
    }

    fun returnPendingToDraft(onReady: () -> Unit) {
        if (sending || saving) return
        val question = conversation.pending?.let { JSONObject(it).optString("message") } ?: return
        saving = true
        scope.launch {
            try {
                conversation = withContext(Dispatchers.IO) { store.cancelPending() }
                draft = question
                searchWeb = false
                error = null
                pendingModelError = false
                onReady()
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                error = "質問を入力欄に戻せませんでした。もう一度お試しください。"
            } finally {
                saving = false
            }
        }
    }

    fun updateWebSearch(enabled: Boolean) {
        if (!busy && !useLegacy && conversation.pending == null) searchWeb = enabled
    }

    fun testConnection(next: CoachConnection) {
        if (busy || !useLegacy) return
        testing = true
        scope.launch {
            error = null
            connectionStatus = "接続を確認しています…"
            try {
                val response = CoachClient.health(next)
                check(response.optString("status") == "ok" && response.optString("accountType") == "chatgpt") {
                    "PC側のCodexをChatGPTアカウントでログインしてください。"
                }
                connectionStatus = "接続できました。ChatGPT契約のCodexで会話できます。"
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                connectionStatus = null
                error = problem.message ?: "接続を確認できませんでした。"
            } finally {
                testing = false
            }
        }
    }

    fun send(entries: List<WorkoutSet>, selectedDate: String, exercises: List<String>) {
        val question = draft.trim()
        if (!loaded || busy || question.isBlank() || conversation.pending != null) return
        if (!ready) {
            error = if (useLegacy) "「接続設定」でPC側の接続情報を入力してください。" else "「Continue with ChatGPT」からログインして、利用を許可してください。"
            return
        }
        if (question.length > 4000) {
            error = "質問は4,000文字以内で入力してください。"
            return
        }
        val requestedWebSearch = !useLegacy && searchWeb
        sending = true
        scope.launch {
            error = null
            pendingModelError = false
            progress = "必要な筋トレ記録を用意しています…"
            try {
                val id = UUID.randomUUID().toString()
                val current = conversation
                val selectedPreferences = if (useLegacy) preferences else {
                    progress = "利用できるモデルを確認しています…"
                    val catalog = ChatGptClient(requireNotNull(auth)).models()
                    modelCatalog = catalog
                    val model = catalog.selected(preferences.model)
                        ?: throw CoachServerException("unsupported_model", "選んだモデルは現在使えません。「モデル・推論」で選び直してください。")
                    if (preferences.effort != null && preferences.effort !in model.efforts) {
                        throw CoachServerException("unsupported_effort", "選んだ推論設定は現在使えません。「モデル・推論」で選び直してください。")
                    }
                    preferences.copy(model = model.id, modelName = model.displayName)
                }
                val body = withContext(Dispatchers.IO) {
                    JSONObject()
                        .put("requestId", id)
                        .put("message", question)
                        .put("context", CoachContext.build(entries, question, selectedDate, exercises))
                        .put("recentMessages", CoachClient.recentMessages(current.messages))
                        .put("summary", current.summary.take(6000))
                        .put("_provider", if (useLegacy) "relay" else "chatgpt")
                        .also { if (!useLegacy) it.put("_account", accountIdentity()).put("webSearch", requestedWebSearch) }
                        .also { selectedPreferences.applyTo(it) }
                }
                conversation = withContext(Dispatchers.IO) {
                    store.enqueue(CoachMessage(id, "user", question, System.currentTimeMillis(), "pending"), body)
                }
                draft = ""
                searchWeb = false
                performRequest(body, entries)
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                if (!useLegacy) account = withContext(Dispatchers.IO) { auth?.account() }
                error = problem.message ?: "送信できませんでした。質問は保存されています。"
            } finally {
                sending = false
            }
        }
    }

    fun retry(entries: List<WorkoutSet>) {
        val pending = conversation.pending ?: return
        if (busy || !loaded) return
        sending = true
        scope.launch {
            error = null
            pendingModelError = false
            try {
                performRequest(JSONObject(pending), entries)
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                if (!useLegacy) account = withContext(Dispatchers.IO) { auth?.account() }
                error = problem.message ?: "送信できませんでした。接続を確認して再試行してください。"
            } finally {
                sending = false
            }
        }
    }

    private suspend fun performRequest(body: JSONObject, entries: List<WorkoutSet>) {
        try {
            performRequestWithHistory(body, entries)
        } catch (problem: CoachServerException) {
            if (problem.code == "unsupported_model" || problem.code == "unsupported_effort") {
                pendingModelError = true
                throw java.io.IOException("${problem.message}\n「モデルを選び直す」で質問を入力欄に戻せます。", problem)
            }
            if (problem.code == "continuation_expired") {
                body.remove("continuation")
                body.remove("historyResults")
                conversation = withContext(Dispatchers.IO) { store.updatePending(body) }
                throw java.io.IOException("接続が更新されました。「再試行」で同じ質問を続けられます。", problem)
            }
            throw problem
        }
    }

    private suspend fun performRequestWithHistory(body: JSONObject, entries: List<WorkoutSet>) {
        progress = "AIコーチが記録を確認しています…深い推論は時間がかかる場合があります。"
        var response = chat(body)
        if (response.has("historyRequest")) {
            check(!body.has("continuation")) { "古い履歴の参照回数が上限に達しました。期間を指定して質問し直してください。" }
            val request = response.optJSONObject("historyRequest") ?: throw java.io.IOException("履歴の参照条件を読み取れませんでした。")
            val continuation = response.optString("continuation")
            check(continuation.isNotBlank() && continuation.length <= 4096) { "会話の続きを取得できませんでした。" }
            progress = "質問に関係する過去の記録を追加で確認しています…"
            val history = withContext(Dispatchers.IO) { CoachContext.history(entries, request) }
            body.put("historyResults", JSONArray().put(history)).put("continuation", continuation)
            if (!useLegacy && response.has("webResearch")) {
                val research = response.opt("webResearch") as? String
                check(research != null && research.length <= 24_000) { "Web検索結果を読み取れませんでした。再試行してください。" }
                body.put("webResearch", research)
            }
            conversation = withContext(Dispatchers.IO) { store.updatePending(body) }
            response = chat(body)
        }
        check(!response.has("historyRequest")) { "履歴の参照が完了しませんでした。再試行してください。" }
        val reply = response.optString("reply").trim()
        check(reply.isNotBlank() && reply.length <= 100_000) { "回答を読み取れませんでした。再試行してください。" }
        val summary = if (response.has("summary")) response.optString("summary").take(6000) else conversation.summary
        conversation = withContext(Dispatchers.IO) { store.complete(body.getString("requestId"), reply, summary) }
    }

    private fun accountIdentity(): String = account?.let { "${it.subject}:${it.clientId}" }.orEmpty()

    private suspend fun chat(body: JSONObject): JSONObject {
        checkCoachRequestOwner(body, useLegacy, accountIdentity(), ready)
        val request = JSONObject(body.toString()).apply { remove("_provider"); remove("_account") }
        return if (useLegacy) CoachClient.chat(connection, request) else ChatGptClient(requireNotNull(auth)).chat(request)
    }

    fun cancelPending() {
        if (sending || saving) return
        saving = true
        scope.launch {
            try {
                conversation = withContext(Dispatchers.IO) { store.cancelPending() }
                error = null
                pendingModelError = false
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                error = "送信待ちの状態を変更できませんでした。"
            } finally {
                saving = false
            }
        }
    }

    fun resetConversation() {
        if (sending || saving) return
        saving = true
        scope.launch {
            try {
                conversation = withContext(Dispatchers.IO) { store.resetConversation() }
                draft = ""
                searchWeb = false
                error = null
                pendingModelError = false
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                error = "会話の削除を完了できませんでした。"
            } finally {
                saving = false
            }
        }
    }
}

internal fun checkCoachRequestOwner(body: JSONObject, useLegacy: Boolean, accountIdentity: String, ready: Boolean) {
    // Pending requests created before direct sign-in belong to the original PC provider.
    val provider = body.optString("_provider", "relay")
    check(provider == if (useLegacy) "relay" else "chatgpt") {
        "この質問は別の接続方法で送信待ちです。接続方法を戻すか、質問を入力欄に戻してください。"
    }
    if (!useLegacy) {
        check(ready && accountIdentity.isNotBlank() && body.optString("_account") == accountIdentity) {
            "送信したときのChatGPTアカウントでログインするか、質問を入力欄に戻してください。"
        }
    }
}

@Composable
internal fun rememberCoachController(context: Context): CoachController {
    val scope = rememberCoroutineScope()
    val store = remember(context.applicationContext) { CoachStore(context.applicationContext) }
    val auth = remember(context.applicationContext) { ChatGptAuth.get(context.applicationContext) }
    val providerSettings = remember(context.applicationContext) { CoachProviderSettings(context.applicationContext) }
    val controller = remember(store, scope, auth, providerSettings) { CoachController(store, scope, auth, providerSettings) }
    LaunchedEffect(controller) { controller.load() }
    DisposableEffect(store) { onDispose { store.close() } }
    return controller
}

@Composable
internal fun CoachScreen(controller: CoachController, entries: List<WorkoutSet>, selectedDate: String, exercises: List<String>) {
    val context = LocalContext.current
    val openBrowser: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    var settingsOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var dailyUseOpen by remember { mutableStateOf(false) }
    val conversation = controller.conversation
    val listState = rememberLazyListState()
    LaunchedEffect(conversation.messages.size, controller.sending) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AIコーチ", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("筋トレの記録を見ながら相談", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { settingsOpen = true }, enabled = controller.loaded && !controller.busy) { Text("接続設定") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { modelsOpen = true }, enabled = controller.loaded && !controller.busy) { Text("モデル・推論") }
            TextButton(onClick = { dailyUseOpen = true }) { Text("毎回の使い方") }
        }
        Text("選択：${controller.preferences.label}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("参照する日：$selectedDate（記録タブで変更）", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        if (controller.loaded && !controller.useLegacy) {
            if (controller.ready) {
                Text("ChatGPT：${controller.account?.email.orEmpty().ifBlank { "接続済み" }}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            } else if (controller.signingIn) {
                Text("ブラウザでログインしたら、IronLogに戻ってください。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller.cancelSignIn() }) { Text("ログインをキャンセル") }
            } else {
                Button(onClick = { controller.signIn(openBrowser) }, enabled = !controller.busy) { Text("Continue with ChatGPT") }
                Text(if (controller.account != null) "ChatGPTの利用枠へのアクセスを許可してください。" else "ChatGPTにログインして相談できます。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        HorizontalDivider()
        if (!controller.loaded) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (controller.error == null) CircularProgressIndicator() else Text(controller.error.orEmpty())
            }
        } else if (conversation.messages.isEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("メニューや重量について、気軽に聞いてください。", fontSize = 18.sp)
                Text("「最近ベンチやりすぎ？」\n「次は何kgにしたらいい？」\n「この1か月のベンチの伸びを見て」", lineHeight = 28.sp)
                Text("選んだ日の記録・最近の履歴・質問に関係する記録を参照します。必要なときだけ古い履歴を追加します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("質問を送信すると、必要な筋トレ記録と会話をOpenAIに送ります。ログインだけでは記録を送りません。", style = MaterialTheme.typography.bodySmall)
                if (controller.useLegacy) {
                    if (!controller.connection.configured) OutlinedButton(onClick = { settingsOpen = true }) { Text("家のPCに接続する") }
                    Text("PCとTailscaleが動いている間に利用できます。", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(conversation.messages, key = { it.id }) { message ->
                    val fromUser = message.role == "user"
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
                        Text(if (fromUser) "あなた" else "AIコーチ", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(message.content,
                                modifier = Modifier.fillMaxWidth(if (fromUser) 0.91f else 0.97f)
                                    .background(if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                                    .padding(14.dp), fontSize = 16.sp, lineHeight = 25.sp,
                                color = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (message.status == "cancelled") Text("再送をやめました", style = MaterialTheme.typography.bodySmall)
                        if (message.status == "pending" && !controller.sending) Text("送信待ち", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }
        if (controller.sending) Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(controller.progress, style = MaterialTheme.typography.bodySmall)
        }
        controller.error?.takeIf { controller.loaded }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (conversation.pending != null && !controller.sending) {
            if (controller.pendingModelError) TextButton(onClick = {
                controller.returnPendingToDraft { modelsOpen = true }
            }, enabled = !controller.busy) { Text("モデルを選び直す") }
            else TextButton(onClick = { controller.returnPendingToDraft {} }, enabled = !controller.busy) { Text("質問を入力欄に戻す") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.retry(entries) }, enabled = !controller.busy) { Text("再試行") }
                TextButton(onClick = { controller.cancelPending() }, enabled = !controller.busy) { Text("再送をやめる") }
            }
        }
        if (controller.loaded && !controller.useLegacy && conversation.pending == null) {
            Row(Modifier.fillMaxWidth().toggleable(value = controller.searchWeb, enabled = !controller.busy,
                role = Role.Checkbox, onValueChange = controller::updateWebSearch), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = controller.searchWeb, onCheckedChange = null, enabled = !controller.busy)
                Text("Web検索", style = MaterialTheme.typography.bodySmall)
            }
            if (controller.searchWeb) Text("今回の質問文をWeb検索に使います。個人情報は質問文に含めないでください。記録・過去の会話は検索に渡しません。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = controller.draft, onValueChange = { if (it.length <= 4000) controller.draft = it },
                modifier = Modifier.weight(1f).heightIn(max = 150.dp), label = { Text("質問を書く") }, minLines = 1, maxLines = 5,
                enabled = controller.loaded && !controller.sending && conversation.pending == null)
            Button(onClick = { controller.send(entries, selectedDate, exercises) },
                enabled = controller.loaded && controller.ready && !controller.busy && conversation.pending == null && controller.draft.isNotBlank()) { Text("送信") }
        }
        if (conversation.messages.isNotEmpty()) {
            TextButton(onClick = { confirmReset = true }, enabled = !controller.sending && !controller.saving,
                modifier = Modifier.align(Alignment.End)) { Text("会話をリセット", style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (settingsOpen) {
        if (controller.directAvailable) CoachAccountDialog(controller, onDismiss = { settingsOpen = false })
        else CoachConnectionDialog(controller, onDismiss = { settingsOpen = false })
    }
    if (modelsOpen) CoachModelDialog(controller, onDismiss = { modelsOpen = false })
    if (dailyUseOpen) CoachDailyUseDialog(controller.ready, controller.useLegacy, onDismiss = { dailyUseOpen = false })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("会話をリセットしますか？") },
        text = { Text("AIコーチとの会話と会話の要約を削除します。筋トレ記録・種目・接続設定はそのまま残ります。") },
        confirmButton = { TextButton(onClick = { confirmReset = false; controller.resetConversation() }) { Text("会話だけ削除する") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("戻る") } })
}

@Composable
private fun CoachAccountDialog(controller: CoachController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var advanced by remember { mutableStateOf(controller.useLegacy) }
    var legacySettingsOpen by remember { mutableStateOf(false) }
    if (legacySettingsOpen) {
        CoachConnectionDialog(controller, onDismiss = { legacySettingsOpen = false })
        return
    }
    AlertDialog(onDismissRequest = { if (!controller.saving) onDismiss() }, title = { Text("接続設定") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("接続方法：${controller.providerLabel}", style = MaterialTheme.typography.titleSmall)
                if (!controller.useLegacy) {
                    controller.account?.let { account ->
                        Text(account.email.ifBlank { "ChatGPTにログイン済み" })
                    }
                    if (controller.signingIn) {
                        Text("ブラウザでログインしてください。完了したらIronLogに戻ってください。")
                        TextButton(onClick = { controller.cancelSignIn() }) { Text("ログインをキャンセル") }
                    } else if (!controller.ready) {
                        if (controller.account != null) Text("ChatGPTの利用枠へのアクセスを許可してください。")
                        Button(onClick = { controller.signIn { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            enabled = !controller.busy) { Text("Continue with ChatGPT") }
                    }
                    if (controller.ready && !controller.signingIn) TextButton(onClick = {
                        controller.signIn { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }, enabled = !controller.busy) { Text("ログインし直す") }
                    if (controller.account != null) TextButton(onClick = { controller.signOut() }, enabled = !controller.busy) { Text("ログアウト") }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptAuth.USAGE_URL))) }, enabled = !controller.busy) { Text("ChatGPTの利用状況を開く") }
                    Text("質問の送信時に、必要な筋トレ記録と会話をOpenAIに送ります。ログインだけでは記録を送りません。", style = MaterialTheme.typography.bodySmall)
                    Text("ChatGPTの利用条件と利用枠が適用されます。", style = MaterialTheme.typography.bodySmall)
                }
                controller.connectionStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { advanced = !advanced }, enabled = !controller.busy) { Text(if (advanced) "詳細設定を閉じる" else "詳細設定") }
                if (advanced) {
                    Text("以前のPC接続も引き続き使えます。保存済みの接続情報は残ります。", style = MaterialTheme.typography.bodySmall)
                    if (controller.useLegacy) {
                        OutlinedButton(onClick = { legacySettingsOpen = true }, enabled = !controller.busy) { Text("PCの接続設定") }
                        OutlinedButton(onClick = { controller.setLegacy(false) }, enabled = !controller.busy) { Text("ChatGPTへの直接接続を使う") }
                    } else {
                        OutlinedButton(onClick = { controller.setLegacy(true) }, enabled = !controller.busy) { Text("家のPCへの接続を使う") }
                    }
                    if (controller.conversation.pending != null) Text("送信待ちの質問は、元の接続方法とアカウントで再試行してください。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !controller.saving) { Text("閉じる") } })
}

@Composable
private fun CoachConnectionDialog(controller: CoachController, onDismiss: () -> Unit) {
    var pairing by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(controller.connection.url) }
    var token by remember { mutableStateOf(controller.connection.token) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val busy = controller.busy
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("家のPCへの接続") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PCで発行された接続情報を貼り付けてください。スマホのTailscaleも接続してください。")
                OutlinedTextField(pairing, { if (it.length <= 4096) pairing = it }, label = { Text("接続情報を貼り付け") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                OutlinedButton(onClick = {
                    try {
                        val json = JSONObject(pairing.trim())
                        val parsed = CoachConnection(json.getString("url"), json.getString("token")).checked()
                        url = parsed.url
                        token = parsed.token
                        pairing = ""
                        inputError = null
                    } catch (problem: Exception) {
                        inputError = "接続情報を読み取れませんでした。PCに表示された情報を省略せず貼り付けてください。"
                    }
                }, enabled = !busy && pairing.isNotBlank()) { Text("接続情報を読み込む") }
                OutlinedTextField(url, { if (it.length <= 1024) url = it }, label = { Text("PCの接続先URL") },
                    placeholder = { Text("https://…ts.net:8765") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(token, { if (it.length <= 512) token = it }, label = { Text("接続キー") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                Text("OpenAI APIキーは使いません。PCのCodexをChatGPTアカウントでログインします。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { inputError = null; controller.testConnection(CoachConnection(url, token)) }, enabled = !busy) {
                    Text(if (controller.testing) "確認しています…" else "接続を確認する")
                }
                controller.connectionStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                (inputError ?: controller.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { inputError = null; controller.saveConnection(CoachConnection(url, token), onDismiss) }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("閉じる") } })
}
