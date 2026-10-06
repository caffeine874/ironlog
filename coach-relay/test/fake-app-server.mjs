import readline from 'node:readline';
const mode = process.argv[2];
const cliOverrides = process.argv.flatMap((argument, index, args) => argument === '-c' ? [args[index + 1]] : []);
const override = (key) => cliOverrides.filter((value) => value?.startsWith(`${key}=`)).at(-1);
const send = (value) => process.stdout.write(`${JSON.stringify(value)}\n`);
const reply = (id, result) => send({ id, result });
let selectedModel;
for await (const line of readline.createInterface({ input: process.stdin })) {
  const rpc = JSON.parse(line);
  if (rpc.method === 'initialize') reply(rpc.id, { userAgent: 'fake' });
  if (rpc.method === 'account/read') reply(rpc.id, { account: { type: mode === 'apikey' ? 'apiKey' : 'chatgpt' }, requiresOpenaiAuth: true });
  if (rpc.method === 'model/list') {
    if (mode === 'selection') {
      reply(rpc.id, rpc.params.cursor ? { data: [{ id: 'display-b', model: 'model-b', displayName: 'Second', isDefault: false, defaultReasoningEffort: 'medium', supportedReasoningEfforts: [{ reasoningEffort: 'medium' }, { reasoningEffort: 'high' }] }] } : { data: [{ id: 'display-a', model: 'model-a', displayName: 'First', isDefault: true, defaultReasoningEffort: 'medium', supportedReasoningEfforts: [{ reasoningEffort: 'low' }, { reasoningEffort: 'medium' }] }], nextCursor: 'page-2' });
    } else reply(rpc.id, { data: [{ id: 'fake', model: 'fake', isDefault: true, defaultReasoningEffort: 'low', supportedReasoningEfforts: [] }] });
  }
  if (rpc.method === 'thread/start') {
    if (!rpc.params.ephemeral || rpc.params.sandbox !== 'read-only' || rpc.params.approvalPolicy !== 'never' || rpc.params.environments.length !== 0 || rpc.params.dynamicTools.length !== 0) process.exit(9);
    if (rpc.params.allowProviderModelFallback !== false) process.exit(9);
    if (mode === 'websearch') {
      const required = [
        'web_search="disabled"', 'features.code_mode=false', 'features.code_mode_host=false',
        'features.shell_tool=false', 'features.unified_exec=false', 'features.apps=false',
        'features.plugins=false', 'features.remote_plugin=false', 'features.browser_use=false'
      ];
      if (required.some((setting) => override(setting.split('=')[0]) !== setting) || rpc.params.config?.web_search !== 'disabled') process.exit(9);
    }
    selectedModel = rpc.params.model;
    if (mode === 'selection' && selectedModel !== 'model-b') process.exit(9);
    reply(rpc.id, { thread: { id: 'fake-thread' } });
  }
  if (rpc.method === 'turn/start') {
    if (!rpc.params.outputSchema || rpc.params.sandboxPolicy.networkAccess !== false) process.exit(9);
    if (rpc.params.model !== selectedModel || (mode === 'selection' && rpc.params.effort !== 'high')) process.exit(9);
    reply(rpc.id, { turn: { id: 'turn', status: 'inProgress' } });
    if (mode === 'timeout') continue;
    if (mode === 'tool') { send({ id: 999, method: 'item/commandExecution/requestApproval', params: {} }); continue; }
    if (mode === 'websearch') {
      const item = { id: 'search-item', type: 'webSearch', query: '筋力トレーニング 指針' };
      send({ method: 'item/started', params: { threadId: 'fake-thread', item } });
      send({ method: 'item/completed', params: { threadId: 'fake-thread', item } });
    }
    // An unexpected execution event must be rejected even without an approval
    // request, and even when the process immediately emits a valid final answer.
    if (mode === 'command') send({ method: 'item/started', params: { threadId: 'fake-thread', item: { id: 'command-item', type: 'commandExecution', command: 'synthetic command that is never executed', status: 'inProgress' } } });
    const replyText = mode === 'websearch' ? '検索結果を確認しました。参考: https://example.org/training-guidance' : '前回は 60 kg × 8 回でした。';
    const text = mode === 'malformed' ? 'not json' : JSON.stringify({ kind: 'answer', reply: replyText, summary: 'ベンチプレスの相談。', historyRequest: null });
    send({ method: 'item/completed', params: { threadId: 'fake-thread', item: { id: 'item', type: 'agentMessage', phase: 'final_answer', text } } });
    send({ method: 'turn/completed', params: { threadId: 'fake-thread', turn: { id: 'turn', status: 'completed' } } });
  }
}
