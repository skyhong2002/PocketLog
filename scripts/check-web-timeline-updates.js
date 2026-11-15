/* Opt-in, loopback-only browser acceptance with controlled scheduler, visibility,
 * online state and timeline reads. No service write or provider call. */
(async()=>{
 if(!window.POCKETLOG_SYNTHETIC_UPDATES_CHECK||!['localhost','127.0.0.1'].includes(location.hostname)||location.port!=='18751')throw Error('Disposable synthetic fixture required');
 const assert=(v,m)=>{if(!v)throw Error(m);};
 const pause=()=>new Promise(r=>setTimeout(r,30));
 const until=async f=>{for(let i=0;i<150;i++){if(f())return;await pause();}throw Error('Wait timed out');};
 const original=window.fetch.bind(window),interval=window.setInterval,clear=window.clearInterval,RealDate=window.Date;
 const visibility=Object.getOwnPropertyDescriptor(document,'visibilityState'),online=Object.getOwnPropertyDescriptor(navigator,'onLine');
 let visible='visible',connected=true,version=0,readCount=0,hold=false,release=null,failure=false,next=-100000;
 const watches=[],base=new Map(),json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
 const button=t=>[...document.querySelectorAll('button')].find(n=>n.textContent===t);
 const update=()=>document.querySelector('[data-key=timeline-load-updates]');
 const shown=()=>update()&&!update().closest('.callout').hidden;
 const watch=()=>watches.filter(w=>w.active).at(-1);
 const nav=async label=>{document.querySelector(`button[aria-label="${label}"]`).click();await until(()=>label==='時間軸'?!!update():document.querySelector('h1')?.textContent===label);};
 const tick=async()=>{assert(watch(),'current watcher missing');await watch().fn();};
 const restore=(object,name,value)=>{if(value)Object.defineProperty(object,name,value);else delete object[name];};
 try{
  Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>visible});
  Object.defineProperty(navigator,'onLine',{configurable:true,get:()=>connected});
  window.setInterval=function(fn,ms,...args){if(ms===120000){const w={fn,id:next--,active:true,ms};watches.push(w);return w.id;}return interval.call(window,fn,ms,...args);};
  window.clearInterval=function(id){const w=watches.find(w=>w.id===id);if(w)w.active=false;else clear.call(window,id);};
  window.fetch=async(url,opt)=>{
   const path=String(url);
   if(path==='/session'&&opt?.method==='DELETE')return new Response(null,{status:204});
   if(!path.startsWith('/v1/timeline?'))return original(url,opt);
   readCount++;
   const key=new URL(path,location.origin).searchParams.get('from_ms');
   if(!base.has(key)){const r=await original(url,opt);if(!r.ok)return r;base.set(key,await r.json());}
   const data=structuredClone(base.get(key));
   if(version&&data.cards.length)data.cards[0].title='合成即時更新 '+version;
   if(hold){hold=false;return new Promise(resolve=>{release=(status=200)=>resolve(status===200?json(data):json({error:'synthetic failure'},status));});}
   return failure?json({error:'synthetic failure'},503):json(data);
  };
  await nav('時間軸');document.querySelector('[data-key=view-grid]').click();await until(()=>!!document.querySelector('.timeline-window')&&!!update());assert(watch().ms===120000,'cadence');assert(!shown(),'unchanged initial banner');
  let n=readCount;await tick();assert(readCount===n+1&&!shown(),'unchanged check');
  for(const state of ['hidden','offline','editing','modal']){
   if(state==='hidden')visible='hidden';if(state==='offline')connected=false;
   if(state==='editing')document.querySelector('[data-key=day-date]').focus();
   if(state==='modal'){document.querySelector('[data-key=chip-edit]').click();await until(()=>document.querySelector('#modal').open);}
   n=readCount;await tick();assert(readCount===n,'read during '+state);
   visible='visible';connected=true;document.activeElement.blur();
   if(state==='modal'){document.querySelector('#modal .close').click();await until(()=>!document.querySelector('#modal').open);}
  }
  // A held check owns one request; a second tick cannot overlap it.
  hold=true;release=null;n=readCount;const first=tick();await until(()=>!!release);await tick();assert(readCount===n+1,'overlapping checks');release();await first;
  version=1;const active=document.querySelector('[data-key=chip-null]');active.focus();const focus=document.activeElement;
  const viewport=document.querySelector('.timeline-window');if(viewport)viewport.scrollTop=400;const scroll=viewport?.scrollTop;
  await tick();assert(shown()&&document.body.textContent.includes('有新的活動或分類更新'),'update missing');assert(document.activeElement===focus,'check stole focus');if(viewport)assert(viewport.scrollTop===scroll,'check moved timeline');assert(!document.querySelector('.timeline-panel').textContent.includes('合成即時更新'),'check silently replaced snapshot');
  update().click();await until(()=>!!update()&&!shown()&&document.querySelector('.timeline-panel')?.textContent.includes('合成即時更新 1'));assert(document.activeElement.dataset.key==='timeline-content','explicit reload focus');if(viewport)assert(document.querySelector('.timeline-window').scrollTop===scroll,'explicit reload moved timeline');
  // Explicit week reload retains both time and horizontal day position too.
  document.querySelector('[data-key=mode-week]').click();await until(()=>!!document.querySelector('.timeline-panel .scroll')&&!!update());
  let week=document.querySelector('.timeline-panel .scroll');week.scrollTop=600;week.scrollLeft=300;const weekPosition=[week.scrollTop,week.scrollLeft];version=2;document.activeElement.blur();await tick();assert(shown(),'week update missing');update().click();await until(()=>!!update()&&!shown()&&!!document.querySelector('.timeline-panel .scroll'));await tick();assert(!shown(),'week reload retained old baseline');week=document.querySelector('.timeline-panel .scroll');assert(week.scrollTop===weekPosition[0]&&week.scrollLeft===weekPosition[1],'explicit week reload moved viewport');
  document.querySelector('[data-key=mode-day]').click();await until(()=>!!document.querySelector('.timeline-window')&&!!update());
  failure=true;await tick();assert(shown()&&document.body.textContent.includes('暫時無法檢查'),'failure guidance');assert(document.querySelector('.timeline-panel').textContent.includes('合成即時更新 2'),'failure lost snapshot');failure=false;await tick();assert(!shown(),'recovery failed');
  // Old completions cannot repopulate another page, and navigation clears timers.
  hold=true;release=null;const pending=tick();await until(()=>!!release);const stale=watch();await nav('每日回顧');assert(!stale.active,'navigation did not clear watcher');release();await pending;assert(!update(),'late check changed other page');
  await nav('時間軸');const dateNow=RealDate.now();window.Date=class extends RealDate{constructor(...args){super(...(args.length?args:[dateNow+86400000]));}static now(){return dateNow+86400000;}};
  n=readCount;await tick();assert(readCount===n&&shown()&&document.body.textContent.includes('新的一天已開始'),'rollover');window.Date=RealDate;
  await nav('時間軸');hold=true;release=null;const logoutPending=tick();await until(()=>!!release);const logoutWatch=watch();await nav('設定');await until(()=>!!button('登出'));button('登出').click();await until(()=>document.body.textContent.includes('已登出'));assert(!logoutWatch.active,'logout did not clear watcher');release(401);await logoutPending;assert(document.body.textContent.includes('已登出')&&!update(),'stale401 changed logout');
  assert(document.documentElement.scrollWidth<=innerWidth,'overflow');
  return {passed:true,width:innerWidth,checks:'120s controlled cadence,unchanged,hidden/offline/edit/modal skips,no overlap,change preserves focus/scroll/snapshot,explicit reload,failure/recovery,stale navigation,rollover,logout401'};
 }finally{window.fetch=original;for(const w of watches)w.active=false;window.setInterval=interval;window.clearInterval=clear;window.Date=RealDate;restore(document,'visibilityState',visibility);restore(navigator,'onLine',online);delete window.POCKETLOG_SYNTHETIC_UPDATES_CHECK;}
})()
