const encoder = new TextEncoder();
const DAY = 86400;
const SESSION_SECONDS = 30 * DAY;

class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

const reply = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
const hex = bytes => [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
const randomHex = size => hex(crypto.getRandomValues(new Uint8Array(size)));
const base64url = bytes => btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const sha256 = async value => hex(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value))));
const nowSeconds = () => Math.floor(Date.now() / 1000);

async function legacyPasswordHash(password, saltHex) {
  const salt = new Uint8Array(saltHex.match(/../g).map(value => parseInt(value, 16)));
  const key = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', salt, iterations: 210000, hash: 'SHA-256' }, key, 256);
  return hex(new Uint8Array(bits));
}

async function passwordHash(password, saltHex, env) {
  const key = await crypto.subtle.importKey('raw', encoder.encode(env.PASSWORD_PEPPER),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const digest = await crypto.subtle.sign('HMAC', key, encoder.encode(saltHex + ':' + password));
  return 'hmac:' + hex(new Uint8Array(digest));
}

async function passwordMatches(password, account, env) {
  if (!account?.password_hash) return false;
  const expected = account.password_hash.startsWith('hmac:')
    ? await passwordHash(password, account.password_salt, env)
    : await legacyPasswordHash(password, account.password_salt);
  return constantTimeEqual(expected, account.password_hash);
}

function constantTimeEqual(left, right) {
  if (left.length !== right.length) return false;
  let mismatch = 0;
  for (let i = 0; i < left.length; i++) mismatch |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return mismatch === 0;
}

// 参加しているコミュニティのID。未参加なら空文字。移行前の行（community_id が空）は 'default' とみなす。
function communityOf(row) {
  if (!row?.community_joined) return '';
  return row.community_id || 'default';
}

function accountJson(row) {
  return {
    accountId: row.account_code,
    loginName: row.login_name,
    displayName: row.display_name,
    message: row.message,
    registeredAt: row.registered_at,
    communityMember: Boolean(row.community_joined),
    communityId: communityOf(row),
    guideSeenAt: row.onboarding_guide_seen_at || 0,
    avatarUrl: row.avatar_updated_at ? `/api/v1/community/members/${encodeURIComponent(row.account_code)}/avatar` : '',
    avatarUpdatedAt: row.avatar_updated_at,
  };
}

async function newSession(db, accountCode) {
  const token = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const expiresAt = nowSeconds() + SESSION_SECONDS;
  await db.prepare('INSERT INTO sessions(token_hash, account_code, expires_at) VALUES (?, ?, ?)')
    .bind(await sha256(token), accountCode, expiresAt).run();
  return { token, expiresAt };
}

async function currentAccount(request, env) {
  const match = /^Bearer (\S+)$/i.exec(request.headers.get('Authorization') || '');
  if (!match) throw new ApiError(401, 'ログインしてください');
  const row = await env.DB.prepare(
    'SELECT a.* FROM sessions s JOIN accounts a ON a.account_code=s.account_code WHERE s.token_hash=? AND s.expires_at>?'
  ).bind(await sha256(match[1]), nowSeconds()).first();
  if (!row) throw new ApiError(401, 'ログインの有効期限が切れています');
  return row;
}

function requestIp(request) { return request.headers.get('CF-Connecting-IP') || 'unknown'; }
async function ipHash(request, env) { return sha256(env.PI_SHARED_SECRET + ':' + requestIp(request)); }

async function register(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || typeof body !== 'object') throw new ApiError(400, '入力内容を確認してください');
  const loginName = String(body.loginName || '').trim().toLowerCase();
  const displayName = String(body.displayName || '').trim();
  const password = body.password;
  if (!/^[a-z0-9_.-]{3,32}$/.test(loginName)) throw new ApiError(400, 'ログイン名は英数字と . _ - の3〜32文字で入力してください');
  if (typeof password !== 'string' || password.length < 12 || password.length > 200) throw new ApiError(400, 'パスワードは12〜200文字で設定してください');
  if (!displayName || displayName.length > 80) throw new ApiError(400, 'アカウント名は1〜80文字で入力してください');
  const ip = await ipHash(request, env), now = nowSeconds();
  const attempts = await env.DB.prepare('SELECT COUNT(*) AS count FROM registration_attempts WHERE ip_hash=? AND attempted_at>?')
    .bind(ip, now - DAY).first();
  if (attempts.count >= 5) throw new ApiError(429, '登録の試行が多いため、明日以降に再試行してください');
  await env.DB.prepare('INSERT INTO registration_attempts(ip_hash, attempted_at) VALUES (?, ?)')
    .bind(ip, now).run();
  const count = await env.DB.prepare('SELECT COUNT(*) AS count FROM accounts').first();
  if (count.count >= Number(env.MAX_ACCOUNTS || 1000)) throw new ApiError(409, '登録できるアカウント数の上限に達しています');
  const existing = await env.DB.prepare('SELECT 1 FROM accounts WHERE login_name=?').bind(loginName).first();
  if (existing) throw new ApiError(409, 'そのログイン名はすでに使われています');
  const salt = randomHex(16), hash = await passwordHash(password, salt, env);
  const accountCode = `BD-${randomHex(6).toUpperCase()}`;
  try {
    await env.DB.prepare(
      'INSERT INTO accounts(account_code, login_name, display_name, password_salt, password_hash, registered_at) VALUES (?, ?, ?, ?, ?, ?)'
    ).bind(accountCode, loginName, displayName, salt, hash, Date.now()).run();
  } catch {
    throw new ApiError(409, 'ログイン名またはアカウントIDがすでに使われています');
  }
  const session = await newSession(env.DB, accountCode);
  const account = await env.DB.prepare('SELECT * FROM accounts WHERE account_code=?').bind(accountCode).first();
  return reply({ ...session, account: accountJson(account) }, 201);
}

async function tryLegacyLogin(env, loginName, password) {
  if (env.LEGACY_LOGIN_ENABLED !== 'true') return null;
  const response = await fetch(new URL('/api/v1/auth/login', env.PI_ORIGIN), {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ loginName, password }),
  }).catch(() => null);
  if (!response?.ok) return null;
  const result = await response.json().catch(() => null);
  if (!result?.account?.accountId) return null;
  const legacy = result.account;
  const salt = randomHex(16), hash = await passwordHash(password, salt, env);
  const existing = await env.DB.prepare('SELECT account_code FROM accounts WHERE login_name=?').bind(loginName).first();
  if (existing && existing.account_code !== legacy.accountId) return null;
  if (existing) {
    await env.DB.prepare('UPDATE accounts SET password_salt=?, password_hash=?, community_joined=1, community_id=COALESCE(community_id, \'default\') WHERE account_code=?')
      .bind(salt, hash, legacy.accountId).run();
  } else {
    await env.DB.prepare(
      'INSERT INTO accounts(account_code, login_name, display_name, message, password_salt, password_hash, registered_at, community_joined, community_id, avatar_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, \'default\', ?)'
    ).bind(legacy.accountId, loginName, legacy.displayName || loginName, legacy.message || '', salt, hash,
      Number(legacy.registeredAt) || Date.now(), Number(legacy.avatarUpdatedAt) || 0).run();
  }
  return env.DB.prepare('SELECT * FROM accounts WHERE login_name=?').bind(loginName).first();
}

async function login(request, env) {
  const body = await request.json().catch(() => null);
  const loginName = String(body?.loginName || '').trim().toLowerCase();
  const password = body?.password;
  if (!loginName || typeof password !== 'string') throw new ApiError(401, 'ログイン名またはパスワードが違います');
  const ip = await ipHash(request, env), now = nowSeconds();
  const attempts = await env.DB.prepare('SELECT COUNT(*) AS count FROM login_attempts WHERE ip_hash=? AND login_name=? AND attempted_at>?')
    .bind(ip, loginName, now - 900).first();
  if (attempts.count >= 8) throw new ApiError(429, 'ログイン試行が多いため、15分後に再試行してください');
  let account = await env.DB.prepare('SELECT * FROM accounts WHERE login_name=?').bind(loginName).first();
  if (!account || !account.password_hash) account = await tryLegacyLogin(env, loginName, password);
  const valid = await passwordMatches(password, account, env);
  if (!valid) {
    await env.DB.prepare('INSERT INTO login_attempts(ip_hash, login_name, attempted_at) VALUES (?, ?, ?)')
      .bind(ip, loginName, now).run();
    throw new ApiError(401, 'ログイン名またはパスワードが違います');
  }
  if (!account.password_hash.startsWith('hmac:')) {
    const salt = randomHex(16);
    await env.DB.prepare('UPDATE accounts SET password_salt=?, password_hash=? WHERE account_code=?')
      .bind(salt, await passwordHash(password, salt, env), account.account_code).run();
  }
  await env.DB.prepare('DELETE FROM login_attempts WHERE ip_hash=? AND login_name=?').bind(ip, loginName).run();
  return reply({ ...(await newSession(env.DB, account.account_code)), account: accountJson(account) });
}

async function joinCommunity(request, env, account) {
  if (account.community_joined) return reply({ account: accountJson(account) });
  const body = await request.json().catch(() => null);
  const code = body?.inviteCode;
  if (typeof code !== 'string' || !code || code.length > 200) throw new ApiError(400, 'コミュニティの招待コードを入力してください');
  const now = nowSeconds(), codeHash = await sha256(code);
  const limit = Number(env.MAX_COMMUNITY_MEMBERS || 10);
  // 招待コードがどのコミュニティ用かを調べる
  const invite = await env.DB.prepare(
    'SELECT community_id FROM community_invites WHERE code_hash=? AND used_by IS NULL AND expires_at>?'
  ).bind(codeHash, now).first();
  if (!invite) throw new ApiError(400, '招待コードが無効か、有効期限が切れています');
  const communityId = invite.community_id || 'default';
  const community = await env.DB.prepare('SELECT 1 AS found FROM communities WHERE id=?').bind(communityId).first();
  if (!community) throw new ApiError(400, '招待コードのコミュニティが見つかりません');
  // 定員はコミュニティごとに数える
  const count = await env.DB.prepare(
    "SELECT COUNT(*) AS count FROM accounts WHERE community_joined=1 AND COALESCE(community_id, 'default')=?"
  ).bind(communityId).first();
  if (count.count >= limit) throw new ApiError(409, 'コミュニティの定員に達しています');
  const updateInvite = await env.DB.prepare(
    'UPDATE community_invites SET used_by=? WHERE code_hash=? AND used_by IS NULL AND expires_at>?'
  ).bind(account.account_code, codeHash, now).run();
  if (!updateInvite.meta.changes) throw new ApiError(400, '招待コードが無効か、有効期限が切れています');
  const joined = await env.DB.prepare(
    "UPDATE accounts SET community_joined=1, community_id=? WHERE account_code=? AND community_joined=0 AND " +
    "(SELECT COUNT(*) FROM accounts WHERE community_joined=1 AND COALESCE(community_id, 'default')=?)<?"
  ).bind(communityId, account.account_code, communityId, limit).run();
  if (!joined.meta.changes) {
    await env.DB.prepare('UPDATE community_invites SET used_by=NULL WHERE code_hash=? AND used_by=?')
      .bind(codeHash, account.account_code).run();
    throw new ApiError(409, 'コミュニティの定員に達しています');
  }
  // 'default' 以外の新しいコミュニティでは、最初に参加した人を管理者にする（コミュニティ名を変更できる）
  if (communityId !== 'default') {
    await env.DB.prepare(
      'INSERT OR IGNORE INTO community_admins(community_id, account_code) ' +
      'SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM community_admins WHERE community_id=?)'
    ).bind(communityId, account.account_code, communityId).run();
  }
  const updated = await env.DB.prepare('SELECT * FROM accounts WHERE account_code=?').bind(account.account_code).first();
  return reply({ account: accountJson(updated) });
}

async function signIdentity(env, account) {
  const claims = {
    accountId: account.account_code, loginName: account.login_name, displayName: account.display_name,
    message: account.message, registeredAt: account.registered_at,
    communityMember: Boolean(account.community_joined), communityId: communityOf(account),
    expiresAt: nowSeconds() + 60,
  };
  const payload = base64url(encoder.encode(JSON.stringify(claims)));
  const key = await crypto.subtle.importKey('raw', encoder.encode(env.PI_SHARED_SECRET), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signature = base64url(new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(payload))));
  return payload + '.' + signature;
}

async function communitySettings(request, env, account) {
  if (!account.community_joined) throw new ApiError(403, 'コミュニティへの参加が必要です');
  const id = communityOf(account);
  const admin = await env.DB.prepare('SELECT 1 AS allowed FROM community_admins WHERE community_id=? AND account_code=?')
    .bind(id, account.account_code).first();
  if (request.method === 'PATCH') {
    if (!admin) throw new ApiError(403, 'コミュニティ名の変更は管理者だけが行えます');
    const body = await request.json().catch(() => null), name = body?.name;
    if (typeof name !== 'string' || !name.trim() || name.trim().length > 60 || /[\x00-\x1f\x7f]/.test(name))
      throw new ApiError(400, 'コミュニティ名は1〜60文字で入力してください');
    await env.DB.prepare('UPDATE communities SET name=?, updated_at=? WHERE id=?').bind(name.trim(), nowSeconds(), id).run();
  }
  const row = await env.DB.prepare('SELECT id, name, updated_at FROM communities WHERE id=?').bind(id).first();
  if (!row) throw new ApiError(404, 'コミュニティが見つかりません');
  return reply({ community: { id: row.id, name: row.name, updatedAt: row.updated_at, isAdmin: Boolean(admin) } });
}

async function proxyToPi(request, env, account) {
  const url = new URL(request.url);
  const upstream = new Request(new URL(url.pathname + url.search, env.PI_ORIGIN), request);
  upstream.headers.delete('Authorization');
  upstream.headers.delete('Cookie');
  upstream.headers.set('X-Biodiary-Identity', await signIdentity(env, account));
  return fetch(upstream);
}

function allowedOrigin(origin, configured) {
  return configured.split(',').some(raw => {
    const item = raw.trim();
    if (item === origin) return true;
    if (item.startsWith('https://*.')) {
      const suffix = item.slice('https://*'.length);
      return origin.startsWith('https://') && origin.endsWith(suffix) && origin !== 'https://' + suffix.slice(1);
    }
    return false;
  });
}

async function handle(request, env) {
  const url = new URL(request.url), path = url.pathname;
  if (path === '/api/v1/status' && request.method === 'GET') return reply({ service: 'Biodiary API', version: 2 });
  if (path === '/api/v1/auth/register' && request.method === 'POST') return register(request, env);
  if (path === '/api/v1/auth/login' && request.method === 'POST') return login(request, env);
  const account = await currentAccount(request, env);
  if (path === '/api/v1/community' && ['GET', 'PATCH'].includes(request.method))
    return communitySettings(request, env, account);
  if (path === '/api/v1/auth/pi-ticket' && request.method === 'GET')
    return reply({ baseUrl: env.PI_ORIGIN, identity: await signIdentity(env, account), expiresAt: nowSeconds() + 60 });
  if (path === '/api/v1/auth/logout' && request.method === 'POST') {
    const token = request.headers.get('Authorization').slice(7);
    await env.DB.prepare('DELETE FROM sessions WHERE token_hash=?').bind(await sha256(token)).run();
    return reply({ ok: true });
  }
  if (path === '/api/v1/account/me' && request.method === 'GET') return reply({ account: accountJson(account) });
  if (path === '/api/v1/account/me/guide-seen' && request.method === 'POST') {
    const seenAt = account.onboarding_guide_seen_at || Date.now();
    if (!account.onboarding_guide_seen_at) {
      await env.DB.prepare('UPDATE accounts SET onboarding_guide_seen_at=? WHERE account_code=? AND onboarding_guide_seen_at=0')
        .bind(seenAt, account.account_code).run();
    }
    return reply({ guideSeenAt: seenAt });
  }
  if (path === '/api/v1/account/me' && request.method === 'PATCH') {
    const body = await request.json().catch(() => null);
    const name = body?.displayName, message = body?.message;
    if (typeof name !== 'string' || !name.trim() || name.trim().length > 80 ||
        typeof message !== 'string' || message.length > 500)
      throw new ApiError(400, 'アカウント名または一言を確認してください');
    await env.DB.prepare('UPDATE accounts SET display_name=?, message=? WHERE account_code=?')
      .bind(name.trim(), message, account.account_code).run();
    const updated = await env.DB.prepare('SELECT * FROM accounts WHERE account_code=?').bind(account.account_code).first();
    return reply({ account: accountJson(updated) });
  }
  if (path === '/api/v1/account/me/avatar-updated' && request.method === 'POST') {
    await env.DB.prepare('UPDATE accounts SET avatar_updated_at=? WHERE account_code=?')
      .bind(Date.now(), account.account_code).run();
    const updated = await env.DB.prepare('SELECT * FROM accounts WHERE account_code=?')
      .bind(account.account_code).first();
    return reply({ account: accountJson(updated) });
  }
  if (path === '/api/v1/community/join' && request.method === 'POST') return joinCommunity(request, env, account);
  if (path === '/api/v1/account/me/avatar' && request.method === 'PUT') {
    const upstream = await proxyToPi(request, env, account);
    if (!upstream.ok) return upstream;
    const result = await upstream.json();
    await env.DB.prepare('UPDATE accounts SET avatar_updated_at=? WHERE account_code=?')
      .bind(Number(result.account?.avatarUpdatedAt) || Date.now(), account.account_code).run();
    const updated = await env.DB.prepare('SELECT * FROM accounts WHERE account_code=?').bind(account.account_code).first();
    return reply({ account: accountJson(updated) });
  }
  if (path === '/api/v1/community/members' && request.method === 'GET') {
    if (!account.community_joined) throw new ApiError(403, 'コミュニティへの参加が必要です');
    const rows = await env.DB.prepare(
      "SELECT * FROM accounts WHERE community_joined=1 AND COALESCE(community_id, 'default')=? ORDER BY registered_at, login_name"
    ).bind(communityOf(account)).all();
    return reply({ members: rows.results.map(accountJson) });
  }
  const memberMatch = /^\/api\/v1\/community\/members\/(BD-[A-F0-9]{8,12})$/.exec(path);
  if (memberMatch && request.method === 'GET') {
    if (!account.community_joined) throw new ApiError(403, 'コミュニティへの参加が必要です');
    const member = await env.DB.prepare(
      "SELECT * FROM accounts WHERE account_code=? AND community_joined=1 AND COALESCE(community_id, 'default')=?"
    ).bind(memberMatch[1], communityOf(account)).first();
    if (!member) throw new ApiError(404, 'メンバーが見つかりません');
    return reply({ member: accountJson(member) });
  }
  const avatarMatch = /^\/api\/v1\/community\/members\/(BD-[A-F0-9]{8,12})\/avatar$/.exec(path);
  if (avatarMatch && request.method === 'GET') {
    if (avatarMatch[1] !== account.account_code) {
      if (!account.community_joined) throw new ApiError(403, 'コミュニティへの参加が必要です');
      const owner = await env.DB.prepare('SELECT community_joined, community_id FROM accounts WHERE account_code=?')
        .bind(avatarMatch[1]).first();
      if (!owner?.community_joined || communityOf(owner) !== communityOf(account)) throw new ApiError(404, '見つかりません');
    }
    return proxyToPi(request, env, account);
  }
  if (!account.community_joined) throw new ApiError(403, 'コミュニティへの参加が必要です');
  const permitted = (path === '/api/v1/tag-groups' && ['GET', 'PUT'].includes(request.method)) ||
    (path === '/api/v1/notifications' && request.method === 'GET') ||
    (path === '/api/v1/notifications/read' && request.method === 'PATCH') ||
    (path === '/api/v1/observations' && ['GET', 'POST'].includes(request.method)) ||
    (/^\/api\/v1\/observations\/[A-Za-z0-9_-]{1,80}$/.test(path) &&
      ['GET', 'PUT', 'DELETE'].includes(request.method)) ||
    (/^\/api\/v1\/observations\/[A-Za-z0-9_-]{1,80}\/photo(?:\/[1-4])?$/.test(path) && request.method === 'GET') ||
    (/^\/api\/v1\/observations\/[A-Za-z0-9_-]{1,80}\/comments$/.test(path) && ['GET','POST'].includes(request.method)) ||
    (/^\/api\/v1\/observations\/[A-Za-z0-9_-]{1,80}\/comments\/[A-Za-z0-9_-]{1,80}$/.test(path) && ['PATCH','DELETE'].includes(request.method)) ||
    (/^\/api\/v1\/community\/members\/BD-[A-F0-9]{8,12}\/observations$/.test(path) && request.method === 'GET') ||
    (path === '/api/v1/observations/reactions/summary' && request.method === 'GET') ||
    (/^\/api\/v1\/observations\/[A-Za-z0-9_-]{1,80}\/likes$/.test(path) && ['GET','PUT','DELETE'].includes(request.method));
  if (!permitted) throw new ApiError(404, '見つかりません');
  return proxyToPi(request, env, account);
}

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin');
    const permitted = origin && allowedOrigin(origin, env.ALLOWED_ORIGINS || '');
    if (request.method === 'OPTIONS') {
      if (!permitted) return new Response(null, { status: 403 });
      return new Response(null, { status: 204, headers: {
        'Access-Control-Allow-Origin': origin,
        'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type',
        'Access-Control-Max-Age': '86400', Vary: 'Origin',
      } });
    }
    let response;
    try {
      if (!env.DB || !env.PI_SHARED_SECRET || !env.PASSWORD_PEPPER || !env.PI_ORIGIN)
        throw new ApiError(503, 'サーバー設定が完了していません');
      response = await handle(request, env);
    } catch (error) {
      response = reply({ error: error instanceof ApiError ? error.message : 'サーバーで問題が発生しました' },
        error instanceof ApiError ? error.status : 500);
    }
    const output = new Response(response.body, response);
    if (permitted) {
      output.headers.set('Access-Control-Allow-Origin', origin);
      output.headers.set('Vary', 'Origin');
    }
    output.headers.set('Cache-Control', 'no-store');
    return output;
  },
};
