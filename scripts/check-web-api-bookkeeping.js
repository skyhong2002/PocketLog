/* Disposable fixture regression: both destructive successes are synthetic local
 * responses. No journal deletion or logout is sent to the service by this script. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_API_BOOKKEEPING || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 150; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), held = [];
  const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
  const button = text => [...document.querySelectorAll('button')].find(n => n.textContent === text);
  const marker = '合成：刪除後不得保留的對話';
  let deleted = false;
  const settings = async () => { document.querySelector('button[aria-label="設定"]').click(); await until(() => !!button('登出')); };
  const chat = async () => { document.querySelector('button[aria-label="Chat"]').click(); await until(() => !!document.querySelector('[data-key=chat-history]')); };
  try {
    window.fetch = (url, options) => {
      const path = String(url);
      if (path === '/v1/conversations') return Promise.resolve(json({ conversations: deleted ? [] : [{ id: 'synthetic-bookkeeping', title: '合成回應測試' }] }));
      if (path === '/v1/conversations/synthetic-bookkeeping') return Promise.resolve(json({ turns: [{ question: marker, answer: '合成回答' }], next_cursor: null }));
      if ((path === '/v1/journal' || path === '/session') && options?.method === 'DELETE') return new Promise(resolve => held.push({ path, resolve }));
      return original(url, options);
    };
    await chat(); const picker = document.querySelector('[data-key=chat-history]'); picker.value = 'synthetic-bookkeeping'; picker.dispatchEvent(new Event('change'));
    await until(() => document.querySelector('.transcript')?.textContent.includes(marker));
    await settings(); button('永久刪除全部日誌…').click(); await until(() => document.querySelector('#confirm').open); button('刪除全部日誌').click(); await until(() => held.length === 1);
    await chat(); await until(() => document.querySelector('.transcript')?.textContent.includes(marker));
    deleted = true; held[0].resolve(new Response(null, { status: 204 }));
    await until(() => !!document.querySelector('.transcript') && !document.querySelector('.transcript').textContent.includes(marker));
    assert(!document.querySelector('.transcript').textContent.includes('合成回答'), 'Deleted reply retained in memory/UI');
    await settings(); button('登出').click(); await until(() => held.length === 2); button('重新閱讀初始設定').click();
    await until(() => document.body.textContent.includes('步驟 1／4'));
    held[1].resolve(new Response(null, { status: 204 }));
    await until(() => document.body.textContent.includes('已登出') && document.body.textContent.includes('Owner 金鑰'));
    assert(!document.querySelector('nav') && !document.querySelector('.transcript'), 'Successful logout left private shell');
    return { passed: true, width: innerWidth, controlledDeletes: held.length, checks: 'successful journal deletion clears retained chat across navigation,successful logout resets private UI after onboarding navigation,zero service deletes' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_API_BOOKKEEPING; }
})()
