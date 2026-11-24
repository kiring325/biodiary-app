# Biodiary アカウント API（Cloudflare Workers + D1）

Biodiary のアカウント登録・ログイン・セッション・コミュニティ参加を担当する API です。Cloudflare Workers で動作し、データは Cloudflare D1 に保存します。写真と共有記録は Raspberry Pi の API（[`Biodiary-Android/server`](../Biodiary-Android/server/README.md)）が保存し、この Worker は写真を扱いません。

## 役割

| 機能 | 内容 |
|---|---|
| アカウント | ログイン名・表示名・ひとこと・パスワードを管理。登録に招待は不要 |
| セッション | ログイン時に 30 日間有効のトークンを発行 |
| コミュニティ | 招待コードによる参加、メンバー一覧、コミュニティ名の変更（管理者のみ） |
| 写真サーバーへの橋渡し | Raspberry Pi へ直接接続するための、60 秒間有効の署名付き本人情報を発行 |

## 写真サーバーとの連携

```
端末 ──(ログイン)──▶ Worker ──▶ D1
端末 ◀─(署名付き本人情報)── Worker
端末 ──(署名を添えて写真を送受信)──▶ Raspberry Pi
```

`GET /api/v1/auth/pi-ticket` は、アカウント ID・コミュニティ ID・参加状態などを JSON にまとめ、HMAC-SHA256 で署名して返します。端末はこれを `X-Biodiary-Identity` ヘッダーに付けて Raspberry Pi へ直接接続し、Pi は共有の秘密鍵で署名と有効期限を検証します。

当初は Worker が写真の通信を中継していましたが、Worker から Tailscale Funnel への接続で HTTP 525 が発生したため、端末が直接接続するこの方式に変更しました。アカウント情報を Pi に持たせる必要がなくなり、Pi 側は署名を検証するだけの構成になっています。

## 複数コミュニティ

招待コードは参加先のコミュニティ ID を持ち、参加したアカウントにそのコミュニティが記録されます。署名にもコミュニティ ID が含まれるため、Pi 側は同じコミュニティの投稿だけを返せます。

- 1 つのアカウントが参加できるコミュニティは 1 つ
- 定員はコミュニティごとに数える
- 新しく作られたコミュニティでは、最初の参加者が管理者になる
- 既存のアカウントは D1 マイグレーションで `default` コミュニティへ移行

## セキュリティ

- パスワードはソルトとサーバー側の秘密値（ペッパー）を加えた HMAC-SHA256 で保存し、比較は定数時間で行います。
- セッショントークンと招待コードは SHA-256 のハッシュ値だけを保存します。
- 登録は IP ごとに 1 日 5 回、ログイン失敗は 15 分間に 8 回までに制限しています（IP アドレスもハッシュ化して保存）。
- 招待コードは 1 回限り・14 日間有効です。定員を超えた場合はコードを未使用に戻します。
- CORS はブラウザ版の公開元だけを許可します。
- 秘密の値（`PI_SHARED_SECRET`、`PASSWORD_PEPPER`）は Wrangler の Secret で管理し、ソースコードには含めません。

## API の概要

| メソッド・パス | 内容 |
|---|---|
| `POST /api/v1/auth/register` | アカウント作成 |
| `POST /api/v1/auth/login`、`POST /api/v1/auth/logout` | ログイン・ログアウト |
| `GET /api/v1/auth/pi-ticket` | 写真サーバー用の署名付き本人情報 |
| `GET` / `PATCH /api/v1/account/me` | プロフィールの取得・更新 |
| `POST /api/v1/community/join` | 招待コードでコミュニティに参加 |
| `GET` / `PATCH /api/v1/community` | コミュニティ名の取得・変更 |
| `GET /api/v1/community/members` | 同じコミュニティのメンバー一覧 |

上記以外の共有記録・コメント・いいね・通知などの要求は、参加状態を確認したうえで署名を付けて Pi へ転送します。

## 構成ファイル

| ファイル | 内容 |
|---|---|
| `src/index.js` | Worker 本体 |
| `migrations/` | D1 の表定義（マイグレーション） |
| `scripts/new-invite.mjs` | 招待コードを生成し、登録用の SQL を出力するスクリプト |
| `wrangler.example.toml` | 設定の見本（実際の `wrangler.toml` は環境ごとの値を含むためリポジトリに含めない） |
