-- コミュニティを複数持てるようにする（例：面接・見学用の別コミュニティ）。
-- 参加済みの既存アカウントと既存の招待コードは、これまでどおり 'default' のコミュニティに属する。
ALTER TABLE accounts ADD COLUMN community_id TEXT;
UPDATE accounts SET community_id = 'default' WHERE community_joined = 1 AND community_id IS NULL;
CREATE INDEX IF NOT EXISTS accounts_community ON accounts(community_id, community_joined);
ALTER TABLE community_invites ADD COLUMN community_id TEXT NOT NULL DEFAULT 'default';
