# Biodiary Android アプリ

Kotlin と Jetpack Compose で作成した、Biodiary の Android アプリです。ブラウザ版と同じアカウント・共有データを利用できます。

## 主な機能

- アプリからの撮影と、写真ピッカーからの登録（1 件につき最大 4 枚、登録前のトリミング）
- 写真の EXIF から撮影日時・GPS 位置を読み取り。位置がない場合は現在地の取得または地図のタップで指定
- OpenStreetMap の地図に、季節ごとの色枠を付けた写真ピンを表示。春夏秋冬を重ねて表示可能
- ジャンル別の図鑑、GBIF の API による分類情報の検索・自動入力
- 色付きのタググループによる絞り込み
- コミュニティへの共有と同期、コメント・返信、いいね、通知、プロフィールカード
- 場所の詳細を非公開にした共有（県庁所在地の座標で表示し、写真の位置情報を除去）

## 技術構成

| 項目 | 内容 |
|---|---|
| 言語・UI | Kotlin 2.2、Jetpack Compose（Material 3） |
| 対応 OS | Android 8.0（API 26）以上、targetSdk 36 |
| 画像 | Coil（表示）、ExifInterface（撮影情報の読み取り・除去） |
| 地図 | WebView + Leaflet（OpenStreetMap）。API キーや課金設定は不要 |
| 非同期処理 | Kotlin Coroutines |
| 保存 | 未共有の記録は端末内にのみ保存。ログイン情報は Android Keystore の鍵で暗号化して保存 |

## ビルド

1. このフォルダを Android Studio で開きます（Gradle Wrapper の Gradle 8.13 を使用）。
2. 接続先（Cloudflare Worker）の URL はソースコードに含めていません。`local.properties` に次の 1 行を追加してからビルドします。設定がない場合は、アプリの「接続先設定」で入力できます。

   ```properties
   biodiary.apiUrl=https://<Worker の URL>
   ```

3. `app` の実行構成をエミュレーターまたは端末で起動します。

アプリの内部 ID（`jp.morinote.app`）は開発初期の仮称の名残です。インストール済みのアプリを更新できるよう、変更していません。

## 関連

`server/` には、写真と共有記録を保存する Raspberry Pi 用の API が入っています。詳しくは [server/README.md](server/README.md) を参照してください。
