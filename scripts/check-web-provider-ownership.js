/* Opt-in synthetic browser acceptance for 設定 › AI (pick provider → key → model →
 * 測試並儲存). Every provider write and bound probe is held locally; no
 * configuration/key change or model call reaches the service. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_PROVIDER_OWNERSHIP || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 150; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), writes = [], probes = [];
  const baseline = await (await original('/v1/ai')).json();
  let aiReads = 0;
  const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  const saved = (model, id) => ({ ...baseline, available: true, enabled: true, probe_provider_binding: true, has_key: true, auto_summarize: false, protocol: 'openai', endpoint: 'https://api.openai.com/v1/chat/completions', model, provider_id: id.repeat(64).slice(0, 64) });
  const modal = () => document.querySelector('#modal');
  const model = () => document.getElementById([...modal().querySelectorAll('label')].find(n => n.textContent.includes('模型')).htmlFor);
  const key = () => modal().querySelector('input[type=password]');
  const fill = (n, value) => { n.value = value; n.dispatchEvent(new Event('input', { bubbles: true })); };
  const open = async () => {
    document.querySelector('[data-key=settings-ai-setup]').click(); await until(() => modal().open && !!modal().querySelector('[data-key=provider-openai], [data-key=ai-change]'));
    // A connected provider shows its switches first; the form is behind 更換 AI.
    if (modal().querySelector('[data-key=ai-change]')) { modal().querySelector('[data-key=ai-change]').click(); await until(() => !!modal().querySelector('[data-key=provider-openai]')); }
    modal().querySelector('[data-key=provider-openai]').click(); await until(() => !!key());
    assert(modal().querySelector('[data-key=provider-openai]').getAttribute('aria-pressed') === 'true', 'Provider choice not pressed');
    assert(!modal().textContent.includes('API 網址'), 'Fixed provider must not show an endpoint field');
  };
  const close = async () => { modal().querySelector('.close').click(); await until(() => !modal().open); await pause(); };
  const save = async (text = 'synthetic-model') => { fill(model(), text); fill(key(), 'synthetic-key'); const n = writes.length; modal().querySelector('[data-key=ai-save]').click(); await until(() => writes.length > n); return writes.at(-1); };
  const settings = async () => { document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-ai-setup]')); };
  try {
    window.fetch = (url, options) => {
      const path = String(url), method = options?.method || 'GET';
      if (path === '/v1/ai/test') return new Promise(resolve => probes.push({ resolve, body: JSON.parse(options.body) }));
      if (path === '/v1/ai' && ['PUT', 'DELETE'].includes(method)) return new Promise(resolve => writes.push({ resolve, method, body: options.body ? JSON.parse(options.body) : null }));
      if (path === '/v1/ai') aiReads++;
      return original(url, options);
    };
    await settings();
    // The preset fills the endpoint/protocol; the key is sent only in the PUT body.
    await open(); const first = await save();
    assert(first.body.endpoint === 'https://api.openai.com/v1/chat/completions' && first.body.protocol === 'openai' && first.body.api_key === 'synthetic-key' && first.body.image_support === true, 'Preset settings not submitted');
    assert(model().disabled && key().disabled, 'Saving controls remain editable');
    // A late save completion must not close, expire or rewrite a newer editor.
    for (const status of [200, 503, 401]) {
      // The first iteration reuses the save still held from the preset check above.
      const pending = status === 200 ? first : await (async () => { await open(); return save(); })();
      await close(); await open(); fill(model(), '合成：新的草稿 ' + status); model().focus();
      fill(key(), 'synthetic-unsent-key'); const count = writes.length;
      modal().querySelector('[data-key=ai-save]').click(); await pause();
      assert(writes.length === count && key().value === 'synthetic-unsent-key', 'Overlapping save sent or blocked key erased');
      pending.resolve(json(saved('synthetic-stale', 'b'), status)); await pause(); await pause();
      assert(modal().open && model().value.endsWith(String(status)) && document.activeElement === model(), 'Stale save ' + status + ' changed new draft/focus');
      assert(probes.length === 0, 'Detached save continued to a probe');
      await close();
      assert(!document.body.textContent.includes('synthetic-stale'), 'Stale save installed global provider');
    }
    // Current save runs exactly one probe bound to the identity it just saved.
    await open(); let pending = await save('synthetic-probe');
    pending.resolve(json(saved('synthetic-probe', 'c'))); await until(() => probes.length === 1);
    assert(probes[0].body.provider_id === 'c'.repeat(64), 'Probe not bound to the saved provider');
    // A late probe result belongs to its own editor only.
    await close(); await open(); fill(model(), '合成：測試後保留'); model().focus();
    probes[0].resolve(json({}, 401)); await pause(); await pause();
    assert(modal().open && model().value === '合成：測試後保留' && !document.body.textContent.includes('工作階段已結束'), 'Detached probe changed editor');
    // Current probe failures stay in the form, leave it editable and do not retry.
    for (const [status, text] of [[502, '測試沒有成功'], [409, '設定剛在別處被更改']]) {
      pending = await save('synthetic-failure'); const n = probes.length;
      pending.resolve(json(saved('synthetic-failure', 'd'))); await until(() => probes.length > n);
      probes.at(-1).resolve(json({ error: 'synthetic' }, status));
      await until(() => !!modal().querySelector('.ai-feedback [role=alert], .ai-feedback .callout'));
      assert(modal().open && modal().textContent.includes(text) && !model().disabled, 'Probe failure ' + status + ' not shown in form');
      await pause(); assert(probes.length === n + 1, 'Probe was retried automatically');
    }
    // Ordinary success closes the dialog and shows the authoritative save result without an extra read.
    const reads = aiReads;
    pending = await save('synthetic-authoritative-result'); pending.resolve(json(saved('synthetic-authoritative-result', 'e')));
    await until(() => probes.length && probes.at(-1).body.provider_id === 'e'.repeat(64)); probes.at(-1).resolve(json({ ok: true, journal_data_sent: false }));
    await until(() => !modal().open && !!document.querySelector('.page-settings')?.textContent.includes('synthetic-authoritative-result'));
    assert(document.body.textContent.includes('已連接：OpenAI · synthetic-authoritative-result'), 'Connected state missing');
    assert(aiReads === reads, 'Redundant provider read after save');
    assert(document.querySelector('.page-settings').textContent.includes('自動整理關閉'), 'Auto switch must stay off after a new save');
    assert(document.documentElement.scrollWidth <= innerWidth, 'Document overflow');
    // A current unauthorized save must still expire the actual UI session.
    await open(); const expired = await save(); expired.resolve(json({}, 401)); await until(() => !modal().open && document.body.textContent.includes('工作階段已結束'));
    return { passed: true, width: innerWidth, controlledWrites: writes.length, controlledProbes: probes.length, checks: 'preset endpoint/protocol/key body,stale save200/503/401,serialized writes,key preservation,probe bound to saved identity,detached probe,current probe 502/409 in form without retry,authoritative result without extra read,auto stays off,current401,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_PROVIDER_OWNERSHIP; }
})()
