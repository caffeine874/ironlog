import { spawn } from 'node:child_process';
import { mkdir, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { RelayError } from './errors.mjs';
import { COACH_INSTRUCTIONS, outputSchema, promptFor, parseCoachOutput } from './protocol.mjs';
import { fetchModelCatalog, selectModel } from './models.mjs';

// These overrides supplement an isolated CODEX_HOME. Never start with the user's
// ordinary home/config: a read-only sandbox alone still allows reading files.
export const HARDENING = [
  'forced_login_method="chatgpt"', 'model_provider="openai"',
  'web_search="disabled"', 'approval_policy="never"', 'sandbox_mode="read-only"',
  'project_doc_max_bytes=0', 'check_for_update_on_startup=false',
  'history.persistence="none"', 'analytics.enabled=false', 'feedback.enabled=false',
  'shell_environment_policy.inherit="none"', 'agents.enabled=false',
  'features.shell_tool=false', 'features.unified_exec=false', 'features.shell_snapshot=false',
  'features.apps=false', 'features.multi_agent=false', 'features.hooks=false',
  'features.plugins=false', 'features.remote_plugin=false', 'features.memories=false',
  'features.browser_use=false', 'features.computer_use=false', 'features.in_app_browser=false',
  'features.image_generation=false', 'features.view_image=false', 'features.goals=false',
  // Legacy turns include private workout history. They cannot expose any
  // hosted search or code transport that could send that context elsewhere.
  'features.code_mode=false', 'features.code_mode_host=false', 'features.sleep_tool=false',
  'features.skill_mcp_dependency_install=false', 'features.skill_search=false',
  'features.skip_host_skill_discovery=true', 'features.tool_suggest=false',
  'features.workspace_dependencies=false', 'features.recommended_plugins=false',
  'features.request_permissions_tool=false', 'features.prevent_idle_sleep=false'
];

export function childEnvironment(home, source = process.env) {
  // Only operating-system/network environment needed by Codex. No provider keys,
  // desktop session endpoints, relay tokens or arbitrary inherited credentials.
  const allowed = new Set(['PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP', 'TMPDIR', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA', 'PROGRAMDATA', 'LANG', 'LC_ALL', 'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY', 'SSL_CERT_FILE', 'SSL_CERT_DIR']);
  const env = Object.fromEntries(Object.entries(source).filter(([key]) => allowed.has(key.toUpperCase())));
  env.CODEX_HOME = home;
  return env;
}

export class AppServer {
  constructor(config, spawnProcess = spawn) {
    this.config = config;
    this.spawnProcess = spawnProcess;
    this.nextId = 1;
    this.pending = new Map();
    this.handlers = new Set();
    this.buffer = '';
    this.closed = false;
  }

  async start() {
    const home = resolve(this.config.codexHome);
    this.cwd = join(home, 'coach-workdir');
    await mkdir(this.cwd, { recursive: true });
    // Reject accidentally reused regular homes instead of merging unsafe config.
    const names = await readdir(home);
    const skillNames = names.includes('skills') ? await readdir(join(home, 'skills')) : [];
    if (names.some((name) => ['config.toml', 'mcp.json', 'plugins', 'AGENTS.md'].includes(name)) || skillNames.some((name) => name !== '.system')) {
      throw new RelayError('codex_unavailable', 'AI コーチ専用の Codex 保存先を使用してください。通常の Codex 設定は読み込めません。', 503);
    }
    const args = [...(this.config.codexArgs ?? []), 'app-server', ...HARDENING.flatMap((entry) => ['-c', entry])];
    this.child = this.spawnProcess(this.config.codexCommand, args, { cwd: this.cwd, env: childEnvironment(home), windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', (chunk) => this.read(chunk));
    // Drain diagnostics without logging credentials, filesystem paths or prompts.
    this.child.stderr.on('data', () => {});
    this.child.on('error', () => this.fail(new RelayError('codex_unavailable', 'PC の Codex を起動できません。セットアップを実行してください。', 503)));
    this.child.on('exit', () => this.fail(new RelayError('codex_unavailable', 'PC の Codex が終了しました。もう一度接続してください。', 503)));
    await this.call('initialize', { clientInfo: { name: 'training_ai_coach', title: 'Training AI Coach', version: '1.2.0' }, capabilities: { experimentalApi: true } });
    this.send({ method: 'initialized' });
    const { account } = await this.call('account/read', { refreshToken: false });
    if (account?.type !== 'chatgpt') throw new RelayError('chatgpt_login_required', 'PC で AI コーチ用 Codex に ChatGPT アカウントでログインしてください。API キーは使用できません。', 503);
    return account;
  }

  send(value) {
    if (!this.closed && this.child?.stdin.writable) this.child.stdin.write(`${JSON.stringify(value)}\n`);
  }

  call(method, params, timeoutMs = 25000) {
    if (this.closed) return Promise.reject(new RelayError('codex_unavailable', 'Codex との接続がありません。', 503));
    const id = this.nextId++;
    return new Promise((resolveCall, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new RelayError('codex_timeout', 'Codex との接続が時間切れになりました。', 504)); }, timeoutMs);
      this.pending.set(id, { resolve: resolveCall, reject, timer });
      this.send({ id, method, params });
    });
  }

  read(chunk) {
    this.buffer += chunk;
    if (this.buffer.length > 2 * 1024 * 1024) { this.fail(new RelayError('invalid_response', 'Codex の応答が大きすぎます。')); this.stop(); return; }
    let newline;
    while ((newline = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, newline);
      this.buffer = this.buffer.slice(newline + 1);
      if (!line.trim()) continue;
      let value;
      try { value = JSON.parse(line); } catch { continue; }
      if (value.id !== undefined && value.method) {
        // No approval, external tool, input or OAuth requests are delegated to the
        // phone. Unexpected capabilities fail closed and terminate this process.
        this.send({ id: value.id, error: { code: -32601, message: 'Client-executed tools and approvals are disabled for this coach.' } });
        this.fail(new RelayError('codex_error', 'AI が利用できない操作を要求しました。質問を変えてお試しください。'));
        this.stop();
        return;
      }
      if (value.id !== undefined) {
        const pending = this.pending.get(value.id);
        if (!pending) continue;
        clearTimeout(pending.timer);
        this.pending.delete(value.id);
        if (value.error) pending.reject(new RelayError('codex_error', 'Codex が要求を受け付けませんでした。PC のログイン状態と利用枠を確認してください。'));
        else pending.resolve(value.result);
      } else {
        for (const handler of this.handlers) handler(value);
      }
    }
  }

  fail(error) {
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear();
    for (const handler of this.handlers) handler({ method: '_relay/failure', error });
  }

  models() {
    return fetchModelCatalog(this, this.config);
  }

  async answer(input, historyRequest, frozenSelection) {
    // Refresh availability before every turn even if the phone's picker is cached.
    const selection = selectModel(await this.models(), input, frozenSelection);
    const { thread } = await this.call('thread/start', {
      model: selection.model, allowProviderModelFallback: false, modelProvider: 'openai', cwd: this.cwd, ephemeral: true,
      approvalPolicy: 'never', sandbox: 'read-only', environments: [], dynamicTools: [], selectedCapabilityRoots: [],
      baseInstructions: COACH_INSTRUCTIONS, developerInstructions: COACH_INSTRUCTIONS,
      config: { web_search: 'disabled', project_doc_max_bytes: 0 }
    });
    if (!thread?.id) throw new RelayError('invalid_response', 'AI の会話を開始できませんでした。');
    return new Promise((resolveAnswer, reject) => {
      let output = '';
      let finished = false;
      const finish = (error, value) => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        this.handlers.delete(handler);
        if (error) reject(error); else resolveAnswer(value);
      };
      const timer = setTimeout(() => {
        finish(new RelayError('codex_timeout', 'AI の回答が時間切れになりました。少し待ってからお試しください。', 504));
        this.stop();
      }, this.config.turnTimeoutMs ?? (['none', 'minimal', 'low', 'medium'].includes(selection.effort) ? 180000 : 600000));
      const handler = (event) => {
        if (event.method === '_relay/failure') { finish(event.error); return; }
        if (event.params?.threadId !== thread.id) return;
        if (['item/started', 'item/completed'].includes(event.method) && ['webSearch', 'commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall', 'imageGeneration', 'collabAgentToolCall', 'collabToolCall', 'codeExecution'].includes(event.params.item?.type)) {
          finish(new RelayError('codex_error', 'AI の操作を安全のため停止しました。'));
          this.stop();
        }
        if (event.method === 'item/completed' && event.params.item?.type === 'agentMessage') {
          const item = event.params.item;
          if (!item.phase || item.phase === 'final_answer') output = item.text ?? '';
        }
        if (event.method === 'turn/completed') {
          if (event.params.turn.status !== 'completed') {
            const info = event.params.turn.error?.codexErrorInfo;
            const limited = info === 'usageLimitExceeded' || (info && typeof info === 'object' && 'modelCap' in info);
            finish(new RelayError(limited ? 'usage_limit' : 'codex_error', limited ? 'Codex の利用枠に達しました。枠の回復後にお試しください。' : 'AI の回答が完了しませんでした。PC の接続状態をご確認ください。'));
            return;
          }
          try { finish(null, { ...parseCoachOutput(output, Boolean(input.continuation)), ...selection }); } catch (error) { finish(error); }
        }
      };
      this.handlers.add(handler);
      this.call('turn/start', { threadId: thread.id, input: [{ type: 'text', text: promptFor(input, historyRequest) }], approvalPolicy: 'never', sandboxPolicy: { type: 'readOnly', networkAccess: false }, outputSchema, model: selection.model, effort: selection.effort }).catch((error) => finish(error));
    });
  }

  stop() {
    if (this.closed) return;
    this.closed = true;
    this.fail(new RelayError('codex_unavailable', 'Codex との接続を終了しました。', 503));
    this.child?.stdin.destroy();
    this.child?.kill();
  }
}

export function codexRunner(config, Client = AppServer) {
  return {
    async health() {
      const client = new Client(config);
      try { await client.start(); return { status: 'ok', accountType: 'chatgpt' }; } finally { client.stop(); }
    },
    async models() {
      const client = new Client(config);
      try { await client.start(); return await client.models(); } finally { client.stop(); }
    },
    async answer(input, historyRequest, frozenSelection) {
      const client = new Client(config);
      try { await client.start(); return await client.answer(input, historyRequest, frozenSelection); } finally { client.stop(); }
    }
  };
}
