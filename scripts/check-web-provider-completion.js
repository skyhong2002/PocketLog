/* Disposable browser fixture only. All AI writes/probes are local controlled
 * responses. No model call or provider configuration mutation reaches server. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_PROVIDER_COMPLETION || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 150; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window);
  const baseline = await (await original('/v1/ai')).json(), status = await (await original('/v1/status')).json();
  assert(baseline.enabled && baseline.probe_provider_binding, 'Seeded synthetic provider required');
  const writes = [], probes = [], reads = [], statusReads = [];
  let holdStatus = false;
  const json = (value, code = 200) => new Response(JSON.stringify(value), { status: code, headers: { 'Content-Type': 'application/json' } });
  const modal = () => document.querySelector('#modal');
  const button = (text, scope = document) => [...scope.querySelectorAll('button')].find(n => n.textContent === text);
  const model = () => document.getElementById([...modal().querySelectorAll('label')].find(n => n.textContent.includes('模型 ID')).htmlFor);
  const close = async () => { modal().querySelector('.close').click(); await until(() => !modal().open); await pause(); };
  const settings = async () => { document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-ai]')); };
  const open = async () => { document.querySelector('[data-key=settings-ai]').click(); await until(() => modal().open && !!model()); };
  const probeConflict = async () => {
    const n = probes.length; button('測試已儲存的設定', modal()).click(); await until(() => document.querySelector('#confirm').open);
    button('送出測試', document.querySelector('#confirm')).click(); await until(() => probes.length > n);
    assert(probes.at(-1).body.provider_id === baseline.provider_id, 'Missing provider binding');
    probes.at(-1).resolve(json({}, 409)); await until(() => !!button('重新載入已儲存的模型', modal()));
    const r = reads.length; button('重新載入已儲存的模型', modal()).click(); await until(() => reads.length > r); return reads.at(-1);
  };
  try {
    window.fetch = (url, options) => {
      const path = String(url), method = options?.method || 'GET';
      if (path === '/v1/ai/test') return new Promise(resolve => probes.push({ resolve, body: JSON.parse(options.body) }));
      if (path === '/v1/ai' && method === 'PUT') return new Promise(resolve => writes.push({ resolve, body: JSON.parse(options.body) }));
      if (path === '/v1/ai') return new Promise(resolve => reads.push({ resolve }));
      if (path === '/v1/status' && holdStatus) return new Promise(resolve => statusReads.push({ resolve }));
      return original(url, options);
    };
    await settings(); await open(); model().value = 'synthetic-authoritative-result';
    button('儲存設定', modal()).click(); await until(() => writes.length === 1);
    writes[0].resolve(json({ ...baseline, model: 'synthetic-authoritative-result' }));
    await until(() => !modal().open && !!document.querySelector('[data-key=settings-ai]'));
    assert(reads.length === 0, 'Redundant completion GET remains');
    assert(document.body.textContent.includes('synthetic-authoritative-result'), 'Authoritative save result missing');
    await open(); assert(model().value === 'synthetic-authoritative-result', 'Next editor ignored save result'); await close();
    // Current conflict reload opens the reloaded model after its own refresh.
    await open(); const current = await probeConflict(); current.resolve(json(baseline));
    await until(() => modal().open && !!document.querySelector('[data-key=settings-ai]') && model().value === baseline.model);
    await close();
    // If the reload's page render is delayed, a newer editor must win.
    await open(); const recovery = await probeConflict(); holdStatus = true;
    recovery.resolve(json(baseline)); await until(() => statusReads.length === 1); holdStatus = false;
    assert(!modal().open, 'Recovery must close its old editor before refreshing');
    await settings(); document.querySelector('[data-key=settings-categories]').click();
    await until(() => modal().open && !!modal().querySelector('[data-category-id]'));
    const draft = modal().querySelector('[data-category-id]'); draft.value = '合成：後續分類草稿'; draft.dispatchEvent(new Event('input', { bubbles: true })); draft.focus();
    statusReads[0].resolve(json(status)); await pause(); await pause();
    assert(modal().open && modal().querySelector('[data-category-id]') === draft && draft.value === '合成：後續分類草稿' && document.activeElement === draft, 'Late refresh reopened provider over new editor');
    assert(reads.length === 2 && writes.length === 1 && probes.length === 2, 'Unexpected request');
    assert(document.documentElement.scrollWidth <= innerWidth, 'Document overflow');
    return { passed: true, width: innerWidth, controlledWrites: writes.length, controlledProbes: probes.length, explicitRecoveryReads: reads.length, redundantProviderReads: 0, checks: 'authoritative save result,no redundant read,current reload,newer page/editor/focus survives late reload refresh,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_PROVIDER_COMPLETION; }
})()
