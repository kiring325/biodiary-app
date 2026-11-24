"""Export existing account metadata for Cloudflare D1 without password hashes."""

import os
import sqlite3
from pathlib import Path


data_dir = Path(os.environ.get("BIODIARY_DATA_DIR", Path(__file__).resolve().parent / "data"))
connection = sqlite3.connect(data_dir / "biodiary.sqlite3")
connection.row_factory = sqlite3.Row


def sql_text(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


for row in connection.execute(
    "SELECT account_code, login_name, display_name, message, registered_at, avatar_updated_at FROM accounts"
):
    values = ", ".join(
        (
            sql_text(row["account_code"]),
            sql_text(row["login_name"]),
            sql_text(row["display_name"]),
            sql_text(row["message"]),
            "''", "''", str(row["registered_at"]), "1", str(row["avatar_updated_at"]),
        )
    )
    print(
        "INSERT OR IGNORE INTO accounts(account_code, login_name, display_name, message, "
        "password_salt, password_hash, registered_at, community_joined, avatar_updated_at) "
        f"VALUES ({values});"
    )
