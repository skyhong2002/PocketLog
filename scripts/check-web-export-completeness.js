/* Browser-only, opt-in synthetic fixture acceptance. Controls read completeness
 * flags and captures the export Blob before a file download. No service writes,
 * provider calls, clipboard changes or actual download. Reload afterwards. */
(async()=>{
 if(!window.POCKETLOG_SYNTHETIC_EXPORT_CHECK||!['localhost','127.0.0.1'].includes(location.hostname)||location.port!=='18751')throw Error('Disposable synthetic fixture required');
 const assert=(v,m)=>{if(!v)throw Error(m);};
 const pause=()=>new Promise(r=>setTimeout(r,30));
 const until=async f=>{for(let i=0;i<150;i++){if(f())return;await pause();}throw Error('Wait timed out');};
 const original=window.fetch.bind(window),create=URL.createObjectURL,click=HTMLAnchorElement.prototype.click;
 const response=v=>new Response(JSON.stringify(v),{headers:{'Content-Type':'application/json'}});
 let cards=false,reviews=false,blobPromise=null;
 const button=t=>[...document.querySelectorAll('button')].find(n=>n.textContent===t);
 const nav=async label=>{document.querySelector(`button[aria-label="${label}"]`).click();await until(()=>!!button(label==='時間軸'?'匯出 JSON':label==='每日回顧'||label==='每週回顧'?'匯出這個範圍的 JSON':label==='Flow'?'開始專注':'選取 JSONL 檔並預覽…'));};
 try{
  window.fetch=async(url,opt)=>{
   const path=String(url);
   if(path.startsWith('/v1/reviews?'))return response({records:[],truncated:reviews});
   const r=await original(url,opt);
   if(path.startsWith('/v1/timeline?')&&r.ok){const data=await r.json();data.truncated=cards;return response(data);}
   return r;
  };
  URL.createObjectURL=function(blob){blobPromise=blob.text().then(JSON.parse);return create.call(URL,blob);};
  HTMLAnchorElement.prototype.click=function(){if(!this.download)return click.call(this);};
  for(const [cardLimit,reviewLimit] of [[false,false],[true,false],[false,true],[true,true]]){
   cards=cardLimit;reviews=reviewLimit;await nav('時間軸');
   assert(document.body.textContent.includes('回顧紀錄已達單次讀取上限')===reviews,'timeline review warning');
   assert(document.body.textContent.includes('下面的統計只涵蓋已載入的活動卡')===cards,'timeline activity warning');
   blobPromise=null;button('匯出 JSON').click();await until(()=>document.querySelector('#confirm').open);
   assert(document.querySelector('#confirm').textContent.includes('這份匯出不完整')===(cards||reviews),'confirmation completeness');
   button('下載 JSON').click();await until(()=>!!blobPromise);const snapshot=await blobPromise;
   assert(snapshot.version===1&&Array.isArray(snapshot.days)&&Array.isArray(snapshot.reviews),'existing export shape');
   assert(snapshot.truncated===(cards||reviews)&&snapshot.reviews_truncated===reviews,'export completeness flags');
   assert(snapshot.days.every(d=>d.truncated===cards),'day flags lost');
  }
  // Review-only truncation follows every consumer; complete state clears it.
  cards=false;reviews=true;
  for(const label of ['每日回顧','每週回顧','Flow','Agents']){
   await nav(label);assert(document.body.textContent.includes('回顧紀錄已達單次讀取上限'),label+' lost completeness');
   if(label==='每日回顧'||label==='每週回顧'){
    blobPromise=null;button('匯出這個範圍的 JSON').click();await until(()=>document.querySelector('#confirm').open);assert(document.querySelector('#confirm').textContent.includes('這份匯出不完整'),'review-page confirmation');button('下載 JSON').click();await until(()=>!!blobPromise);assert((await blobPromise).reviews_truncated,'review-page export lost marker');
   }
  }
  reviews=false;await nav('時間軸');assert(!document.body.textContent.includes('回顧紀錄已達單次讀取上限'),'warning persisted after complete read');
  blobPromise=null;button('匯出 JSON').click();await until(()=>document.querySelector('#confirm').open);button('取消').click();await until(()=>!document.querySelector('#confirm').open);assert(blobPromise===null,'cancel exported');
  assert(document.documentElement.scrollWidth<=innerWidth,'overflow');
  return {passed:true,width:innerWidth,checks:'complete/card/review/both,confirmation,Blob shape/flags,day markers,Daily/Weekly/Flow/Agents,recovery,cancel,no download'};
 }finally{window.fetch=original;URL.createObjectURL=create;HTMLAnchorElement.prototype.click=click;delete window.POCKETLOG_SYNTHETIC_EXPORT_CHECK;}
})()
