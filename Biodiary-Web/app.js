(() => {
  'use strict';
  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => [...root.querySelectorAll(s)];
  const SEASONS = [{name:'春',color:'#ed8194',months:[2,3,4]},{name:'夏',color:'#e7d544',months:[5,6,7]},{name:'秋',color:'#e69b57',months:[8,9,10]},{name:'冬',color:'#91bdf0',months:[11,0,1]}];
  const CATEGORIES = ['植物','鳥類','昆虫','哺乳類','魚類','爬虫類','両生類','菌類','その他'];
  const CATEGORY_COLORS = ['#739B72','#7A94C2','#D99648','#BB7797','#4DB6AC','#A0B85A','#6FC2D0','#AC8ACB','#A5A5A5'];
  const categoryColor = category => {const raw=category==='爬虫類・両生類（旧記録）'?'#BEB45E':CATEGORY_COLORS[CATEGORIES.indexOf(category)]||'#A5A5A5';return '#'+[1,3,5].map(i=>Math.floor(parseInt(raw.slice(i,i+2),16)*.85).toString(16).padStart(2,'0')).join('');};
  const PREFECTURE_CAPITALS = [
    ['北海道',43.0618,141.3545],['青森県',40.8244,140.7400],['岩手県',39.7036,141.1527],['宮城県',38.2682,140.8694],
    ['秋田県',39.7186,140.1024],['山形県',38.2404,140.3633],['福島県',37.7503,140.4676],['茨城県',36.3418,140.4468],
    ['栃木県',36.5658,139.8836],['群馬県',36.3911,139.0608],['埼玉県',35.8617,139.6455],['千葉県',35.6074,140.1065],
    ['東京都',35.6895,139.6917],['神奈川県',35.4478,139.6425],['新潟県',37.9161,139.0364],['富山県',36.6953,137.2113],
    ['石川県',36.5947,136.6256],['福井県',36.0652,136.2216],['山梨県',35.6642,138.5684],['長野県',36.6513,138.1810],
    ['岐阜県',35.3912,136.7223],['静岡県',34.9769,138.3831],['愛知県',35.1802,136.9066],['三重県',34.7303,136.5086],
    ['滋賀県',35.0045,135.8686],['京都府',35.0212,135.7556],['大阪府',34.6863,135.5200],['兵庫県',34.6913,135.1830],
    ['奈良県',34.6851,135.8049],['和歌山県',34.2261,135.1675],['鳥取県',35.5039,134.2377],['島根県',35.4723,133.0505],
    ['岡山県',34.6551,133.9195],['広島県',34.3853,132.4553],['山口県',34.1859,131.4714],['徳島県',34.0658,134.5593],
    ['香川県',34.3401,134.0434],['愛媛県',33.8416,132.7657],['高知県',33.5597,133.5311],['福岡県',33.6064,130.4183],
    ['佐賀県',33.2494,130.2988],['長崎県',32.7503,129.8779],['熊本県',32.7898,130.7417],['大分県',33.2382,131.6126],
    ['宮崎県',31.9111,131.4239],['鹿児島県',31.5602,130.5581],['沖縄県',26.2124,127.6809],
  ];
  function coordinatePoint(lat,lng){
    if(![lat,lng].every(value=>(typeof value==='number'||typeof value==='string')&&String(value).trim()!==''))return null;
    lat=Number(lat);lng=Number(lng);
    return Number.isFinite(lat)&&Number.isFinite(lng)&&Math.abs(lat)<=90&&Math.abs(lng)<=180?{lat,lng}:null;
  }
  const recordPoint=o=>coordinatePoint(o.lat??o.latitude,o.lng??o.longitude);
  const mapPoint=o=>{const capital=o.locationPrivate&&PREFECTURE_CAPITALS.find(x=>x[0]===o.prefecture);return capital?{lat:capital[1],lng:capital[2]}:recordPoint(o);};
  const coordinateLabel=o=>{const point=mapPoint(o);return point?point.lat.toFixed(5)+', '+point.lng.toFixed(5):'位置情報がありません。編集画面で場所を選んでください。';};
  const DEFAULT_GROUPS = [];
  const DB_NAME='morinote-web-v1'; const DEFAULT_SERVER=String(window.BIODIARY_CONFIG?.apiUrl||'');
  // 以前の版で保存された、写真サーバー（Tailscale）への直接の接続先は使わない
  const isLegacyPiUrl=url=>/\.ts\.net(?:[/:]|$)/i.test(String(url||''));
  let db, observations=[], groups=DEFAULT_GROUPS.map(g=>structuredClone(g)), profile=null, communityMembers=[];
  let communitySession=(()=>{try{const saved=JSON.parse(sessionStorage.getItem('biodiary-session')||'null');return isLegacyPiUrl(saved?.baseUrl)?null:saved;}catch{return null}})();
  let activeSeasons=new Set(), activeTags={}, activeBuiltinTags={}, activeShareOwners=new Set(), activeMap='japan', mapTabs=[{id:'japan',name:'日本地図'}], currentView='map-view', mainMap, pinLayer, currentObservation=null, toastTimer, pendingPlace=null, editDraft=null;
  const timeBandFor = value => {const h=new Date(value).getHours();return h>=5&&h<=10?'朝':h>=11&&h<=15?'昼':h>=16&&h<=18?'夕方':'夜';};
  const seasonFor = value => { const m=new Date(value).getMonth(); return SEASONS.find(s=>s.months.includes(m))||SEASONS[0]; };
  const localDateTime = value => {const d=new Date(value);return `${d.getFullYear()}年${d.getMonth()+1}月${d.getDate()}日 ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;};
  const esc = v => String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function notify(text){const el=$('#toast');el.textContent=text;el.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),2400);}
  function openDB(){return new Promise((resolve,reject)=>{const request=indexedDB.open(DB_NAME,1);request.onupgradeneeded=()=>{const d=request.result;d.createObjectStore('observations',{keyPath:'id'});d.createObjectStore('settings',{keyPath:'id'});};request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});}
  function store(name,mode='readonly'){return db.transaction(name,mode).objectStore(name);}
  const dbGetAll=()=>new Promise((resolve,reject)=>{const r=store('observations').getAll();r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
  const dbGet=(key)=>new Promise((resolve,reject)=>{const r=store('settings').get(key);r.onsuccess=()=>resolve(r.result?.value);r.onerror=()=>reject(r.error);});
  const dbSet=(id,value)=>new Promise((resolve,reject)=>{const r=store('settings','readwrite').put({id,value});r.onsuccess=resolve;r.onerror=()=>reject(r.error);});
  const dbPutObservation=value=>new Promise((resolve,reject)=>{const {_url,...record}=value;const r=store('observations','readwrite').put(record);r.onsuccess=resolve;r.onerror=()=>reject(r.error);});
  const dbDeleteObservation=id=>new Promise((resolve,reject)=>{const r=store('observations','readwrite').delete(id);r.onsuccess=resolve;r.onerror=()=>reject(r.error);});
  const seasonList = ['春','夏','秋','冬'];
  let releaseHistoryRequest=null;
  async function showReleaseHistory(){
    const button=$('#release-history-button');if(button.disabled)return;button.disabled=true;
    try{
      releaseHistoryRequest ||= fetch('./release-history.json').then(response=>{if(!response.ok)throw new Error('履歴を読み込めませんでした');return response.json();}).catch(error=>{releaseHistoryRequest=null;throw error;});
      const releases=await releaseHistoryRequest;
      const body='<div class="release-history">'+releases.map(release=>`<section><h3>ver${esc(release.version)}（${esc(release.date.slice(-5).split('-').map(Number).join('/'))}）</h3><ul>${release.changes.map(change=>`<li>${esc(change)}</li>`).join('')}</ul></section>`).join('')+'</div><div class="modal-actions"><button type="button" id="history-close" class="secondary-full">閉じる</button></div>';
      const modal=showModal('更新履歴',body),dismiss=()=>{closeModal();button.focus();};
      $('#history-close').onclick=dismiss;$('#modal-close').onclick=dismiss;modal.tabIndex=-1;modal.focus();
    }catch(error){notify('更新履歴を読み込めませんでした。再読み込みしてお試しください。');}
    finally{button.disabled=false;}
  }
  function init(){
    $('#notification-bell').onclick=showNotifications;
    document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!$('#notification-panel'))refreshNotifications();});
    window.addEventListener('focus',()=>{if(!$('#notification-panel')&&Date.now()-notificationState.fetchedAt>10000)refreshNotifications();});
    setInterval(()=>{if(!document.hidden&&!$('#notification-panel'))refreshNotifications();},60000);
    document.addEventListener('click',event=>{const button=event.target.closest('[data-profile]');if(!button||button.disabled)return;event.preventDefault();event.stopPropagation();showMemberProfile(button.dataset.profile,button.closest('.modal')?.dataset.observationId||null);},true);
    $('#release-history-button').onclick=showReleaseHistory;
    $('#season-filters').innerHTML=seasonList.map(s=>`<button class="filter-chip" data-season="${s}">${s}</button>`).join('');
    $$('.nav-item').forEach(b=>b.addEventListener('click',()=>switchView(b.dataset.view)));
    $('#bulk-share').onclick=openBulkShare;$('#add-observation').onclick=()=>openEditor();$('#book-add').onclick=()=>openEditor();$('#map-new-tag-group').onclick=()=>editGroup();
    $('#book-search').oninput=renderBook;$('#book-category').onchange=renderBook;$('#book-sort').onchange=renderBook;
    $('#season-filters').onclick=e=>{const s=e.target.closest('[data-season]')?.dataset.season;if(!s)return;activeSeasons.has(s)?activeSeasons.delete(s):activeSeasons.add(s);renderSeasons();renderMapPins();};
    for(const id of ['share-filters','book-share-filters'])$(`#${id}`).onclick=e=>{const accountId=e.target.closest('[data-share-owner]')?.dataset.shareOwner;if(!accountId)return;activeShareOwners.has(accountId)?activeShareOwners.delete(accountId):activeShareOwners.add(accountId);renderSharingFilters();renderMapPins();renderBook();};
    $('#builtin-filters').onclick=e=>{const b=e.target.closest('[data-built-group][data-built-tag]');if(!b)return;const group=b.dataset.builtGroup,tag=b.dataset.builtTag;const values=new Set(activeBuiltinTags[group]||[]);values.has(tag)?values.delete(tag):values.add(tag);if(values.size)activeBuiltinTags[group]=[...values];else delete activeBuiltinTags[group];renderBuiltInFilters();renderMapPins();};
    $('#custom-filters').onclick=e=>{if(e.target.closest('[data-edit-group],[data-delete-group]'))return handleGroupAction(e);const b=e.target.closest('[data-filter-group][data-filter-tag]');if(!b)return;const group=b.dataset.filterGroup,tag=b.dataset.filterTag;const selected=new Set(activeTags[group]||[]);selected.has(tag)?selected.delete(tag):selected.add(tag);if(selected.size)activeTags[group]=[...selected];else delete activeTags[group];renderCustomFilters();renderMapPins();};
    $('#avatar-button').onclick=()=>$('#avatar-file').click();$('#avatar-file').onchange=chooseAvatar;
    $('#resend-avatar').onclick=async()=>{if(!communitySession||!profile?.avatar)return;try{const account=await uploadAvatar(profile.avatar);profile.avatarUrl=account.avatarUrl;profile.avatarUpdatedAt=account.avatarUpdatedAt;await dbSet('profile',profile);if(communitySession.communityMember)await refreshCommunityMembers();renderAvatarState();notify('アイコンを保存しました');}catch(error){notify(error.message||'アイコンを保存できませんでした');}};
    $('#save-profile').onclick=saveProfile;$('#new-tag-group').onclick=()=>editGroup();
    $('#export-data').onclick=exportBackup;$('#import-data').onclick=()=>$('#import-file').click();$('#import-file').onchange=importBackup;
    $('#server-check').onclick=checkServer;$('#server-login').onclick=loginCommunity;$('#server-register').onclick=registerCommunity;$('#server-logout').onclick=logoutCommunity;$('#server-sync').onclick=syncCommunity;$('#community-join-button').onclick=joinCommunity;$('#save-community-name').onclick=renameCommunity;
    $('#refresh-community').onclick=()=>refreshCommunityMembers().catch(e=>notify(e.message||'メンバーを取得できませんでした'));
    $('#tag-group-list').onclick=handleGroupAction;
    document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!$('#modal-root .modal')?.dataset.busy)closeModal();});
    $('#genre-legend').innerHTML=CATEGORIES.map(category=>`<span><i class="dot" style="background:${categoryColor(category)}"></i>${esc(category)}</span>`).join('');
  }
  function serverBase(){const url=($('#server-url').value.trim()||DEFAULT_SERVER).replace(/\/+$/,'');if(!/^https:\/\//i.test(url))throw new Error('接続先設定にCloudflareのAPI URLを入力してください。');return url;}
  let piTicket=null;
  async function piAccess(){if(!communitySession)throw new Error('ログインしてください');if(piTicket?.sessionToken===communitySession.token&&piTicket.expiresAt*1000>Date.now()+15000)return piTicket;const r=await fetch(communitySession.baseUrl+'/api/v1/auth/pi-ticket',{headers:{Authorization:`Bearer ${communitySession.token}`}});const data=await r.json().catch(()=>({}));if(!r.ok)throw new Error(data.error||'写真サーバーへの接続情報を取得できませんでした');piTicket={...data,sessionToken:communitySession.token};return piTicket;}
  async function piFetch(path,options={}){
    const retryable=['GET','HEAD'].includes((options.method||'GET').toUpperCase());
    for(let attempt=0;attempt<2;attempt++){
      const ticket=await piAccess(),headers=new Headers(options.headers||{});
      headers.set('X-Biodiary-Identity',ticket.identity);
      let response;
      try{response=await fetch(ticket.baseUrl+path,{...options,headers});}
      catch{if(retryable&&attempt===0){await new Promise(resolve=>setTimeout(resolve,500));continue;}throw new Error('写真サーバーに接続できません。ラズパイの通信状態を確認してください。');}
      if(retryable&&attempt===0&&(response.status===401||[502,503,504,525].includes(response.status))){
        if(response.status===401)piTicket=null;
        await response.body?.cancel().catch(()=>{});
        await new Promise(resolve=>setTimeout(resolve,500));continue;
      }
      return response;
    }
  }
  async function serverRequest(path,options={}){const base=communitySession?.baseUrl||serverBase();if(!/^https:\/\//i.test(base))throw new Error('CloudflareのAPI URLを入力してください。');const onPi=path.startsWith('/api/v1/tag-groups')||path.startsWith('/api/v1/observations')||path.startsWith('/api/v1/notifications')||/^\/api\/v1\/community\/members\/BD-[A-F0-9]{8,12}\/observations$/.test(path.split('?')[0]);const headers=new Headers(options.headers||{});headers.set('Accept','application/json');if(!onPi&&communitySession?.token)headers.set('Authorization',`Bearer ${communitySession.token}`);const response=onPi?await piFetch(path,{...options,headers}):await fetch(base+path,{...options,headers});const body=await response.json().catch(()=>({}));if(!response.ok)throw new Error(body.error||`サーバーとの通信に失敗しました（${response.status}）`);return body;}
  function setServerMessage(message){$('#server-status').textContent=message;}
  async function checkServer(){setServerMessage('接続を確認しています…');try{const r=await serverRequest('/api/v1/status');setServerMessage(`接続できました：${r.service}`);}catch(e){setServerMessage(e.message||'接続できませんでした');}}
  let notificationState={key:'',items:[],unread:0,newest:0,before:null,busy:false,loaded:false,error:'',fetchedAt:0};
  function notificationSessionKey(){return (communitySession?.token||'')+':'+!!communitySession?.communityMember;}
  function syncNotificationSession(){
    const key=notificationSessionKey();
    if(notificationState.key===key)return;
    notificationState={key,items:[],unread:0,newest:0,before:null,busy:false,loaded:false,error:'',fetchedAt:0};
    renderNotificationBadge();renderNotifications();
    if(communitySession?.communityMember)refreshNotifications();
  }
  function renderNotificationBadge(){
    const count=notificationState.unread,badge=$('#notification-badge');
    badge.hidden=!count;badge.textContent=count>99?'99+':String(count);
    $('#notification-bell').setAttribute('aria-label','通知を開く・未読'+count+'件');
  }
  async function refreshNotifications(before=null){
    const state=notificationState;
    if(!communitySession?.communityMember||state.busy||state.key!==notificationSessionKey())return;
    state.busy=true;state.error='';renderNotifications();
    try{
      const response=await serverRequest('/api/v1/notifications?limit=30'+(before?'&before='+before:''));
      if(notificationState!==state||state.key!==notificationSessionKey())return;
      const page=response.notifications||[];
      state.items=(before?[...state.items,...page]:page).filter((item,i,all)=>all.findIndex(other=>other.id===item.id)===i);
      state.unread=Math.max(0,Number(response.unreadCount)||0);state.newest=Number(response.newestId)||0;
      state.before=response.nextBefore||null;state.loaded=true;state.fetchedAt=Date.now();renderNotificationBadge();
    }catch(error){if(notificationState===state)state.error=error.message||'通知を取得できませんでした';}
    finally{state.busy=false;if(notificationState===state)renderNotifications();}
  }
  function showNotifications(){
    showModal('通知','<div id="notification-panel"></div><div class="modal-actions"><button type="button" class="secondary-full" id="notifications-close">閉じる</button></div>');
    $('#notifications-close').onclick=closeModal;
    renderNotifications();refreshNotifications();
  }
  function renderNotifications(){
    const root=$('#notification-panel');if(!root)return;
    if(!communitySession?.communityMember){root.innerHTML='<p>'+(!communitySession?'ログインすると通知を確認できます。':'コミュニティに参加すると、コメント・返信・いいねの通知を確認できます。')+'</p>';return;}
    const state=notificationState;
    root.innerHTML=`<div class="notification-tools"><small>未読${state.unread}件</small><button type="button" class="quiet-button" data-refresh-notifications ${state.busy?'disabled':''}>更新</button><button type="button" class="quiet-button" data-read-all ${state.busy||!state.unread||!state.newest?'disabled':''}>すべて既読にする</button></div>${state.error?`<p class="social-error" role="status">${esc(state.error)}</p>`:''}${state.busy?'<p class="notification-loading" role="status">読み込み中…</p>':''}<div class="notification-list">${state.items.map(item=>{
      const actor=communityMembers.find(m=>m.accountId===item.actor?.accountId)||item.actor||{},name=actor.displayName||actor.loginName||'メンバー';
      const action=item.kind==='like'?'さんがあなたの投稿にいいねしました':item.kind==='reply'?'さんがあなたのコメントに返信しました':'さんがあなたの投稿にコメントしました';
      const cached=observations.find(o=>o.id===item.observationId);
      return `<button type="button" class="notification-item ${item.read?'':'unread'}" data-notification-id="${item.id}" ${state.busy?'disabled':''}><span class="notification-avatar">${actor.avatarDataUrl?`<img src="${esc(actor.avatarDataUrl)}" alt="">`:esc(name.slice(0,1))}</span><span class="notification-content"><span>${esc(name+action)}</span><strong>${esc(item.observationName)}</strong>${item.commentBody?`<span class="notification-excerpt">${esc(item.commentBody)}</span>`:''}<small>${esc(localDateTime(item.createdAt))}</small></span>${cached?.photo?`<span class="notification-photo"><img src="${photoUrl(cached)}" alt=""></span>`:''}${item.read?'':'<span class="notification-unread-dot" aria-label="未読"></span>'}</button>`;
    }).join('')||(!state.busy&&state.loaded?'<p>通知はまだありません。</p>':'')}</div>${state.before?`<button type="button" class="secondary-button wide" data-older-notifications ${state.busy?'disabled':''}>以前の通知を読み込む</button>`:''}`;
    root.onclick=async event=>{
      const button=event.target.closest('button');if(!button||state.busy||notificationState!==state)return;
      if(button.hasAttribute('data-refresh-notifications'))return refreshNotifications();
      if(button.hasAttribute('data-older-notifications'))return refreshNotifications(state.before);
      const item=state.items.find(item=>String(item.id)===button.dataset.notificationId);
      const all=button.hasAttribute('data-read-all');if(!all&&!item)return;
      state.busy=true;state.error='';renderNotifications();const token=communitySession.token;
      try{
        const mark=async()=>{
          const response=await serverRequest('/api/v1/notifications/read',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(all?{all:true,throughId:state.newest}:{ids:[item.id]})});
          if(notificationState!==state||communitySession?.token!==token)return;
          state.unread=Math.max(0,Number(response.unreadCount)||0);
          state.items=state.items.map(row=>all&&row.id<=state.newest||row.id===item?.id?{...row,read:true}:row);renderNotificationBadge();
        };
        if(all)await mark();else await openSharedProfilePost(item.observationId,root,token,mark);
      }catch(error){if(notificationState===state)state.error=error.message||'通知を開けませんでした';}
      finally{state.busy=false;if(notificationState===state)renderNotifications();}
    };
  }
  function setCommunitySession(session){communitySession=session;piTicket=null;if(session){sessionStorage.setItem('biodiary-session',JSON.stringify(session));localStorage.setItem('biodiary-base-url',session.baseUrl);}else sessionStorage.removeItem('biodiary-session');document.body.classList.toggle('account-required',!session);renderCommunitySession();renderCommunity();renderAvatarState();renderSyncStatus().catch(()=>{});switchView(session?'map-view':'account-view');}
  function maybeShowOnboardingGuide(account){
    if(!account?.accountId||communitySession?.accountId!==account.accountId)return;
    const seenKey='biodiary-guide-v1-'+account.accountId;
    const syncedKey='biodiary-guide-synced-v1-'+account.accountId;
    if(account.guideSeenAt){localStorage.setItem(seenKey,'shown');localStorage.setItem(syncedKey,'yes');return;}
    if(localStorage.getItem(seenKey)!=='shown'){
      localStorage.setItem(seenKey,'shown');
      showModal('はじめての観察記録','<ol class="onboarding-guide"><li>地図の「＋ 記録する」から写真を選ぶか、カメラで撮影します。</li><li>生きものの名前・ジャンル・撮影場所を確認します。位置情報がなければ、現在地か地図から場所を選べます。</li><li>「記録を保存」を押すと、自分の地図と図鑑に追加されます。この時点では公開されません。</li><li>コミュニティに参加したあと、記録の詳細から「コミュニティに共有」を押すと公開できます。複数の記録は「まとめて共有」で選べます。</li></ol><div class="modal-actions"><button type="button" id="onboarding-start" class="primary-button">はじめる</button></div>');
      $('#onboarding-start').onclick=closeModal;
    }
    if(localStorage.getItem(syncedKey)!=='yes')serverRequest('/api/v1/account/me/guide-seen',{method:'POST'})
      .then(()=>{if(communitySession?.accountId===account.accountId)localStorage.setItem(syncedKey,'yes');}).catch(()=>{});
  }
  function renderCommunitySession(){syncNotificationSession();const loggedIn=!!communitySession;$('#community-settings').hidden=!(communitySession?.communityMember&&communitySettings?.accountId===communitySession.accountId);$('#community-admin-settings').hidden=!(communitySettings?.accountId===communitySession?.accountId&&communitySettings?.isAdmin);$('#account-auth').hidden=loggedIn;$('#account-profile').hidden=!loggedIn;$('#community-join').hidden=!loggedIn||!!communitySession?.communityMember;$('#server-sync').hidden=!communitySession?.communityMember;$('#community-membership-status').textContent=!loggedIn?'アカウントにログインしてください。':communitySession.communityMember?'コミュニティに参加しています。':'コミュニティへの参加は任意です。招待コードがあれば参加できます。';}
  async function loadAccountObservations(accountId, previousAccountId=''){const all=await dbGetAll();for(const item of all){if(item.localAccountId)continue;item.localAccountId=item.owner?.accountId||previousAccountId||accountId;await dbPutObservation(item);}observations=all.filter(item=>item.localAccountId===accountId||(communitySession?.communityMember&&item.isShared&&item.owner?.accountId));renderSharingFilters();renderMapPins();renderBook();}
  async function applyServerProfile(account){if(!account)return;const previousAccountId=profile.accountId||'';if(previousAccountId&&previousAccountId!==account.accountId){profile.avatar='';profile.avatarUpdatedAt=0;}if(account.avatarUrl&&(!profile.avatar||profile.avatarUpdatedAt!==account.avatarUpdatedAt)){try{const response=await piFetch(account.avatarUrl);if(response.ok)profile.avatar=await blobToDataUrl(await response.blob());}catch{}}profile={...profile,accountId:account.accountId,id:account.accountId,loginName:account.loginName||'',name:account.displayName||profile.name,message:account.message||'',registeredAt:account.registeredAt||profile.registeredAt,avatarUrl:account.avatarUrl||'',avatarUpdatedAt:account.avatarUpdatedAt||0};await dbSet('profile',profile);await loadAccountObservations(account.accountId,previousAccountId);renderAccount();}
  async function loginCommunity(){setServerMessage('ログインしています…');try{const base=serverBase(),r=await fetch(base+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},body:JSON.stringify({loginName:$('#server-login-name').value.trim(),password:$('#server-password').value})});const data=await r.json().catch(()=>({}));if(!r.ok)throw new Error(data.error||`ログインに失敗しました（${r.status}）`);setCommunitySession({baseUrl:base,token:data.token,accountId:data.account.accountId,displayName:data.account.displayName,communityMember:!!data.account.communityMember});await applyServerProfile(data.account);if(data.account.communityMember)await refreshCommunityMembers().catch(()=>{});setServerMessage('ログインしました。');maybeShowOnboardingGuide(data.account);}catch(e){setServerMessage(e.message||'ログインできませんでした');}}
  async function registerCommunity(){setServerMessage('アカウントを作成しています…');try{const base=serverBase();if(!/^https:\/\//i.test(base))throw new Error('接続先にはhttps:// URLを入力してください。');const r=await fetch(base+'/api/v1/auth/register',{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},body:JSON.stringify({loginName:$('#server-login-name').value.trim(),password:$('#server-password').value,displayName:$('#server-display-name').value.trim()})});const data=await r.json().catch(()=>({}));if(!r.ok)throw new Error(data.error||`登録に失敗しました（${r.status}）`);setCommunitySession({baseUrl:base,token:data.token,accountId:data.account.accountId,displayName:data.account.displayName,communityMember:!!data.account.communityMember});await applyServerProfile(data.account);setServerMessage('アカウントを作成しました。');maybeShowOnboardingGuide(data.account);}catch(e){setServerMessage(e.message||'登録できませんでした');}}
  async function joinCommunity(){if(!communitySession)return;const code=$('#community-invite').value.trim();if(!code)return notify('招待コードを入力してください');try{const result=await serverRequest('/api/v1/community/join',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({inviteCode:code})});communitySession={...communitySession,communityMember:!!result.account.communityMember};piTicket=null;sessionStorage.setItem('biodiary-session',JSON.stringify(communitySession));renderCommunitySession();await refreshCommunityMembers().catch(()=>{});$('#community-invite').value='';notify('コミュニティに参加しました');}catch(error){notify(error.message||'コミュニティに参加できませんでした');}}
  async function logoutCommunity(){try{await serverRequest('/api/v1/auth/logout',{method:'POST'});}catch{}setCommunitySession(null);setServerMessage('ログアウトしました。端末内の記録は残っています。');}
  async function renderSyncStatus(){
    const el=$('#sync-status');
    if(!el)return;
    if(!communitySession?.communityMember){el.textContent='';return;}
    const state=await dbGet('sync-status-'+communitySession.accountId).catch(()=>null);
    el.textContent=state?'前回の同期：'+new Date(state.at).toLocaleString('ja-JP')+'（更新'+state.records+'件・写真'+state.photos+'枚'+(state.failures?'・未取得'+state.failures+'枚':'')+'）':'まだ同期していません。';
  }
  async function syncCommunity(){
    if(!communitySession?.communityMember)return setServerMessage('コミュニティに参加すると共有記録を同期できます。');
    const button=$('#server-sync');if(button.disabled)return;button.disabled=true;
    const accountId=communitySession.accountId,key='sync-cursor-'+accountId,since=Number(await dbGet(key).catch(()=>0))||0;
    let offset=0,serverTime=0,visibleIds=null,changed=0,photosDownloaded=0;
    const failures=[],categoryMap={PLANT:'植物',BIRD:'鳥類',INSECT:'昆虫',MAMMAL:'哺乳類',FISH:'魚類',REPTILE:'爬虫類',AMPHIBIAN:'両生類',FUNGI:'菌類',OTHER:'その他'};
    $('#sync-status').textContent='新しい共有記録を確認しています…';
    try{
      await refreshCommunityMembers().catch(()=>{});
      let page;
      do{
        page=await serverRequest('/api/v1/observations?limit=200&offset='+offset+'&since='+since);
        if(!offset){serverTime=Number(page.serverTime)||0;visibleIds=Array.isArray(page.visibleIds)?new Set(page.visibleIds):null;}
        for(const remote of page.observations||[]){
          for(const group of remote.tagGroups||[])if(!groups.some(g=>g.id===group.id))groups.push({...group,sharedOwner:remote.owner?.accountId||''});
          const prior=observations.find(o=>o.id===remote.id),cached=prior?observationPhotos(prior):[];
          const urls=(remote.photoUrls?.length?remote.photoUrls:[remote.photoUrl]).slice(0,4),revision=Number(remote.updatedAt)||0,photos=[];
          let photoFailed=false;
          for(let index=0;index<urls.length;index++){
            let photo=cached[index]||null;
            if(!(revision>0&&prior?.remoteUpdatedAt===revision&&photo instanceof Blob)){
              try{
                const result=await piFetch(urls[index]);if(!result.ok)throw new Error('写真の取得に失敗しました（'+result.status+'）');
                const downloaded=await result.blob();if(!downloaded.size||!downloaded.type.startsWith('image/'))throw new Error('写真データの形式が正しくありません');
                photo=downloaded;photosDownloaded++;
              }catch(error){photoFailed=true;failures.push((remote.name||'名前未設定')+' 写真'+(index+1)+'：'+error.message);}
            }
            photos.push(photo);
          }
          const tags=[];for(const[groupId,names]of Object.entries(remote.tagValues||{}))for(const name of names||[])tags.push({groupId,name});
          const keepExact=!!(remote.locationPrivate&&prior?.locationPrivate&&remote.owner?.accountId===accountId);
          const item={...(prior||{}),id:remote.id,name:remote.name,photo:photos[0],photos,date:remote.dateMillis,postedAt:remote.createdAt||prior?.postedAt||remote.dateMillis,likeCount:remote.likeCount||0,likedByMe:!!remote.likedByMe,lat:keepExact?prior.lat:remote.latitude,lng:keepExact?prior.lng:remote.longitude,locationPrivate:!!remote.locationPrivate,prefecture:remote.prefecture||'',place:keepExact?prior.place:remote.place||'',comment:remote.comment||'',category:categoryMap[remote.category]||remote.category||'その他',species:remote.species||'',scientificName:remote.speciesScientificName||'',genus:remote.genus||'',family:remote.family||'',order:remote.order||'',tags,mapId:prior?.mapId||'japan',isShared:!!remote.shared,owner:remote.owner,localAccountId:remote.owner?.accountId||accountId,remoteUpdatedAt:photoFailed?0:revision};
          delete item._url;await dbPutObservation(item);
          const ix=observations.findIndex(o=>o.id===item.id);if(ix<0)observations.push(item);else observations[ix]=item;
          changed++;
        }
        offset+=(page.observations||[]).length;
        $('#sync-status').textContent='同期中：'+changed+'件を確認・写真'+photosDownloaded+'枚を取得';
      }while((page.observations||[]).length===200);
      if(visibleIds)for(const item of observations.filter(o=>o.owner?.accountId&&!visibleIds.has(o.id))){await dbDeleteObservation(item.id);observations=observations.filter(o=>o.id!==item.id);}
      if(serverTime&&!failures.length)await dbSet(key,serverTime);
      await dbSet('sync-status-'+accountId,{at:Date.now(),records:changed,photos:photosDownloaded,failures:failures.length});
      await dbSet('groups',groups);renderCustomFilters();renderSharingFilters();renderMapPins();renderBook();await refreshGuideReactions();await renderSyncStatus();
      setServerMessage('同期完了：更新'+changed+'件・写真'+photosDownloaded+'枚'+(failures.length?'。未取得の写真は次回再試行します。':''));
      if(failures.length)showModal('同期できなかった写真','<p>ほかの記録は同期しました。通信が復旧したら、もう一度同期してください。</p><ul>'+failures.map(message=>'<li>'+esc(message)+'</li>').join('')+'</ul>');
    }catch(error){$('#sync-status').textContent='同期に失敗しました。もう一度「共有記録を同期」を押してください。';setServerMessage(error.message||'同期できませんでした');}
    finally{button.disabled=false;}
  }
  async function refreshCommunityMembers(){await refreshCommunitySettings();
    if(!communitySession?.communityMember){communityMembers=[];renderCommunity();renderAvatarState();return;}
    let response=await serverRequest('/api/v1/community/members');
    const own=response.members?.find(m=>m.accountId===communitySession.accountId);
    if(profile?.avatar&&own&&!own.avatarUrl){
      try{await uploadAvatar(profile.avatar);response=await serverRequest('/api/v1/community/members');}
      catch(error){setServerMessage(`端末内のアイコンを共有できませんでした：${error.message}`);}
    }
    const previous=new Map(communityMembers.map(m=>[m.accountId,m]));
    communityMembers=await Promise.all((response.members||[]).map(async member=>{
      let avatarDataUrl='',avatarError='';const old=previous.get(member.accountId);
      if(member.avatarUrl&&old?.avatarUpdatedAt===member.avatarUpdatedAt)avatarDataUrl=old.avatarDataUrl||'';
      if(member.avatarUrl&&!avatarDataUrl&&member.avatarUrl.startsWith('/api/v1/community/members/')&&!member.avatarUrl.includes('..')){
        try{const r=await piFetch(member.avatarUrl);if(!r.ok)throw new Error(`HTTP ${r.status}`);avatarDataUrl=await blobToDataUrl(await r.blob());}
        catch(error){avatarError=error.message||'アイコンを取得できませんでした';}
      }
      return{...member,avatarDataUrl,avatarError};
    }));
    await dbSet('community-members',communityMembers);renderSharingFilters();renderCommunity();renderAvatarState();renderMapPins();renderBook();
  }
  function ownerFor(o){return communityMembers.find(m=>m.accountId===o.owner?.accountId)||o.owner||null;}
  function avatarBadge(o,extra=''){if(!o.isShared||!o.owner?.accountId)return '';const owner=ownerFor(o),label=owner?.displayName||owner?.loginName||'共有者';return `<button type="button" data-profile="${esc(o.owner.accountId)}" class="owner-avatar ${extra}" title="${esc(label)}" aria-label="${esc(label)}のプロフィール">${owner?.avatarDataUrl?`<img src="${esc(owner.avatarDataUrl)}" alt="">`:esc(label.slice(0,1).toUpperCase())}</button>`;}
  function renderSharingFilters(){const known=new Map(communityMembers.map(m=>[m.accountId,m.displayName||m.loginName]));for(const o of observations)if(o.isShared&&o.owner?.accountId&&!known.has(o.owner.accountId))known.set(o.owner.accountId,o.owner.displayName||o.owner.loginName||o.owner.accountId);const options=[...known].map(([id,name])=>`<button class="filter-chip ${activeShareOwners.has(id)?'active':''}" data-share-owner="${esc(id)}">${esc(name)}</button>`).join('');for(const id of ['share-filters','book-share-filters'])$(`#${id}`).innerHTML=options?`<div class="custom-filter-row"><span>共有</span><div class="chip-row">${options}</div></div>`:'';}
  let communitySettings=null;
  async function refreshCommunitySettings(){
    const panel=$('#community-settings'),admin=$('#community-admin-settings');
    panel.hidden=true;admin.hidden=true;
    if(!communitySession?.communityMember){communitySettings=null;return;}
    const accountId=communitySession.accountId,token=communitySession.token;
    const result=await serverRequest('/api/v1/community');
    if(communitySession?.accountId!==accountId||communitySession?.token!==token)return;
    communitySettings={...result.community,accountId};
    $('#community-name').textContent=communitySettings.name;
    $('#community-name-input').value=communitySettings.name;
    panel.hidden=false;admin.hidden=!communitySettings.isAdmin;
  }
  async function renameCommunity(){
    const name=$('#community-name-input').value.trim(),button=$('#save-community-name');
    if(!name||name.length>60)return notify('コミュニティ名は1〜60文字で入力してください');
    button.disabled=true;const accountId=communitySession?.accountId,token=communitySession?.token;
    try{
      const result=await serverRequest('/api/v1/community',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});
      if(communitySession?.accountId!==accountId||communitySession?.token!==token)return;
      communitySettings={...result.community,accountId};$('#community-name').textContent=communitySettings.name;$('#community-name-input').value=communitySettings.name;
      notify('コミュニティ名を変更しました');
    }catch(error){notify(error.message);}
    finally{button.disabled=false;}
  }
  function renderCommunity(){const root=$('#community-members');renderCommunitySession();if(!communitySession){root.innerHTML='<p class="account-note">アカウントにログインしてください。</p>';return;}if(!communitySession.communityMember){root.innerHTML='<p class="account-note">参加前でも地図と図鑑を利用できます。</p>';return;}root.innerHTML=communityMembers.length?communityMembers.map(m=>`<article class="community-member"><button type="button" class="member-avatar" data-profile="${esc(m.accountId)}" aria-label="${esc(m.displayName||m.loginName)}のプロフィール">${m.avatarDataUrl?`<img src="${m.avatarDataUrl}" alt="">`:esc((m.displayName||m.loginName||'?').slice(0,1).toUpperCase())}</button><div><strong>${esc(m.displayName||m.loginName)}</strong><small>アカウントID：${esc(m.accountId)}</small>${m.avatarUrl&&!m.avatarDataUrl?'<small>アイコンを読み込めませんでした</small>':''}<p>${esc(m.message||'ひとことはまだありません')}</p></div></article>`).join(''):'<p class="account-note">メンバー情報を読み込んでいます。更新を押してください。</p>';}
  function resizeAvatar(source){return new Promise((resolve,reject)=>{const url=URL.createObjectURL(source),image=new Image();image.onload=()=>{const side=Math.min(image.naturalWidth,image.naturalHeight),canvas=document.createElement('canvas');canvas.width=256;canvas.height=256;canvas.getContext('2d').drawImage(image,(image.naturalWidth-side)/2,(image.naturalHeight-side)/2,side,side,0,0,256,256);URL.revokeObjectURL(url);canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('アイコンを処理できませんでした')),'image/jpeg',.82);};image.onerror=()=>{URL.revokeObjectURL(url);reject(new Error('アイコンを読み込めませんでした'));};image.src=url;});}
  async function uploadAvatar(source){if(!communitySession)throw new Error('アカウントにログインしてください');const blob=source instanceof Blob?source:await(await fetch(source)).blob();const compact=await resizeAvatar(blob),form=new FormData();form.append('avatar',compact,'avatar.jpg');const upload=await piFetch('/api/v1/account/me/avatar',{method:'PUT',body:form});const result=await upload.json().catch(()=>({}));if(!upload.ok)throw new Error(result.error||`アイコンの保存に失敗しました（${upload.status}）`);if(!result.account?.avatarUrl)throw new Error('サーバーがアイコンの保存を確認できませんでした');const response=await serverRequest('/api/v1/account/me/avatar-updated',{method:'POST'});return response.account;}
  function switchView(id){if(!communitySession&&id!=='account-view')id='account-view';currentView=id;$$('.view').forEach(v=>v.classList.toggle('active',v.id===id));$$('.nav-item').forEach(b=>b.classList.toggle('selected',b.dataset.view===id));if(id==='map-view'){setTimeout(()=>mainMap?.invalidateSize(),80);refreshGuideReactions();}if(id==='book-view'){renderBook();refreshGuideReactions();}if(id==='account-view')renderAccount();if(id==='community-view'){renderCommunity();if(communitySession?.communityMember)refreshCommunityMembers().catch(e=>notify(e.message||'メンバーを取得できませんでした'));}}
  function renderSeasons(){$$('#season-filters .filter-chip').forEach(b=>b.classList.toggle('active',activeSeasons.has(b.dataset.season)));}
  function renderBuiltInFilters(){const definitions=[
    {id:'year',name:'撮影年',values:[...new Set(observations.map(o=>String(new Date(o.date).getFullYear())))].sort((a,b)=>Number(b)-Number(a)).map(v=>[v,`${v}年`,'#6D9FBF'])},
    {id:'category',name:'ジャンル',values:CATEGORIES.map(v=>[v,v,categoryColor(v)])},
    {id:'month',name:'月',values:Array.from({length:12},(_,i)=>[String(i+1),`${i+1}月`,'#81947E'])},
    {id:'time',name:'時間帯',values:[['朝','朝','#B88C37'],['昼','昼','#C46E3A'],['夕方','夕方','#AB5870'],['夜','夜','#4C5B7B']]},
    {id:'likes',name:'いいね',values:[['mine','自分がいいねした投稿','#D9D917']]},
  ];$('#builtin-filters').innerHTML=definitions.map(g=>`<div class="custom-filter-row"><span>${g.name}</span><div class="chip-row">${g.values.map(([key,label,color])=>`<button class="filter-chip ${activeBuiltinTags[g.id]?.includes(key)?'active':''}" style="--tag-color:${color}" data-built-group="${g.id}" data-built-tag="${esc(key)}">${label}</button>`).join('')}</div></div>`).join('');}
  function renderCustomFilters(){$('#custom-filters').innerHTML=groups.map(g=>`<div class="custom-filter-row editable-filter-row"><span>${esc(g.name)}</span><div class="chip-row">${g.tags.map(t=>`<button class="filter-chip ${activeTags[g.id]?.includes(t.name)?'active':''}" style="--tag-color:${safeColor(t.colorHex)}" data-filter-group="${esc(g.id)}" data-filter-tag="${esc(t.name)}">${esc(t.name)}</button>`).join('')}</div><div class="group-filter-actions"><button type="button" class="quiet-button" data-edit-group="${esc(g.id)}" aria-label="${esc(g.name)}のタグを追加・編集">＋ タグ／編集</button><button type="button" class="quiet-button" data-delete-group="${esc(g.id)}" aria-label="${esc(g.name)}を削除">削除</button></div></div>`).join('');}
  function matchesBuiltInTag(o,group,tag){return group==='year'?String(new Date(o.date).getFullYear())===tag:group==='category'?o.category===tag:group==='month'?String(new Date(o.date).getMonth()+1)===tag:group==='time'?timeBandFor(o.date)===tag:group==='likes'?tag==='mine'&&!!o.isShared&&!!o.likedByMe:true;}
  function mapMatches(o){if(activeSeasons.size&&!activeSeasons.has(seasonFor(o.date).name))return false;if(!Object.entries(activeBuiltinTags).every(([group,tags])=>!tags.length||tags.some(tag=>matchesBuiltInTag(o,group,tag))))return false;if(activeShareOwners.size&&(!o.isShared||!activeShareOwners.has(o.owner?.accountId)))return false;return Object.entries(activeTags).every(([group,tags])=>!tags.length||tags.some(name=>(o.tags||[]).some(t=>t.groupId===group&&t.name===name)));}
  function showObservationOnMap(o){
    const point=mapPoint(o);
    if(!point){notify('位置情報がありません。記録を編集して場所を指定してください。');return;}
    if(activeSeasons.size&&!activeSeasons.has(seasonFor(o.date).name))activeSeasons.clear();
    activeBuiltinTags=Object.fromEntries(Object.entries(activeBuiltinTags).filter(([group,tags])=>!tags.length||tags.some(tag=>matchesBuiltInTag(o,group,tag))));
    activeTags=Object.fromEntries(Object.entries(activeTags).filter(([group,tags])=>!tags.length||tags.some(name=>(o.tags||[]).some(t=>t.groupId===group&&t.name===name))));
    if(activeShareOwners.size&&(!o.isShared||!activeShareOwners.has(o.owner?.accountId)))activeShareOwners.clear();
    closeModal();renderSeasons();renderBuiltInFilters();renderCustomFilters();renderSharingFilters();
    switchView('map-view');renderMapPins();
    requestAnimationFrame(()=>{if(currentView!=='map-view'||!mainMap)return;mainMap.invalidateSize();mainMap.setView([point.lat,point.lng],13);});
  }
  const observationPhotos=o=>Array.isArray(o.photos)&&o.photos.length?o.photos.slice(0,4):[o.photo||null];
  const photoUrls=new WeakMap();
  function photoUrl(o){if(!(o.photo instanceof Blob))return '';let url=photoUrls.get(o.photo);if(!url){url=URL.createObjectURL(o.photo);photoUrls.set(o.photo,url);}return url;}
  function renderMapPins(){
    if(!mainMap)return;
    pinLayer.clearLayers();
    const matched=observations.filter(mapMatches),list=matched.filter(o=>mapPoint(o));
    list.forEach(o=>{
      const point=mapPoint(o),node=document.createElement('div');
      node.className='photo-pin';node.style.setProperty('--genre-color',categoryColor(o.category));
      node.innerHTML='<img alt="">'+avatarBadge(o);node.querySelector('img').src=photoUrl(o);
    const icon=L.divIcon({className:'',html:node.outerHTML,iconSize:[52,52],iconAnchor:[0,52]});
      L.marker([point.lat,point.lng],{icon}).addTo(pinLayer).on('click',()=>showObservation(o.id));
    });
    $('#map-summary').textContent=list.length+'件の観察記録'+(activeSeasons.size?' · '+[...activeSeasons].join('・')+'の記録':'')+(matched.length>list.length?' · 位置情報のない'+(matched.length-list.length)+'件は図鑑から確認できます':'');
  }
  function isOwnObservation(o){const accountId=communitySession?.accountId;return !!accountId && (o.owner?.accountId||o.localAccountId)===accountId;}
  function renderBook(){const q=$('#book-search').value.trim().toLocaleLowerCase(),cat=$('#book-category').value,sort=$('#book-sort').value;const groups=new Map();observations.filter(o=>(!activeShareOwners.size||(o.isShared&&activeShareOwners.has(o.owner?.accountId)))&&(!cat||o.category===cat)&&(!q||`${o.name} ${o.species} ${o.scientificName} ${o.genus} ${o.family}`.toLocaleLowerCase().includes(q))).forEach(o=>{const name=(o.name||o.species||o.scientificName||'').trim();if(!name)return;if(!groups.has(name))groups.set(name,[]);groups.get(name).push(o);});const items=[...groups].map(([name,records])=>({name,o:records.sort(guideOrder)[0],newest:Math.max(...records.map(guidePostedAt)),oldest:Math.min(...records.map(guidePostedAt))}));items.sort((a,b)=>sort==='name'?a.name.localeCompare(b.name,'ja'):sort==='oldest'?a.oldest-b.oldest||a.name.localeCompare(b.name,'ja'):b.newest-a.newest||a.name.localeCompare(b.name,'ja'));$('#book-grid').innerHTML=items.length?items.map(({name,o})=>`<article class="creature-card" data-observation="${esc(o.id)}"><div class="creature-photo" style="--genre-color:${categoryColor(o.category)}">${o.photo?`<img alt="${esc(name)}" src="${photoUrl(o)}">`:'<div class="creature-empty">🌿</div>'}${avatarBadge(o)}${isOwnObservation(o)?`<button type="button" class="catalog-edit-shortcut" data-edit-observation="${esc(o.id)}" aria-label="${esc(name)}の記録を編集" title="編集">✎</button>`:''}</div><strong>${esc(name)}</strong></article>`).join(''):'<div class="empty-state">該当する観察記録はありません。</div>';$('#book-grid').onclick=e=>{const edit=e.target.closest('[data-edit-observation]');if(edit){const record=observations.find(o=>o.id===edit.dataset.editObservation);if(record&&isOwnObservation(record))openEditor(record);return;}const card=e.target.closest('[data-observation]');if(card)showObservation(card.dataset.observation);};}
  function renderAvatarState(){if(!profile)return;const own=communitySession?communityMembers.find(m=>m.accountId===communitySession.accountId):null;const image=own?.avatarDataUrl||profile.avatar;$('#avatar-button').innerHTML=image?`<img src="${image}" alt="プロフィールアイコン">`:'🌿';$('#avatar-sync-status').textContent=!communitySession?'ログイン後にアイコンを設定できます':own?.avatarDataUrl||profile.avatarUrl?'アイコンは保存されています':profile.avatar?'アイコンを保存しています':'アイコンは未設定です';$('#resend-avatar').hidden=!communitySession||!profile.avatar;}
  function renderAccount(){if(!profile)return;$('#profile-name').value=profile.name||'';$('#profile-id').value=communitySession?.accountId||profile.id;$('#profile-login-id').value=profile.loginName||'';$('#profile-message').value=profile.message||'';$('#registered-date').textContent=`登録日時：${new Date(profile.registeredAt).toLocaleDateString('ja-JP')}`;renderAvatarState();renderGroups();}
  function renderGroups(){const root=$('#tag-group-list');root.innerHTML=groups.length?groups.map(g=>`<div class="tag-group-row"><div><strong>${esc(g.name)}</strong><div class="tag-list">${g.tags.map(t=>`<span class="tag-badge" style="background:${safeColor(t.colorHex)}">${esc(t.name)}</span>`).join('')}</div></div><div><button class="danger-link" data-edit-group="${esc(g.id)}">編集</button> <button class="danger-link" data-delete-group="${esc(g.id)}">削除</button></div></div>`).join(''):'<p class="account-note">タググループはまだありません。</p>';}
  function safeColor(v){return /^#[\da-f]{6}$/i.test(v||'')?v:'#719b78';}
  function newId(){return crypto.randomUUID?crypto.randomUUID():`${Date.now()}-${Math.random().toString(16).slice(2)}`;}
  let editorLocationMap=null, editorLocationMarker=null;
  function releaseEditorLocationMap(){if(editorLocationMap){editorLocationMap.remove();editorLocationMap=null;editorLocationMarker=null;}}
  function closeModal(){modalCleanup?.();modalCleanup=null;releaseEditorLocationMap();const root=$('#modal-root');root.innerHTML='';pendingPlace=null;editDraft=null;selectedPhoto=null;sourceImage=null;cropLoadVersion++;if(cropSourceUrl)URL.revokeObjectURL(cropSourceUrl);cropSourceUrl=null;cropChanged=false;editorPhotoItems=[];activePhotoIndex=0;editorSelectionVersion++;}
  function showModal(title,html,actions=''){modalCleanup?.();modalCleanup=null;releaseEditorLocationMap();const root=$('#modal-root');root.innerHTML=`<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true"><div class="modal-header"><h2>${title}</h2><button class="icon-button" id="modal-close" aria-label="閉じる">×</button></div>${html}${actions}</section></div>`;const preview=$('#photo-preview',root);if(preview){const frame=document.createElement('div');frame.className='photo-frame';preview.parentNode.insertBefore(frame,preview);frame.append(preview);}$('#modal-close').onclick=closeModal;return $('.modal',root);}
  function handleGroupAction(e){const del=e.target.closest('[data-delete-group]')?.dataset.deleteGroup;const edit=e.target.closest('[data-edit-group]')?.dataset.editGroup;if(del){if(!confirm('このタググループを削除しますか？記録に付いたタグも削除されます。'))return;groups=groups.filter(g=>g.id!==del);delete activeTags[del];observations=observations.map(o=>({...o,tags:(o.tags||[]).filter(t=>t.groupId!==del)}));Promise.all([dbSet('groups',groups),...observations.map(dbPutObservation)]).then(()=>{renderGroups();renderCustomFilters();renderMapPins();notify('タググループを削除しました');});}else if(edit)editGroup(groups.find(g=>g.id===edit));}
  function editGroup(group){const isNew=!group,g=group||{id:newId(),name:'',tags:[]};const row=(tag={name:'',colorHex:'#6A994E'})=>`<div class="tag-editor-row"><input type="text" class="tag-name-input" maxlength="100" value="${esc(tag.name)}" placeholder="タグ名"><input type="color" class="tag-color-input" value="${safeColor(tag.colorHex)}" aria-label="タグ色"><button type="button" class="remove-tag-row" aria-label="タグを削除">×</button></div>`;const body=`<label class="form-field">グループ名<input id="group-name" value="${esc(g.name)}" maxlength="30"></label><div id="tag-editor-list">${g.tags.map(row).join('')}</div><button id="add-tag-row" class="secondary-button" type="button" style="margin-top:10px">＋ タグを追加</button><div class="modal-actions"><button class="secondary-full" id="group-cancel">キャンセル</button><button class="primary-button" id="group-save">保存</button></div>`;showModal(isNew?'タググループを作成':'タググループを編集',body);$('#group-cancel').onclick=closeModal;$('#add-tag-row').onclick=()=>$('#tag-editor-list').insertAdjacentHTML('beforeend',row());$('#tag-editor-list').onclick=e=>{if(e.target.closest('.remove-tag-row'))e.target.closest('.tag-editor-row').remove();};$('#group-save').onclick=async()=>{const name=$('#group-name').value.trim();if(!name)return notify('グループ名を入力してください');if(groups.some(x=>x.id!==g.id&&x.name===name))return notify('同じ名前のグループがあります');const tags=$$('#tag-editor-list .tag-editor-row').map(el=>({name:el.querySelector('.tag-name-input').value.trim(),colorHex:safeColor(el.querySelector('.tag-color-input').value)})).filter(t=>t.name);if(new Set(tags.map(t=>t.name)).size!==tags.length)return notify('同じ名前のタグがあります');if(!isNew){const keep=new Set(tags.map(t=>t.name));observations=observations.map(o=>({...o,tags:(o.tags||[]).filter(t=>t.groupId!==g.id||keep.has(t.name))}));await Promise.all(observations.map(dbPutObservation));}groups=isNew?[...groups,{...g,name,tags}]:groups.map(x=>x.id===g.id?{...g,name,tags}:x);await dbSet('groups',groups);closeModal();renderGroups();renderCustomFilters();renderMapPins();notify('タググループを保存しました');};}
  function seasonColor(date){return seasonFor(date).color;}
  function openEditor(existing){currentObservation=existing||null;selectedPhoto=null;sourceImage=null;cropChanged=false;editorPhotoItems=existing?observationPhotos(existing).map(blob=>({blob,isNew:false})):[];activePhotoIndex=0;editDraft=existing?{...existing}:{};const body=`<div class="form-field"><span>写真</span><div class="button-row"><button id="choose-photo" class="secondary-button">写真を追加</button><button id="take-photo" class="secondary-button">カメラで撮る</button></div><input id="photo-input" type="file" accept="image/*" multiple hidden><input id="camera-input" type="file" accept="image/*" capture="environment" hidden></div><div id="editor-photo-list" class="editor-photo-list"></div><p class="account-note">最大4枚。1枚目を地図と図鑑に表示します。</p><button id="remove-editor-photo" class="secondary-button" type="button">選択した写真を外す</button><img id="photo-preview" class="photo-preview" alt="写真プレビュー" hidden><div id="crop-controls" class="crop-controls" hidden><label>拡大<input id="crop-zoom" type="range" min="1" max="2.8" step=".05" value="1"></label><label>横位置<input id="crop-x" type="range" min="0" max="100" value="50"></label><label>縦位置<input id="crop-y" type="range" min="0" max="100" value="50"></label></div><p class="account-note">位置の調整範囲がない場合は、拡大すると横・縦に動かせます。</p><label class="form-field">生きものの名前<input id="obs-name" value="${esc(existing?.name||'')}" placeholder="例：アオサギ"></label><div class="taxonomy-row"><button id="taxonomy-search" class="secondary-button">GBIFで分類を検索</button><span id="taxonomy-status" class="account-note"></span></div><div id="taxonomy-results" class="taxonomy-results"></div><label class="form-field">種（和名）<input id="obs-species" value="${esc(existing?.species||'')}" placeholder="アオサギ"></label><label class="form-field">種の学名<input id="obs-scientific" value="${esc(existing?.scientificName||'')}"></label><div class="form-grid"><label class="form-field">属<input id="obs-genus" value="${esc(existing?.genus||'')}"></label><label class="form-field">科<input id="obs-family" value="${esc(existing?.family||'')}"></label><label class="form-field">目<input id="obs-order" value="${esc(existing?.order||'')}"></label><label class="form-field">ジャンル（必須）<select id="obs-category" required><option value="" disabled ${existing?.category?'':'selected'}>ジャンルを選んでください（必須）</option>${CATEGORIES.map(c=>`<option ${existing?.category===c?'selected':''}>${c}</option>`).join('')}</select></label></div><label class="form-field">撮影日時<input id="obs-date" type="datetime-local" value="${toLocalInput(existing?.date||Date.now())}"></label><label class="form-field">場所のメモ<input id="obs-place" value="${esc(existing?.place||'')}" placeholder="例：近所の公園"></label><button id="gps-button" class="secondary-button">現在地を使う</button><button id="pick-location" class="secondary-button">地図で場所を選ぶ</button><p id="location-status" class="account-note">${existing?coordinateLabel(existing):'写真の位置情報がない場合は、地図で指定できます。'}</p><label class="form-field privacy-choice"><input id="location-private" type="checkbox" ${existing?.locationPrivate?'checked':''}> 場所の詳細を非公開にする</label><label id="prefecture-field" class="form-field" ${existing?.locationPrivate?'':'hidden'}>表示する都道府県（必須）<select id="obs-prefecture"><option value="">選んでください</option>${PREFECTURE_CAPITALS.map(([name])=>`<option value="${name}" ${existing?.prefecture===name?'selected':''}>${name}</option>`).join('')}</select><small>地図と共有先には県庁所在地を表示し、正確な座標と場所メモは共有しません。</small></label><div id="editor-map" class="location-map" aria-label="観察場所を選ぶ地図"></div><p id="editor-map-status" class="account-note">地図をタップして観察場所を選べます。</p><label class="form-field">ひとことコメント<textarea id="obs-comment" rows="2">${esc(existing?.comment||'')}</textarea></label><div id="custom-tag-fields"></div><div class="modal-actions"><button id="editor-cancel" class="secondary-full">キャンセル</button><button id="editor-save" class="primary-button">${existing?'変更を保存':'記録を保存'}</button></div>`;const modal=showModal(existing?'観察記録を編集':'観察を記録',body);if(existing?.photo){const img=$('#photo-preview');img.src=photoUrl(existing);img.hidden=false;$('#crop-controls').hidden=false;}else if(sourceImage){const img=$('#photo-preview');img.src=sourceImage.src;img.hidden=false;$('#crop-controls').hidden=false;}renderEditorTags(existing);$('#editor-cancel').onclick=closeModal;$('#choose-photo').onclick=()=>$('#photo-input').click();$('#take-photo').onclick=()=>$('#camera-input').click();$('#photo-input').onchange=previewPhoto;$('#camera-input').onchange=previewPhoto;$('#crop-controls').oninput=updateCropPreview;$('#crop-controls').onchange=updateCropPreview;$('#gps-button').onclick=useCurrentLocation;$('#pick-location').onclick=pickLocation;$('#editor-save').onclick=saveObservation;$('#taxonomy-search').onclick=searchTaxonomy;$('#location-private').onchange=()=>{$('#prefecture-field').hidden=!$('#location-private').checked;};initEditorLocationMap(existing);$('#remove-editor-photo').onclick=removeEditorPhoto;renderEditorPhotoList();if(editorPhotoItems[0]?.blob)selectEditorPhoto(0);return modal;}
  function toLocalInput(value){const d=new Date(value);return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}T${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;}
  function renderEditorTags(existing){const root=$('#custom-tag-fields');root.innerHTML=groups.map(g=>{const selected=new Set((existing?.tags||[]).filter(t=>t.groupId===g.id).map(t=>t.name));return `<fieldset class="tag-fieldset"><legend>${esc(g.name)}</legend><div class="tag-list">${g.tags.map(t=>`<label class="tag-choice"><input type="checkbox" data-group="${esc(g.id)}" value="${esc(t.name)}" ${selected.has(t.name)?'checked':''}><span class="tag-badge" style="background:${safeColor(t.colorHex)}">${esc(t.name)}</span></label>`).join('')}</div></fieldset>`;}).join('');}
  let selectedPhoto=null,sourceImage=null,cropSourceUrl=null,cropLoadVersion=0,cropChanged=false;
  let editorPhotoItems=[],activePhotoIndex=0,editorSelectionVersion=0;
  async function loadCropSource(file){
    const version=++cropLoadVersion;
    sourceImage=null;
    if(cropSourceUrl)URL.revokeObjectURL(cropSourceUrl);
    cropSourceUrl=URL.createObjectURL(file);
    const url=cropSourceUrl,image=new Image(),save=$('#editor-save');
    if(save)save.disabled=true;
    try{
      await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=()=>reject(new Error('写真を読み込めませんでした。JPEG・PNG・WebP形式の写真を選んでください。'));image.src=url;});
      if(version!==cropLoadVersion)return;
      sourceImage=image;$('#crop-controls').hidden=false;updateCropPreview();
    }catch(error){if(version===cropLoadVersion){selectedPhoto=null;notify(error.message);}}
    finally{if(version===cropLoadVersion&&save)save.disabled=false;}
  }
  function renderEditorPhotoList(){
    const root=$('#editor-photo-list');if(!root)return;
    root.innerHTML=editorPhotoItems.map((item,index)=>'<button type="button" data-photo-index="'+index+'" aria-pressed="'+(index===activePhotoIndex)+'"><img src="'+photoUrl({photo:item.blob})+'" alt="写真'+(index+1)+'"><span>写真'+(index+1)+'</span></button>').join('');
    root.onclick=event=>{const button=event.target.closest('[data-photo-index]');if(button)selectEditorPhoto(Number(button.dataset.photoIndex)).catch(error=>notify(error.message));};
    $('#choose-photo').disabled=editorPhotoItems.length>=4;$('#take-photo').disabled=editorPhotoItems.length>=4;
    $('#remove-editor-photo').disabled=!editorPhotoItems.length;
  }
  async function commitEditorPhoto(){
    const items=editorPhotoItems,index=activePhotoIndex;
    if(cropChanged&&sourceImage&&items[index]){
      const blob=await cropImage();items[index]={blob,isNew:false};
      if(items===editorPhotoItems&&index===activePhotoIndex)cropChanged=false;
    }
  }
  async function selectEditorPhoto(index){
    if(index<0||index>=editorPhotoItems.length)return;
    const version=++editorSelectionVersion;
    await commitEditorPhoto();if(version!==editorSelectionVersion||!$('#photo-preview'))return;activePhotoIndex=index;
    const item=editorPhotoItems[index];
    selectedPhoto=item.blob;cropChanged=item.isNew;
    $('#crop-zoom').value='1';$('#crop-x').value=$('#crop-y').value='50';
    renderEditorPhotoList();
    if(item.blob)await loadCropSource(item.blob);
    else{sourceImage=null;$('#photo-preview').hidden=true;$('#crop-controls').hidden=true;notify('この写真を取得できていません。同期するか写真を選び直してください。');}
  }
  async function removeEditorPhoto(){
    if(!editorPhotoItems.length)return;
    cropLoadVersion++;editorSelectionVersion++;editorPhotoItems.splice(activePhotoIndex,1);sourceImage=null;selectedPhoto=null;cropChanged=false;
    activePhotoIndex=Math.max(0,Math.min(activePhotoIndex,editorPhotoItems.length-1));
    renderEditorPhotoList();
    if(editorPhotoItems.length)await selectEditorPhoto(activePhotoIndex);
    else{$('#photo-preview').hidden=true;$('#crop-controls').hidden=true;$('#photo-preview').parentElement.hidden=true;}
  }
  async function previewPhoto(e){
    const files=[...(e.target.files||[])];e.target.value='';if(!files.length)return;
    if(editorPhotoItems.length+files.length>4){notify('写真は1件につき4枚までです。追加する枚数を減らしてください。');return;}
    try{
      const initial=editorPhotoItems.length===0&&!currentObservation;
      const items=editorPhotoItems;
      await commitEditorPhoto();
      if(items!==editorPhotoItems)return;
      const firstNew=editorPhotoItems.length;editorPhotoItems.push(...files.map(blob=>({blob,isNew:true})));
      await selectEditorPhoto(firstNew);
      if(initial){const meta=await readExif(files[0]);if(!$('#obs-date'))return;if(meta.date)$('#obs-date').value=toLocalInput(meta.date);if(meta.lat!=null)setEditorLocation(meta.lat,meta.lng,'写真の位置情報を読み取りました');}
    }catch(error){notify(error.message);}
  }
  async function prepareEditorPhotos(){
    if(!editorPhotoItems.length||editorPhotoItems.length>4)throw new Error('写真は1〜4枚で登録してください');
    const items=editorPhotoItems;
    await commitEditorPhoto();
    const photos=[];
    for(const item of items){
      if(!(item.blob instanceof Blob))throw new Error('未取得の写真があります。同期するか写真を選び直してください。');
      if(item.isNew){item.blob=await photoWithoutExif(item.blob);item.isNew=false;}
      photos.push(item.blob);
    }
    return photos;
  }
  async function readExif(file){try{const buf=await file.arrayBuffer();const v=new DataView(buf);if(v.getUint16(0)!==0xffd8)return{};let pos=2;while(pos+4<buf.byteLength){if(v.getUint8(pos)!==0xff)break;const marker=v.getUint8(pos+1),length=v.getUint16(pos+2);if(marker===0xe1&&length>10&&String.fromCharCode(...new Uint8Array(buf,pos+4,6))==='Exif\0\0'){const base=pos+10,le=v.getUint16(base)===0x4949,rd16=o=>v.getUint16(o,le),rd32=o=>v.getUint32(o,le),ifd0=base+rd32(base+4),count=rd16(ifd0);let exifOffset=0,gpsOffset=0,date='';for(let i=0;i<count;i++){const e=ifd0+2+i*12,tag=rd16(e);if(tag===0x8769)exifOffset=base+rd32(e+8);if(tag===0x8825)gpsOffset=base+rd32(e+8);}if(exifOffset){const n=rd16(exifOffset);for(let i=0;i<n;i++){const e=exifOffset+2+i*12;if(rd16(e)===0x9003){const off=base+rd32(e+8),size=rd32(e+4);date=String.fromCharCode(...new Uint8Array(buf,off,Math.min(size,32))).replace(/\0.*$/,'');break;}}}let lat=null,lng=null;if(gpsOffset){const n=rd16(gpsOffset),found={};for(let i=0;i<n;i++){const e=gpsOffset+2+i*12,tag=rd16(e),type=rd16(e+2),cnt=rd32(e+4),off=base+rd32(e+8);if(tag===1||tag===3){found[tag]=String.fromCharCode(v.getUint8(type===2&&cnt<=4?e+8:off));}if((tag===2||tag===4)&&type===5&&cnt>=3){const a=[];for(let k=0;k<3;k++){const p=off+k*8;a.push(rd32(p)/rd32(p+4));}found[tag]=a[0]+a[1]/60+a[2]/3600;}}if(found[2]!=null&&found[4]!=null){lat=found[2]*(found[1]==='S'?-1:1);lng=found[4]*(found[3]==='W'?-1:1);}}const d=date.match(/^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/);return{date:d?new Date(+d[1],+d[2]-1,+d[3],+d[4],+d[5],+d[6]).getTime():null,lat,lng};}pos+=2+length;}return{};}catch{return{};}}
  function cropBounds(image,zoom,x,y,ratio=4/3){
    const width=image.naturalWidth,height=image.naturalHeight;
    let sw=width/zoom,sh=height/zoom;
    if(sw/sh>ratio)sw=sh*ratio;else sh=sw/ratio;
    return {sx:(width-sw)*x,sy:(height-sh)*y,sw,sh};
  }
  function drawCrop(canvas){
    const bounds=cropBounds(sourceImage,Number($('#crop-zoom').value),Number($('#crop-x').value)/100,Number($('#crop-y').value)/100);
    canvas.getContext('2d').drawImage(sourceImage,bounds.sx,bounds.sy,bounds.sw,bounds.sh,0,0,canvas.width,canvas.height);
    return bounds;
  }
  function updateCropPreview(event){
    const img=$('#photo-preview');if(!img||!sourceImage)return;
    if(event)cropChanged=true;
    const canvas=document.createElement('canvas');canvas.width=600;canvas.height=450;
    const bounds=drawCrop(canvas);
    img.style.transform='none';img.style.objectPosition='center';img.src=canvas.toDataURL('image/jpeg',.9);img.hidden=false;
    if(img.parentElement?.classList.contains('photo-frame'))img.parentElement.hidden=false;
    $('#crop-x').disabled=sourceImage.naturalWidth-bounds.sw<.01;
    $('#crop-y').disabled=sourceImage.naturalHeight-bounds.sh<.01;
  }
  function cropImage(){return new Promise((resolve,reject)=>{
    if(!cropChanged&&editorPhotoItems[activePhotoIndex]?.blob){resolve(editorPhotoItems[activePhotoIndex].blob);return;}
    if(!sourceImage){reject(new Error('写真の読み込みが完了してから保存してください'));return;}
    const canvas=document.createElement('canvas');canvas.width=1200;canvas.height=900;drawCrop(canvas);
    canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('写真を処理できませんでした')),'image/jpeg',.88);
  });}
  async function useCurrentLocation(){const status=$('#location-status');if(!navigator.geolocation){status.textContent='このブラウザでは位置情報を利用できません。';return;}status.textContent='現在地を取得しています…';navigator.geolocation.getCurrentPosition(p=>{setEditorLocation(p.coords.latitude,p.coords.longitude,'現在地を設定しました');},err=>{status.textContent=err.code===1?'位置情報の利用が許可されていません。ブラウザ設定で許可してください。':'現在地を取得できませんでした。';},{enableHighAccuracy:true,timeout:12000});}
  function setEditorLocation(lat,lng,label){const valid=coordinatePoint(lat,lng);if(!valid){notify('位置情報が正しくありません。地図から場所を選んでください。');return;}({lat,lng}=valid);pendingPlace=valid;const status=$('#location-status');if(status)status.textContent=`${label}：${lat.toFixed(5)}, ${lng.toFixed(5)}`;if(editorLocationMap){const point=[lat,lng];editorLocationMap.setView(point,12);if(editorLocationMarker)editorLocationMarker.setLatLng(point);else editorLocationMarker=L.marker(point,{icon:L.divIcon({className:'location-pin',html:'●',iconSize:[26,32],iconAnchor:[13,28]})}).addTo(editorLocationMap);}}
  function initEditorLocationMap(existing){const el=$('#editor-map');if(!el||typeof L==='undefined')return;requestAnimationFrame(()=>{if(!el.isConnected)return;const selected=(pendingPlace&&coordinatePoint(pendingPlace.lat,pendingPlace.lng))||(existing?recordPoint(existing):null);editorLocationMap=L.map(el,{zoomControl:true,scrollWheelZoom:false,tap:true}).setView(selected?[selected.lat,selected.lng]:[36.2,138.25],selected?12:5);const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors'}).addTo(editorLocationMap);tiles.on('tileerror',()=>{const status=$('#editor-map-status');if(status)status.textContent='地図を読み込めませんでした。通信環境を確認して再度お試しください。';});if(selected){editorLocationMarker=L.marker([selected.lat,selected.lng],{icon:L.divIcon({className:'location-pin',html:'●',iconSize:[26,32],iconAnchor:[13,28]})}).addTo(editorLocationMap);}editorLocationMap.on('click',event=>setEditorLocation(event.latlng.lat,event.latlng.lng,'地図で場所を設定しました'));requestAnimationFrame(()=>editorLocationMap?.invalidateSize());});}
  function pickLocation(){const el=$('#editor-map');if(!el)return;el.scrollIntoView({behavior:'smooth',block:'center'});setTimeout(()=>editorLocationMap?.invalidateSize(),250);}
  async function saveObservation(){const name=$('#obs-name').value.trim();if(!name)return notify('生きものの名前を入力してください');if(!CATEGORIES.includes($('#obs-category').value))return notify('ジャンルを選択してください');const coords=(pendingPlace&&coordinatePoint(pendingPlace.lat,pendingPlace.lng))||(currentObservation?recordPoint(currentObservation):null);if(!coords){notify('現在地を使うか、地図で観察場所を選んでください');return;}try{const photos=await prepareEditorPhotos(),photo=photos[0];const tags=$$('#custom-tag-fields input:checked').map(x=>({groupId:x.dataset.group,name:x.value}));const locationPrivate=$('#location-private').checked,prefecture=$('#obs-prefecture').value;if(locationPrivate&&!prefecture)return requirePrefecture();const record={...(currentObservation||{}),id:currentObservation?.id||newId(),name,photo,photos,date:new Date($('#obs-date').value).getTime(),lat:coords.lat,lng:coords.lng,locationPrivate,prefecture:locationPrivate?prefecture:'',place:$('#obs-place').value.trim(),comment:$('#obs-comment').value.trim(),category:$('#obs-category').value,species:$('#obs-species').value.trim(),scientificName:$('#obs-scientific').value.trim(),genus:$('#obs-genus').value.trim(),family:$('#obs-family').value.trim(),order:$('#obs-order').value.trim(),mapId:'japan',tags,localAccountId:communitySession.accountId,postedAt:currentObservation?.postedAt||currentObservation?.date||Date.now()};if(record.isShared||record.owner?.accountId===communitySession?.accountId){if(!communitySession)throw new Error('共有中の記録を編集するにはログインしてください。');await uploadObservation(record,true);}await dbPutObservation(record);const ix=observations.findIndex(o=>o.id===record.id);if(ix>=0)observations[ix]=record;else observations.push(record);selectedPhoto=null;sourceImage=null;closeModal();renderBuiltInFilters();renderMapPins();renderBook();notify('観察記録を保存しました');}catch(err){console.error(err);notify(err.message||'保存できませんでした。写真サイズを小さくしてお試しください。');}}
  function requirePrefecture(){const field=$('#obs-prefecture');field.focus();field.scrollIntoView({behavior:'smooth',block:'center'});notify('保存するには都道府県を選んでください');}
  function serverObservation(o,shared){const tagValues={};for(const t of o.tags||[])(tagValues[t.groupId]??=[]).push(t.name);const point=mapPoint(o);if(!point)throw new Error('位置情報がありません。記録を編集して場所を指定してください。');return{id:o.id,name:o.name,photoCount:observationPhotos(o).length,dateMillis:o.date,latitude:point.lat,longitude:point.lng,place:o.locationPrivate?'':o.place||'',locationPrivate:!!o.locationPrivate,prefecture:o.locationPrivate?o.prefecture||'':'',comment:o.comment||'',category:o.category||'その他',species:o.species||'',speciesScientificName:o.scientificName||'',genus:o.genus||'',family:o.family||'',order:o.order||'',tags:(o.tags||[]).map(t=>t.name),tagValues,shared};}
  function photoWithoutExif(blob){return new Promise((resolve,reject)=>{const url=URL.createObjectURL(blob),image=new Image();image.onload=()=>{const scale=Math.min(1,1800/Math.max(image.naturalWidth,image.naturalHeight)),canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(image.naturalWidth*scale));canvas.height=Math.max(1,Math.round(image.naturalHeight*scale));canvas.getContext('2d').drawImage(image,0,0,canvas.width,canvas.height);URL.revokeObjectURL(url);canvas.toBlob(result=>result?resolve(result):reject(new Error('写真を処理できませんでした')),'image/jpeg',.85);};image.onerror=()=>{URL.revokeObjectURL(url);reject(new Error('写真を読み込めませんでした'));};image.src=url;});}
  async function uploadObservation(o,update,syncGroups=true){
    if(!communitySession)throw new Error('コミュニティへログインしてください。');
    const photos=observationPhotos(o);
    if(photos.length<1||photos.length>4||photos.some(photo=>!(photo instanceof Blob)))throw new Error('全写真を取得してから共有してください。写真は最大4枚です。');
    if(photos.length>1){const response=await piFetch('/api/v1/status');const status=await response.json();if(!response.ok||status.maxPhotosPerObservation<photos.length||!status.maxPhotosPerObservation)throw new Error('複数写真を共有するには写真サーバーの更新が必要です');}
    if(syncGroups)await serverRequest('/api/v1/tag-groups',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({groups:groups.filter(g=>!g.sharedOwner||g.sharedOwner===communitySession.accountId)})});
    const form=new FormData();form.append('metadata',JSON.stringify(serverObservation(o,!!o.isShared)));
    for(let index=0;index<photos.length;index++){const photo=o.locationPrivate?await photoWithoutExif(photos[index]):photos[index];form.append(index===0?'photo':'photo'+(index+1),photo,o.id+'-'+(index+1)+'.jpg');}
    return serverRequest(update?'/api/v1/observations/'+encodeURIComponent(o.id):'/api/v1/observations',{method:update?'PUT':'POST',body:form});
  }
  function openBulkShare(){
    if(!communitySession?.communityMember)return notify('まとめて共有するにはコミュニティへの参加が必要です');
    const accountId=communitySession.accountId,selected=new Set();
    const eligible=()=>observations.filter(o=>!o.isShared&&isOwnObservation(o)&&(!o.owner?.accountId||o.owner.accountId===accountId))
      .sort((a,b)=>guidePostedAt(b)-guidePostedAt(a));
    let busy=false;
    showModal('まとめて共有','<p>未共有の自分の投稿を選んでください。写真・撮影日時・場所・タグ・コメントを公開します。</p><div class="bulk-share-tools"><span id="bulk-share-count">0件選択</span><button type="button" id="bulk-share-all" class="quiet-button">すべて選択</button><button type="button" id="bulk-share-clear" class="quiet-button">解除</button></div><div id="bulk-share-list" class="bulk-share-list"></div><p id="bulk-share-status" role="status" class="account-note"></p><div class="modal-actions"><button type="button" id="bulk-share-cancel" class="secondary-full">閉じる</button><button type="button" id="bulk-share-confirm" class="primary-button">選択した投稿を共有</button></div>');
    const render=()=>{
      const items=eligible();
      $('#bulk-share-count').textContent=selected.size+'件選択';
      $('#bulk-share-confirm').disabled=busy||!items.some(o=>selected.has(o.id));
      $('#bulk-share-all').disabled=busy||!items.length;
      $('#bulk-share-clear').disabled=busy||!selected.size;
      $('#bulk-share-cancel').disabled=busy;
      $('#modal-close').disabled=busy;
      $('#bulk-share-list').innerHTML=items.length?items.map(o=>{
        const place=o.locationPrivate?esc(o.prefecture||'都道府県未選択')+'の県庁所在地':esc(coordinateLabel(o))+' '+esc(o.place||'');
        const tags=(o.tags||[]).map(t=>t.name).join('・')||'なし';
        return '<label class="bulk-share-item"><input type="checkbox" data-bulk-id="'+esc(o.id)+'" '+(selected.has(o.id)?'checked ':'')+(busy?'disabled':'')+'><span class="bulk-share-photo">'+(o.photo?'<img src="'+photoUrl(o)+'" alt="">':'写真未取得')+'</span><span class="bulk-share-info"><strong>'+esc(o.name)+'</strong><small>'+esc(localDateTime(o.date))+'・写真'+observationPhotos(o).length+'枚</small><small>公開場所：'+place+'</small><small>タグ：'+esc(tags)+'</small>'+(o.comment?'<small>コメント：'+esc(o.comment)+'</small>':'')+'</span></label>';
      }).join(''):'<p>未共有の投稿はありません。</p>';
    };
    $('#bulk-share-list').onchange=event=>{const input=event.target.closest('[data-bulk-id]');if(!input||busy)return;input.checked?selected.add(input.dataset.bulkId):selected.delete(input.dataset.bulkId);render();};
    $('#bulk-share-all').onclick=()=>{eligible().forEach(o=>selected.add(o.id));render();};
    $('#bulk-share-clear').onclick=()=>{selected.clear();render();};
    $('#bulk-share-cancel').onclick=closeModal;
    $('#bulk-share-confirm').onclick=async()=>{
      if(busy||communitySession?.accountId!==accountId)return;
      const items=eligible().filter(o=>selected.has(o.id));if(!items.length)return;
      busy=true;$('#modal-root .modal').dataset.busy='true';render();
      const status=$('#bulk-share-status'),failures=[];
      let shared=0;
      try{
        await serverRequest('/api/v1/tag-groups',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({groups:groups.filter(g=>!g.sharedOwner||g.sharedOwner===accountId)})});
        for(let index=0;index<items.length;index++){
          if(communitySession?.accountId!==accountId){failures.push('アカウントが切り替わりました');break;}
          const o=items[index];status.textContent='共有中 '+(index+1)+'/'+items.length+'件…';
          try{
            if(o.locationPrivate&&!o.prefecture)throw new Error('編集画面で都道府県を選んでください');
            await uploadObservation({...o,isShared:true},!!o.owner?.accountId,false);
            const member=communityMembers.find(m=>m.accountId===accountId);
            o.isShared=true;o.owner={accountId,displayName:communitySession.displayName,loginName:member?.loginName||communitySession.displayName};
            await dbPutObservation(o);selected.delete(o.id);shared++;
          }catch(error){failures.push(o.name+'：'+(error.message||'共有できませんでした'));}
        }
        renderSharingFilters();renderMapPins();renderBook();
        if(!failures.length){closeModal();notify(shared+'件をコミュニティに共有しました');return;}
        status.textContent=shared+'件共有しました。失敗した'+failures.length+'件は選択を残しています。\n'+failures.join('\n');
      }catch(error){status.textContent='共有を開始できませんでした：'+(error.message||'通信エラー');}
      finally{busy=false;if($('#bulk-share-list')){delete $('#modal-root .modal').dataset.busy;render();}}
    };
    render();
  }
  async function toggleObservationSharing(id){
    if(!communitySession?.communityMember)return notify('共有するにはコミュニティへの参加が必要です');
    const o=observations.find(x=>x.id===id);if(!o)return;
    const share=!o.isShared;
    if(!share&&!confirm('「'+o.name+'」の共有を停止しますか？'))return;
    const perform=async()=>{
      const button=$('#share-preview-confirm');if(button)button.disabled=true;
      try{
        if(share){
          await uploadObservation({...o,isShared:true},!!o.owner?.accountId);
          o.isShared=true;
          const member=communityMembers.find(m=>m.accountId===communitySession.accountId);
          o.owner={accountId:communitySession.accountId,displayName:communitySession.displayName,loginName:member?.loginName||communitySession.displayName};
        }else{await uploadObservation({...o,isShared:false},true);o.isShared=false;}
        await dbPutObservation(o);closeModal();renderSharingFilters();renderMapPins();renderBook();
        notify(share?'コミュニティに共有しました':'共有を停止しました');
      }catch(error){notify(error.message||'共有設定を変更できませんでした');if(button)button.disabled=false;}
    };
    if(!share)return perform();
    const tagNames=(o.tags||[]).map(tag=>tag.name).join('・')||'なし';
    const photos=observationPhotos(o);
    const images=photos.map((photo,index)=>photo instanceof Blob?'<img src="'+photoUrl({photo})+'" alt="写真'+(index+1)+'">':'<span>写真未取得</span>').join('');
    showModal('共有前に確認','<div class="share-preview-photos">'+images+'</div><p><strong>'+esc(o.name)+'</strong>（'+esc(o.category)+'）・写真'+photos.length+'枚</p><p>撮影日時：'+esc(localDateTime(o.date))+'</p><p>公開する場所：'+(o.locationPrivate?esc(o.prefecture)+'の県庁所在地':esc(coordinateLabel(o))+' '+esc(o.place||''))+'</p><p>タグ：'+esc(tagNames)+'</p><p>コメント：'+esc(o.comment||'なし')+'</p><div class="modal-actions"><button type="button" id="share-preview-cancel" class="secondary-full">戻る</button><button type="button" id="share-preview-confirm" class="primary-button">共有する</button></div>');
    $('#share-preview-cancel').onclick=()=>showObservation(id);
    $('#share-preview-confirm').onclick=perform;
  }
  async function searchTaxonomy(){const query=$('#obs-name').value.trim();if(!query)return notify('生きものの名前を先に入力してください');$('#taxonomy-status').textContent='GBIFを検索しています…';$('#taxonomy-results').innerHTML='';try{const url=`https://api.gbif.org/v1/species/search?q=${encodeURIComponent(query)}&qField=VERNACULAR&rank=SPECIES&status=ACCEPTED&limit=8`;let response=await fetch(url);let data=await response.json();let results=data.results||[];if(!results.length){response=await fetch(`https://api.gbif.org/v1/species/search?q=${encodeURIComponent(query)}&qField=SCIENTIFIC&rank=SPECIES&status=ACCEPTED&limit=8`);data=await response.json();results=data.results||[];}if(!results.length){$('#taxonomy-status').textContent='候補が見つかりません。手入力できます。';return;}$('#taxonomy-status').textContent='候補から選択してください';$('#taxonomy-results').innerHTML=results.map((r,i)=>`<button class="taxonomy-result" data-taxonomy="${i}">${esc(r.vernacularName||query)}<small>${esc(r.canonicalName||r.scientificName)} · 種</small></button>`).join('');$('#taxonomy-results').onclick=async e=>{const i=e.target.closest('[data-taxonomy]')?.dataset.taxonomy;if(i==null)return;const candidate=results[Number(i)];$('#taxonomy-status').textContent='分類情報を取得しています…';try{const lineage=await getLineage(candidate);$('#obs-name').value=candidate.vernacularName||query;$('#obs-species').value=candidate.vernacularName||query;$('#obs-scientific').value=candidate.canonicalName||candidate.scientificName||'';$('#obs-genus').value=lineage.GENUS||'';$('#obs-family').value=lineage.FAMILY||'';$('#obs-order').value=lineage.ORDER||'';$('#taxonomy-results').innerHTML='';$('#taxonomy-status').textContent='分類を入力しました。必要に応じて編集できます。';}catch(err){$('#taxonomy-status').textContent='種名を入力しました。上位分類は手入力してください。';$('#obs-species').value=candidate.vernacularName||query;$('#obs-scientific').value=candidate.canonicalName||candidate.scientificName||'';}};}catch(err){$('#taxonomy-status').textContent='検索できませんでした。通信環境をご確認ください。';}}
  async function getLineage(candidate){const arr=await (await fetch(`https://api.gbif.org/v1/species/${candidate.key}/parents`)).json();const rankMap={};for(const t of [...arr,candidate])rankMap[String(t.rank||'').toUpperCase()]=t;const entries=await Promise.all(['GENUS','FAMILY','ORDER'].map(async rank=>{const t=rankMap[rank];if(!t)return[rank,''];try{const names=await(await fetch(`https://api.gbif.org/v1/species/${t.key}/vernacularNames`)).json();const jp=names.filter(n=>String(n.language||'').toLowerCase()==='ja').sort((a,b)=>Number(b.preferred)-Number(a.preferred))[0];return[rank,jp?.vernacularName||t.canonicalName||t.scientificName||''];}catch{return[rank,t.canonicalName||t.scientificName||''];}}));return Object.fromEntries(entries);}
  function renderDetailPhotos(o){
    attachRelatedObservations(o);
    const photos=observationPhotos(o),frame=$('#modal-root .detail-photo'),image=frame.querySelector('.photo-preview');
    const missing=document.createElement('p');missing.className='detail-photo-missing';missing.textContent='この写真は未取得です。共有記録を同期してください。';frame.append(missing);
    let index=0;
    const pager=document.createElement('div');pager.className='photo-pager';
    pager.innerHTML='<button type="button" class="secondary-button">前へ</button><span></span><button type="button" class="secondary-button">次へ</button>';
    const [prev,next]=pager.querySelectorAll('button'),label=pager.querySelector('span');
    const render=()=>{const photo=photos[index];image.hidden=!photo;missing.hidden=!!photo;image.src=photoUrl({photo});image.alt=o.name+' 写真'+(index+1);label.textContent=(index+1)+' / '+photos.length;prev.disabled=index===0;next.disabled=index===photos.length-1;};
    prev.onclick=()=>{if(index>0){index--;render();}};next.onclick=()=>{if(index<photos.length-1){index++;render();}};
    if(photos.length>1)frame.after(pager);render();
  }
  function decorateObservationDetail(o){
    const point=mapPoint(o),button=document.createElement('button');
    button.className='secondary-button wide';button.textContent='地図で見る';button.disabled=!point;
    button.onclick=()=>showObservationOnMap(o);
    $('#modal-root .modal-actions')?.before(button);
    if(!point)return;
    const link=document.createElement('a');link.className='secondary-button map-link';
    link.href='https://www.google.com/maps/search/?api=1&query='+point.lat+','+point.lng;
    link.target='_blank';link.rel='noopener noreferrer';link.textContent='この地点をGoogleマップで確認';
    $('#modal-root .modal-actions')?.before(link);
  }
  function attachRelatedObservations(o){
    const title=record=>(record.name||record.species||record.scientificName||'').trim();
    const related=observations.filter(record=>title(record)===title(o)).sort(guideOrder);
    const summary=document.createElement('section');
    summary.className='species-summary';
    summary.innerHTML='<strong>この生きものの観察：'+related.length+'件</strong><span>初回：'+esc(localDateTime(Math.min(...related.map(x=>Number(x.date)||0))))+'<br>最新：'+esc(localDateTime(Math.max(...related.map(x=>Number(x.date)||0))))+'</span>';
    $('#modal-root .detail-photo').before(summary);
    if(related.length<2)return;
    const section=document.createElement('section');section.className='related-observations';section.setAttribute('aria-label','同じ生きものの投稿');
    section.innerHTML=`<h3>同じ生きものの投稿（${related.length}件）</h3><p>写真を選ぶと、その投稿の詳細を開きます</p><div class="related-posts">${related.map(record=>{
      const selected=record.id===o.id,author=record.isShared?(ownerFor(record)?.displayName||record.owner?.displayName||record.owner?.loginName||record.owner?.accountId||'共有者'):'自分の記録';
      return `<div class="related-post-wrap"><button type="button" class="related-post" data-related-post="${esc(record.id)}" aria-pressed="${selected}" aria-label="${esc(author+'・'+localDateTime(record.date)+'の詳細を開く')}"><span class="related-photo" style="--genre-color:${categoryColor(record.category)}">${record.photo?`<img src="${photoUrl(record)}" alt="${esc(record.name)}">`:'<span>写真未取得</span>'}</span><strong>${esc(author)}</strong><small>${esc(localDateTime(record.date))}</small>${selected?'<small class="related-selected">表示中</small>':''}</button>${isOwnObservation(record)?`<button type="button" class="related-edit" data-edit-related="${esc(record.id)}" aria-label="${esc(record.name)}の記録を編集">✎ 編集</button>`:''}</div>`;
    }).join('')}</div>`;
    $('#modal-root .detail-photo').before(section);
    section.onclick=event=>{const edit=event.target.closest('[data-edit-related]');if(edit){const record=observations.find(item=>item.id===edit.dataset.editRelated);if(record&&isOwnObservation(record))openEditor(record);return;}const button=event.target.closest('[data-related-post]');if(button&&button.dataset.relatedPost!==o.id)showObservation(button.dataset.relatedPost);};
    $('.related-post[aria-pressed=true]',section)?.scrollIntoView({block:'nearest',inline:'nearest'});
  }
  function showObservation(id){const o=observations.find(x=>x.id===id);if(!o)return;const body=`<div class="detail-photo" style="--genre-color:${categoryColor(o.category)}"><img class="photo-preview" src="${photoUrl(o)}" alt="${esc(o.name)}">${avatarBadge(o)}</div><h3 style="color:#d9d917;margin:13px 0 4px">${esc(o.name)}</h3><p>${esc(o.category)} · ${esc(seasonFor(o.date).name)} · ${esc(localDateTime(o.date))}</p><p>${o.locationPrivate?`場所の詳細は非公開です（${esc(o.prefecture)}の県庁所在地）`:esc(o.place||'場所のメモなし')}<br>${coordinateLabel(o)}</p>${o.species?`<p>種：${esc(o.species)}</p>`:''}${o.scientificName?`<p>学名：<i>${esc(o.scientificName)}</i></p>`:''}${o.genus?`<p>属：${esc(o.genus)}　科：${esc(o.family)}　目：${esc(o.order)}</p>`:''}${o.comment?`<p>${esc(o.comment)}</p>`:''}${o.isShared&&o.owner?.accountId?`<p>共有者：${esc(ownerFor(o)?.displayName||o.owner.displayName||o.owner.loginName||o.owner.accountId)}</p>`:''}${(o.tags||[]).map(t=>{const g=groups.find(x=>x.id===t.groupId),tag=g?.tags.find(x=>x.name===t.name);return `<span class="tag-badge" style="background:${safeColor(tag?.colorHex)}">${esc(t.name)}</span>`}).join('')}<div class="modal-actions"><button id="detail-close" class="secondary-full">閉じる</button><button id="detail-share" class="secondary-full">${o.isShared?'共有を停止':'コミュニティに共有'}</button><button id="detail-edit" class="secondary-full">編集</button><button id="detail-delete" class="secondary-full" style="color:#ff9b9b">削除</button></div>`;showModal('観察記録',body);$('.modal').dataset.observationId=id;renderDetailPhotos(o);decorateObservationDetail(o);attachObservationLikes(o);attachObservationComments(o);const isOther=o.owner?.accountId&&communitySession?.accountId!==o.owner.accountId;$('#detail-close').onclick=closeModal;$('#detail-share').hidden=!!isOther;$('#detail-edit').hidden=!!isOther;$('#detail-delete').hidden=!!isOther;$('#detail-share').onclick=()=>toggleObservationSharing(o.id);$('#detail-edit').onclick=()=>{if(isOther)return notify('他のメンバーの記録は編集できません');closeModal();openEditor(o);};$('#detail-delete').onclick=async()=>{if(isOther)return notify('他のメンバーの記録は削除できません');if(!confirm(o.owner?.accountId===communitySession?.accountId?'この記録をサーバーと端末から削除しますか？':'この観察記録を削除しますか？'))return;try{if(o.owner?.accountId===communitySession?.accountId)await serverRequest(`/api/v1/observations/${encodeURIComponent(o.id)}`,{method:'DELETE'});await dbDeleteObservation(o.id);observations=observations.filter(x=>x.id!==o.id);closeModal();renderBook();renderMapPins();notify('記録を削除しました');}catch(e){notify(e.message||'削除できませんでした');}};}
  let modalCleanup=null,reactionRefreshPending=false,reactionRevision=0;
  const reactionRevisions=new Map();
  const guideLikes=o=>o.isShared?Math.max(0,Number(o.likeCount)||0):0;
  const guidePostedAt=o=>Number(o.postedAt||o.createdAt||o.date)||0;
  const guideOrder=(a,b)=>guideLikes(b)-guideLikes(a)||guidePostedAt(b)-guidePostedAt(a)||String(b.id).localeCompare(String(a.id));
  async function applyReaction(o,reaction){
    reactionRevision++;
    reactionRevisions.set(o.id,reactionRevision);
    o.likeCount=Math.max(0,Number(reaction.likeCount)||0);o.likedByMe=!!reaction.likedByMe;
    if(reaction.createdAt)o.postedAt=Number(reaction.createdAt);
    await dbPutObservation(o);renderBook();renderMapPins();
  }
  async function refreshGuideReactions(){
    if(!communitySession?.communityMember||reactionRefreshPending)return;
    const token=communitySession.token,revision=reactionRevision;reactionRefreshPending=true;
    try{
      const response=await serverRequest('/api/v1/observations/reactions/summary');if(communitySession?.token!==token)return;
      const byId=new Map(response.reactions.map(reaction=>[reaction.id,reaction]));
      for(const o of observations){const reaction=byId.get(o.id);if(!reaction||(reactionRevisions.get(o.id)||0)>revision)continue;o.likeCount=Math.max(0,Number(reaction.likeCount)||0);o.likedByMe=!!reaction.likedByMe;o.postedAt=Number(reaction.createdAt)||o.postedAt;await dbPutObservation(o);}
      renderBook();renderMapPins();
    }catch{}finally{reactionRefreshPending=false;}
  }
  function attachObservationLikes(o){
    if(!o.isShared||!o.owner?.accountId||!communitySession?.communityMember)return;
    const frame=$('#modal-root .detail-photo'),root=document.createElement('section'),status=document.createElement('p');
    root.className='observation-likes';frame.append(root);status.className='social-error like-status';status.setAttribute('role','status');status.hidden=true;frame.after(status);
    const token=communitySession.token,own=o.owner.accountId===communitySession.accountId,state={count:guideLikes(o),liked:false,busy:true,available:false,error:''};
    const valid=()=>root.isConnected&&communitySession?.token===token;
    function render(){
      if(!valid())return;
      const label=state.error?'いいねを再取得':`いいね ${state.count}件`+(own?'。自分の投稿にはいいねを付けられません':state.liked?'。タップして取り消し':'。タップしていいね');
      root.innerHTML=`<button type="button" class="like-button" aria-label="${esc(label)}" title="${esc(label)}" aria-pressed="${state.liked}" ${state.busy||(!state.error&&(!state.available||own))?'disabled':''}><span class="like-symbol" aria-hidden="true">${state.error?'!':state.liked?'♥':'♡'}</span><span class="like-count" aria-hidden="true">${state.error?'再取得':state.count}</span></button>`;
      status.textContent=state.error;status.hidden=!state.error;
      $('.like-button',root).onclick=()=>load(state.error?'GET':state.liked?'DELETE':'PUT');
    }
    async function load(method){
      if(!valid()||((method==='PUT'||method==='DELETE')&&(own||state.busy||!state.available)))return;
      state.busy=true;state.error='';render();
      try{const response=await serverRequest('/api/v1/observations/'+encodeURIComponent(o.id)+'/likes',{method});if(!valid())return;const reaction=response.reaction;state.count=Math.max(0,Number(reaction.likeCount)||0);state.liked=!!reaction.likedByMe;state.available=true;await applyReaction(o,reaction);}
      catch(error){state.error=error.message||'いいねを取得できませんでした';}
      finally{state.busy=false;render();}
    }
    render();load('GET');
  }
  function commentThreads(comments){
    const sorted=[...comments].sort((a,b)=>a.createdAt-b.createdAt||a.id.localeCompare(b.id)),ids=new Set(sorted.map(c=>c.id)),children=new Map();
    for(const c of sorted){const list=children.get(c.parentId)||[];list.push(c);children.set(c.parentId,list);}
    const stack=sorted.filter(c=>!ids.has(c.parentId)).reverse().map(c=>[c,0]),result=[],visited=new Set();
    while(stack.length){const[c,depth]=stack.pop();if(visited.has(c.id))continue;visited.add(c.id);result.push([c,depth]);for(const child of [...(children.get(c.id)||[])].reverse())stack.push([child,depth+1]);}
    return result;
  }
  function attachObservationComments(o){
    const root=document.createElement('section');root.className='observation-comments';$('#modal-root .modal-actions').before(root);
    if(!o.isShared||!o.owner?.accountId||!communitySession?.communityMember){root.innerHTML='<h3>コメント</h3><p>コミュニティに共有した投稿には、自分やメンバーがコメントできます。</p>';return;}
    const token=communitySession.token,state={comments:[],draft:'',reply:null,busy:false,error:'',nextBefore:null,editing:null};
    const valid=()=>root.isConnected&&communitySession?.token===token;
    const authorFor=c=>communityMembers.find(m=>m.accountId===c.author?.accountId)||c.author||{};
    const authorName=c=>authorFor(c).displayName||authorFor(c).loginName||'メンバー';
    function render(){
      if(!valid())return;
      const rows=commentThreads(state.comments).map(([c,depth])=>{
        const author=authorFor(c),parent=state.comments.find(x=>x.id===c.parentId),avatar=author.avatarDataUrl;
        const editing=state.editing?.id===c.id,disabled=state.busy||!!state.editing;
        const body=editing?`<label class="form-field">コメントを編集<textarea rows="3" maxlength="1000" data-comment-edit-input ${state.busy?'disabled':''}>${esc(state.editing.draft)}</textarea></label><div class="comment-actions"><button type="button" class="primary-button" data-save-comment-edit ${state.busy?'disabled':''}>保存</button><button type="button" class="quiet-button" data-cancel-comment-edit ${state.busy?'disabled':''}>キャンセル</button></div>`:`<p>${esc(c.deleted?'このコメントは削除されました':c.body)}</p>${c.deleted?'':`<div class="comment-actions"><button type="button" class="quiet-button" data-reply="${esc(c.id)}" ${disabled?'disabled':''}>返信</button>${author.accountId===communitySession.accountId?`<button type="button" class="quiet-button" data-edit-comment="${esc(c.id)}" ${disabled?'disabled':''}>編集</button><button type="button" class="quiet-button danger-link" data-delete-comment="${esc(c.id)}" ${disabled?'disabled':''}>削除</button>`:''}</div>`}`;
        return `<article class="post-comment" style="margin-left:${Math.min(depth,2)*12}px"><div class="comment-author"><button type="button" class="member-avatar" data-profile="${esc(author.accountId)}" aria-label="${esc(authorName(c))}のプロフィール">${avatar?`<img src="${esc(avatar)}" alt="">`:esc(authorName(c).slice(0,1))}</button><div><strong>${esc(authorName(c))}</strong><small>${esc(localDateTime(c.createdAt))}${c.editedAt&&!c.deleted?'（編集済み）':''}</small></div></div>${c.parentId?`<small>返信先：${esc(parent?authorName(parent):'以前のコメント')}</small>`:''}${body}</article>`;
      }).join('');
      root.innerHTML=`<div class="comments-heading"><h3>コメント</h3><button type="button" class="quiet-button" data-refresh-comments ${state.busy?'disabled':''}>更新</button></div>${state.error?`<p class="social-error" role="status">${esc(state.error)}</p>`:''}${state.nextBefore?`<button type="button" class="secondary-button" data-older-comments ${state.busy?'disabled':''}>以前のコメントを読み込む</button>`:''}${rows||`<p>${state.busy?'読み込み中…':'まだコメントはありません。'}</p>`}<form class="comment-form">${state.reply?`<div class="reply-hint">${esc(authorName(state.reply))}への返信<button type="button" class="quiet-button" data-cancel-reply ${state.busy?'disabled':''}>解除</button></div>`:''}<label class="form-field">${state.reply?'返信を書く':'コメントを書く'}<textarea rows="3" maxlength="1000" aria-label="コメント本文" ${state.busy?'disabled':''}>${esc(state.draft)}</textarea></label><small>1000文字まで</small><button type="submit" class="primary-button" ${state.busy?'disabled':''}>${state.busy?'処理中…':'送信'}</button></form>`;
      $('.comment-form textarea',root).oninput=event=>state.draft=event.target.value;
      const editInput=$('[data-comment-edit-input]',root);if(editInput)editInput.oninput=event=>state.editing.draft=event.target.value;
      const composerDisabled=state.busy||!!state.editing;
      $('.comment-form textarea',root).disabled=composerDisabled;
      $('.comment-form [type=submit]',root).disabled=composerDisabled;
      $$('[data-refresh-comments],[data-older-comments],[data-cancel-reply],[data-profile]',root).forEach(button=>button.disabled=composerDisabled);
      $('.comment-form',root).onsubmit=async event=>{
        event.preventDefault();if(state.busy||state.editing||!valid())return;if(!state.draft.trim())return notify('コメントを入力してください');
        state.busy=true;state.error='';render();
        try{const response=await serverRequest(`/api/v1/observations/${encodeURIComponent(o.id)}/comments`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({body:state.draft.trim(),parentId:state.reply?.id||null})});if(!valid())return;state.comments.push(response.comment);state.draft='';state.reply=null;}
        catch(error){state.error=error.message||'コメントを送信できませんでした';}
        finally{state.busy=false;render();}
      };
    }
    async function load(before=null){
      if(state.busy||!valid())return;state.busy=true;state.error='';render();
      try{const response=await serverRequest(`/api/v1/observations/${encodeURIComponent(o.id)}/comments`+(before?'?before='+encodeURIComponent(before):''));if(!valid())return;state.comments=before?[...response.comments,...state.comments].filter((c,i,all)=>all.findIndex(x=>x.id===c.id)===i):response.comments;state.nextBefore=response.nextBefore||null;}
      catch(error){state.error=error.message||'コメントを取得できませんでした';}
      finally{state.busy=false;render();}
    }
    root.onclick=async event=>{
      const button=event.target.closest('button');if(!button||state.busy||!valid())return;
      if(button.hasAttribute('data-cancel-comment-edit')){state.editing=null;state.error='';render();return;}
      if(button.hasAttribute('data-save-comment-edit')){
        if(!state.editing?.draft.trim())return notify('コメントを入力してください');
        const edit={...state.editing};state.busy=true;state.error='';render();
        try{const response=await serverRequest(`/api/v1/observations/${encodeURIComponent(o.id)}/comments/${encodeURIComponent(edit.id)}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({body:edit.draft.trim()})});if(!valid())return;state.comments=state.comments.map(c=>c.id===response.comment.id?response.comment:c);state.editing=null;}
        catch(error){state.error=error.message||'編集内容を保存できませんでした';}
        finally{state.busy=false;render();}return;
      }
      if(state.editing)return;
      if(button.dataset.editComment){const target=state.comments.find(c=>c.id===button.dataset.editComment);if(!target||target.deleted||target.author?.accountId!==communitySession.accountId)return;state.editing={id:target.id,draft:target.body};state.error='';render();$('[data-comment-edit-input]',root).focus();return;}
      if(button.hasAttribute('data-refresh-comments'))return load();
      if(button.hasAttribute('data-older-comments'))return load(state.nextBefore);
      if(button.hasAttribute('data-cancel-reply')){state.reply=null;render();return;}
      if(button.dataset.reply){state.reply=state.comments.find(c=>c.id===button.dataset.reply);render();$('textarea',root).focus();return;}
      if(button.dataset.deleteComment){
        if(!confirm('このコメントを削除しますか？返信は残ります。'))return;state.busy=true;state.error='';render();
        try{const response=await serverRequest(`/api/v1/observations/${encodeURIComponent(o.id)}/comments/${encodeURIComponent(button.dataset.deleteComment)}`,{method:'DELETE'});if(!valid())return;state.comments=state.comments.map(c=>c.id===response.comment.id?response.comment:c);}
        catch(error){state.error=error.message||'コメントを削除できませんでした';}
        finally{state.busy=false;render();}
      }
    };
    render();load();
  }
  async function openSharedProfilePost(id,root,token,beforeOpen=null){
    const response=await serverRequest('/api/v1/observations/'+encodeURIComponent(id)),remote=response.observation;
    if(!remote?.shared)throw new Error('この投稿は共有されていません');
    if(!root.isConnected||communitySession?.token!==token)return;
    const prior=observations.find(o=>o.id===id),cached=prior?observationPhotos(prior):[],photos=[];
    const urls=(remote.photoUrls?.length?remote.photoUrls:[remote.photoUrl]).slice(0,4);
    for(let index=0;index<urls.length;index++){
      let photo=cached[index]||null;
      try{const result=await piFetch(urls[index]);if(result.ok){const blob=await result.blob();if(blob.size&&blob.type.startsWith('image/'))photo=blob;}}catch{}
      photos.push(photo);
    }
    if(!root.isConnected||communitySession?.token!==token)return;
    const tags=[];for(const[groupId,names]of Object.entries(remote.tagValues||{}))for(const name of names||[])tags.push({groupId,name});
    for(const group of remote.tagGroups||[])if(!groups.some(g=>g.id===group.id))groups.push({...group,sharedOwner:remote.owner?.accountId||''});
    const categoryMap={PLANT:'植物',BIRD:'鳥類',INSECT:'昆虫',MAMMAL:'哺乳類',FISH:'魚類',REPTILE:'爬虫類',AMPHIBIAN:'両生類',FUNGI:'菌類',OTHER:'その他'};
    const keepExact=remote.locationPrivate&&prior?.locationPrivate&&remote.owner?.accountId===communitySession.accountId;
    const item={...(prior||{}),id,name:remote.name,photo:photos[0],photos,date:remote.dateMillis,postedAt:remote.createdAt||prior?.postedAt||remote.dateMillis,likeCount:remote.likeCount||0,likedByMe:!!remote.likedByMe,lat:keepExact?prior.lat:remote.latitude,lng:keepExact?prior.lng:remote.longitude,place:keepExact?prior.place:remote.place||'',locationPrivate:!!remote.locationPrivate,prefecture:remote.prefecture||'',comment:remote.comment||'',category:categoryMap[remote.category]||remote.category||'その他',species:remote.species||'',scientificName:remote.speciesScientificName||'',genus:remote.genus||'',family:remote.family||'',order:remote.order||'',tags,mapId:prior?.mapId||'japan',isShared:true,owner:remote.owner,localAccountId:remote.owner?.accountId};
    await dbPutObservation(item);await dbSet('groups',groups);const index=observations.findIndex(o=>o.id===id);if(index<0)observations.push(item);else observations[index]=item;
    if(beforeOpen)await beforeOpen();
    if(!root.isConnected||communitySession?.token!==token)return;
    switchView('book-view');showObservation(id);renderBook();renderMapPins();renderCustomFilters();
  }
  function showMemberProfile(accountId,returnObservationId=null){
    if(!communitySession?.communityMember)return notify('プロフィールを見るにはコミュニティへの参加が必要です');
    if(!/^BD-[A-F0-9]{8,12}$/.test(accountId))return;
    const token=communitySession.token,urls=[];
    showModal('プロフィール','<div id="member-profile-card"></div><div class="modal-actions"><button type="button" id="profile-close" class="secondary-full">閉じる</button></div>');
    const root=$('#member-profile-card'),state={member:communityMembers.find(m=>m.accountId===accountId)||{accountId},posts:[],total:null,busy:false,error:'',hasMore:false,nextOffset:0};
    modalCleanup=()=>urls.forEach(url=>URL.revokeObjectURL(url));
    const valid=()=>root.isConnected&&communitySession?.token===token;
    $('#profile-close').onclick=$('#modal-close').onclick=()=>returnObservationId?showObservation(returnObservationId):closeModal();
    function render(){
      if(!valid())return;const m=state.member;
      root.innerHTML=`<div class="profile-summary"><button type="button" class="member-avatar profile-card-avatar" data-profile="${esc(accountId)}" aria-label="プロフィール">${m.avatarDataUrl?`<img src="${esc(m.avatarDataUrl)}" alt="">`:esc((m.displayName||m.loginName||'?').slice(0,1))}</button><h3>${esc(m.displayName||m.loginName||'読み込み中…')}</h3><p>アカウントID：${esc(accountId)}<br>登録日時：${m.registeredAt?esc(localDateTime(m.registeredAt)):'取得中'}</p><p class="profile-message">${esc(m.message||'ひとことはまだありません')}</p><strong>投稿件数（共有）：${state.total??'取得中'}</strong></div>${state.error?`<p class="social-error" role="status">${esc(state.error)}</p>`:''}<button type="button" class="secondary-button wide" data-refresh-profile ${state.busy?'disabled':''}>投稿一覧を更新</button><div class="profile-posts">${state.posts.map(post=>`<button type="button" class="profile-post" data-profile-post="${esc(post.id)}" ${state.busy?'disabled':''}><span class="profile-post-photo" style="--genre-color:${categoryColor(({PLANT:'植物',BIRD:'鳥類',INSECT:'昆虫',MAMMAL:'哺乳類',FISH:'魚類',REPTILE:'爬虫類',AMPHIBIAN:'両生類',FUNGI:'菌類',OTHER:'その他'})[post.category]||post.category)}"><img alt="" data-profile-photo="${esc(post.id)}"></span><span><strong>${esc(post.name)}</strong></span></button>`).join('')||`<p>${state.busy?'読み込み中…':'共有された投稿はありません。'}</p>`}</div>${state.hasMore?`<button type="button" class="secondary-button wide" data-more-profile ${state.busy?'disabled':''}>さらに読み込む</button>`:''}`;
      for(const post of state.posts){const img=$(`[data-profile-photo="${post.id}"]`,root);if(!img)continue;const cached=observations.find(o=>o.id===post.id);if(cached?.photo){img.src=photoUrl(cached);continue;}piFetch(post.photoUrl).then(async response=>{if(!response.ok)return;const blob=await response.blob();if(!valid()||!img.isConnected||!blob.type.startsWith('image/'))return;const url=URL.createObjectURL(blob);urls.push(url);img.src=url;}).catch(()=>{});}
    }
    async function load(offset=0){
      if(state.busy||!valid())return;state.busy=true;state.error='';render();
      try{const response=await serverRequest(`/api/v1/community/members/${encodeURIComponent(accountId)}/observations?limit=20&offset=${offset}`);if(!valid())return;state.posts=offset?[...state.posts,...response.observations].filter((p,i,all)=>all.findIndex(x=>x.id===p.id)===i):response.observations;state.total=response.total;state.hasMore=response.hasMore;state.nextOffset=response.nextOffset;}
      catch(error){state.error=error.message||'投稿一覧を取得できませんでした';}
      finally{state.busy=false;render();}
    }
    root.onclick=async event=>{
      const button=event.target.closest('button');if(!button||!valid()||state.busy)return;
      if(button.hasAttribute('data-refresh-profile'))return load();if(button.hasAttribute('data-more-profile'))return load(state.nextOffset);
      if(button.dataset.profilePost){state.busy=true;state.error='';render();try{await openSharedProfilePost(button.dataset.profilePost,root,token);}catch(error){state.error=error.message||'投稿を開けませんでした';}finally{state.busy=false;render();}}
    };
    render();
    (async()=>{try{const response=await serverRequest('/api/v1/community/members/'+encodeURIComponent(accountId));if(!valid())return;state.member={...state.member,...response.member};if(response.member.avatarUrl){try{const result=await piFetch(response.member.avatarUrl);if(result.ok){const data=await blobToDataUrl(await result.blob());if(valid())state.member.avatarDataUrl=data;}}catch{}}render();}catch(error){state.error=error.message;render();}if(valid())load();})();
  }
  let avatarLoadVersion=0;
  async function chooseAvatar(e){
    const file=e.target.files?.[0];if(!file)return;
    const version=++avatarLoadVersion,accountId=communitySession?.accountId;
    const url=URL.createObjectURL(file),image=new Image();
    try{
      await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=()=>reject(new Error('この画像を読み込めませんでした'));image.src=url;});
      if(version!==avatarLoadVersion||communitySession?.accountId!==accountId)return;
      openAvatarCrop(image,accountId);
    }catch(error){notify(error.message||'アイコンを読み込めませんでした');}
    finally{URL.revokeObjectURL(url);e.target.value='';}
  }
  function openAvatarCrop(image,accountId){
    showModal('アイコンをトリミング',`<p class="account-note">丸い枠の中がアイコンになります。画像をドラッグするか、下の項目で位置と拡大を調整してください。</p><div class="avatar-crop-preview"><canvas id="avatar-crop-canvas" width="512" height="512" aria-label="アイコンの切り抜きプレビュー"></canvas></div><div class="crop-controls"><label>拡大<input id="avatar-crop-zoom" type="range" min="1" max="4" step=".05" value="1"></label><label>横位置<input id="avatar-crop-x" type="range" min="0" max="100" value="50"></label><label>縦位置<input id="avatar-crop-y" type="range" min="0" max="100" value="50"></label></div><p class="account-note">動かせる範囲がない場合は、拡大すると位置を調整できます。</p><div class="modal-actions"><button type="button" id="avatar-crop-cancel" class="secondary-full">キャンセル</button><button type="button" id="avatar-crop-save" class="primary-button">この範囲で保存</button></div>`);
    const canvas=$('#avatar-crop-canvas'),zoom=$('#avatar-crop-zoom'),x=$('#avatar-crop-x'),y=$('#avatar-crop-y');
    const bounds=()=>cropBounds(image,Number(zoom.value),Number(x.value)/100,Number(y.value)/100,1);
    function render(){
      const b=bounds(),ctx=canvas.getContext('2d');ctx.fillStyle='#3b663b';ctx.fillRect(0,0,512,512);
      ctx.drawImage(image,b.sx,b.sy,b.sw,b.sh,0,0,512,512);
      x.disabled=image.naturalWidth-b.sw<.01;y.disabled=image.naturalHeight-b.sh<.01;
    }
    [zoom,x,y].forEach(input=>input.oninput=render);
    let pointer=null;
    canvas.onpointerdown=event=>{pointer={id:event.pointerId,x:event.clientX,y:event.clientY};canvas.setPointerCapture(event.pointerId);};
    canvas.onpointermove=event=>{
      if(!pointer||pointer.id!==event.pointerId)return;
      const b=bounds(),rect=canvas.getBoundingClientRect(),dx=event.clientX-pointer.x,dy=event.clientY-pointer.y;
      const move=(input,delta,size,overflow)=>{if(overflow>.01)input.value=String(Math.max(0,Math.min(100,Number(input.value)-delta/rect.width*size/overflow*100)));};
      move(x,dx,b.sw,image.naturalWidth-b.sw);move(y,dy,b.sh,image.naturalHeight-b.sh);
      pointer={id:event.pointerId,x:event.clientX,y:event.clientY};render();
    };
    canvas.onpointerup=canvas.onpointercancel=canvas.onlostpointercapture=()=>{pointer=null;};
    $('#avatar-crop-cancel').onclick=closeModal;
    $('#avatar-crop-save').onclick=async()=>{
      if(communitySession?.accountId!==accountId)return notify('アカウントが変更されました。もう一度画像を選んでください。');
      const save=$('#avatar-crop-save');if(save.disabled)return;save.disabled=true;$('#avatar-crop-cancel').disabled=true;$('#modal-close').disabled=true;
      try{
        const compact=await new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('切り抜きに失敗しました')),'image/jpeg',.9));
        const dataUrl=await blobToDataUrl(compact);
        if(communitySession?.accountId!==accountId)return;
        profile.avatar=dataUrl;await dbSet('profile',profile);
        const own=communityMembers.find(member=>member.accountId===accountId);if(own)own.avatarDataUrl=dataUrl;
        closeModal();renderAvatarState();renderCommunity();renderBook();renderMapPins();
        try{
          const account=await uploadAvatar(compact);
          if(communitySession?.accountId!==accountId)return;
          profile.avatarUrl=account.avatarUrl;profile.avatarUpdatedAt=account.avatarUpdatedAt;await dbSet('profile',profile);
          if(communitySession.communityMember)await refreshCommunityMembers();
          renderAvatarState();renderBook();renderMapPins();notify('アイコンを保存しました');
        }catch(error){renderAvatarState();notify(`端末には保存しました。同期は失敗しました：${error.message}`);}
      }catch(error){notify(error.message||'アイコンを保存できませんでした');if(save.isConnected){save.disabled=false;$('#avatar-crop-cancel').disabled=false;$('#modal-close').disabled=false;}}
    };
    render();
  }
  async function saveProfile(){if(!communitySession)return notify('アカウントにログインしてください');const name=$('#profile-name').value.trim(),message=$('#profile-message').value.trim();if(!name||name.length>80)return notify('アカウント名を1〜80文字で入力してください');let account;try{const r=await serverRequest('/api/v1/account/me',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({displayName:name,message})});account=r.account;}catch(e){setServerMessage(`プロフィールを保存できませんでした：${e.message}`);notify('保存できませんでした');return;}profile.name=account.displayName;profile.message=account.message;await dbSet('profile',profile);communitySession.displayName=account.displayName;sessionStorage.setItem('biodiary-session',JSON.stringify(communitySession));let refreshError;if(communitySession.communityMember)await refreshCommunityMembers().catch(e=>{refreshError=e;});renderAccount();setServerMessage(refreshError?`アカウント名は保存しました。コミュニティ一覧の更新に失敗しました：${refreshError.message}`:'アカウント名とプロフィールを保存しました。');notify('プロフィールを保存しました');}
  function blobToDataUrl(blob){return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=reject;reader.readAsDataURL(blob);});}
  async function exportBackup(){try{const out=await Promise.all(observations.map(async o=>{const {_url,...record}=o,photos=await Promise.all(observationPhotos(o).map(photo=>photo?blobToDataUrl(photo):null));return{...record,photo:photos[0],photos};}));const blob=new Blob([JSON.stringify({version:1,observations:out,groups,maps:mapTabs,profile},null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=`biodiary-backup-${new Date().toISOString().slice(0,10)}.json`;a.click();URL.revokeObjectURL(a.href);notify('バックアップを書き出しました');}catch(e){console.error(e);notify('書き出しに失敗しました');}}
  async function importBackup(e){const file=e.target.files?.[0];if(!file)return;try{const data=JSON.parse(await file.text());if(!Array.isArray(data.observations))throw new Error('invalid');for(const o of data.observations){const photos=await Promise.all((o.photos?.length?o.photos:[o.photo]).slice(0,4).map(async photo=>photo?await(await fetch(photo)).blob():null));const copy={...o,photo:photos[0],photos,localAccountId:communitySession.accountId};await dbPutObservation(copy);}await loadAccountObservations(communitySession.accountId);if(data.groups){groups=data.groups;await dbSet('groups',groups);}if(data.maps){mapTabs=data.maps;await dbSet('maps',mapTabs);}renderMapPins();renderBook();renderAccount();notify('バックアップを読み込みました');}catch(err){console.error(err);notify('バックアップを読み込めませんでした');}finally{e.target.value='';}}
  async function start(){init();try{
    const savedBase=localStorage.getItem('biodiary-base-url')||'';
    $('#server-url').value=communitySession?.baseUrl||(isLegacyPiUrl(savedBase)?'':savedBase)||DEFAULT_SERVER;
    db=await openDB();observations=await dbGetAll();groups=(await dbGet('groups')||DEFAULT_GROUPS.map(g=>structuredClone(g))).filter(g=>g.id!=='time-of-day');
    mapTabs=await dbGet('maps')||mapTabs;communityMembers=await dbGet('community-members')||[];
    profile=await dbGet('profile')||{name:'自然観察者',avatar:'',id:`BD-${newId().slice(0,8).toUpperCase()}`,message:'',registeredAt:Date.now()};
    await dbSet('profile',profile);await dbSet('groups',groups);
    renderSeasons();renderBuiltInFilters();renderCustomFilters();renderSharingFilters();renderAccount();renderCommunitySession();renderCommunity();renderBook();await renderSyncStatus();
    mainMap=L.map('map',{zoomControl:false,scrollWheelZoom:true,tap:true}).setView([36.2,138.25],5.15);
    const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors'}).addTo(mainMap);
    tiles.on('tileload',()=>$('#map-loading').classList.add('hidden'));
    tiles.on('tileerror',()=>{const status=$('#map-loading');status.textContent='地図タイルを読み込めません。通信環境を確認して再試行してください。';status.classList.remove('hidden');});
    pinLayer=L.layerGroup().addTo(mainMap);renderMapPins();
    const resizeMap=()=>requestAnimationFrame(()=>mainMap.invalidateSize({pan:false}));
    window.addEventListener('resize',resizeMap);window.addEventListener('pageshow',resizeMap);
    window.addEventListener('orientationchange',()=>setTimeout(resizeMap,250));
    window.visualViewport?.addEventListener('resize',resizeMap);
    if('ResizeObserver'in window)new ResizeObserver(resizeMap).observe($('#map-wrap'));
    document.body.classList.toggle('account-required',!communitySession);
    if(!communitySession)switchView('account-view');
    else {try{const response=await serverRequest('/api/v1/account/me');communitySession={...communitySession,displayName:response.account.displayName,communityMember:!!response.account.communityMember};sessionStorage.setItem('biodiary-session',JSON.stringify(communitySession));renderCommunitySession();await applyServerProfile(response.account);if(communitySession.communityMember)await refreshCommunityMembers();maybeShowOnboardingGuide(response.account);}catch(error){setServerMessage('アカウント情報を更新できませんでした。再ログインが必要な場合があります。');}}
    resizeMap();
  }catch(error){console.error(error);const status=$('#map-loading');if(status){status.textContent=`地図を初期化できませんでした：${error?.message||'原因不明'}`;status.classList.remove('hidden');}notify('画面の初期化に失敗しました。');}}
  start();
  if('serviceWorker'in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(console.warn));
})();
