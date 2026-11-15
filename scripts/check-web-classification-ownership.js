/* Opt-in disposable browser fixture; intercepts classification preview and
 * workspace writes/recovery reads. No provider or workspace write is sent. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_CLASSIFICATION_OWNERSHIP || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async predicate => { for (let i = 0; i < 150; i++) { if (predicate()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), previews = [], writes = [], recoveries = [];
  const snapshot = await (await original('/v1/workspace')).json();
  let holdRecovery = false;
  const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  const release = (item, status, value = {}) => item.resolve(json(value, status));
  const button = text => [...document.querySelectorAll('button')].find(n => n.textContent === text);
  const modal = () => document.querySelector('#modal');
  const name = () => modal().querySelector('[data-category-id]');
  const fill = (node, value) => { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); };
  const open = async () => { document.querySelector('[data-key=settings-categories]').click(); await until(() => modal().open && !!name()); };
  const close = async () => { modal().querySelector('.close').click(); await until(() => !modal().open); await pause(); };
  const preview = async () => { const n = previews.length; fill(document.querySelector('.rule-preview input'), 'org.example.synthetic'); button('試算規則').click(); await until(() => previews.length > n); return previews.at(-1); };
  const save = async () => { const n = writes.length; button('儲存分類').click(); await until(() => writes.length > n); return writes.at(-1); };
  try {
    window.fetch = (url, options) => {
      const path = String(url);
      if (path === '/v1/workspace/preview') return new Promise(resolve => previews.push({ resolve }));
      if (path === '/v1/workspace' && options?.method === 'PUT') return new Promise(resolve => writes.push({ resolve, body: JSON.parse(options.body) }));
      if (path === '/v1/workspace' && holdRecovery) return new Promise(resolve => recoveries.push({ resolve }));
      return original(url, options);
    };
    document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-categories]'));
    // Closed preview cannot expire a new editor or change its focus/draft.
    for (const status of [200, 503, 401]) {
      await open(); const pending = await preview(); await close(); await open();
      fill(name(), '合成：新的分類草稿 ' + status); name().focus(); const focused = name();
      release(pending, status, { category: 'work', matched_rule_id: null }); await pause(); await pause();
      assert(modal().open && name().value.endsWith(String(status)) && document.activeElement === focused, 'detached preview changed editor ' + status);
      assert(!modal().querySelector('.preview-outcome'), 'detached preview result leaked'); await close();
    }
    // Changing the sample invalidates a request, including its old 401.
    await open(); const changed = await preview(); fill(document.querySelector('.rule-preview input'), 'org.example.changed');
    release(changed, 401); await pause(); await pause();
    assert(modal().open && document.querySelector('.rule-preview input').value === 'org.example.changed', 'sample epoch ignored');
    const currentError = await preview(); release(currentError, 503); await until(() => document.querySelector('.rule-preview-result .warn'));
    const currentSuccess = await preview(); release(currentSuccess, 200, { category: 'work', matched_rule_id: null });
    await until(() => !!modal().querySelector('.preview-outcome')); assert(modal().textContent.includes('範例將分類為'), 'current success missing'); await close();
    // A save's detached response cannot install an old workspace globally.
    await open(); const staleSave = await save(); await close(); await open(); fill(name(), '合成：儲存回應後保留'); name().focus();
    release(staleSave, 200, { ...snapshot, focus_minutes: 1234 }); await pause(); await pause();
    assert(modal().open && name().value === '合成：儲存回應後保留', 'stale save closed new editor'); await close();
    document.querySelector('[data-key=settings-goals]').click(); await until(() => modal().open && !!modal().querySelector('input'));
    assert(Number(modal().querySelector('input').value) === snapshot.focus_minutes, 'stale save replaced prefs'); await close();
    // A conflict recovery GET is also owned by the originating editor.
    for (const status of [200, 401]) {
      await open(); holdRecovery = true; const pendingSave = await save(); release(pendingSave, 409);
      const n = recoveries.length; await until(() => recoveries.length > n); const recovery = recoveries.at(-1); holdRecovery = false;
      await close(); await open(); fill(name(), '合成：復原後保留 ' + status); name().focus();
      release(recovery, status, snapshot); await pause(); await pause();
      assert(modal().open && name().value.endsWith(String(status)) && document.activeElement === name(), 'stale recovery changed editor ' + status); await close();
    }
    assert(document.documentElement.scrollWidth <= innerWidth, 'overflow');
    // Current 401 must still end the current session UI.
    await open(); const expired = await preview(); release(expired, 401);
    await until(() => !modal().open && !document.querySelector('main nav') && document.body.textContent.includes('Owner 金鑰'));
    assert(document.body.textContent.includes('工作階段已結束'), 'current401 did not explain expiry');
    return { passed: true, width: innerWidth, controlledPreviews: previews.length, controlledWrites: writes.length, recoveryReads: recoveries.length, checks: 'stale200/503/401,sample epoch,current error/retry/success,save global prefs ownership,conflict recovery ownership,current401,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_CLASSIFICATION_OWNERSHIP; }
})()
