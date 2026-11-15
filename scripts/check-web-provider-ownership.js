/* Opt-in synthetic browser acceptance. All provider mutations and probes are
 * held locally; no configuration/key change or model call reaches the service. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_PROVIDER_OWNERSHIP || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 150; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), writes = [], probes = [], reads = [];
  const baseline = await (await original('/v1/ai')).json();
  assert(baseline.enabled && baseline.probe_provider_binding, 'Seeded synthetic provider required');
  let holdReads = false;
  const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  const release = (item, status = 200, value = baseline) => item.resolve(json(value, status));
  const modal = () => document.querySelector('#modal');
  const button = (text, scope = document) => [...scope.querySelectorAll('button')].find(n => n.textContent === text);
  const model = () => document.getElementById([...modal().querySelectorAll('label')].find(n => n.textContent.includes('模型 ID')).htmlFor);
  const key = () => modal().querySelector('input[type=password]');
  const fill = (n, value) => { n.value = value; n.dispatchEvent(new Event('input', { bubbles: true })); };
  const open = async () => { document.querySelector('[data-key=settings-ai]').click(); await until(() => modal().open && !!model()); };
  const close = async () => { modal().querySelector('.close').click(); await until(() => !modal().open); await pause(); };
  const save = async () => { const n = writes.length; button('儲存設定', modal()).click(); await until(() => writes.length > n); return writes.at(-1); };
  const probe = async () => { const n = probes.length; button('測試已儲存的設定', modal()).click(); await until(() => document.querySelector('#confirm').open); assert(document.querySelector('#confirm').textContent.includes(baseline.model), 'Missing saved model confirmation'); button('送出測試', document.querySelector('#confirm')).click(); await until(() => probes.length > n); assert(probes.at(-1).body.provider_id === baseline.provider_id, 'Probe lost provider binding'); return probes.at(-1); };
  const disable = async () => { const n = writes.length; button('停用並刪除 key', modal()).click(); await until(() => document.querySelector('#confirm').open); button('停用並刪除', document.querySelector('#confirm')).click(); await until(() => writes.length > n); return writes.at(-1); };
  try {
    window.fetch = (url, options) => {
      const path = String(url), method = options?.method || 'GET';
      if (path === '/v1/ai/test') return new Promise(resolve => probes.push({ resolve, body: JSON.parse(options.body) }));
      if (path === '/v1/ai' && ['PUT', 'DELETE'].includes(method)) return new Promise(resolve => writes.push({ resolve, method, body: options.body ? JSON.parse(options.body) : null }));
      if (path === '/v1/ai' && holdReads) return new Promise(resolve => reads.push({ resolve }));
      return original(url, options);
    };
    document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-ai]'));
    // Late save and test completions must not close, expire or rewrite a new editor.
    for (const kind of ['save', 'probe']) for (const status of [200, 503, 401]) {
      await open(); const pending = kind === 'save' ? await save() : await probe();
      if (kind === 'save') assert(model().disabled && key().disabled, 'Saving controls remain editable');
      await close(); await open(); fill(model(), '合成：新的草稿 ' + kind + status); model().focus();
      if (kind === 'save') {
        fill(key(), 'synthetic-unsent-key'); const count = writes.length;
        button('儲存設定', modal()).click(); await pause();
        assert(writes.length === count && key().value === 'synthetic-unsent-key', 'Overlapping save or blocked key erased');
      }
      release(pending, status, { ...baseline, model: 'synthetic-stale' }); await pause(); await pause();
      assert(modal().open && model().value.endsWith(kind + status) && document.activeElement === model(), 'Stale ' + kind + status + ' changed new draft/focus');
      await close(); await open(); assert(model().value === baseline.model, 'Stale response installed global provider'); await close();
    }
    // Current errors are visible, and retry plus ordinary success works.
    await open(); const failed = await save(); release(failed, 503); await until(() => !!modal().querySelector('[role=alert]')); assert(!model().disabled, 'Failure left form disabled');
    const retry = await save(); release(retry); await until(() => !modal().open && !!document.querySelector('[data-key=settings-ai]')); await pause();
    // Conflict recovery must not overwrite another editor or expire its session.
    for (const status of [200, 401]) {
      await open(); const pending = await probe(); release(pending, 409); await until(() => !!button('重新載入已儲存的模型', modal()));
      holdReads = true; const n = reads.length; button('重新載入已儲存的模型', modal()).click(); await until(() => reads.length > n); const recovery = reads.at(-1); holdReads = false;
      await close(); await open(); fill(model(), '合成：復原後保留 ' + status); model().focus(); release(recovery, status); await pause(); await pause();
      assert(modal().open && model().value.endsWith(String(status)) && document.activeElement === model(), 'Stale recovery changed editor'); await close();
    }
    // Both DELETE itself and its follow-up read belong to the original editor.
    for (const status of [200, 401]) {
      await open(); const pending = await disable(); const count = reads.length; holdReads = true;
      await close(); await open(); fill(model(), '合成：停用後保留 ' + status); model().focus(); release(pending, status); await pause(); await pause(); holdReads = false;
      assert(reads.length === count && modal().open && model().value.endsWith(String(status)), 'Detached delete continued or changed editor'); await close();
    }
    await open(); const pendingDelete = await disable(); holdReads = true; const n = reads.length; release(pendingDelete); await until(() => reads.length > n); const afterDelete = reads.at(-1); holdReads = false;
    await close(); await open(); fill(model(), '合成：停用讀取後保留'); model().focus(); release(afterDelete, 401); await pause(); await pause();
    assert(modal().open && model().value === '合成：停用讀取後保留', 'Detached delete read expired new editor'); await close();
    assert(document.documentElement.scrollWidth <= innerWidth, 'Document overflow');
    // A current unauthorized operation must still expire the actual UI session.
    await open(); const expired = await save(); release(expired, 401); await until(() => !modal().open && document.body.textContent.includes('工作階段已結束'));
    return { passed: true, width: innerWidth, controlledWrites: writes.length, controlledProbes: probes.length, controlledRecoveryReads: reads.length, checks: 'stale save/probe200/503/401,current error/retry/success,serialized writes,key preservation,provider consent binding,recovery200/401,delete and read ownership,current401,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_PROVIDER_OWNERSHIP; }
})()
