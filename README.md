# BookReader for Meta Quest 3

Meta Quest 3でPDFを「右開きの見開き本」として読むためのSpatial SDKアプリです。

## 0.2.0
- 見開きの本を現実空間（Passthrough）に表示
- 本全体を掴んで、好きな位置へ移動できる構造
- 右ページが先、左ページが次のページ（右開き）
- 紙面ドラッグ量に追従してページが傾き、影が変化するページめくり
- 右スワイプで次の見開き、左スワイプで前の見開き
- Questのハンドトラッキング/コントローラーの両方で操作
- ページジャンプと読書進捗表示
- アカウントごとのクラウドPDFライブラリ
- スマホ/PCのWeb画面から複数PDFをまとめてアップロード
- Questの本棚は5秒間隔で自動同期。手動Refreshは不要
- 読書位置をサーバーと端末へ保存
- ダウンロード済みPDFはQuest本体に保持し、再ダウンロードを回避
- ログイン状態、サーバーURL、直近の本棚情報をQuest側に保持
- Quest 3向けAPKをGitHub Actionsで生成

## 構成
- `app/`: Meta Spatial SDK + Kotlin + Jetpack Compose
- `server/`: Node.js/Express + SQLite。ログイン、PDFアップロード、PDF配信、読書位置保存、PDF削除
- `server/public/`: スマホ/PC向けのPDFアップロード画面

## 使い方
1. BookReaderサーバーをHTTPSで公開する。
2. スマホ/PCのブラウザでサーバーURLを開いてアカウントを作成する。
3. PDFを選択、またはドラッグ&ドロップしてアップロードする。複数冊を一度に選択可能。
4. QuestのBookReaderで同じServer URL・アカウントへログインする。
5. 以後、スマホ/PCで追加したPDFはQuestの本棚へ自動同期される。
6. 本を選ぶとQuestへ初回だけダウンロードされ、その後は端末保存済みPDFを利用する。

## APK
GitHub Actionsの `Build Quest APK` が `bookreader-debug-apk` を生成します。

初回起動時、ライブラリ画面の `Server URL` にHTTPSで公開したBookReaderサーバーのURLを入力します。サーバーURLを変えてもAPKの再ビルドは不要です。

## サーバー起動
```bash
cd server
npm install
JWT_SECRET='十分に長いランダム文字列' npm start
```

既定ポートは `8787`。PDFとSQLite DBは `server/data/` に保存されます。本番ではこのディレクトリを永続化し、HTTPSのリバースプロキシまたはクラウドサービスの背後で公開してください。

Docker利用時は `/app/data` を永続ボリュームへ割り当てます。

## インストール
Quest 3をDeveloper Modeにし、APKを取得後にADB等でインストールできます。
```bash
adb install -r app-debug.apk
```

## 次の品質向上候補
現在のページめくりは、ドラッグ追従・遠近感・影・ページ切替を組み合わせた軽量な疑似ページカールです。さらに物理本へ近づける場合は、カスタムメッシュを使ったページ端のつまみ操作と、Bezier/Cylindrical Curlによる実メッシュ変形へ拡張します。
