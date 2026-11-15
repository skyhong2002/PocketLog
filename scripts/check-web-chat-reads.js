/* Browser-only controlled read-race acceptance. Open the disposable localhost:18751
 * fixture, sign in with its synthetic owner, then explicitly set
 * window.POCKETLOG_SYNTHETIC_CHAT_CHECK = true before evaluating this file.
 * It makes no conversation writes or provider calls; reload afterwards. */
(async()=>{
 if(!window.POCKETLOG_SYNTHETIC_CHAT_CHECK || !["localhost","127.0.0.1"].includes(location.hostname) || location.port!=="18751")throw Error("Explicit disposable loopback fixture required");
 const assert=(v,m)=>{if(!v)throw Error(m);};
 const pause=()=>new Promise(r=>setTimeout(r,30));
 const until=async f=>{for(let i=0;i<120;i++){if(f())return;await pause();}throw Error('Wait timed out');};
 const original=window.fetch.bind(window),json=(v,status=200)=>new Response(JSON.stringify(v),{status,headers:{'Content-Type':'application/json'}});
 const data=(id,older)=>({turns:[{question:`合成 ${id} ${older?'早期':'目前'}問題`,answer:`合成 ${id} ${older?'早期':'目前'}回答`}],next_cursor:id==='A'&&!older?{before_ms:1,before_id:'synthetic-older'}:null});
 try {
 window.chatHold=null;window.chatHeld=null;window.chatAskCalls=0;
 window.fetch=(url,options)=>{const path=String(url);if(path==='/v1/ask'){window.chatAskCalls++;throw Error('Unexpected provider call');}
  if(path==='/v1/conversations')return Promise.resolve(json({conversations:[{id:'synthetic-A',title:'合成對話 A'},{id:'synthetic-B',title:'合成對話 B'}]}));
  if(path.startsWith('/v1/conversations/synthetic-')){const id=path.includes('synthetic-A')?'A':'B',older=path.includes('?');
   if(window.chatHold?.(id,older)){window.chatHold=null;return new Promise(resolve=>{window.chatHeld=status=>{window.chatHeld=null;resolve(json(status===200?data(id,older):{error:'synthetic delayed failure'},status));};});}
   return Promise.resolve(json(data(id,older)));}
  return original(url,options);
 };
 const nav=async name=>{document.querySelector(`button[aria-label="${name}"]`).click();await until(()=>name==='Chat'?!!document.querySelector('[data-key=chat-history]'):document.querySelector('h1')?.textContent==='時間軸');};
 const select=id=>{const h=document.querySelector('[data-key=chat-history]');h.value='synthetic-'+id;h.dispatchEvent(new Event('change'));};
 const text=()=>document.querySelector('.transcript')?.textContent||'';
 const loaded=async id=>{await until(()=>text().includes(`合成 ${id} 目前回答`));};
 const button=t=>[...document.querySelectorAll('button')].find(x=>x.textContent===t);
 await nav('Chat');select('B');await loaded('B');
 window.chatHold=(id,older)=>id==='A'&&!older;select('A');await until(()=>!!window.chatHeld);
 assert(text().includes('正在讀取所選對話'),'loading state');assert(document.querySelector('.transcript').getAttribute('aria-busy')==='true','busy');assert(button('傳送這次提問').disabled&&button('刪除此對話').disabled,'loading actions blocked');
 const releaseA=window.chatHeld;select('B');await loaded('B');releaseA(200);await pause();assert(!text().includes('合成 A'),'late A replaced B');
 window.chatHold=(id,older)=>id==='A'&&!older;select('A');await until(()=>!!window.chatHeld);const staleError=window.chatHeld;select('B');await loaded('B');document.querySelector('#notice').hidden=true;staleError(503);await pause();assert(document.querySelector('#notice').hidden,'stale error surfaced');
 window.chatHold=(id,older)=>id==='A'&&!older;select('A');await until(()=>!!window.chatHeld);const freshRead=window.chatHeld;button('開啟新對話').click();await until(()=>text().includes('新的對話'));freshRead(200);await pause();assert(!text().includes('合成 A'),'new chat overwritten');
 select('B');await loaded('B');window.chatHold=(id,older)=>id==='A'&&!older;select('A');await until(()=>!!window.chatHeld);const offpage=window.chatHeld;await nav('時間軸');offpage(200);await pause();assert(document.querySelector('h1').textContent==='時間軸','offpage render');await nav('Chat');await loaded('B');assert(!text().includes('合成 A'),'offpage shared state changed');
 select('A');await loaded('A');window.chatHold=(id,older)=>id==='A'&&older;button('載入更早的問答').click();await until(()=>!!window.chatHeld);const oldPage=window.chatHeld;select('B');await loaded('B');oldPage(200);await pause();assert(!text().includes('合成 A'),'older A prepended into B');
 window.chatHold=(id,older)=>id==='A'&&!older;select('A');await until(()=>!!window.chatHeld);window.chatHeld(503);await until(()=>!document.querySelector('#notice').hidden);assert(!button('傳送這次提問').disabled,'latest failure left send blocked');assert(document.querySelector('[data-key=chat-history]').value==='synthetic-B','latest failure picker');assert(text().includes('合成 B'),'latest failure lost current chat');
 select('A');await loaded('A');button('載入更早的問答').click();await until(()=>text().includes('合成 A 早期回答'));assert(!button('載入更早的問答'),'cursor completion');assert(text().split('合成 A 早期回答').length===2,'duplicate older answer');
 assert(window.chatAskCalls===0,'implicit ask');assert(document.documentElement.scrollWidth<=innerWidth,'overflow');
 return {passed:true,width:innerWidth,checks:'selection race,stale error,new chat,navigation,pagination race,latest failure,retry,pagination success,blocked writes,no provider'};
 } finally {window.fetch=original;delete window.chatHold;delete window.chatHeld;delete window.chatAskCalls;delete window.POCKETLOG_SYNTHETIC_CHAT_CHECK;}
})()
