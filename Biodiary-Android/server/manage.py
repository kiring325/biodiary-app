"""Local administrator commands. Invite codes are generated on the Pi and shown once."""

from __future__ import annotations

import argparse
import secrets
import time

from biodiary_api import connect_db, hash_secret


def create_invites(count: int, expires_days: int) -> None:
    now = int(time.time())
    connection = connect_db()
    codes = []
    for _ in range(count):
        code = secrets.token_urlsafe(18)
        connection.execute(
            "INSERT INTO invites(code_hash, created_at, expires_at) VALUES (?, ?, ?)",
            (hash_secret(code), now, now + expires_days * 86400),
        )
        codes.append(code)
    connection.commit()
    connection.close()
    for code in codes:
        print(code)


def main() -> None:
    parser = argparse.ArgumentParser(description="Biodiary APIの管理コマンド")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("init", help="データベースと写真保存先を初期化")
    invite_parser = subparsers.add_parser("invite", help="一度だけ使える招待コードを作成")
    invite_parser.add_argument("--count", type=int, default=1)
    invite_parser.add_argument("--expires-days", type=int, default=30)
    args = parser.parse_args()
    if args.command == "init":
        print("Biodiary APIのデータベースと写真保存先を準備しました。")
    elif args.command == "invite":
        if not 1 <= args.count <= 10 or not 1 <= args.expires_days <= 365:
            parser.error("countは1〜10、expires-daysは1〜365で指定してください")
        create_invites(args.count, args.expires_days)


if __name__ == "__main__":
    main()
