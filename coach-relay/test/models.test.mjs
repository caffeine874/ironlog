import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { fetchModelCatalog, selectModel } from '../models.mjs';
import { validateInput } from '../protocol.mjs';
import { CoachService } from '../service.mjs';
import { codexRunner } from '../app-server.mjs';
import { createRelay } from '../server.mjs';

const input = (extra = {}) => ({ requestId: 'models-test-0001', message: '今日のメニューは？', context: {}, recentMessages: [], summary: '', ...extra });
const rawModel = (model = 'first', efforts = ['low', 'medium'], isDefault = true) => ({ id: `display-${model}`, model, displayName: model, isDefault, defaultReasoningEffort: 'medium', supportedReasoningEfforts: efforts.map((reasoningEffort) => ({ reasoningEffort })) });
const catalog = { models: [{ id: 'first', displayName: 'First', isDefault: true, defaultReasoningEffort: 'medium', supportedReasoningEfforts: ['low', 'medium'] }, { id: 'second', displayName: 'Second', isDefault: false, defaultReasoningEffort: 'medium', supportedReasoningEfforts: ['medium', 'high'] }], defaultModel: 'first', defaultReasoningEffort: 'low' };
const removeHome = (home) => {
  assert.ok(resolve(home).startsWith(resolve(join(tmpdir(), 'training-coach-model-test-'))));
  return rm(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
};

test('model catalog follows pages, exposes canonical names and current efforts', async () => {
  const calls = [];
  const client = { async call(method, params) { calls.push({ method, params }); return params.cursor ? { data: [rawModel('second', ['medium', 'ultra'], false)] } : { data: [rawModel()], nextCursor: 'next' }; } };
  const result = await fetchModelCatalog(client);
  assert.deepEqual(result.models.map((model) => model.id), ['first', 'second']);
  assert.deepEqual(result.models[1].supportedReasoningEfforts, ['medium', 'ultra']);
  assert.equal(result.defaultModel, 'first');
  assert.equal(result.defaultReasoningEffort, 'low');
  assert.equal(calls[1].params.cursor, 'next');
  assert.equal(calls[0].params.includeHidden, false);
});

test('catalog rejects cursor loops, oversized list and unavailable configured default', async () => {
  await assert.rejects(fetchModelCatalog({ call: async () => ({ data: [rawModel()], nextCursor: 'same' }) }), { code: 'codex_unavailable' });
  await assert.rejects(fetchModelCatalog({ call: async () => ({ data: Array.from({ length: 201 }, (_, i) => rawModel(`model-${i}`)) }) }), { code: 'codex_unavailable' });
  await assert.rejects(fetchModelCatalog({ call: async () => ({ data: [rawModel()] }) }, { model: 'unavailable' }), { code: 'codex_unavailable' });
});

test('automatic selection preserves 1.7 low preference and exact explicit choices', () => {
  assert.deepEqual(selectModel(catalog), { model: 'first', effort: 'low' });
  assert.deepEqual(selectModel(catalog, { model: 'second' }), { model: 'second', effort: 'medium' });
  assert.deepEqual(selectModel(catalog, { effort: 'medium' }), { model: 'first', effort: 'medium' });
  assert.deepEqual(selectModel(catalog, { model: 'second', effort: 'high' }), { model: 'second', effort: 'high' });
  assert.throws(() => selectModel(catalog, { model: 'removed' }), { code: 'unsupported_model', status: 400 });
  assert.throws(() => selectModel(catalog, { model: 'second', effort: 'low' }), { code: 'unsupported_effort', status: 400 });
});

test('frozen choice overrides changing automatic default without fallback', () => {
  const frozen = { model: 'first', effort: 'low' };
  assert.deepEqual(selectModel({ ...catalog, defaultModel: 'second' }, {}, frozen), frozen);
  assert.throws(() => selectModel({ ...catalog, models: [catalog.models[1]] }, {}, frozen), { code: 'unsupported_model' });
});

test('request validates model and effort fields, keeps them in identity and continuation', async () => {
  for (const model of ['', null, 'a'.repeat(129), 'bad\nmodel']) assert.throws(() => validateInput(input({ model })), { code: 'unsupported_model' });
  for (const effort of ['', null, 'a'.repeat(33), 'bad effort']) assert.throws(() => validateInput(input({ effort })), { code: 'unsupported_effort' });
  const selections = [];
  const service = new CoachService({ answer: async (value, history, frozen) => {
    selections.push(frozen);
    return value.continuation ? { reply: 'answer', summary: '', model: 'second', effort: 'high' } : { historyRequest: { limit: 60 }, model: 'second', effort: 'high' };
  } });
  const first = await service.chat(input({ model: 'second', effort: 'high' }));
  await assert.rejects(service.chat(input({ model: 'second', effort: 'medium' })), { code: 'request_conflict' });
  await service.chat(input({ model: 'second', effort: 'high', continuation: first.continuation, historyResults: {} }));
  assert.deepEqual(selections, [undefined, { model: 'second', effort: 'high' }]);
});

test('model picker cache is short lived, deduplicates parallel loads and never serves expired data after failure', async () => {
  let now = 0;
  let calls = 0;
  let fail = false;
  const service = new CoachService({ models: async () => { calls++; if (fail) throw new Error('offline'); return catalog; } }, { now: () => now, modelsTtl: 10 });
  await Promise.all([service.models(), service.models()]);
  assert.equal(calls, 1);
  await service.models();
  assert.equal(calls, 1);
  now = 11;
  await service.models();
  assert.equal(calls, 2);
  now = 22;
  fail = true;
  await assert.rejects(service.models(), /offline/);
});

test('HTTP models require authentication and return the advertised contract', async (t) => {
  let calls = 0;
  const token = 'x'.repeat(43);
  const server = createRelay({ token }, { models: async () => { calls++; return catalog; } });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const url = `http://127.0.0.1:${server.address().port}/v1/models`;
  assert.equal((await fetch(url)).status, 401);
  assert.equal(calls, 0);
  const result = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  assert.deepEqual(await result.json(), catalog);
});

test('real stdio fake server receives exact paginated model and high effort; API key cannot list models', async (t) => {
  const home = await mkdtemp(join(tmpdir(), 'training-coach-model-test-'));
  t.after(() => removeHome(home));
  const config = { codexHome: home, codexCommand: process.execPath, codexArgs: [fileURLToPath(new URL('./fake-app-server.mjs', import.meta.url)), 'selection'], turnTimeoutMs: 1000 };
  const runner = codexRunner(config);
  const models = await runner.models();
  assert.deepEqual(models.models.map((model) => model.id), ['model-a', 'model-b']);
  const reply = await runner.answer(input({ model: 'model-b', effort: 'high' }));
  assert.equal(reply.model, 'model-b');
  assert.equal(reply.effort, 'high');
  await assert.rejects(runner.answer(input({ model: 'model-b', effort: 'low' })), { code: 'unsupported_effort' });
  const apiKeyRunner = codexRunner({ ...config, codexArgs: [config.codexArgs[0], 'apikey'] });
  await assert.rejects(apiKeyRunner.models(), { code: 'chatgpt_login_required' });
});

test('stable and old APK URLs return the configured new APK with correct filename', async (t) => {
  const home = await mkdtemp(join(tmpdir(), 'training-coach-model-test-'));
  const apkPath = join(home, 'Training-1.8.apk');
  await writeFile(apkPath, 'version-1.8-bytes');
  const server = createRelay({ token: 'x'.repeat(43), downloadToken: 'd'.repeat(43), apkPath }, {});
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); server.close(); await removeHome(home); });
  const base = `http://127.0.0.1:${server.address().port}/download/${'d'.repeat(43)}`;
  for (const name of ['Training-latest.apk', 'Training-1.7.apk', 'Training-1.8.apk']) {
    const response = await fetch(`${base}/${name}`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-disposition'), 'attachment; filename="Training-1.8.apk"');
    assert.equal(await response.text(), 'version-1.8-bytes');
  }
});
