# Biodiary ブラウザ版

Android 版と同じアカウント・共有データを使える、スマートフォン向けのブラウザ版です。フレームワークを使わない JavaScript で作成し、ホーム画面に追加できる PWA として Cloudflare Pages で公開しています。

- 公開サイト：https://biodiary-album.pages.dev/

## 主な機能

- 写真の撮影・選択と、登録前のトリミング（1 件につき最大 4 枚）
- 写真の EXIF から撮影日時・位置を読み取り。位置がない場合は現在地の取得または地図のタップで指定
- OpenStreetMap の地図に、季節ごとの色枠を付けた写真ピンを表示
- ジャンル別の図鑑と、新しい順・古い順・五十音順の並び替え
- GBIF の API による種・属・科・目の候補検索
- 色付きのタググループによる絞り込み
- コミュニティへの共有、コメント・返信、いいね、通知、プロフィールカード
- 場所の詳細を非公開にした共有（県庁所在地の座標で表示）
- 記録の JSON バックアップの書き出し・読み込み

## 技術的な特徴

| 項目 | 内容 |
|---|---|
| 端末内の保存 | 未共有の記録と写真は IndexedDB に保存し、サーバーには送らない |
| オフライン対応 | Service Worker で画面の基本ファイルをキャッシュ |
| 地図 | Leaflet を同梱し、OpenStreetMap の利用ポリシーに沿った帰属表示と Referrer 設定 |
| 同期 | 共有記録は差分同期。サーバーで閲覧できなくなった記録は端末からも削除 |
| 写真の通信 | Cloudflare が発行する短時間の署名付き本人情報を使い、写真サーバーへ直接接続 |

## 動かし方

HTTPS（または localhost）で配信してください。カメラ・位置情報・Service Worker は HTTPS 環境でのみ動作します。`file://` で直接開くと、地図タイルが表示されないことがあります。

接続先（Cloudflare Worker）の URL はリポジトリに含めていません。`config.example.js` を `config.js` という名前でコピーして URL を書くか、画面の「接続先設定」に入力してください。

## ライセンス表記

Leaflet のライセンスは [assets/LEAFLET-LICENSE.txt](assets/LEAFLET-LICENSE.txt) を参照してください。地図データは © OpenStreetMap contributors です。
