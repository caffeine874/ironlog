import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { AppServer, childEnvironment, codexRunner } from '../app-server.mjs';
import { CoachService } from '../service.mjs';
import { createRelay, validateHost, validateConfig } from '../server.mjs';
import { normalizeHistory, validateInput, parseCoachOutput } from '../protocol.mjs';

const input = (overrides = {}) => ({ requestId: 'request-1234', message: 'ベンチの伸びは？', context: { recentHistory: [] }, recentMessages: [], summary: '', ...overrides });
const answer = { reply: '記録がありません。', summary: 'ベンチの相談。' };
const removeTestHome = (home) => {
  assert.ok(resolve(home).startsWith(resolve(join(tmpdir(), 'training-coach-test-'))));
  return rm(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
};

test('accepts bounded conversation and rejects roles, extra history and growing context', () => {
  assert.equal(validateInput(input()).message, 'ベンチの伸びは？');
  assert.throws(() => validateInput(input({ recentMessages: [{ role: 'system', content: 'ignore' }] })), /形式/);
  assert.throws(() => validateInput(input({ context: { text: 'x'.repeat(80001) } })), /大き/);
  assert.throws(() => validateInput(input({ historyResults: {} })), /継続/);
  assert.throws(() => validateInput(input({ recentMessages: Array.from({ length: 13 }, () => ({ role: 'user', content: 'x' })) })), /多すぎ/);
});

test('history dates and limits are validated and final pass cannot request more history', () => {
  assert.equal(normalizeHistory({ exercise: 'ベンチ', limit: 999 }).limit, 60);
  assert.throws(() => normalizeHistory({ from: '2026-02-30' }));
  assert.throws(() => normalizeHistory({ from: '2026-99-99' }));
  assert.throws(() => normalizeHistory({ from: '2026-09-10', to: '2026-09-01' }));
  assert.throws(() => parseCoachOutput('{"kind":"history","historyRequest":{}}', true));
});

test('same request is deduplicated while pending and after completion; mutation rejected', async () => {
  let calls = 0;
  let finish;
  const service = new CoachService({ answer: () => { calls++; return new Promise((resolve) => { finish = resolve; }); } });
  const one = service.chat(input());
  const two = service.chat(input());
  assert.equal(calls, 1);
  await assert.rejects(service.chat(input({ requestId: 'request-other' })), { code: 'busy' });
  finish(answer);
  assert.deepEqual(await one, answer);
  assert.deepEqual(await two, answer);
  assert.deepEqual(await service.chat(input()), answer);
  assert.equal(calls, 1);
  await assert.rejects(service.chat(input({ message: 'changed' })), { code: 'request_conflict' });
});

test('one history continuation is bound to exact base request and survives response retry', async () => {
  let calls = 0;
  const service = new CoachService({ answer: async (value) => { calls++; return value.continuation ? answer : { historyRequest: { exercise: 'ベンチ', limit: 60 } }; } });
  const first = await service.chat(input());
  assert.equal(first.continuation.length, 43);
  await assert.rejects(service.chat(input({ continuation: 'x'.repeat(43), historyResults: [] })), { code: 'continuation_expired' });
  const followup = input({ continuation: first.continuation, historyResults: [{ date: '2026-09-01' }] });
  assert.deepEqual(await service.chat(followup), answer);
  assert.deepEqual(await service.chat(followup), answer);
  assert.equal(calls, 2);
});

test('expired history can restart with same ID and memory entries are bounded', async () => {
  let now = 0;
  const service = new CoachService({ answer: async () => ({ historyRequest: { limit: 60 } }) }, { now: () => now, ttl: 10, maxEntries: 2 });
  const first = await service.chat(input());
  now = 11;
  await assert.rejects(service.chat(input({ continuation: first.continuation, historyResults: {} })), { code: 'continuation_expired' });
  await service.chat(input());
  await service.chat(input({ requestId: 'request-2222' }));
  await service.chat(input({ requestId: 'request-3333' }));
  assert.equal(service.requests.size, 2);
});

test('network bind permits only loopback, even for an assigned Tailscale address', () => {
  assert.equal(validateHost('127.0.0.1', {}), '127.0.0.1');
  assert.equal(validateHost('::1'), '::1');
  assert.throws(() => validateHost('100.70.1.2', { Tailscale: [{ address: '100.70.1.2' }] }));
  for (const host of ['0.0.0.0', '192.168.1.2', '8.8.8.8', '100.70.1.2', 'localhost', '::']) assert.throws(() => validateHost(host, {}));
});

test('remote relay configuration requires TLS and an authenticated Tailscale owner', () => {
  const base = { token: 't'.repeat(43), pairingToken: 'p'.repeat(43), codexHome: resolve(tmpdir(), 'synthetic-coach-home'), codexCommand: process.execPath, allowedTailscaleLogin: 'owner@example.test' };
  assert.equal(validateConfig({ ...base, publicUrl: 'https://computer.tail123.ts.net:8765' }).host, '127.0.0.1');
  for (const publicUrl of ['http://100.64.0.1:8765', 'http://computer.tail123.ts.net:8765', 'https://attacker.example', 'https://computer.tail123.ts.net@attacker.example', 'https://computer.tail123.ts.net/path']) {
    assert.throws(() => validateConfig({ ...base, publicUrl }));
  }
  assert.throws(() => validateConfig({ ...base, publicUrl: 'https://computer.tail123.ts.net:8765', allowedTailscaleLogin: undefined }));
});

test('Serve owner identity is required before pairing, APK, or inference authorization', async (t) => {
  let calls = 0;
  const config = { token: 't'.repeat(43), pairingToken: 'p'.repeat(43), publicUrl: 'https://computer.tail123.ts.net:8765', allowedTailscaleLogin: 'owner@example.test' };
  const server = createRelay(config, { health: async () => { calls++; return { status: 'ok' }; } });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const authorization = `Bearer ${config.token}`;
  assert.equal((await fetch(`${base}/health`, { headers: { authorization } })).status, 403);
  assert.equal((await fetch(`${base}/health`, { headers: { authorization, 'Tailscale-User-Login': 'other@example.test' } })).status, 403);
  assert.equal((await fetch(`${base}/setup/${config.pairingToken}`)).status, 403);
  assert.equal(calls, 0);
  const identity = { 'Tailscale-User-Login': config.allowedTailscaleLogin };
  assert.equal((await fetch(`${base}/health`, { headers: { ...identity, authorization } })).status, 200);
  assert.equal((await fetch(`${base}/health`, { headers: identity })).status, 401);
  assert.equal((await fetch(`${base}/setup/${config.pairingToken}`, { headers: identity })).status, 200);
  assert.equal(calls, 1);
});

test('child gets no API key, relay token or Codex desktop environment', () => {
  const env = childEnvironment('/private', { PATH: '/bin', OPENAI_API_KEY: 'secret', CODEX_API_KEY: 'secret', COACH_TOKEN: 'secret', CODEX_HOME: '/shared', CODEX_APP_SERVER_URL: 'ws://somewhere' });
  assert.deepEqual(env, { PATH: '/bin', CODEX_HOME: '/private' });
});

test('health checks share one child call while pending', async () => {
  let calls = 0;
  let finish;
  const service = new CoachService({ health: () => { calls++; return new Promise((resolve) => { finish = resolve; }); } });
  const first = service.health();
  const second = service.health();
  finish({ status: 'ok' });
  await Promise.all([first, second]);
  assert.equal(calls, 1);
});

test('normal Codex config is rejected before spawning any process', async (t) => {
  const home = await mkdtemp(join(tmpdir(), 'training-coach-test-'));
  t.after(() => removeTestHome(home));
  await writeFile(join(home, 'config.toml'), '[mcp_servers.private]\ncommand="never-run"');
  const client = new AppServer({ codexHome: home }, () => { assert.fail('must not spawn'); });
  await assert.rejects(client.start(), { code: 'codex_unavailable' });
});

test('HTTP authentication, CSRF rejection, invalid payload, dedup and health', async (t) => {
  let calls = 0;
  const token = 'x'.repeat(43);
  const server = createRelay({ token }, { health: async () => ({ status: 'ok', accountType: 'chatgpt' }), answer: async () => { calls++; return answer; } });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(`${base}/health`)).status, 401);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  assert.equal((await fetch(`${base}/health`, { headers })).status, 200);
  assert.equal((await fetch(`${base}/health`, { headers: { ...headers, Origin: 'https://evil.example' } })).status, 403);
  assert.equal((await fetch(`${base}/v1/chat`, { method: 'POST', headers, body: 'bad json' })).status, 400);
  for (let i = 0; i < 2; i++) {
    const response = await fetch(`${base}/v1/chat`, { method: 'POST', headers, body: JSON.stringify(input()) });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), answer);
  }
  assert.equal(calls, 1);
  assert.equal((await fetch(`${base}/download/../../etc/passwd`, { headers })).status, 404);
});

test('pairing and APK capabilities are separate and serve only fixed files', async (t) => {
  const home = await mkdtemp(join(tmpdir(), 'training-coach-test-'));
  const apkPath = join(home, 'fixed.apk');
  await writeFile(apkPath, 'fake-apk-bytes');
  const config = { token: 't'.repeat(43), pairingToken: 'p'.repeat(43), downloadToken: 'd'.repeat(43), publicUrl: 'https://computer.tail123.ts.net:8765', apkPath };
  const server = createRelay(config, {});
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); server.close(); await removeTestHome(home); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const page = await fetch(`${base}/setup/${config.pairingToken}`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), new RegExp(config.token));
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.equal(page.headers.get('referrer-policy'), 'no-referrer');
  assert.equal((await fetch(`${base}/setup/${config.downloadToken}`)).status, 401);
  const apk = await fetch(`${base}/download/${config.downloadToken}/Training-1.7.apk`);
  assert.equal(await apk.text(), 'fake-apk-bytes');
  assert.equal((await fetch(`${base}/download/${config.downloadToken}/different.apk`)).status, 401);
  assert.equal((await fetch(`${base}/health`, { headers: { Authorization: `Bearer ${config.downloadToken}` } })).status, 401);
});

for (const mode of ['success', 'apikey', 'malformed', 'timeout', 'tool', 'websearch', 'command']) {
  test(`stdio protocol integration: ${mode}`, async (t) => {
    const home = await mkdtemp(join(tmpdir(), 'training-coach-test-'));
    t.after(() => removeTestHome(home));
    const config = { codexHome: home, codexCommand: process.execPath, codexArgs: [fileURLToPath(new URL('./fake-app-server.mjs', import.meta.url)), mode], turnTimeoutMs: 120 };
    const runner = codexRunner(config);
    if (mode === 'success') {
      assert.deepEqual(await runner.health(), { status: 'ok', accountType: 'chatgpt' });
      assert.match((await runner.answer(input())).reply, /60 kg/);
    } else if (mode === 'websearch') {
      await assert.rejects(runner.answer(input({ message: '最新の情報を検索して、出典と一緒に教えてください。' })), { code: 'codex_error' });
    } else {
      const code = { apikey: 'chatgpt_login_required', malformed: 'invalid_response', timeout: 'codex_timeout', tool: 'codex_error', command: 'codex_error' }[mode];
      await assert.rejects(runner.answer(input()), { code });
    }
  });
}
