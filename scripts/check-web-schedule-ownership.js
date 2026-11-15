/* Opt-in synthetic browser acceptance. Schedule responses are held locally;
 * no schedule mutation or model call reaches the service. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_SCHEDULE_OWNERSHIP || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 150; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), reads = [], writes = [];
  const baseline = await (await original('/v1/briefs/schedule')).json(), provider = await (await original('/v1/ai')).json();
  const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  const modal = () => document.querySelector('#modal');
  const button = (text, scope = document) => [...scope.querySelectorAll('button')].find(n => n.textContent === text);
  const timezone = () => modal().querySelector('input[type=text]');
  const time = () => modal().querySelector('input[type=time]');
  const settings = async () => { document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-schedule]')); };
  const close = async () => { modal().querySelector('.close').click(); await until(() => !modal().open); await pause(); };
  const load = async () => { const n = reads.length; document.querySelector('[data-key=settings-schedule]').click(); await until(() => reads.length > n); return reads.at(-1); };
  const open = async () => { const pending = await load(); pending.resolve(json(baseline)); await until(() => modal().open && !!time()); };
  const save = async () => { const n = writes.length; button('儲存排程', modal()).click(); await until(() => writes.length > n); return writes.at(-1); };
  try {
    window.fetch = (url, options) => {
      if (String(url) === '/v1/briefs/schedule') return new Promise(resolve => (options?.method === 'PUT' ? writes : reads).push({ resolve, body: options?.body ? JSON.parse(options.body) : null }));
      return original(url, options);
    };
    await settings();
    // Late read after page navigation must neither open nor expire anything.
    for (const status of [200, 503, 401]) {
      const pending = await load(); document.querySelector('button[aria-label="時間軸"]').click();
      await until(() => document.querySelector('h1')?.textContent === '時間軸');
      pending.resolve(json(baseline, status)); await pause(); await pause();
      assert(!modal().open && document.querySelector('h1')?.textContent === '時間軸', 'Late read changed page ' + status); await settings();
    }
    // Another modal on the same page also supersedes a pending opener.
    const pending = await load(); document.querySelector('[data-key=settings-goals]').click();
    await until(() => modal().open && !!modal().querySelector('input')); const goal = modal().querySelector('input'); goal.value = '321'; goal.focus();
    pending.resolve(json(baseline)); await pause(); await pause();
    assert(modal().querySelector('input') === goal && goal.value === '321' && document.activeElement === goal, 'Late read replaced another modal'); await close();
    // Late writes cannot close/expire new drafts; duplicate writes stay local.
    for (const status of [200, 409, 503, 401]) {
      await open(); const pendingSave = await save(); assert(time().disabled && timezone().disabled, 'Saving fields still editable'); await close(); await open();
      timezone().value = 'Asia/Tokyo'; timezone().focus(); const count = writes.length;
      button('儲存排程', modal()).click(); await pause(); assert(writes.length === count, 'Concurrent schedule save sent');
      pendingSave.resolve(json(baseline, status)); await pause(); await pause();
      assert(modal().open && timezone().value === 'Asia/Tokyo' && document.activeElement === timezone(), 'Late save changed newer draft ' + status); await close();
    }
    // Invalid current values, current server error, retry and explicit consent.
    await open(); time().value = '03:59'; button('儲存排程', modal()).click(); await until(() => !!modal().querySelector('[role=alert]'));
    assert(modal().textContent.includes('04:00–23:59'), 'Time validation missing');
    time().value = '09:15'; timezone().value = 'Asia/Taipei'; const consent = modal().querySelector('input[type=checkbox]'); consent.checked = true;
    assert(consent.closest('label').textContent.includes(provider.model), 'Displayed provider missing');
    const failed = await save(); assert(failed.body.provider_id === provider.provider_id && failed.body.enabled && failed.body.minute_of_day === 555, 'Consent or submitted schedule mismatch');
    failed.resolve(json({}, 503)); await until(() => !time().disabled); assert(modal().open && timezone().value === 'Asia/Taipei', 'Current error lost draft');
    const retry = await save(); retry.resolve(json(baseline)); await until(() => !modal().open);
    assert(document.documentElement.scrollWidth <= innerWidth, 'Document overflow');
    // Genuine current401 still ends the session UI.
    await open(); const expired = await save(); expired.resolve(json({}, 401)); await until(() => !modal().open && document.body.textContent.includes('工作階段已結束'));
    return { passed: true, width: innerWidth, controlledReads: reads.length, controlledWrites: writes.length, checks: 'late read200/503/401,page and other-modal ownership,late write200/409/503/401,serialized saves,validation,current error/retry/success,explicit provider consent,current401,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_SCHEDULE_OWNERSHIP; }
})()
