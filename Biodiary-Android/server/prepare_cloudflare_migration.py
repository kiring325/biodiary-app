"""Take a consistent Pi database backup and export existing account metadata."""

import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


data_dir = Path(os.environ["BIODIARY_DATA_DIR"]).expanduser()
source = data_dir / "biodiary.sqlite3"
work_dir = Path(__file__).resolve().parent
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
backup_path = work_dir / f"biodiary-before-cloudflare-{stamp}.sqlite3"
export_path = work_dir / "existing-accounts.sql"


def sql_text(value):
    return "'" + str(value).replace("'", "''") + "'"


with sqlite3.connect(source) as original, sqlite3.connect(backup_path) as backup:
    original.backup(backup)
os.chmod(backup_path, 0o600)

with sqlite3.connect(backup_path) as backup, export_path.open("w", encoding="utf-8") as output:
    backup.row_factory = sqlite3.Row
    rows = backup.execute(
        "SELECT account_code, login_name, display_name, message, registered_at, avatar_updated_at FROM accounts"
    ).fetchall()
    for row in rows:
        values = ", ".join(
            [sql_text(row[key]) for key in ("account_code", "login_name", "display_name", "message")]
            + ["''", "''", str(row["registered_at"]), "1", str(row["avatar_updated_at"])]
        )
        output.write(
            "INSERT OR IGNORE INTO accounts(account_code, login_name, display_name, message, "
            "password_salt, password_hash, registered_at, community_joined, avatar_updated_at) "
            f"VALUES ({values});\n"
        )
os.chmod(export_path, 0o600)
print(f"バックアップ: {backup_path}")
print(f"既存アカウント: {len(rows)}件")
print(f"移行SQL: {export_path}")
