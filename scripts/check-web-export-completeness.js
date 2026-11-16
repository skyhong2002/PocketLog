/* Browser-only, opt-in synthetic fixture acceptance. Controls read completeness
 * flags and captures the export Blob before a file download. No service writes,
 * provider calls, clipboard changes or actual download. Reload afterwards.
 * Export lives in 設定 › 資料; the timeline still warns about truncated days. */
(async()=>{
 if(!window.POCKETLOG_SYNTHETIC_EXPORT_CHECK||!['localhost','127.0.0.1'].includes(location.hostname)||location.port!=='18751')throw Error('Disposable synthetic fixture required');
 const assert=(v,m)=>{if(!v)throw Error(m);};
 const pause=()=>new Promise(r=>setTimeout(r,30));
 const until=async f=>{for(let i=0;i<150;i++){if(f())return;await pause();}throw Error('Wait timed out');};
 const original=window.fetch.bind(window),create=URL.createObjectURL,click=HTMLAnchorElement.prototype.click;
 const response=v=>new Response(JSON.stringify(v),{headers:{'Content-Type':'application/json'}});
 let cards=false,reviews=false,blobPromise=null;
 const button=t=>[...document.querySelectorAll('button')].find(n=>n.textContent===t);
 const nav=async label=>{document.querySelector(`button[aria-label="${label}"]`).click();await until(()=>label==='設定'?!!button('匯出 JSON'):!!document.querySelector('.page-timeline'));};
 const exportRange=async days=>{const range=document.querySelector('[data-key=settings-export-range]');range.value=String(days);blobPromise=null;button('匯出 JSON').click();await until(()=>document.querySelector('#confirm').open);};
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
   assert(document.body.textContent.includes('畫面只顯示了一部分')===cards,'timeline activity warning');
   await nav('設定');
   for(const days of [1,7]){
    await exportRange(days);
    assert(document.querySelector('#confirm').textContent.includes('這份匯出不完整')===(cards||reviews),'confirmation completeness');
    button('下載 JSON').click();await until(()=>!!blobPromise);const snapshot=await blobPromise;
    assert(snapshot.version===1&&Array.isArray(snapshot.days)&&Array.isArray(snapshot.reviews),'existing export shape');
    assert(snapshot.days.length===days,'export range');
    assert(snapshot.truncated===(cards||reviews)&&snapshot.reviews_truncated===reviews,'export completeness flags');
    assert(snapshot.days.every(d=>d.truncated===cards),'day flags lost');
   }
  }
  cards=false;reviews=false;await nav('時間軸');assert(!document.body.textContent.includes('畫面只顯示了一部分'),'warning persisted after complete read');
  await nav('設定');await exportRange(7);button('取消').click();await until(()=>!document.querySelector('#confirm').open);assert(blobPromise===null,'cancel exported');
  assert(document.documentElement.scrollWidth<=innerWidth,'overflow');
  return {passed:true,width:innerWidth,checks:'complete/card/review/both,1 and 7 day ranges,confirmation,Blob shape/flags,day markers,timeline warning/recovery,cancel,no download'};
 }finally{window.fetch=original;URL.createObjectURL=create;HTMLAnchorElement.prototype.click=click;delete window.POCKETLOG_SYNTHETIC_EXPORT_CHECK;}
})()
