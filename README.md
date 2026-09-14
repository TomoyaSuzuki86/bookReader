# BookReader for Meta Quest 3

Meta Quest 3でPDFを「右開きの見開き本」として読むためのMVPです。

## MVP
- 見開きの本を空中に表示
- 右ページが先、左ページが次のページ（右開き）
- 紙面を右へスワイプすると次の見開き、左へスワイプすると前の見開き
- ページジャンプ
- アカウントごとのPDFライブラリ
- スマホ/PCのWeb画面からPDFアップロード
- 読書位置をサーバーへ保存
- Quest 3向けAPKをGitHub Actionsで生成

## 構成
- `app/`: Meta Spatial SDK + Kotlin + Jetpack Compose
- `server/`: Node.js/Express。ログイン、PDFアップロード、PDF配信、読書位置保存

## APKビルド
GitHub Actionsの `Build Quest APK` が `bookreader-debug-apk` を生成します。

## サーバーURL
APKのビルド時にGradleプロパティ `BOOK_SERVER_URL` を指定します。未指定時は `https://bookreader.invalid` です。

例: `gradle :app:assembleDebug -PBOOK_SERVER_URL=https://reader.example.com`

## サーバー起動
```bash
cd server
npm install
JWT_SECRET=change-me npm start
```

PDFとDBは `server/data/` に保存されます。本番では永続ボリュームを割り当ててください。
