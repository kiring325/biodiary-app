"""Small, invite-only API for the Biodiary Raspberry Pi deployment."""

from __future__ import annotations

import hashlib
import base64
import hmac
import json
import math
import os
import re
import secrets
import sqlite3
import tempfile
import time
import uuid
from functools import wraps
from pathlib import Path
from typing import Any

from flask import Flask, abort, g, jsonify, request, send_file
from werkzeug.security import check_password_hash, generate_password_hash


BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("BIODIARY_DATA_DIR", BASE_DIR / "data")).expanduser()
PHOTO_DIR = DATA_DIR / "photos"
DB_PATH = DATA_DIR / "biodiary.sqlite3"
MAX_USERS = max(1, int(os.environ.get("BIODIARY_MAX_USERS", "10")))
MAX_UPLOAD_BYTES = max(1024 * 1024, int(os.environ.get("BIODIARY_MAX_UPLOAD_BYTES", str(8 * 1024 * 1024))))
SESSION_DAYS = max(1, int(os.environ.get("BIODIARY_SESSION_DAYS", "30")))
WEB_ORIGINS = {item.strip() for item in os.environ.get("BIODIARY_WEB_ORIGINS", "").split(",") if item.strip()}
IDENTITY_SECRET = os.environ.get("BIODIARY_CLOUDFLARE_IDENTITY_SECRET", "")
CLOUDFLARE_AUTH_ONLY = os.environ.get("BIODIARY_CLOUDFLARE_AUTH_ONLY", "0") == "1"
PREFECTURE_CAPITALS = dict([
    ("北海道", (43.0618, 141.3545)), ("青森県", (40.8244, 140.7400)), ("岩手県", (39.7036, 141.1527)),
    ("宮城県", (38.2682, 140.8694)), ("秋田県", (39.7186, 140.1024)), ("山形県", (38.2404, 140.3633)),
    ("福島県", (37.7503, 140.4676)), ("茨城県", (36.3418, 140.4468)), ("栃木県", (36.5658, 139.8836)),
    ("群馬県", (36.3911, 139.0608)), ("埼玉県", (35.8617, 139.6455)), ("千葉県", (35.6074, 140.1065)),
    ("東京都", (35.6895, 139.6917)), ("神奈川県", (35.4478, 139.6425)), ("新潟県", (37.9161, 139.0364)),
    ("富山県", (36.6953, 137.2113)), ("石川県", (36.5947, 136.6256)), ("福井県", (36.0652, 136.2216)),
    ("山梨県", (35.6642, 138.5684)), ("長野県", (36.6513, 138.1810)), ("岐阜県", (35.3912, 136.7223)),
    ("静岡県", (34.9769, 138.3831)), ("愛知県", (35.1802, 136.9066)), ("三重県", (34.7303, 136.5086)),
    ("滋賀県", (35.0045, 135.8686)), ("京都府", (35.0212, 135.7556)), ("大阪府", (34.6863, 135.5200)),
    ("兵庫県", (34.6913, 135.1830)), ("奈良県", (34.6851, 135.8049)), ("和歌山県", (34.2261, 135.1675)),
    ("鳥取県", (35.5039, 134.2377)), ("島根県", (35.4723, 133.0505)), ("岡山県", (34.6551, 133.9195)),
    ("広島県", (34.3853, 132.4553)), ("山口県", (34.1859, 131.4714)), ("徳島県", (34.0658, 134.5593)),
    ("香川県", (34.3401, 134.0434)), ("愛媛県", (33.8416, 132.7657)), ("高知県", (33.5597, 133.5311)),
    ("福岡県", (33.6064, 130.4183)), ("佐賀県", (33.2494, 130.2988)), ("長崎県", (32.7503, 129.8779)),
    ("熊本県", (32.7898, 130.7417)), ("大分県", (33.2382, 131.6126)), ("宮崎県", (31.9111, 131.4239)),
    ("鹿児島県", (31.5602, 130.5581)), ("沖縄県", (26.2124, 127.6809)),
])

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 4 * MAX_UPLOAD_BYTES + 64 * 1024


def connect_db() -> sqlite3.Connection:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=5)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA busy_timeout = 5000")
    return connection


def initialize_database() -> None:
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    with connect_db() as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                id TEXT PRIMARY KEY,
                account_code TEXT NOT NULL UNIQUE,
                login_name TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                display_name TEXT NOT NULL,
                message TEXT NOT NULL DEFAULT '',
                avatar_filename TEXT NOT NULL DEFAULT '',
                avatar_mime TEXT NOT NULL DEFAULT '',
                avatar_updated_at INTEGER NOT NULL DEFAULT 0,
                registered_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS invites (
                code_hash TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                used_at INTEGER,
                used_by TEXT REFERENCES accounts(id) ON DELETE SET NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS sessions_account_id ON sessions(account_id);
            CREATE TABLE IF NOT EXISTS login_failures (
                login_key TEXT NOT NULL,
                source_key TEXT NOT NULL,
                attempted_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS login_failures_lookup
                ON login_failures(login_key, source_key, attempted_at);
            CREATE TABLE IF NOT EXISTS observations (
                id TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                data_json TEXT NOT NULL,
                photo_filename TEXT NOT NULL,
                photo_mime TEXT NOT NULL,
                shared INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS observations_owner_date
                ON observations(owner_id, updated_at DESC);
            CREATE INDEX IF NOT EXISTS observations_shared_date
                ON observations(shared, updated_at DESC);
            CREATE TABLE IF NOT EXISTS tag_groups (
                owner_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
                groups_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS observation_comments (
                id TEXT PRIMARY KEY,
                observation_id TEXT NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
                author_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                parent_id TEXT REFERENCES observation_comments(id) ON DELETE SET NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                deleted_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS comments_observation_date
                ON observation_comments(observation_id, created_at DESC, id DESC);
            CREATE TABLE IF NOT EXISTS observation_likes (
                observation_id TEXT NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
                account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (observation_id, account_id)
            );
            CREATE TABLE IF NOT EXISTS notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipient_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                actor_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                observation_id TEXT NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
                comment_id TEXT REFERENCES observation_comments(id) ON DELETE CASCADE,
                kind TEXT NOT NULL CHECK(kind IN ('like', 'comment', 'reply')),
                event_key TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                read_at INTEGER,
                UNIQUE(recipient_id, event_key)
            );
            CREATE INDEX IF NOT EXISTS notifications_recipient_id ON notifications(recipient_id, id DESC);
            CREATE INDEX IF NOT EXISTS notifications_recipient_unread ON notifications(recipient_id, read_at);
            """
        )
        account_columns = {row["name"] for row in connection.execute("PRAGMA table_info(accounts)")}
        for name, definition in (
            ("avatar_filename", "TEXT NOT NULL DEFAULT ''"),
            ("avatar_mime", "TEXT NOT NULL DEFAULT ''"),
            ("avatar_updated_at", "INTEGER NOT NULL DEFAULT 0"),
            ("community_joined", "INTEGER NOT NULL DEFAULT 1"),
            # どのコミュニティに属するか。複数コミュニティ化より前のアカウントは 'default'。
            ("community_id", "TEXT NOT NULL DEFAULT 'default'"),
        ):
            if name not in account_columns:
                connection.execute(f"ALTER TABLE accounts ADD COLUMN {name} {definition}")

        observation_columns = {row["name"] for row in connection.execute("PRAGMA table_info(observations)")}
        if "extra_photos_json" not in observation_columns:
            connection.execute("ALTER TABLE observations ADD COLUMN extra_photos_json TEXT NOT NULL DEFAULT '[]'")
        comment_columns = {row["name"] for row in connection.execute("PRAGMA table_info(observation_comments)")}
        if "edited_at" not in comment_columns:
            connection.execute("ALTER TABLE observation_comments ADD COLUMN edited_at INTEGER NOT NULL DEFAULT 0")


initialize_database()


def get_db() -> sqlite3.Connection:
    if "db" not in g:
        g.db = connect_db()
    return g.db


@app.teardown_appcontext
def close_db(_error: BaseException | None = None) -> None:
    connection = g.pop("db", None)
    if connection is not None:
        connection.close()


@app.after_request
def add_cors_headers(response):
    origin = request.headers.get("Origin", "")
    if origin in WEB_ORIGINS:
        response.headers["Access-Control-Allow-Origin"] = origin
        response.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, X-Biodiary-Identity"
        response.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, PATCH, DELETE, OPTIONS"
        response.headers["Access-Control-Max-Age"] = "600"
        response.headers["Vary"] = "Origin"
    response.headers["Cache-Control"] = "no-store"
    return response


@app.before_request
def handle_preflight():
    if request.method == "OPTIONS":
        return "", 204
    return None


@app.errorhandler(413)
def request_too_large(_error):
    return jsonify(error=f"アップロードできる写真は最大{MAX_UPLOAD_BYTES // (1024 * 1024)}MBです"), 413


def json_error(message: str, status: int):
    return jsonify(error=message), status


def hash_secret(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def account_json(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "accountId": row["account_code"],
        "loginName": row["login_name"],
        "displayName": row["display_name"],
        "message": row["message"],
        "avatarUrl": f"/api/v1/community/members/{row['account_code']}/avatar" if row["avatar_filename"] else "",
        "avatarUpdatedAt": row["avatar_updated_at"],
        "registeredAt": row["registered_at"],
    }


def new_session(connection: sqlite3.Connection, account_id: str) -> str:
    token = secrets.token_urlsafe(32)
    now = int(time.time())
    connection.execute(
        "INSERT INTO sessions(token_hash, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
        (hash_secret(token), account_id, now, now + SESSION_DAYS * 86400),
    )
    connection.commit()
    return token


def trusted_cloudflare_account() -> sqlite3.Row | None:
    token = request.headers.get("X-Biodiary-Identity", "")
    if not token or not IDENTITY_SECRET:
        return None
    payload, separator, signature = token.partition(".")
    if not separator or len(payload) > 3000:
        return None
    expected = base64.urlsafe_b64encode(
        hmac.new(IDENTITY_SECRET.encode(), payload.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    if not hmac.compare_digest(expected, signature):
        return None
    try:
        raw = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        claims = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        return None
    account_code = claims.get("accountId")
    login_name = claims.get("loginName")
    if (not isinstance(account_code, str) or not re.fullmatch(r"BD-[A-F0-9]{8,12}", account_code)
            or not isinstance(login_name, str) or not re.fullmatch(r"[a-z0-9_.-]{3,32}", login_name)
            or not isinstance(claims.get("expiresAt"), int)
            or not int(time.time()) <= claims["expiresAt"] <= int(time.time()) + 120):
        return None
    display_name = claims.get("displayName")
    message = claims.get("message")
    registered_at = claims.get("registeredAt")
    if (not isinstance(display_name, str) or not display_name or len(display_name) > 80
            or not isinstance(message, str) or len(message) > 500
            or not isinstance(registered_at, int)
            or not isinstance(claims.get("communityMember"), bool)):
        return None
    # 旧版のWorkerは communityId を送らないので、その場合は 'default' とみなす
    community_id = claims.get("communityId", "default" if claims["communityMember"] else "")
    if not isinstance(community_id, str) or not re.fullmatch(r"[a-z0-9-]{0,32}", community_id):
        return None
    if claims["communityMember"] and not community_id:
        return None
    connection = get_db()
    row = connection.execute("SELECT * FROM accounts WHERE account_code = ?", (account_code,)).fetchone()
    try:
        if row is None:
            connection.execute(
                """INSERT INTO accounts(id, account_code, login_name, password_hash, display_name, message,
                   registered_at, community_joined, community_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (str(uuid.uuid4()), account_code, login_name,
                 generate_password_hash(secrets.token_urlsafe(32), method="scrypt"), display_name, message,
                 registered_at, int(claims["communityMember"]), community_id or "default"),
            )
        elif claims["communityMember"]:
            connection.execute(
                "UPDATE accounts SET display_name = ?, message = ?, community_joined = 1, community_id = ? WHERE account_code = ?",
                (display_name, message, community_id, account_code))
        else:
            # 未参加になった場合も community_id は残す（過去の共有記録が元のコミュニティに残るように）
            connection.execute("UPDATE accounts SET display_name = ?, message = ?, community_joined = 0 WHERE account_code = ?",
                               (display_name, message, account_code))
        connection.commit()
    except sqlite3.IntegrityError:
        connection.rollback()
        return None
    return connection.execute("SELECT * FROM accounts WHERE account_code = ?", (account_code,)).fetchone()


def my_community() -> str:
    return g.account["community_id"] or "default"


def shared_in_my_community(alias: str = "") -> str:
    """「同じコミュニティの人が共有した記録」を表すSQLの条件。値として my_community() を1つ渡す。"""
    prefix = alias + "." if alias else ""
    return f"({prefix}shared = 1 AND {prefix}owner_id IN (SELECT id FROM accounts WHERE community_id = ?))"


def visible_to_me(row: sqlite3.Row) -> bool:
    """自分の記録か、同じコミュニティの人が共有した記録なら見られる。"""
    if row["owner_id"] == g.account["id"]:
        return True
    if not row["shared"]:
        return False
    owner = get_db().execute("SELECT community_id FROM accounts WHERE id = ?", (row["owner_id"],)).fetchone()
    return bool(owner and owner["community_id"] == my_community())


def signed_request_allowed(account: sqlite3.Row) -> bool:
    path, method = request.path, request.method
    own_avatar = f"/api/v1/community/members/{account['account_code']}/avatar"
    if (path == "/api/v1/account/me/avatar" and method == "PUT") or (path == own_avatar and method == "GET"):
        return True
    if not account["community_joined"]:
        return False
    if path == "/api/v1/notifications":
        return method == "GET"
    if path == "/api/v1/notifications/read":
        return method == "PATCH"
    if path == "/api/v1/community/members" and method == "GET":
        return True
    target = re.fullmatch(r"/api/v1/community/members/(BD-[A-F0-9]{8,12})/avatar", path)
    if target and method == "GET":
        member = get_db().execute("SELECT community_joined, community_id FROM accounts WHERE account_code = ?",
                                  (target[1],)).fetchone()
        return bool(member and member["community_joined"] and member["community_id"] == (account["community_id"] or "default"))
    if path == "/api/v1/tag-groups":
        return method in ("GET", "PUT")
    if path == "/api/v1/observations":
        return method in ("GET", "POST")
    if re.fullmatch(r"/api/v1/observations/[A-Za-z0-9_-]{1,80}", path):
        return method in ("GET", "PUT", "DELETE")
    if re.fullmatch(r"/api/v1/observations/[A-Za-z0-9_-]{1,80}/photo(?:/[1-4])?", path):
        return method == "GET"
    if re.fullmatch(r"/api/v1/observations/[A-Za-z0-9_-]{1,80}/comments", path):
        return method in ("GET", "POST")
    if re.fullmatch(r"/api/v1/observations/[A-Za-z0-9_-]{1,80}/comments/[A-Za-z0-9_-]{1,80}", path):
        return method in ("PATCH", "DELETE")
    if re.fullmatch(r"/api/v1/community/members/BD-[A-F0-9]{8,12}/observations", path):
        return method == "GET"
    if path == "/api/v1/observations/reactions/summary":
        return method == "GET"
    if re.fullmatch(r"/api/v1/observations/[A-Za-z0-9_-]{1,80}/likes", path):
        return method in ("GET", "PUT", "DELETE")
    return False


def require_account(handler):
    @wraps(handler)
    def wrapped(*args, **kwargs):
        if request.headers.get("X-Biodiary-Identity"):
            row = trusted_cloudflare_account()
            if row is None:
                return json_error("Cloudflareからの認証を確認できませんでした", 401)
            if not signed_request_allowed(row):
                return json_error("この操作にはコミュニティへの参加が必要です", 403)
            g.account = row
            return handler(*args, **kwargs)
        if CLOUDFLARE_AUTH_ONLY:
            return json_error("CloudflareのAPIから接続してください", 401)
        auth = request.headers.get("Authorization", "")
        scheme, _, token = auth.partition(" ")
        if scheme.lower() != "bearer" or not token:
            return json_error("ログインしてください", 401)
        connection = get_db()
        row = connection.execute(
            """SELECT a.* FROM sessions s JOIN accounts a ON a.id = s.account_id
               WHERE s.token_hash = ? AND s.expires_at > ?""",
            (hash_secret(token), int(time.time())),
        ).fetchone()
        if row is None:
            return json_error("ログインの有効期限が切れています", 401)
        g.account = row
        return handler(*args, **kwargs)

    return wrapped


def request_payload() -> tuple[dict[str, Any] | None, Any | None]:
    if request.mimetype == "multipart/form-data":
        try:
            payload = json.loads(request.form.get("metadata", "{}"))
        except (json.JSONDecodeError, TypeError):
            return None, None
        return (payload, request.files.get("photo")) if isinstance(payload, dict) else (None, None)
    payload = request.get_json(silent=True)
    return (payload, None) if isinstance(payload, dict) else (None, None)


def validate_observation(payload: dict[str, Any], forced_id: str | None = None) -> dict[str, Any] | None:
    observation_id = forced_id or payload.get("id") or str(uuid.uuid4())
    if not isinstance(observation_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", observation_id):
        return None
    try:
        date_millis = int(payload["dateMillis"])
        latitude = float(payload["latitude"])
        longitude = float(payload["longitude"])
    except (KeyError, TypeError, ValueError, OverflowError):
        return None
    if not math.isfinite(latitude) or not math.isfinite(longitude):
        return None
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        return None

    def text_field(key: str, limit: int, fallback: str = "") -> str | None:
        value = payload.get(key, fallback)
        if not isinstance(value, str) or len(value) > limit:
            return None
        return value

    name = text_field("name", 200)
    if not name or not name.strip():
        return None
    categories = {
        "PLANT", "BIRD", "INSECT", "MAMMAL", "FISH", "REPTILE", "AMPHIBIAN", "FUNGI", "OTHER",
        "REPTILE_AMPHIBIAN", "LEGACY_REPTILE_AMPHIBIAN",
        "植物", "鳥類", "昆虫", "哺乳類", "魚類", "爬虫類", "両生類", "菌類", "その他",
        "爬虫類・両生類", "爬虫類・両生類（旧記録）",
    }
    if not isinstance(payload.get("category"), str) or payload["category"] not in categories:
        return None
    result: dict[str, Any] = {
        "id": observation_id,
        "name": name.strip(),
        "dateMillis": date_millis,
        "latitude": latitude,
        "longitude": longitude,
    }
    location_private = payload.get("locationPrivate", False)
    prefecture = payload.get("prefecture", "")
    if not isinstance(location_private, bool) or not isinstance(prefecture, str) or len(prefecture) > 20:
        return None
    if location_private and prefecture not in PREFECTURE_CAPITALS:
        return None
    if location_private:
        latitude, longitude = PREFECTURE_CAPITALS[prefecture]
        result["latitude"] = latitude
        result["longitude"] = longitude
    result["locationPrivate"] = location_private
    result["prefecture"] = prefecture if location_private else ""
    limits = {
        "place": 300,
        "comment": 2000,
        "category": 80,
        "species": 200,
        "speciesScientificName": 250,
        "genus": 200,
        "family": 200,
        "order": 200,
    }
    for key, limit in limits.items():
        value = text_field(key, limit)
        if value is None:
            return None
        result[key] = value
    if location_private:
        result["place"] = ""
    tags = payload.get("tags", [])
    tag_values = payload.get("tagValues", {})
    if not isinstance(tags, list) or len(tags) > 100 or any(not isinstance(v, str) or len(v) > 100 for v in tags):
        return None
    if not isinstance(tag_values, dict) or len(tag_values) > 100:
        return None
    clean_tag_values = {}
    for key, values in tag_values.items():
        if not isinstance(key, str) or len(key) > 100 or not isinstance(values, list) or len(values) > 100:
            return None
        if any(not isinstance(value, str) or len(value) > 100 for value in values):
            return None
        clean_tag_values[key] = values
    result["tags"] = tags
    result["tagValues"] = clean_tag_values
    return result


def strip_jpeg_metadata(data: bytes) -> bytes:
    if not data.startswith(b"\xff\xd8"):
        raise ValueError("JPEG画像が必要です")
    result = bytearray(data[:2])
    position = 2
    while position + 4 <= len(data):
        if data[position] != 0xFF:
            raise ValueError("JPEG画像の形式が正しくありません")
        marker = data[position + 1]
        if marker == 0xDA:
            result.extend(data[position:])
            return bytes(result)
        length = int.from_bytes(data[position + 2:position + 4], "big")
        end = position + 2 + length
        if length < 2 or end > len(data):
            raise ValueError("JPEG画像の形式が正しくありません")
        if not (0xE1 <= marker <= 0xEF or marker == 0xFE):
            result.extend(data[position:end])
        position = end
    raise ValueError("JPEG画像の形式が正しくありません")


def store_photo(upload, max_bytes: int = MAX_UPLOAD_BYTES, strip_metadata: bool = False) -> tuple[str, str] | None:
    if upload is None:
        return None
    stream = upload.stream
    header = stream.read(12)
    stream.seek(0)
    if header.startswith(b"\xff\xd8\xff"):
        mime, extension = "image/jpeg", "jpg"
    elif header.startswith(b"\x89PNG\r\n\x1a\n"):
        mime, extension = "image/png", "png"
    elif header.startswith(b"RIFF") and header[8:12] == b"WEBP":
        mime, extension = "image/webp", "webp"
    else:
        return None
    if strip_metadata and mime != "image/jpeg":
        return None
    filename = f"{uuid.uuid4().hex}.{extension}"
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".upload-", dir=PHOTO_DIR, delete=False) as output:
            temporary_path = Path(output.name)
            total = 0
            while True:
                chunk = stream.read(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise ValueError("写真が大きすぎます")
                output.write(chunk)
        if strip_metadata:
            temporary_path.write_bytes(strip_jpeg_metadata(temporary_path.read_bytes()))
        os.replace(temporary_path, PHOTO_DIR / filename)
        return filename, mime
    except (OSError, ValueError):
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        return None



def row_photos(row: sqlite3.Row) -> list[dict[str, str]]:
    return [{"filename": row["photo_filename"], "mime": row["photo_mime"]}] + json.loads(row["extra_photos_json"])


def delete_photos(photos: list[dict[str, str]]) -> None:
    for photo in photos:
        (PHOTO_DIR / photo["filename"]).unlink(missing_ok=True)


def save_observation_photos(payload: dict, *, required: bool) -> list[dict[str, str]] | None:
    fields = ["photo", "photo2", "photo3", "photo4"]
    if any(key not in fields or len(request.files.getlist(key)) != 1 for key in request.files):
        raise ValueError("写真は1件につき4枚までです")
    uploads = [request.files.get(field) for field in fields]
    count = payload.get("photoCount")
    if count is not None and (type(count) is not int or not 1 <= count <= 4):
        raise ValueError("写真は1件につき1〜4枚で登録してください")
    if all(upload is None for upload in uploads):
        if required:
            raise ValueError("写真を1枚以上選んでください")
        return None
    actual_count = sum(upload is not None for upload in uploads)
    if any(upload is None for upload in uploads[:actual_count]) or (count is not None and count != actual_count):
        raise ValueError("写真の枚数と送信内容が一致しません")
    photos = []
    try:
        for upload in uploads[:actual_count]:
            saved = store_photo(upload, strip_metadata=bool(payload.get("locationPrivate")))
            if saved is None:
                raise ValueError("写真は各8MB以下のJPEG・PNG・WebP形式で選んでください")
            filename, mime = saved
            photos.append({"filename": filename, "mime": mime})
        return photos
    except Exception:
        delete_photos(photos)
        raise


def like_state(connection: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
    actor = g.get("account")
    actor_id = actor["id"] if actor else ""
    stats = connection.execute("SELECT COUNT(*) AS count, COALESCE(MAX(account_id = ?), 0) AS liked "
                               "FROM observation_likes WHERE observation_id = ?", (actor_id, row["id"])).fetchone()
    return {"id": row["id"], "likeCount": stats["count"], "likedByMe": bool(stats["liked"]),
            "createdAt": row["created_at"] * 1000}


def observation_json(connection: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
    data = json.loads(row["data_json"])
    owner = connection.execute(
        "SELECT * FROM accounts WHERE id = ?", (row["owner_id"],)
    ).fetchone()
    groups = connection.execute(
        "SELECT groups_json FROM tag_groups WHERE owner_id = ?", (row["owner_id"],)
    ).fetchone()
    data.update(
        {
            "shared": bool(row["shared"]),
            "updatedAt": row["updated_at"] * 1000,
            "photoUrl": f"/api/v1/observations/{row['id']}/photo",
            "photoUrls": [f"/api/v1/observations/{row['id']}/photo" if index == 0 else
                          f"/api/v1/observations/{row['id']}/photo/{index + 1}"
                          for index, _ in enumerate(row_photos(row))],
            "photoCount": len(row_photos(row)),
            "owner": {
                "accountId": owner["account_code"],
                "loginName": owner["login_name"],
                "displayName": owner["display_name"],
                "avatarUrl": account_json(owner)["avatarUrl"],
                "avatarUpdatedAt": owner["avatar_updated_at"],
            }
            if owner
            else None,
            "tagGroups": json.loads(groups["groups_json"]) if groups else [],
        }
    )
    data.update(like_state(connection, row))
    return data


@app.get("/api/v1/status")
def status():
    return jsonify(service="Biodiary API", version=1, maxPhotosPerObservation=4)


@app.post("/api/v1/auth/register")
def register():
    if CLOUDFLARE_AUTH_ONLY:
        return json_error("アカウント作成はCloudflareのAPIを利用してください", 410)
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return json_error("入力内容を確認してください", 400)
    invite_code = payload.get("inviteCode")
    login_name = payload.get("loginName")
    password = payload.get("password")
    display_name = payload.get("displayName", "")
    if not isinstance(invite_code, str) or not invite_code or len(invite_code) > 200:
        return json_error("招待コードを入力してください", 400)
    if not isinstance(login_name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{3,32}", login_name):
        return json_error("ログイン名は英数字と . _ - の3〜32文字で入力してください", 400)
    if not isinstance(password, str) or len(password) < 12 or len(password) > 200:
        return json_error("パスワードは12文字以上で設定してください", 400)
    if not isinstance(display_name, str) or len(display_name.strip()) > 80:
        return json_error("アカウント名は80文字以内で入力してください", 400)

    login_name = login_name.lower()
    display_name = display_name.strip() or login_name
    password_hash = generate_password_hash(password, method="scrypt")
    now = int(time.time())
    connection = get_db()
    connection.execute("BEGIN IMMEDIATE")
    invite = connection.execute(
        "SELECT * FROM invites WHERE code_hash = ?", (hash_secret(invite_code),)
    ).fetchone()
    if invite is None or invite["used_at"] is not None or invite["expires_at"] <= now:
        connection.rollback()
        return json_error("招待コードが無効か、有効期限が切れています", 400)
    count = connection.execute("SELECT COUNT(*) AS total FROM accounts").fetchone()["total"]
    if count >= MAX_USERS:
        connection.rollback()
        return json_error("登録できるアカウント数の上限に達しています", 409)
    account_id = str(uuid.uuid4())
    account_code = f"BD-{secrets.token_hex(4).upper()}"
    try:
        connection.execute(
            """INSERT INTO accounts(id, account_code, login_name, password_hash, display_name, registered_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (account_id, account_code, login_name, password_hash, display_name, now * 1000),
        )
        connection.execute(
            "UPDATE invites SET used_at = ?, used_by = ? WHERE code_hash = ?",
            (now, account_id, invite["code_hash"]),
        )
        token = secrets.token_urlsafe(32)
        connection.execute(
            "INSERT INTO sessions(token_hash, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
            (hash_secret(token), account_id, now, now + SESSION_DAYS * 86400),
        )
        connection.commit()
    except sqlite3.IntegrityError:
        connection.rollback()
        return json_error("そのログイン名はすでに使われています", 409)
    row = connection.execute("SELECT * FROM accounts WHERE id = ?", (account_id,)).fetchone()
    return jsonify(token=token, expiresAt=now + SESSION_DAYS * 86400, account=account_json(row)), 201


@app.post("/api/v1/auth/login")
def login():
    if CLOUDFLARE_AUTH_ONLY:
        return json_error("CloudflareのAPIからログインしてください", 410)
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return json_error("入力内容を確認してください", 400)
    login_name = payload.get("loginName", "")
    password = payload.get("password", "")
    if not isinstance(login_name, str) or not isinstance(password, str):
        return json_error("ログイン名またはパスワードが違います", 401)
    login_name = login_name.strip().lower()[:32]
    source = request.headers.get("CF-Connecting-IP", request.remote_addr or "unknown")[:100]
    now = int(time.time())
    connection = get_db()
    failures = connection.execute(
        """SELECT COUNT(*) AS total FROM login_failures
           WHERE login_key = ? AND source_key = ? AND attempted_at > ?""",
        (login_name, source, now - 900),
    ).fetchone()["total"]
    if failures >= 8:
        return json_error("ログイン試行が多いため、15分後に再試行してください", 429)
    row = connection.execute("SELECT * FROM accounts WHERE login_name = ?", (login_name,)).fetchone()
    if row is None or not check_password_hash(row["password_hash"], password):
        connection.execute(
            "INSERT INTO login_failures(login_key, source_key, attempted_at) VALUES (?, ?, ?)",
            (login_name, source, now),
        )
        connection.execute("DELETE FROM login_failures WHERE attempted_at < ?", (now - 86400,))
        connection.commit()
        return json_error("ログイン名またはパスワードが違います", 401)
    connection.execute(
        "DELETE FROM login_failures WHERE login_key = ? AND source_key = ?", (login_name, source)
    )
    token = new_session(connection, row["id"])
    return jsonify(token=token, expiresAt=now + SESSION_DAYS * 86400, account=account_json(row))


@app.post("/api/v1/auth/logout")
@require_account
def logout():
    auth = request.headers.get("Authorization", "")
    token = auth.partition(" ")[2]
    get_db().execute("DELETE FROM sessions WHERE token_hash = ?", (hash_secret(token),))
    get_db().commit()
    return jsonify(ok=True)


@app.get("/api/v1/account/me")
@require_account
def get_account():
    return jsonify(account=account_json(g.account))


@app.patch("/api/v1/account/me")
@require_account
def update_account():
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return json_error("入力内容を確認してください", 400)
    display_name = payload.get("displayName", g.account["display_name"])
    message = payload.get("message", g.account["message"])
    if not isinstance(display_name, str) or not display_name.strip() or len(display_name.strip()) > 80:
        return json_error("アカウント名は1〜80文字で入力してください", 400)
    if not isinstance(message, str) or len(message) > 500:
        return json_error("ひとことは500文字以内で入力してください", 400)
    connection = get_db()
    connection.execute(
        "UPDATE accounts SET display_name = ?, message = ? WHERE id = ?",
        (display_name.strip(), message, g.account["id"]),
    )
    connection.commit()
    updated = connection.execute("SELECT * FROM accounts WHERE id = ?", (g.account["id"],)).fetchone()
    return jsonify(account=account_json(updated))


@app.put("/api/v1/account/me/avatar")
@require_account
def update_avatar():
    saved = store_photo(request.files.get("avatar"), max_bytes=1024 * 1024)
    if saved is None:
        return json_error("アイコンはJPEG・PNG・WebP形式、1MB以下で選んでください", 400)
    filename, mime = saved
    old_filename = g.account["avatar_filename"]
    connection = get_db()
    connection.execute(
        "UPDATE accounts SET avatar_filename = ?, avatar_mime = ?, avatar_updated_at = ? WHERE id = ?",
        (filename, mime, int(time.time() * 1000), g.account["id"]),
    )
    connection.commit()
    if old_filename:
        (PHOTO_DIR / old_filename).unlink(missing_ok=True)
    updated = connection.execute("SELECT * FROM accounts WHERE id = ?", (g.account["id"],)).fetchone()
    return jsonify(account=account_json(updated))


@app.get("/api/v1/community/members")
@require_account
def community_members():
    rows = get_db().execute("SELECT * FROM accounts WHERE community_joined = 1 AND community_id = ? ORDER BY registered_at, login_name",
                            (my_community(),)).fetchall()
    return jsonify(members=[account_json(row) for row in rows])


@app.get("/api/v1/community/members/<account_code>/avatar")
@require_account
def community_avatar(account_code: str):
    row = get_db().execute("SELECT avatar_filename, avatar_mime FROM accounts WHERE account_code = ?", (account_code,)).fetchone()
    if row is None or not row["avatar_filename"]:
        abort(404)
    path = PHOTO_DIR / row["avatar_filename"]
    if not path.is_file():
        abort(404)
    return send_file(path, mimetype=row["avatar_mime"], conditional=True, max_age=0)


@app.get("/api/v1/tag-groups")
@require_account
def get_tag_groups():
    row = get_db().execute("SELECT groups_json FROM tag_groups WHERE owner_id = ?", (g.account["id"],)).fetchone()
    return jsonify(groups=json.loads(row["groups_json"]) if row else [])


@app.put("/api/v1/tag-groups")
@require_account
def put_tag_groups():
    payload = request.get_json(silent=True)
    groups = payload.get("groups") if isinstance(payload, dict) else None
    if not isinstance(groups, list) or len(groups) > 100:
        return json_error("タググループの形式が正しくありません", 400)
    try:
        encoded = json.dumps(groups, ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError):
        return json_error("タググループの形式が正しくありません", 400)
    if len(encoded.encode("utf-8")) > 512 * 1024:
        return json_error("タググループが大きすぎます", 413)
    connection = get_db()
    connection.execute(
        """INSERT INTO tag_groups(owner_id, groups_json, updated_at) VALUES (?, ?, ?)
           ON CONFLICT(owner_id) DO UPDATE SET groups_json = excluded.groups_json, updated_at = excluded.updated_at""",
        (g.account["id"], encoded, int(time.time())),
    )
    connection.commit()
    return jsonify(groups=groups)


@app.route("/api/v1/observations", methods=["GET", "POST"])
@require_account
def observations():
    connection = get_db()
    if request.method == "GET":
        try:
            limit = min(500, max(1, int(request.args.get("limit", "200"))))
            offset = max(0, int(request.args.get("offset", "0")))
            since = max(0, int(request.args.get("since", "0")))
        except ValueError:
            return json_error("ページ指定が正しくありません", 400)
        server_time = int(time.time())
        if "since" in request.args:
            rows = connection.execute(
                """SELECT * FROM observations WHERE (owner_id = ? OR """ + shared_in_my_community() + """) AND updated_at >= ?
                   ORDER BY updated_at ASC, id ASC LIMIT ? OFFSET ?""",
                (g.account["id"], my_community(), since, limit, offset),
            ).fetchall()
        else:
            rows = connection.execute(
                """SELECT * FROM observations WHERE owner_id = ? OR """ + shared_in_my_community() + """
                   ORDER BY updated_at DESC LIMIT ? OFFSET ?""",
                (g.account["id"], my_community(), limit, offset),
            ).fetchall()
        result = {"observations": [observation_json(connection, row) for row in rows],
                  "serverTime": server_time}
        if offset == 0:
            result["visibleIds"] = [row["id"] for row in connection.execute(
                "SELECT id FROM observations WHERE owner_id = ? OR " + shared_in_my_community(),
                (g.account["id"], my_community()))]
        return jsonify(result)

    payload, upload = request_payload()
    metadata = validate_observation(payload or {}) if payload is not None else None
    if metadata is None:
        return json_error("観察記録の内容を確認してください", 400)
    shared = payload.get("shared", False)
    if not isinstance(shared, bool):
        return json_error("共有設定の形式が正しくありません", 400)
    try:
        photos = save_observation_photos(payload, required=True)
    except ValueError as error:
        return json_error(str(error), 400)
    filename, mime = photos[0]["filename"], photos[0]["mime"]
    now = int(time.time())
    try:
        connection.execute(
            """INSERT INTO observations(id, owner_id, data_json, photo_filename, photo_mime, shared, created_at, updated_at, extra_photos_json)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (metadata["id"], g.account["id"], json.dumps(metadata, ensure_ascii=False, allow_nan=False), filename, mime, int(shared), now, now, json.dumps(photos[1:])),
        )
        connection.commit()
    except sqlite3.IntegrityError:
        delete_photos(photos)
        return json_error("同じIDの観察記録がすでにあります", 409)
    row = connection.execute("SELECT * FROM observations WHERE id = ?", (metadata["id"],)).fetchone()
    return jsonify(observation=observation_json(connection, row)), 201


@app.route("/api/v1/observations/<observation_id>", methods=["GET", "PUT", "DELETE"])
@require_account
def observation_detail(observation_id: str):
    connection = get_db()
    row = connection.execute("SELECT * FROM observations WHERE id = ?", (observation_id,)).fetchone()
    if row is None or not visible_to_me(row):
        return json_error("観察記録が見つかりません", 404)
    if request.method == "GET":
        return jsonify(observation=observation_json(connection, row))
    if row["owner_id"] != g.account["id"]:
        return json_error("他の人の記録は変更できません", 403)
    if request.method == "DELETE":
        connection.execute("DELETE FROM observations WHERE id = ? AND owner_id = ?", (observation_id, g.account["id"]))
        connection.commit()
        delete_photos(row_photos(row))
        return jsonify(ok=True)

    payload, upload = request_payload()
    metadata = validate_observation(payload or {}, forced_id=observation_id) if payload is not None else None
    if metadata is None:
        return json_error("観察記録の内容を確認してください", 400)
    shared = payload.get("shared", bool(row["shared"]))
    if not isinstance(shared, bool):
        return json_error("共有設定の形式が正しくありません", 400)
    if metadata["locationPrivate"] and upload is None:
        return json_error("場所を非公開にする場合は位置情報を除いた写真を再送信してください", 400)
    old_photos = row_photos(row)
    if metadata["locationPrivate"] and len(old_photos) > 1 and "photoCount" not in payload:
        return json_error("全写真の位置情報を削除するため、最新版から写真を再送信してください", 400)
    try:
        photos = save_observation_photos(payload, required=False)
    except ValueError as error:
        return json_error(str(error), 400)
    if photos is None and "photoCount" in payload and payload["photoCount"] != len(old_photos):
        return json_error("写真を変更する場合は写真データも再送信してください", 400)
    # Older clients replacing the primary image must not erase additional photos.
    effective_photos = old_photos if photos is None else (
        photos + old_photos[1:] if "photoCount" not in payload and len(photos) == 1 else photos
    )
    filename, mime = effective_photos[0]["filename"], effective_photos[0]["mime"]
    now = max(int(time.time()), row["updated_at"] + 1)
    try:
        connection.execute(
            """UPDATE observations SET data_json = ?, photo_filename = ?, photo_mime = ?, shared = ?, updated_at = ?, extra_photos_json = ?
               WHERE id = ? AND owner_id = ?""",
            (json.dumps(metadata, ensure_ascii=False, allow_nan=False), filename, mime, int(shared), now,
             json.dumps(effective_photos[1:]), observation_id, g.account["id"]),
        )
        connection.commit()
    except Exception:
        connection.rollback()
        if photos:
            delete_photos(photos)
        raise
    if photos:
        retained = {photo["filename"] for photo in effective_photos}
        delete_photos([photo for photo in old_photos if photo["filename"] not in retained])
    updated = connection.execute("SELECT * FROM observations WHERE id = ?", (observation_id,)).fetchone()
    return jsonify(observation=observation_json(connection, updated))


def shared_comment_observation(observation_id: str):
    if not g.account["community_joined"]:
        abort(403)
    row = get_db().execute("SELECT * FROM observations WHERE id = ? AND " + shared_in_my_community(),
                           (observation_id, my_community())).fetchone()
    if row is None:
        abort(404)
    return row


def comment_json(connection: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
    author = connection.execute("SELECT * FROM accounts WHERE id = ?", (row["author_id"],)).fetchone()
    return {"id": row["id"], "observationId": row["observation_id"], "parentId": row["parent_id"],
            "body": "" if row["deleted_at"] else row["body"], "createdAt": row["created_at"],
            "deleted": bool(row["deleted_at"]), "editedAt": row["edited_at"], "author": account_json(author) if author else None}


def add_notification(connection, recipient_id, observation_id, kind, event_key, comment_id=None):
    if recipient_id == g.account["id"]:
        return
    connection.execute("""INSERT OR IGNORE INTO notifications
        (recipient_id, actor_id, observation_id, comment_id, kind, event_key, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)""",
        (recipient_id, g.account["id"], observation_id, comment_id, kind, event_key, int(time.time() * 1000)))


def notification_counts(connection):
    row = connection.execute("""SELECT COUNT(CASE WHEN n.read_at IS NULL THEN 1 END) AS unread,
        COALESCE(MAX(n.id), 0) AS newest FROM notifications n
        JOIN observations o ON o.id = n.observation_id WHERE n.recipient_id = ? AND """ + shared_in_my_community("o"),
        (g.account["id"], my_community())).fetchone()
    return {"unreadCount": row["unread"], "newestId": row["newest"]}


@app.get("/api/v1/notifications")
@require_account
def list_notifications():
    if not g.account["community_joined"]:
        abort(403)
    try:
        limit = min(100, max(1, int(request.args.get("limit", "30"))))
        before = int(request.args["before"]) if "before" in request.args else None
        if before is not None and before < 1:
            raise ValueError()
    except ValueError:
        return json_error("ページ指定が正しくありません", 400)
    connection = get_db()
    clause = " AND n.id < ?" if before is not None else ""
    params = [g.account["id"], my_community()] + ([before] if before is not None else []) + [limit + 1]
    rows = connection.execute("""SELECT n.*, o.data_json, c.body, c.deleted_at FROM notifications n
        JOIN observations o ON o.id = n.observation_id
        LEFT JOIN observation_comments c ON c.id = n.comment_id
        WHERE n.recipient_id = ? AND """ + shared_in_my_community("o") + clause + " ORDER BY n.id DESC LIMIT ?", params).fetchall()
    page = rows[:limit]
    items = []
    for row in page:
        actor = connection.execute("SELECT * FROM accounts WHERE id = ?", (row["actor_id"],)).fetchone()
        items.append({"id": row["id"], "kind": row["kind"], "observationId": row["observation_id"],
            "observationName": json.loads(row["data_json"]).get("name", ""), "commentId": row["comment_id"],
            "commentBody": (row["body"] or "")[:120] if not row["deleted_at"] else "",
            "createdAt": row["created_at"], "read": row["read_at"] is not None,
            "actor": account_json(actor) if actor else None})
    return jsonify(notifications=items, hasMore=len(rows) > limit,
                   nextBefore=page[-1]["id"] if len(rows) > limit else None, **notification_counts(connection))


@app.patch("/api/v1/notifications/read")
@require_account
def read_notifications():
    if not g.account["community_joined"]:
        abort(403)
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return json_error("通知の指定が正しくありません", 400)
    connection = get_db()
    now = int(time.time() * 1000)
    if payload.get("all") is True:
        through = payload.get("throughId")
        if type(through) is not int or through < 0:
            return json_error("通知の指定が正しくありません", 400)
        connection.execute("""UPDATE notifications SET read_at = ? WHERE recipient_id = ? AND id <= ?
            AND read_at IS NULL AND observation_id IN (SELECT id FROM observations WHERE """ + shared_in_my_community() + ")",
            (now, g.account["id"], through, my_community()))
    else:
        ids = payload.get("ids")
        if not isinstance(ids, list) or not 1 <= len(ids) <= 100 or any(type(i) is not int or i < 1 for i in ids):
            return json_error("通知の指定が正しくありません", 400)
        marks = ",".join("?" for _ in ids)
        connection.execute(f"UPDATE notifications SET read_at = ? WHERE recipient_id = ? AND id IN ({marks}) AND read_at IS NULL",
                           [now, g.account["id"], *ids])
    connection.commit()
    return jsonify(**notification_counts(connection))


@app.route("/api/v1/observations/<observation_id>/comments", methods=["GET", "POST"])
@require_account
def observation_comments(observation_id: str):
    observation = shared_comment_observation(observation_id)
    connection = get_db()
    if request.method == "POST":
        payload = request.get_json(silent=True)
        body = payload.get("body") if isinstance(payload, dict) else None
        parent_id = payload.get("parentId") if isinstance(payload, dict) else None
        if not isinstance(body, str) or not 1 <= len(body.strip()) <= 1000:
            return json_error("コメントは1～1000文字で入力してください", 400)
        if parent_id is not None:
            if not isinstance(parent_id, str) or not connection.execute(
                "SELECT 1 FROM observation_comments WHERE id = ? AND observation_id = ?", (parent_id, observation_id)
            ).fetchone():
                return json_error("返信先のコメントが見つかりません", 400)
        comment_id = str(uuid.uuid4())
        connection.execute("""INSERT INTO observation_comments
            (id, observation_id, author_id, parent_id, body, created_at) VALUES (?, ?, ?, ?, ?, ?)""",
            (comment_id, observation_id, g.account["id"], parent_id, body.strip(), int(time.time() * 1000)))
        recipients = {observation["owner_id"]: "comment"}
        if parent_id:
            parent = connection.execute("SELECT author_id FROM observation_comments WHERE id = ?", (parent_id,)).fetchone()
            recipients[parent["author_id"]] = "reply"
        for recipient, kind in recipients.items():
            add_notification(connection, recipient, observation_id, kind, "comment:" + comment_id, comment_id)
        connection.commit()
        row = connection.execute("SELECT * FROM observation_comments WHERE id = ?", (comment_id,)).fetchone()
        return jsonify(comment=comment_json(connection, row)), 201
    try:
        limit = min(100, max(1, int(request.args.get("limit", "100"))))
    except ValueError:
        return json_error("ページ指定が正しくありません", 400)
    before = request.args.get("before")
    clause, params = "", [observation_id]
    if before:
        cursor = connection.execute("SELECT created_at, id FROM observation_comments WHERE id = ? AND observation_id = ?",
                                    (before, observation_id)).fetchone()
        if cursor is None:
            return json_error("前のコメントの指定が正しくありません", 400)
        clause = " AND (created_at, id) < (?, ?)"
        params.extend([cursor["created_at"], cursor["id"]])
    rows = connection.execute("SELECT * FROM observation_comments WHERE observation_id = ?" + clause +
        " ORDER BY created_at DESC, id DESC LIMIT ?", (*params, limit + 1)).fetchall()
    page = rows[:limit]
    return jsonify(comments=[comment_json(connection, row) for row in reversed(page)], hasMore=len(rows) > limit,
                   nextBefore=page[-1]["id"] if len(rows) > limit else None)


@app.route("/api/v1/observations/<observation_id>/comments/<comment_id>", methods=["PATCH", "DELETE"])
@require_account
def delete_observation_comment(observation_id: str, comment_id: str):
    shared_comment_observation(observation_id)
    connection = get_db()
    row = connection.execute("SELECT * FROM observation_comments WHERE id = ? AND observation_id = ?",
                             (comment_id, observation_id)).fetchone()
    if row is None:
        abort(404)
    if row["author_id"] != g.account["id"]:
        return json_error("他の人のコメントは編集・削除できません", 403)
    if request.method == "PATCH":
        if row["deleted_at"]:
            return json_error("削除済みのコメントは編集できません", 410)
        payload = request.get_json(silent=True)
        body = payload.get("body") if isinstance(payload, dict) else None
        if not isinstance(body, str) or not 1 <= len(body.strip()) <= 1000:
            return json_error("コメントは1～1000文字で入力してください", 400)
        updated = connection.execute("UPDATE observation_comments SET body = ?, edited_at = ? WHERE id = ? AND deleted_at IS NULL",
                                     (body.strip(), int(time.time() * 1000), comment_id))
        if updated.rowcount == 0:
            return json_error("削除済みのコメントは編集できません", 410)
    else:
        connection.execute("UPDATE observation_comments SET body = '', deleted_at = COALESCE(deleted_at, ?) WHERE id = ?",
                           (int(time.time() * 1000), comment_id))
        connection.execute("DELETE FROM notifications WHERE comment_id = ?", (comment_id,))
    connection.commit()
    return jsonify(comment=comment_json(connection, connection.execute(
        "SELECT * FROM observation_comments WHERE id = ?", (comment_id,)).fetchone()))


@app.get("/api/v1/community/members/<account_code>/observations")
@require_account
def member_observations(account_code: str):
    if not g.account["community_joined"]:
        abort(403)
    if not re.fullmatch(r"BD-[A-F0-9]{8,12}", account_code):
        abort(404)
    try:
        limit = min(100, max(1, int(request.args.get("limit", "20"))))
        offset = max(0, int(request.args.get("offset", "0")))
    except ValueError:
        return json_error("ページ指定が正しくありません", 400)
    connection = get_db()
    query = ("FROM observations o JOIN accounts a ON a.id = o.owner_id WHERE a.account_code = ? AND o.shared = 1"
             " AND a.community_id = ?")
    total = connection.execute("SELECT COUNT(*) " + query, (account_code, my_community())).fetchone()[0]
    rows = connection.execute("SELECT o.* " + query + " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?",
                              (account_code, my_community(), limit, offset)).fetchall()
    return jsonify(observations=[observation_json(connection, row) for row in rows], total=total,
                   hasMore=offset + len(rows) < total, nextOffset=offset + len(rows))


@app.get("/api/v1/observations/reactions/summary")
@require_account
def reaction_summary():
    if not g.account["community_joined"]:
        abort(403)
    rows = get_db().execute("""SELECT o.id, o.created_at, COUNT(l.account_id) AS count,
        COALESCE(MAX(l.account_id = ?), 0) AS liked FROM observations o
        LEFT JOIN observation_likes l ON l.observation_id = o.id WHERE """ + shared_in_my_community("o") + """
        GROUP BY o.id""", (g.account["id"], my_community())).fetchall()
    return jsonify(reactions=[{"id": row["id"], "likeCount": row["count"], "likedByMe": bool(row["liked"]),
                               "createdAt": row["created_at"] * 1000} for row in rows])


@app.route("/api/v1/observations/<observation_id>/likes", methods=["GET", "PUT", "DELETE"])
@require_account
def observation_likes(observation_id: str):
    row = shared_comment_observation(observation_id)
    connection = get_db()
    if request.method == "PUT":
        if row["owner_id"] == g.account["id"]:
            return json_error("自分の投稿にはいいねを付けられません", 403)
        inserted = connection.execute("INSERT OR IGNORE INTO observation_likes(observation_id, account_id, created_at) VALUES (?, ?, ?)",
                           (observation_id, g.account["id"], int(time.time() * 1000)))
        if inserted.rowcount:
            add_notification(connection, row["owner_id"], observation_id, "like", "like:" + observation_id + ":" + g.account["id"])
        connection.commit()
    elif request.method == "DELETE":
        connection.execute("DELETE FROM observation_likes WHERE observation_id = ? AND account_id = ?",
                           (observation_id, g.account["id"]))
        connection.execute("DELETE FROM notifications WHERE event_key = ? AND actor_id = ?",
                           ("like:" + observation_id + ":" + g.account["id"], g.account["id"]))
        connection.commit()
    return jsonify(reaction=like_state(connection, row))


@app.get("/api/v1/observations/<observation_id>/photo")
@app.get("/api/v1/observations/<observation_id>/photo/<int:photo_index>")
@require_account
def observation_photo(observation_id: str, photo_index: int = 1):
    row = get_db().execute("SELECT * FROM observations WHERE id = ?", (observation_id,)).fetchone()
    if row is None or not visible_to_me(row):
        abort(404)
    photos = row_photos(row)
    if not 1 <= photo_index <= len(photos):
        abort(404)
    photo = photos[photo_index - 1]
    path = PHOTO_DIR / photo["filename"]
    if not path.is_file():
        abort(404)
    return send_file(path, mimetype=photo["mime"], conditional=True, max_age=0)


if __name__ == "__main__":
    raise SystemExit("開発サーバーでの起動は禁止です。READMEのWaitress手順を使ってください。")
