# 筋トレ AI コーチ中継

既存 Android アプリの AI チャット専用の中継です。Node.js 22 以降の標準ライブラリだけを使い、OpenAI API キーや従量課金 API は使用しません。Codex CLI の App Server を標準入出力で起動し、`account/read` が `chatgpt` の場合だけ質問を送ります。ChatGPT 契約の Codex 利用枠が消費されます。

## 起動

PC の設定・起動・Tailscale 接続は同梱の Windows セットアップを使ってください。中継単体は `node coach-relay/server.mjs` で起動します。設定ファイルは `coach-relay/.runtime/relay-config.json`（`COACH_CONFIG` 環境変数で変更可能）。秘密の接続情報を含むため共有しないでください。

```json
{
  "host": "127.0.0.1",
  "port": 8765,
  "token": "32文字以上の暗号学的乱数を設定",
  "codexHome": "C:\\absolute\\専用のCodex保存先",
  "codexCommand": "C:\\absolute\\codex.exe",
  "apkPath": "C:\\absolute\\Training-1.8.apk",
  "downloadToken": "接続tokenとは別の32文字以上の乱数",
  "pairingToken": "上の二つとは別の32文字以上の乱数",
  "publicUrl": "https://computer.tail123.ts.net:8765",
  "allowedTailscaleLogin": "owner@example.com"
}
```

設定ファイルの `model` は省略すると、ChatGPT アカウントの `model/list` が返す既定モデルを選びます。設定する場合は同リスト中の正規モデル名を使います。Android で選んだモデルが優先されます。モデルが見つからない場合は処理を止め、別のモデルやプロバイダーへ切り替えません。`CODEX_BIN` 環境変数は `codexCommand` を上書きします。Codex CLI 0.155.1 の生成済みスキーマでプロトコルを確認しています。

Codex の専用保存先には認証情報だけを置いてください。通常の `~/.codex/config.toml`、ユーザースキル、MCP やプラグイン設定をコピーしません。CLI が自動で配置する `skills/.system` は許容します。専用の保存先を指定して ChatGPT でログインしてください。API キーのログインでは動きません。

中継は `127.0.0.1:8765` などのループバックだけで待ち受け、Tailscale Serve の HTTPS リバースプロキシを通して接続します。セットアップは Tailscale の DNS 名から `https://…ts.net:8765` を作り、PC の所有者と同じ `Tailscale-User-Login` の要求だけを許可します。接続キーも引き続き必要です。HTTP のリモート接続、TCP 転送、Funnel、ルーターのポート開放は使用しません。[Tailscale Serve の本人識別ヘッダー](https://tailscale.com/docs/features/tailscale-serve#identity-headers)

旧版の `http://100.…:8765` 設定はアプリ内に保持しますが、その接続では送信できません。PC で `scripts/Setup-Coach.ps1` を実行し、新しい HTTPS URL をアプリへ設定してください。既存の接続キーは再利用します。スクリプトは中継の既知の TCP 転送だけを置き換え、別サービスが同じポートを使っている場合は停止します。HTTPS の有効化が必要な場合は Tailscale の案内を完了して再実行してください。実行前の検証には、外部接続しない `scripts/Test-CoachTailscale.ps1` を使えます。

## Android との通信

すべて JSON / UTF-8。`GET /health`、`GET /v1/models`、`POST /v1/chat` に `Authorization: Bearer <token>` が必要です。通常ブラウザからの Origin 付き要求は拒否します。

```json
{
  "requestId": "UUIDなど8から100文字の英数字とハイフン",
  "message": "この1か月のベンチの伸びは？",
  "context": {},
  "recentMessages": [{"role": "user", "content": "ベンチについて相談したい"}],
  "summary": "過去の会話の短い要約"
}
```

回答は `{"reply":"回答", "summary":"更新した短い要約", "model":"使用モデル", "effort":"使用推論レベル"}`。要約と直近会話は Android 側で保存します。PC には筋トレ DB を作りません。新規質問ごとに新しい一時 Codex スレッドを作り、完了したら App Server プロセスを終了するため、Codex スレッドの履歴が使うほど積み上がる構成にはなりません。

### モデルと推論レベル

`GET /v1/models` は ChatGPT 認証を確認して App Server の `model/list` をページ末尾まで読み、次の形式で返します。

```json
{
  "models": [{
    "id": "正規モデル名",
    "displayName": "表示名",
    "defaultReasoningEffort": "medium",
    "supportedReasoningEfforts": ["low", "medium", "high"],
    "isDefault": true
  }],
  "defaultModel": "正規モデル名",
  "defaultReasoningEffort": "low"
}
```

推論レベルは現在のモデル一覧に載っている値だけを選べます。固定のモデル一覧をアプリに埋め込みません。一覧は最大 200 モデル、最大 10 ページで打ち切り、異常なページ循環はエラーにします。中継は一覧を 60 秒キャッシュし、並行する一覧取得をまとめます。回答の生成前には改めて最新の一覧で検証します。

`POST /v1/chat` に任意の `model`（128 文字以内）、`effort`（32 文字以内）を追加できます。省略した場合は自動選択です。モデルだけ選んだ場合、そのモデルで `low` が利用できれば `low`、できなければモデルが通知した既定レベルを使います。モデルを省略して推論レベルだけ指定することもできます。従来の 1.7 アプリからの両方省略も同じ自動選択で動きます。

明示したモデルやレベルが利用できない場合は HTTP 400 の `unsupported_model` / `unsupported_effort` で停止します。勝手に別の選択へ変更しません。同じ質問の再送と追加履歴には同じ選択を付けてください。自動選択で実際に決まったモデルとレベルも、追加履歴の回答が完了するまで固定します。変更したい場合は新しい `requestId` の質問として送ります。

追加の過去履歴が必要なときだけ、`{"historyRequest":{"exercise":"ベンチプレス","from":"2026-08-01","to":"2026-08-31","limit":60},"continuation":"opaque-string"}` を返します。Android は手元の DB から読み取り専用で集計・抽出し、同じ `requestId`・質問・context・recentMessages・summary に `continuation` と `historyResults`（JSON object または array）を加えて再送します。追加取得は 1 回、最大 60 行です。取得結果が省略されている場合、AI は `recordsTruncated` / `omittedExerciseCount` 等を踏まえて回答します。

入力上限は質問 4000 文字、最近の会話 12 件かつ計 18000 文字、要約 6000 文字、context JSON 80000 文字、historyResults JSON 60000 文字、HTTP 本文 320 KiB。日付は実在する `YYYY-MM-DD`、種目名は 80 文字以内です。モデルには原則 3000 文字以内の要約を指示します。

同じ requestId・同じ入力は処理中も完了後も重複呼び出しを避けます。回答キャッシュはメモリー上の最大 64 質問、30 分です。PC 再起動をまたぐ重複防止はありません。`continuation_expired` が来たら追加情報を外し、同じ質問を最初から再送できます。通信失敗の明示的な再試行ではモデルが再度処理する可能性があります。チャット生成は同時 1 件。生成時間の上限は none / minimal / low / medium が 180 秒、high / xhigh / max / ultra などそれ以外が 600 秒です。

エラーは `{"error":{"code":"…","message":"日本語の説明"}}`。主な code は `unauthorized`, `invalid_request`, `unsupported_model`, `unsupported_effort`, `payload_too_large`, `request_conflict`, `continuation_expired`, `busy`, `chatgpt_login_required`, `codex_unavailable`, `codex_timeout`, `usage_limit`, `codex_error`, `invalid_response` です。

## APK と接続設定ページ

`GET /setup/<pairingToken>` はスマホ用の接続設定ページです。接続 JSON `{"url":"…","token":"…"}` をコピーでき、APK をダウンロードできます。この URL 自体が接続情報を渡す秘密のリンクです。

`GET /download/<downloadToken>/Training-latest.apk` は設定した 1 個の APK だけを返します。実際のファイル名（例 `Training-1.8.apk`）の URL と従来の `Training-1.7.apk` リンクも同じ更新版を返し、ダウンロード時のファイル名は実際の APK 名になります。ディレクトリ一覧、任意パス、アップロードはありません。ダウンロードリンクを知ってもチャット用 token は取得できません。どちらのページもキャッシュ禁止・リファラー送信禁止です。スマホで APK を開いて「更新」します。中継は Android のアプリ ID・署名・DB を操作しません。

## 分離とデータ保護

App Server のネットワーク待受ポートは作りません。子プロセスには API キー、relay token、Codex デスクトップ接続変数等を引き継ぎません。ChatGPT 認証と OpenAI provider を固定し、shell・各種アプリ・ブラウザ・画像生成・プラグイン・MCP 継承・サブエージェント・メモリー・フックを無効にします。環境アクセスも空にし、一時スレッド・承認不可・読み取り専用で実行します。想定外のツール要求や承認要求は中断します。

個人データは与えたトレーニング JSON と会話に限り、筋トレデータの記録・変更は Android の既存機能が担当します。送信データは ChatGPT/Codex サービスに渡るため、PC 内だけのローカル推論ではありません。中継自身は質問や token をログに書きません。Codex CLI が作る認証・キャッシュ・診断 DB は専用保存先に残り得ます。

## PC 中継のネット検索

PC 中継は記録と会話を含むため、ネット検索を無効化しています。`web_search="disabled"` とコード実行機構の無効化を固定し、予期しない検索・外部操作のイベントも中断します。最新情報を調べたと偽らず、検索できない旨を回答するよう指示しています。スマホから直接 ChatGPT に接続する方式とは別の制限です。

## 検証

```powershell
cd coach-relay
npm test
```

実サブプロセスを使う模擬 App Server テストで、ChatGPT 以外の認証の拒否、入力制限、追加履歴、二重送信、タイムアウト、想定外ツール拒否、HTTP 認証、公開 IP 禁止を検証します。モデル一覧のページ取得・キャッシュ・未対応の選択の拒否・推論レベルの送信・追加履歴中の選択固定・APK の更新後のファイル名も検証します。検索イベント・承認要求なしのコマンド実行の拒否、HTTPS と本人識別の確認を含め29件です。`scripts/Test-CoachTailscale.ps1` は既存サービスを変更しない合成設定で HTTPS 移行条件11件を検証します。実際の ChatGPT 接続は専用保存先のログイン後に別途確認します。

公式資料: [Codex App Server](https://learn.chatgpt.com/docs/app-server)、[Codex 設定例](https://learn.chatgpt.com/docs/config-file/config-sample)。
