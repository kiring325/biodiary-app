import { randomBytes, createHash } from 'node:crypto';

// 使い方:
//   node scripts/new-invite.mjs 5
//     → いつものコミュニティ（default）の招待コードを5件
//   node scripts/new-invite.mjs 3 --community interview --name "面接用コミュニティ"
//     → 別のコミュニティ「interview」を作り（まだなければ）、その招待コードを3件
const usage = '使い方: node scripts/new-invite.mjs [発行数1〜60] [--community ID] [--name コミュニティ名]';

const args = process.argv.slice(2);
let count = 1, communityId = 'default', communityName = '';
for (let i = 0; i < args.length; i++) {
  const arg = args[i];
  if (arg === '--community') communityId = args[++i];
  else if (arg === '--name') communityName = args[++i];
  else if (/^\d+$/.test(arg)) count = Number(arg);
  else {
    console.error(`わからない指定です: ${arg}\n${usage}`);
    process.exit(1);
  }
}
if (!Number.isInteger(count) || count < 1 || count > 60) {
  console.error(`発行数は1〜60の整数で指定してください。\n${usage}`);
  process.exit(1);
}
if (typeof communityId !== 'string' || !/^[a-z0-9-]{1,32}$/.test(communityId)) {
  console.error('コミュニティIDは英小文字・数字・ハイフンの1〜32文字で指定してください。例: --community interview');
  process.exit(1);
}
if (communityName === undefined || communityName.length > 60 || /[\x00-\x1f\x7f]/.test(communityName)) {
  console.error('コミュニティ名は60文字以内で指定してください。');
  process.exit(1);
}
const isDefault = communityId === 'default';
if (!isDefault && !communityName.trim()) communityName = `Biodiaryコミュニティ（${communityId}）`;
const sqlText = value => `'${value.replaceAll("'", "''")}'`;

const createdAt = Math.floor(Date.now() / 1000);
const expiresAt = createdAt + 14 * 86400;
const invites = Array.from({ length: count }, () => {
  const code = `BD-COM-${randomBytes(12).toString('hex').toUpperCase()}`;
  return { code, hash: createHash('sha256').update(code).digest('hex') };
});

console.log(isDefault
  ? 'いつものコミュニティ（default）の招待コードです。'
  : `コミュニティ「${communityName.trim()}」（ID: ${communityId}）の招待コードです。最初に参加した人がこのコミュニティの管理者になります。`);
console.log('招待コード（1人に1つずつ伝えるもの・14日間有効）:');
invites.forEach(({ code }, index) => console.log(`${index + 1}. ${code}`));
console.log('\nCloudflare D1に一度だけ登録するSQL:');
if (!isDefault) {
  // すでに同じIDのコミュニティがあれば名前は変えない
  console.log(`INSERT OR IGNORE INTO communities(id, name, updated_at) VALUES (${sqlText(communityId)}, ${sqlText(communityName.trim())}, ${createdAt});`);
}
console.log('INSERT INTO community_invites(code_hash, created_at, expires_at, community_id) VALUES\n' +
  invites.map(({ hash }) => `  ('${hash}', ${createdAt}, ${expiresAt}, ${sqlText(communityId)})`).join(',\n') + ';');
