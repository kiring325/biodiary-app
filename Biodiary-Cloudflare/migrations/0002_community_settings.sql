CREATE TABLE IF NOT EXISTS communities (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS community_admins (
  community_id TEXT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
  account_code TEXT NOT NULL REFERENCES accounts(account_code) ON DELETE CASCADE,
  PRIMARY KEY (community_id, account_code)
);
INSERT OR IGNORE INTO communities(id, name, updated_at)
VALUES ('default', 'Biodiaryコミュニティ', unixepoch());
INSERT OR IGNORE INTO community_admins(community_id, account_code)
SELECT 'default', account_code FROM accounts
WHERE account_code = 'BD-3E5D41B6' AND community_joined = 1;
