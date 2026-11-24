CREATE TABLE IF NOT EXISTS accounts (
  account_code TEXT PRIMARY KEY,
  login_name TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  message TEXT NOT NULL DEFAULT '',
  password_salt TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  registered_at INTEGER NOT NULL,
  community_joined INTEGER NOT NULL DEFAULT 0,
  avatar_updated_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  account_code TEXT NOT NULL REFERENCES accounts(account_code) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_account ON sessions(account_code);
CREATE TABLE IF NOT EXISTS community_invites (
  code_hash TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_by TEXT REFERENCES accounts(account_code)
);
CREATE TABLE IF NOT EXISTS registration_attempts (
  ip_hash TEXT NOT NULL,
  attempted_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS registration_attempts_ip ON registration_attempts(ip_hash, attempted_at);
CREATE TABLE IF NOT EXISTS login_attempts (
  ip_hash TEXT NOT NULL,
  login_name TEXT NOT NULL,
  attempted_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS login_attempts_key ON login_attempts(ip_hash, login_name, attempted_at);
