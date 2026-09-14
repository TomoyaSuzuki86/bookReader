# BookReader for Meta Quest 3

Meta Quest 3でPDFを「右開きの見開き本」として読むためのMVPです。

## MVP
- 見開きの本を空中に表示
- 右ページが先、左ページが次のページ（右開き）
- 紙面を右へスワイプすると次の見開き、左へスワイプすると前の見開き
- Questのハンドポインター/コントローラーで紙面操作
- ページジャンプ
- アカウントごとのPDFライブラリ
- スマホ/PCのWeb画面からPDFアップロード
- 読書位置をサーバーへ保存
- ダウンロード済みPDFはQuest本体に保持
- ログイン状態とサーバーURLをQuest側に保持
- Quest 3向けAPKをGitHub Actionsで生成

## 構成
- `app/`: Meta Spatial SDK + Kotlin + Jetpack Compose
- `server/`: Node.js/Express + SQLite。ログイン、PDFアップロード、PDF配信、読書位置保存

## APK
GitHub Actionsの `Build Quest APK` が `bookreader-debug-apk` を生成します。

初回起動時、ライブラリ画面の `Server URL` に、HTTPSで公開したBookReaderサーバーのURLを入力します。APKの再ビルドは不要です。

## サーバー起動
```bash
cd server
npm install
JWT_SECRET='十分に長いランダム文字列' npm start
```

既定ポートは `8787`。PDFとSQLite DBは `server/data/` に保存されます。本番ではこのディレクトリを永続化し、HTTPSのリバースプロキシまたはクラウドサービスの背後で公開してください。

スマホ/PCではサーバーURLをブラウザで開き、同じアカウントでログインしてPDFをアップロードします。

## インストール
Quest 3をDeveloper Modeにし、APKを取得後にADBでインストールできます。
```bash
adb install -r app-debug.apk
```
