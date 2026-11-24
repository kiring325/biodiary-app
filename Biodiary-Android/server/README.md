# Biodiary 写真・共有 API（Raspberry Pi）

コミュニティに共有された観察記録・写真・アイコン画像と、コメント・いいね・通知を保存する API です。Python（Flask + Waitress）と SQLite で動作し、Raspberry Pi 上で運用しています。外部からは Tailscale Funnel を通して接続します。

## 認証のしくみ

アカウントとログインは Cloudflare Worker（[`Biodiary-Cloudflare`](../../Biodiary-Cloudflare/README.md)）が管理します。この API はパスワードを持たず、端末が送ってくる **Cloudflare の署名付き本人情報**を検証して、利用者と所属コミュニティを判断します。

1. 端末は Cloudflare Worker から、アカウント ID・コミュニティ ID などを含む 60 秒間有効の署名（HMAC-SHA256）を受け取る
2. 端末はその署名を `X-Biodiary-Identity` ヘッダーに付けて、この API に直接接続する
3. API は共有の秘密鍵で署名と有効期限を検証し、アカウント情報の写しを更新してから処理する

## 公開範囲の制御

- 新しい記録は非公開で作られ、所有者が共有した場合だけ同じコミュニティのメンバーに表示されます。
- 共有記録・メンバー・コメント・いいね・通知は、**閲覧者と同じコミュニティの投稿者のもの**だけを返します。この条件は SQL の共通部品として、一覧・詳細・写真・コメント・いいね・通知のすべてに適用しています。
- 署名にコミュニティ ID を含まない旧版の Worker からの要求は `default` コミュニティとして扱い、段階的に更新できるようにしています。
- 場所を非公開にした記録は、座標を県庁所在地に置き換え、写真に含まれる位置情報（EXIF）を除去します。

## 写真の扱い

- 1 件の記録に 1〜4 枚（各 8MB 以下）を保存できます。
- 画像は先頭のバイト列で形式（JPEG・PNG・WebP）を確認してから保存します。
- 写真は閲覧権限を確認したうえで返します。2 枚目以降にも 1 枚目と同じ条件を適用します。
- データベースの列の追加（複数写真・複数コミュニティなど）は起動時に自動で行い、既存のデータは消去しません。

## API の概要

| メソッド・パス | 内容 |
|---|---|
| `GET /api/v1/status` | 状態確認 |
| `GET` / `PATCH /api/v1/account/me` | 自分のプロフィール |
| `PUT /api/v1/account/me/avatar` | アイコン画像の登録 |
| `GET /api/v1/community/members` | 同じコミュニティのメンバー一覧 |
| `GET /api/v1/community/members/{id}/avatar` | メンバーのアイコン画像 |
| `GET /api/v1/community/members/{id}/observations` | メンバーの共有投稿 |
| `GET` / `PUT /api/v1/tag-groups` | タググループ |
| `GET` / `POST /api/v1/observations` | 記録の取得（差分同期に対応）と登録 |
| `GET` / `PUT` / `DELETE /api/v1/observations/{id}` | 閲覧・編集・削除（編集と削除は所有者のみ） |
| `GET /api/v1/observations/{id}/photo/{1-4}` | 写真 |
| `GET` / `POST /api/v1/observations/{id}/comments` | コメント・返信 |
| `PATCH` / `DELETE /api/v1/observations/{id}/comments/{commentId}` | 自分のコメントの編集・削除 |
| `GET` / `PUT` / `DELETE /api/v1/observations/{id}/likes` | いいね |
| `GET /api/v1/observations/reactions/summary` | 共有投稿のいいね数の一覧 |
| `GET /api/v1/notifications`、`PATCH /api/v1/notifications/read` | 通知の取得と既読 |

差分同期（`since` 指定）では、更新された記録に加えて現在閲覧できる記録 ID の一覧を返し、端末は一覧にない共有記録を削除します。

## テスト

Flask と Pillow を入れた環境で実行します。データはテスト用の一時フォルダにだけ作成します。

```sh
pip install flask waitress pillow
python3 -m unittest discover -s tests
```

| テスト | 確認内容 |
|---|---|
| `tests/test_multi_photos.py` | 複数写真の保存・差し替え・位置情報の除去 |
| `tests/test_communities.py` | コミュニティ間で投稿・メンバー・いいね・通知が分離されること |

## 構成ファイル

| ファイル | 内容 |
|---|---|
| `biodiary_api.py` | API 本体 |
| `biodiary-api.service`、`cloudflare-systemd.conf` | systemd の設定の見本 |
| `export_cloudflare_accounts.py` など | 旧方式（Pi でアカウントを管理していた頃）から Cloudflare へ移行する際に使ったスクリプト |
