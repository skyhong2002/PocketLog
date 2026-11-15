/* Synthetic browser acceptance. Card POST responses are controlled locally;
 * no activity correction or model call reaches the server. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_CARD_SAVE_CHECK || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 25));
  const until = async check => { for (let i = 0; i < 160; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), held = [];
  let reads = 0;
  const button = text => [...document.querySelectorAll('button')].find(n => n.textContent === text);
  const modal = () => document.querySelector('#modal');
  const title = () => modal().querySelector('input');
  const summary = () => modal().querySelector('textarea');
  const category = () => modal().querySelector('select');
  const fill = (node, value) => { node.value = value; node.dispatchEvent(new Event(node.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })); };
  const edit = async () => {
    if (innerWidth < 1100 || !button('編輯活動卡')) document.querySelector('button[data-key^=card-]').click();
    if (innerWidth >= 1100) { await until(() => !!button('編輯活動卡')); button('編輯活動卡').click(); }
    await until(() => modal().open && !!title());
  };
  const close = async () => { button('取消').click(); await until(() => !modal().open); await pause(); };
  const save = async () => { const n = held.length; button('儲存修改').click(); await until(() => held.length > n); return held.at(-1); };
  const release = (item, status = 200) => item.resolve(new Response(JSON.stringify(status === 200 ? {} : { error: 'synthetic' }), { status, headers: { 'Content-Type': 'application/json' } }));
  const timeline = async () => { document.querySelector('button[aria-label="時間軸"]').click(); await until(() => !!document.querySelector('button[data-key^=card-]')); };
  try {
    window.fetch = (url, options) => {
      const path = String(url);
      if (path === '/session' && options?.method === 'DELETE') return Promise.resolve(new Response(null, { status: 204 }));
      if (path === '/v1/cards') return new Promise(resolve => held.push({ body: JSON.parse(options.body), resolve }));
      if (path.startsWith('/v1/timeline?')) reads++;
      return original(url, options);
    };
    await timeline();
    // Cancel/reopen and duplicate guard. Old completion may not close new draft.
    await edit(); const old = await save(); await close(); await edit();
    fill(title(), '合成：保留新視窗草稿'); title().focus();
    const n = held.length, r = reads; button('儲存修改').click(); await pause();
    assert(held.length === n && modal().textContent.includes('上一筆儲存仍在處理'), 'same-card duplicate was sent');
    release(old); await pause(); await pause();
    assert(modal().open && title().value === '合成：保留新視窗草稿' && reads === r, 'old success closed/reloaded new dialog');
    await close();
    // Old errors, including auth expiry, belong to the detached editor only.
    for (const status of [503, 401]) {
      await edit(); const pending = await save(); await close(); await edit();
      fill(title(), '合成：錯誤後仍保留 ' + status); title().focus(); const focused = title();
      release(pending, status); await pause(); await pause();
      assert(modal().open && title().value.endsWith(String(status)) && document.activeElement === focused, 'old error changed new dialog ' + status);
      assert(!document.querySelector('.dialog-feedback'), 'old error leaked feedback'); await close();
    }
    // New title, summary and category typed while saving are all still unsaved.
    await edit(); fill(title(), '合成：已送出標題'); fill(summary(), '合成：已送出摘要');
    const pending = await save();
    fill(title(), '合成：更新的標題草稿'); fill(summary(), '合成：更新的摘要草稿');
    const next = [...category().options].find(o => o.value !== pending.body.category).value;
    fill(category(), next); summary().focus(); release(pending); await pause(); await pause();
    assert(modal().open && title().value === '合成：更新的標題草稿' && summary().value === '合成：更新的摘要草稿' && category().value === next, 'newer fields lost');
    assert(modal().textContent.includes('上一筆修改已儲存') && document.activeElement === summary(), 'newer draft feedback/focus');
    const retry = await save(); assert(retry.body.title === title().value && retry.body.summary === summary().value && retry.body.category === next, 'retry did not submit newer fields');
    release(retry, 503); await until(() => !!document.querySelector('.dialog-feedback[role=alert]'));
    assert(modal().open && summary().value === '合成：更新的摘要草稿', 'current failure lost draft');
    const success = await save(); release(success); await until(() => !modal().open && !!document.querySelector('button[data-key^=card-]'));
    // Closed editor cannot rerender a different page on completion.
    await edit(); const otherPage = await save(); await close();
    document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-categories]'));
    const settings = document.querySelector('main'), before = reads; release(otherPage); await pause(); await pause();
    assert(document.querySelector('main') === settings && reads === before, 'old success rerendered other page');
    await timeline(); await edit(); const loggedOut = await save(); await close();
    document.querySelector('button[aria-label="設定"]').click(); await until(() => !!button('登出'));
    button('登出').click(); await until(() => document.body.textContent.includes('已登出'));
    release(loggedOut, 401); await pause(); await pause();
    assert(document.body.textContent.includes('已登出') && !document.body.textContent.includes('登入已過期'), 'stale auth replaced logout');
    assert(document.documentElement.scrollWidth <= innerWidth, 'overflow');
    return { passed: true, width: innerWidth, controlledWrites: held.length, checks: 'cancel/reopen,duplicate guard,stale success/503/401,newer title/summary/category,focus,feedback,current error/retry,ordinary success,other-page ownership,logout stale401,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_CARD_SAVE_CHECK; }
})()
