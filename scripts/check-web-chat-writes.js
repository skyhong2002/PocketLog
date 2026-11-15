/* Browser-only send/delete ownership acceptance. Explicit synthetic opt-in and
 * loopback fixture required. ALL ask/delete responses are controlled locally;
 * no provider or conversation write reaches the server. Reload afterwards. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_CHAT_CHECK || !['localhost','127.0.0.1'].includes(location.hostname) || location.port !== '18751') throw Error('Disposable synthetic fixture required');
  const assert = (v,m) => { if (!v) throw Error(m); };
  const pause = () => new Promise(r => setTimeout(r,30));
  const until = async f => { for(let i=0;i<150;i++){if(f())return;await pause();}throw Error('Wait timed out'); };
  const original = window.fetch.bind(window);
  const json = (v,status=200) => new Response(JSON.stringify(v),{status,headers:{'Content-Type':'application/json'}});
  let asks=[], held=[], deletes=[], deleted=new Set(), holdRead=false, releaseRead=null;
  const button = t => [...document.querySelectorAll('button')].find(n=>n.textContent===t);
  const transcript = () => document.querySelector('.transcript')?.textContent||'';
  const draft = text => {const q=document.querySelector('#chat-question');q.value=text;q.dispatchEvent(new Event('input'));};
  const nav = async label => {document.querySelector(`button[aria-label="${label}"]`).click();await until(()=>label==='Chat'?!!document.querySelector('#chat-question'):document.querySelector('h1')?.textContent==='時間軸');};
  const fresh = async () => {button('開啟新對話').click();await until(()=>transcript().includes('新的對話'));};
  const select = async id => {const h=document.querySelector('[data-key=chat-history]');h.value=id;h.dispatchEvent(new Event('change'));await until(()=>transcript().includes(id+' 回答'));};
  const send = async text => {draft(text);document.querySelector('.composer input[type=checkbox]').checked=true;const n=asks.length;button('傳送這次提問').click();await until(()=>asks.length===n+1);return held.at(-1);};
  const remove = async () => {const n=deletes.length;button('刪除此對話').click();await until(()=>document.querySelector('#confirm').open);button('刪除對話').click();await until(()=>deletes.length===n+1);return deletes.at(-1);};
  try {
    window.fetch = (url,options) => {
      const path=String(url);
      if(path==='/v1/conversations')return Promise.resolve(json({conversations:['synthetic-A','synthetic-B'].filter(id=>!deleted.has(id)).map(id=>({id,title:id}))}));
      if(path.startsWith('/v1/conversations/')){
        const id=path.split('/').at(-1);
        if(options?.method==='DELETE')return new Promise(resolve=>deletes.push(()=>{deleted.add(id);resolve(new Response(null,{status:204}));}));
        const thread={turns:[{question:id+' 問題',answer:id+' 回答'}],next_cursor:null};
        if(holdRead){holdRead=false;return new Promise(resolve=>{releaseRead=()=>resolve(json(thread));});}
        return Promise.resolve(json(thread));
      }
      if(path==='/session' && options?.method==='DELETE')return Promise.resolve(new Response(null,{status:204}));
      if(path==='/v1/ask') {asks.push(JSON.parse(options.body));return new Promise(resolve=>held.push((status=200,stored=false)=>resolve(json(status===200?{answer:'合成延遲回答 '+asks.length,stored}:{error:'synthetic failure'},status))));}
      return original(url,options);
    };
    await nav('Chat');await fresh();
    // Late success cannot enter a new thread, even after another render.
    let release=await send('舊問題成功');await fresh();draft('新草稿成功');assert(button('傳送這次提問').disabled,'old pending request permits another send');release();await until(()=>!button('傳送這次提問').disabled);
    document.querySelector('[data-key=chat-scope-week]').click();await until(()=>document.querySelector('[data-key=chat-scope-week]')?.getAttribute('aria-pressed')==='true');
    assert(!transcript().includes('合成延遲回答'),'stale success entered new chat');assert(document.querySelector('#chat-question').value==='新草稿成功','new draft lost');
    // Late failure cannot remove B's transcript or replace B's draft.
    release=await send('舊問題失敗');await select('synthetic-B');draft('B 的草稿');release(503);await until(()=>!button('傳送這次提問').disabled);assert(transcript().includes('synthetic-B 回答')&&!transcript().includes('沒有完成'),'stale failure changed B');assert(document.querySelector('#chat-question').value==='B 的草稿','B draft overwritten');
    // Same-conversation scope and navigation keep the pending operation.
    await fresh();release=await send('同對話等待');document.querySelector('[data-key=chat-scope-day]').click();await until(()=>!!document.querySelector('.pending'));assert(button('傳送這次提問').disabled,'scope reset waiting');
    await nav('時間軸');await nav('Chat');assert(button('傳送這次提問').disabled&&!!document.querySelector('.pending'),'navigation reset waiting');draft('等待中寫的草稿');release();await until(()=>transcript().includes('合成延遲回答'));assert(document.querySelector('#chat-question').value==='等待中寫的草稿','pending draft lost');assert(transcript().split('合成延遲回答').length===2,'answer repeated');
    // A stored completion rebuilds history without discarding the new draft/focus.
    await fresh();release=await send('已保存回答的問題');draft('已保存後保留草稿');document.querySelector('#chat-question').focus();release(200,true);await until(()=>!button('傳送這次提問').disabled&&transcript().includes('合成延遲回答'));assert(document.querySelector('#chat-question').value==='已保存後保留草稿','stored refresh lost draft');assert(document.activeElement.id==='chat-question','stored refresh lost composer focus');
    // Returning to the active thread while another history read waits clears loading.
    await select('synthetic-A');release=await send('A 等待時切換再返回');holdRead=true;
    let picker=document.querySelector('[data-key=chat-history]');picker.value='synthetic-B';picker.dispatchEvent(new Event('change'));await until(()=>!!releaseRead);
    picker.value='synthetic-A';picker.dispatchEvent(new Event('change'));await until(()=>!!document.querySelector('.pending'));releaseRead();release();await until(()=>!button('傳送這次提問').disabled);assert(transcript().includes('synthetic-A 回答')&&!transcript().includes('正在讀取'),'return-to-active stuck loading');
    // Retry retains request identity; changed question/scope gets a new one.
    await fresh();release=await send('重試原問題');const first=asks.at(-1);release(503);await until(()=>transcript().includes('沒有完成'));assert(document.querySelector('#chat-question').value==='重試原問題','failed question not restored');
    release=await send('重試原問題');assert(asks.at(-1).request_id===first.request_id,'retry ID changed');release(503);await until(()=>transcript().includes('沒有完成'));
    release=await send('修改後問題');assert(asks.at(-1).request_id!==first.request_id,'changed question reused ID');const changed=asks.at(-1);release(503);await until(()=>transcript().includes('沒有完成'));
    document.querySelector('[data-key=chat-scope-week]').click();await until(()=>document.querySelector('[data-key=chat-scope-week]')?.getAttribute('aria-pressed')==='true');release=await send('修改後問題');assert(asks.at(-1).request_id!==changed.request_id,'changed scope reused ID');draft('失敗前新增草稿');release(503);await until(()=>transcript().includes('沒有完成'));assert(document.querySelector('#chat-question').value==='失敗前新增草稿','current failure replaced newer draft');
    // Delayed DELETE A must not reset B; deletion invalidates a pending ask.
    await select('synthetic-A');const deletedA=await remove();await select('synthetic-B');draft('刪除 A 時的 B 草稿');deletedA();await pause();assert(transcript().includes('synthetic-B 回答')&&document.querySelector('#chat-question').value==='刪除 A 時的 B 草稿','delete A reset B');
    release=await send('B 刪除前的問題');const deletedB=await remove();deletedB();await until(()=>transcript().includes('新的對話'));draft('刪除後的新草稿');release();await until(()=>!button('傳送這次提問').disabled);assert(!transcript().includes('合成延遲回答')&&document.querySelector('#chat-question').value==='刪除後的新草稿','deleted chat resurrected');
    assert(asks.every(a=>a.consent===true&&a.provider_id&&a.request_id),'consent or identity missing');assert(!document.querySelector('.composer input[type=checkbox]').checked,'consent remained checked');assert(document.documentElement.scrollWidth<=innerWidth,'overflow');
    // Logout clears private state; a late answer or stale 401 cannot reopen it.
    release=await send('登出前的合成問題');await nav('時間軸');document.querySelector('[aria-label="設定"]').click();await until(()=>!!button('登出'));button('登出').click();await until(()=>!document.querySelector('#chat-question')&&document.body.textContent.includes('Owner 金鑰'));release(401);await pause();assert(!document.querySelector('.transcript')&&!document.body.textContent.includes('登出前的合成問題'),'private state returned after logout');assert(document.body.textContent.includes('已登出'),'stale auth response replaced signed-out state');
    return {passed:true,width:innerWidth,controlledAsks:asks.length,checks:'stale success/failure,new/B drafts,scope/navigation busy,one answer,retry IDs,changed scope/question,new draft,delete ownership,no resurrection,explicit consent'};
  } finally {window.fetch=original;delete window.POCKETLOG_SYNTHETIC_CHAT_CHECK;}
})()
