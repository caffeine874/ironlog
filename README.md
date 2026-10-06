# IRON LOG

Android 向けの筋トレ記録アプリです。Kotlin / Jetpack Compose で実装し、記録・グラフ・履歴・手動タイマー・AI コーチをまとめています。

## AI コーチ

「Continue with ChatGPT」からスマホのブラウザで認証し、アプリに戻って利用する方式を追加しています。通常の利用に PC の起動や中継サーバーは必要ありません。従来の PC 中継も任意の接続方式として残しています。

ChatGPT ログイン後、利用資格と利用上限は OpenAI 側で判定されます。ソースを公開するだけで、すべてのアカウントで利用できることを保証するものではありません。実際のアカウントによるログイン完了と回答生成は、公開時点では未確認です。

利用手順とデータの扱いは [AI コーチの使い方](AIコーチの使い方.md) を参照してください。従来の PC 中継については [coach-relay](coach-relay/README.md) に記載しています。

Web 検索は質問ごとに明示的に選択します。検索工程には今回の質問文だけを渡し、筋トレ記録や過去の会話を含めません。記録と検索結果を使う回答工程は外部ツールを無効にしています。OAuth トークンは Android Keystore で暗号化してバックアップ対象外の端末領域に保存します。

実装は OpenAI の [登録・ログイン](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)、[モデル・推論](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[プレビューの制約](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)に基づきます。

## ビルド

- JDK 17
- Android SDK 35
- 同梱の Gradle Wrapper

Android Studio でこのフォルダーを開くか、SDK の場所をローカルの `local.properties` / `ANDROID_HOME` に設定して実行します。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS / Linux では `bash ./gradlew testDebugUnitTest assembleDebug` を使用します。出力は `app/build/outputs/apk/debug/app-debug.apk` です。

PC 中継のテストは Node.js 22 以降で実行できます。外部 npm パッケージのインストールは不要です。

```powershell
cd coach-relay
npm test
```

GitHub Actions はビルドとテストを実行します。個人の署名鍵を使用する配布用 APK の公開は行いません。

## 既存アプリの更新

アプリ ID は `com.example.training`、バージョンは 1.12（versionCode 13）です。既存の筋トレ記録 DB を維持します。

すでにインストールしたアプリを更新する場合は、同じ署名鍵を使用してください。Gradle の `-PironlogDebugKeystore=鍵の絶対パス`、または `IRONLOG_DEBUG_KEYSTORE` 環境変数で指定できます。指定がない Windows 環境では `%USERPROFILE%/.android/debug.keystore` を使用します。他の環境ではユーザーのホームにある `.android/debug.keystore` を使用します。

別の開発環境や CI が生成した鍵では既存アプリを更新できません。更新できない場合も、既存アプリのアンインストールやデータ消去は行わないでください。

## 公開するファイル

ソース、テスト、ビルド設定、説明書を公開します。ログイン情報、接続キー、署名鍵、個人の筋トレ DB、スクリーンショット、QR コード、ローカルの配布成果物は含めません。

## ライセンス

[MIT License](LICENSE)。AndroidX、Kotlin、Gradle などの依存物は、それぞれのライセンスに従います。
