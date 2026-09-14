# BookReader for Meta Quest 3

Meta Quest 3でPDFを「右開きの見開き本」として読むためのPDFリーダーです。

## v0.3.0
- スマホ/PCから複数PDFをアップロード
- Questの本棚は約5秒間隔で自動同期
- ダウンロード済みPDFはQuest本体に保持
- 読書位置をアカウントと端末の両方に保存
- 見開き全体を1つの空間オブジェクトとして掴んで移動
- パススルー空間に本を表示
- 24分割した紙面セグメントによる3Dページカール
- 次/前のページを先読みし、めくった紙の裏面と下のページも実ページで表示
- ページジャンプ

## クラウド本棚
公開サーバー: `https://bookreader-quest.onrender.com`

QuestアプリはこのURLを既定値として持つため、通常はServer URLの入力は不要です。同じアカウントでスマホ/PCとQuestへログインします。

### 永続化
サーバーは2モードで動作します。

1. `SUPABASE_URL` と `SUPABASE_SERVICE_ROLE_KEY` が設定されている場合
   - PDF本体をprivate Supabase Storage bucketへ保存
   - SQLite DBの整合性の取れたスナップショットをStorageへバックアップ
   - Renderの再起動/再デプロイ時にDBを自動復元
   - アカウント、本棚、読書位置、PDF本体を永続化

2. 未設定の場合
   - `DATA_DIR` のローカルファイルシステムを使用
   - ローカル開発向け。Render無料サービスでは永続性を保証しない

環境変数:
- `JWT_SECRET`
- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `SUPABASE_BUCKET` (省略時 `bookreader`)
- `DATA_DIR`

## 構成
- `app/`: Meta Spatial SDK + Kotlin + Jetpack Compose
- `server/`: Node.js/Express + SQLite + durable object storage adapter
- `server/public/`: スマホ/PC向けPDFアップロード画面

## APK
GitHub Actionsの `Build Quest APK` がPRとmainの両方でサーバースモークテストとAPKビルドを実行し、`bookreader-debug-apk` を生成します。

## ローカルサーバー
```bash
cd server
npm install
JWT_SECRET='十分に長いランダム文字列' npm start
```

既定ポートは `8787` です。

## インストール
Quest 3をDeveloper Modeにし、APKをADBでインストールできます。
```bash
adb install -r app-debug.apk
```
