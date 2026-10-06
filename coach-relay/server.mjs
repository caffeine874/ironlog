import http from 'node:http';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { resolve, isAbsolute, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isIP } from 'node:net';
import { codexRunner } from './app-server.mjs';
import { CoachService } from './service.mjs';
import { RelayError, publicError } from './errors.mjs';
import { LIMITS, secretEqual } from './protocol.mjs';

export function validateHost(host) {
  if (host === '127.0.0.1' || host === '::1') return host;
  throw new Error('Listen address must be loopback. Use authenticated Tailscale HTTPS Serve for remote access.');
}

export function validateConfig(raw) {
  if (!raw || typeof raw !== 'object') throw new Error('Relay config is required.');
  if (typeof raw.token !== 'string' || !/^[A-Za-z0-9_-]{32,200}$/.test(raw.token)) throw new Error('A random relay token of at least 32 characters is required.');
  if (typeof raw.codexHome !== 'string' || !isAbsolute(raw.codexHome)) throw new Error('An absolute dedicated codexHome is required.');
  const codexCommand = process.env.CODEX_BIN || raw.codexCommand;
  if (typeof codexCommand !== 'string' || !isAbsolute(codexCommand)) throw new Error('An absolute codexCommand / CODEX_BIN executable path is required.');
  if (raw.codexArgs && (!Array.isArray(raw.codexArgs) || raw.codexArgs.some((value) => typeof value !== 'string'))) throw new Error('codexArgs must be a string array.');
  const port = raw.port ?? 8765;
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Port must be between 1024 and 65535.');
  if (raw.apkPath && (!isAbsolute(raw.apkPath) || !raw.apkPath.toLowerCase().endsWith('.apk'))) throw new Error('apkPath must be one absolute APK file path.');
  if (raw.apkPath && (typeof raw.downloadToken !== 'string' || !/^[A-Za-z0-9_-]{32,200}$/.test(raw.downloadToken) || raw.downloadToken === raw.token)) throw new Error('A separate random downloadToken is required.');
  if (raw.pairingToken && (!/^[A-Za-z0-9_-]{32,200}$/.test(raw.pairingToken) || [raw.token, raw.downloadToken].includes(raw.pairingToken))) throw new Error('pairingToken must be a separate random secret.');
  if (raw.allowedTailscaleLogin !== undefined && (typeof raw.allowedTailscaleLogin !== 'string' || !/^[\x21-\x7e]{1,254}$/.test(raw.allowedTailscaleLogin))) throw new Error('A single Tailscale user login is required.');
  if (raw.pairingToken || (raw.publicUrl && !/^http:\/\/(127\.0\.0\.1|localhost|\[::1\])(?::\d+)?\/?$/.test(raw.publicUrl))) {
    const target = new URL(raw.publicUrl);
    const octets = target.hostname.split('.').map(Number);
    const tailIp = isIP(target.hostname) === 4 && octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127;
    if (target.username || target.password || target.search || target.hash || target.pathname !== '/' || target.protocol !== 'https:' || !(tailIp || target.hostname.endsWith('.ts.net'))) throw new Error('publicUrl must be a private Tailscale HTTPS URL.');
    if (!raw.allowedTailscaleLogin) throw new Error('Remote access requires the owner Tailscale user login. Run Setup-Coach.ps1 again.');
  }
  return { ...raw, host: validateHost(raw.host ?? '127.0.0.1'), port, codexCommand };
}

function setupHtml(config) {
  const escape = (text) => String(text).replace(/[&<>"']/g, (c) => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
  const pairing = escape(JSON.stringify({ url: config.publicUrl.replace(/\/$/, ''), token: config.token }));
  const apk = config.apkPath ? `<p><a class="button" href="/download/${config.downloadToken}/Training-latest.apk">更新用 APK をダウンロード</a></p><p>今の筋トレアプリを残したまま APK を開き、「更新」を選びます。アンインストールしないでください。</p>` : '';
  return `<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>筋トレ AI コーチの接続</title><style>body{font:18px/1.7 system-ui,sans-serif;max-width:680px;margin:32px auto;padding:0 22px;background:#f5f8fc;color:#152438}h1{font-size:28px}textarea{box-sizing:border-box;width:100%;min-height:150px;font:16px/1.5 monospace;padding:14px}button,.button{display:inline-block;font:inherit;background:#1756c7;color:white;padding:13px 20px;border:0;border-radius:10px;text-decoration:none}#result{min-height:32px}</style><h1>筋トレ AI コーチの接続</h1>${apk}<p>アプリの「AIコーチ」→「接続設定」を開きます。「接続情報を貼り付け」欄へ貼り付け、「接続情報を読み込む」→「接続を確認する」→「保存」の順に押してください。</p><textarea id="pairing" readonly aria-label="接続情報">${pairing}</textarea><p><button id="copy">接続情報をコピー</button></p><p id="result" role="status"></p><p>このページの URL と接続情報は、自分のスマホだけで使用してください。</p><script>document.getElementById('copy').onclick=async()=>{const t=document.getElementById('pairing');t.focus();t.select();let ok=false;try{if(navigator.clipboard){await navigator.clipboard.writeText(t.value);ok=true}else{ok=document.execCommand('copy')}}catch{}document.getElementById('result').textContent=ok?'コピーしました。筋トレアプリの接続設定へ貼り付けてください。':'接続情報を長押ししてコピーしてください。'};</script></html>`;
}

function json(response, status, body) {
  const data = JSON.stringify(body);
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(data), 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
  response.end(data);
}

async function readBody(request) {
  if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] ?? '')) throw new RelayError('invalid_request', 'JSON 形式で送信してください。', 415);
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > LIMITS.bodyBytes) throw new RelayError('payload_too_large', '送信データが大きすぎます。', 413);
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new RelayError('invalid_request', 'JSON を読み取れませんでした。', 400); }
}

export function createRelay(config, runner = codexRunner(config)) {
  const service = new CoachService(runner);
  const configuredName = config.apkPath ? basename(config.apkPath) : '';
  const downloadName = /^[A-Za-z0-9._-]+\.apk$/i.test(configuredName) ? configuredName : 'Training-latest.apk';
  const downloadRoutes = new Set(['Training-latest.apk', 'Training-1.7.apk', downloadName].map((name) => `/download/${config.downloadToken}/${encodeURIComponent(name)}`));
  const server = http.createServer({ requestTimeout: 20000, headersTimeout: 10000, maxHeaderSize: 8192 }, async (request, response) => {
    try {
      // Serve strips user-supplied identity headers and sets the authenticated
      // identity. Only trust this header behind a loopback-bound reverse proxy.
      if (config.allowedTailscaleLogin && (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(request.socket.remoteAddress) ||
          request.headers['tailscale-user-login'] !== config.allowedTailscaleLogin)) {
        throw new RelayError('forbidden', '許可された Tailscale アカウントで接続してください。', 403);
      }
      // Browser requests cannot use a paired phone token through an arbitrary site.
      if (request.headers.origin) throw new RelayError('forbidden', 'ブラウザからの接続は許可されていません。', 403);
      if (config.pairingToken && request.url === `/setup/${config.pairingToken}` && request.method === 'GET') {
        const html = setupHtml(config);
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': Buffer.byteLength(html), 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'Content-Security-Policy': "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" });
        response.end(html);
        return;
      }
      if (downloadRoutes.has(request.url) && config.apkPath && ['GET', 'HEAD'].includes(request.method)) {
        const file = await stat(config.apkPath);
        if (!file.isFile()) throw new RelayError('not_found', '更新用 APK が見つかりません。', 404);
        response.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': file.size, 'Content-Disposition': `attachment; filename="${downloadName}"`, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
        if (request.method === 'HEAD') response.end();
        else createReadStream(config.apkPath).on('error', () => response.destroy()).pipe(response);
        return;
      }
      if (!secretEqual(request.headers.authorization, `Bearer ${config.token}`)) throw new RelayError('unauthorized', '接続コードが一致しません。AI コーチの接続設定を確認してください。', 401);
      if (request.method === 'GET' && request.url === '/health') { json(response, 200, await service.health()); return; }
      if (request.method === 'GET' && request.url === '/v1/models') { json(response, 200, await service.models()); return; }
      if (request.method === 'POST' && request.url === '/v1/chat') { json(response, 200, await service.chat(await readBody(request))); return; }
      throw new RelayError('not_found', '接続先が見つかりません。', 404);
    } catch (error) {
      const safe = publicError(error);
      if (!response.headersSent && !response.destroyed) json(response, safe.status, { error: { code: safe.code, message: safe.message } });
    }
  });
  server.maxConnections = 12;
  server.keepAliveTimeout = 5000;
  return server;
}

export async function main() {
  const defaultPath = fileURLToPath(new URL('./.runtime/relay-config.json', import.meta.url));
  const config = validateConfig(JSON.parse((await readFile(process.env.COACH_CONFIG || defaultPath, 'utf8')).replace(/^\uFEFF/, '')));
  const server = createRelay(config);
  server.on('error', () => { process.stderr.write('AI コーチ中継を起動できません。ポートと Tailscale の状態を確認してください。\n'); process.exitCode = 1; });
  server.listen(config.port, config.host, () => process.stdout.write(`AI コーチ中継を起動しました (${config.host}:${config.port})。\n`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.close(); server.closeAllConnections(); });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => { process.stderr.write('AI コーチの設定を読み込めません。PC セットアップを実行してください。\n'); process.exitCode = 1; });
}
