/* Synthetic browser acceptance. Card POST responses are controlled locally;
 * no activity correction or model call reaches the server. Desktop (>=1100px)
 * edits in the timeline inspector; narrower windows edit in the card dialog. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_CARD_SAVE_CHECK || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 25));
  const until = async check => { for (let i = 0; i < 160; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), held = [];
  let reads = 0;
  const wide = innerWidth >= 1100;
  const button = text => [...document.querySelectorAll('button')].find(n => n.textContent === text);
  const modal = () => document.querySelector('#modal');
  const scope = () => (wide ? document.querySelector('#activity-inspector') : modal().open ? modal() : null);
  const title = () => scope()?.querySelector('.text-editor input');
  const summary = () => scope()?.querySelector('.text-editor textarea');
  const fill = (node, value) => { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); };
  const openCard = async () => { document.querySelector('button[data-key^=card-]').click(); await until(() => !!scope()?.querySelector('[data-key=edit-text]')); };
  const edit = async () => { await openCard(); scope().querySelector('[data-key=edit-text]').click(); await until(() => !!title()); };
  const close = async () => {
    if (wide) { scope().querySelector('[data-key=inspector-close]').click(); await until(() => !scope()); }
    else { modal().querySelector('.close').click(); await until(() => !modal().open); }
    await pause();
  };
  const save = async () => { const n = held.length; scope().querySelector('[data-key=save-text]').click(); await until(() => held.length > n); return held.at(-1); };
  const release = (item, status = 200) => item.resolve(new Response(JSON.stringify(status === 200 ? {} : { error: 'synthetic' }), { status, headers: { 'Content-Type': 'application/json' } }));
  const timeline = async () => { document.querySelector('button[aria-label="時間軸"]').click(); await until(() => !!document.querySelector('button[data-key^=card-]')); };
  const settings = async () => { document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-logout]')); };
  try {
    window.fetch = (url, options) => {
      const path = String(url);
      if (path === '/session' && options?.method === 'DELETE') return Promise.resolve(new Response(null, { status: 204 }));
      if (path === '/v1/cards') return new Promise(resolve => held.push({ body: JSON.parse(options.body), resolve }));
      if (path.startsWith('/v1/timeline?')) reads++;
      return original(url, options);
    };
    await timeline();
    // Close/reopen and duplicate guard. Old completion may not close the new draft.
    await edit(); const old = await save(); await close(); await edit();
    fill(title(), '合成：保留新草稿'); title().focus();
    const n = held.length, r = reads; scope().querySelector('[data-key=save-text]').click(); await pause();
    assert(held.length === n && document.body.textContent.includes('上一次修改還在儲存'), 'same-card duplicate was sent');
    release(old); await pause(); await pause();
    assert(title()?.value === '合成：保留新草稿' && reads === r, 'old success closed/reloaded new editor');
    await close();
    // Old errors, including auth expiry, belong to the detached editor only.
    for (const status of [503, 401]) {
      await edit(); const pending = await save(); await close(); await edit();
      fill(title(), '合成：錯誤後仍保留 ' + status); title().focus(); const focused = title();
      release(pending, status); await pause(); await pause();
      assert(title()?.value.endsWith(String(status)) && document.activeElement === focused, 'old error changed new editor ' + status);
      assert(!document.querySelector('.dialog-feedback[role=alert]') && !document.body.textContent.includes('請重新登入'), 'old error leaked feedback'); await close();
    }
    // Title and summary typed while saving stay unsaved and are submitted next time.
    await edit(); fill(title(), '合成：已送出標題'); fill(summary(), '合成：已送出摘要');
    const pending = await save();
    fill(title(), '合成：更新的標題草稿'); fill(summary(), '合成：更新的摘要草稿');
    summary().focus(); release(pending); await pause(); await pause();
    assert(title()?.value === '合成：更新的標題草稿' && summary().value === '合成：更新的摘要草稿', 'newer fields lost');
    assert(document.body.textContent.includes('之後輸入的修改還沒儲存') && document.activeElement === summary(), 'newer draft feedback/focus');
    const retry = await save(); assert(retry.body.title === '合成：更新的標題草稿' && retry.body.summary === '合成：更新的摘要草稿', 'retry did not submit newer fields');
    release(retry, 503);
    // Desktop errors use the page notice; dialogs show an alert inside the dialog.
    await until(() => (wide ? document.querySelector('#notice') : document.querySelector('.dialog-feedback[role=alert]'))?.textContent.includes('伺服器暫時忙碌'));
    assert(summary()?.value === '合成：更新的摘要草稿', 'current failure lost draft');
    const success = await save(); release(success);
    // Narrow dialogs re-render the saved card in place; desktop reloads the day and keeps the inspector.
    await until(() => !!scope() && !title() && (wide || scope().textContent.includes('合成：更新的標題草稿')));
    // One-tap category: the picked chip is saved immediately with unchanged text.
    if (!scope()) await openCard();
    const chip = [...scope().querySelectorAll('[data-key^=pick-]')].find(b => b.getAttribute('aria-pressed') === 'false');
    const picked = chip.dataset.key; const k = held.length, r0 = reads; chip.click(); await until(() => held.length > k);
    assert(held.at(-1).body.category === picked.slice(5) && held.at(-1).body.title && !('source' in held.at(-1).body), 'category pick sent wrong fields');
    release(held.at(-1));
    await until(() => (wide ? reads > r0 && !!scope() : scope()?.querySelector(`[data-key=${picked}]`)?.getAttribute('aria-pressed') === 'true'));
    await close();
    // A closed editor cannot rerender a different page on completion.
    if (!wide) await timeline();
    await edit(); const otherPage = await save(); await close();
    await settings();
    const page = document.querySelector('main'), before = reads; release(otherPage); await pause(); await pause();
    assert(document.querySelector('main') === page && reads === before, 'old success rerendered other page');
    await timeline(); await edit(); const loggedOut = await save(); await close();
    await settings(); document.querySelector('[data-key=settings-logout]').click(); await until(() => document.body.textContent.includes('已登出'));
    release(loggedOut, 401); await pause(); await pause();
    assert(document.body.textContent.includes('已登出') && !document.body.textContent.includes('工作階段已結束'), 'stale auth replaced logout');
    assert(document.documentElement.scrollWidth <= innerWidth, 'overflow');
    return { passed: true, width: innerWidth, controlledWrites: held.length, checks: 'close/reopen,duplicate guard,stale success/503/401,newer title/summary,focus,feedback,current error/retry,ordinary success,one-tap category,other-page ownership,logout stale401,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_CARD_SAVE_CHECK; }
})()
