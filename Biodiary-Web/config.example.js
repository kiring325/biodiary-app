// 接続先の設定。このファイルを config.js という名前でコピーし、公開した Cloudflare Worker の URL を書く。
// config.js は Git に含めない（.gitignore 済み）。公開サイトに配置するときは config.js も一緒に置く。
window.BIODIARY_CONFIG = {
  apiUrl: 'https://<Workerの名前>.<アカウントのサブドメイン>.workers.dev',
};
