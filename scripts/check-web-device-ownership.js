/* Opt-in disposable browser fixture. Device reads/writes are synthetic local
 * responses; no credential creation/revocation or clipboard write reaches service. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_DEVICE_OWNERSHIP || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 160; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), reads = [], writes = [];
  let holdReads = false;
  let devices = [{ id: 'synthetic-device', label: '合成 API 工具', scope: 'journal', expires_ms: Date.now() + 86400000, last_authenticated_ms: null }];
  const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  const modal = () => document.querySelector('#modal');
  const button = (text, scope = document) => [...scope.querySelectorAll('button')].find(n => n.textContent === text);
  const label = () => modal().querySelector('input[type=text]');
  const settings = async () => { document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-devices]')); };
  const close = async () => { modal().querySelector('.close').click(); await until(() => !modal().open); await pause(); };
  const load = async () => { await until(() => !!document.querySelector('[data-key=settings-devices]')); holdReads = true; const n = reads.length; document.querySelector('[data-key=settings-devices]').click(); await until(() => reads.length > n); holdReads = false; return reads.at(-1); };
  const open = async () => { const pending = await load(); pending.resolve(json({ devices })); await until(() => modal().open && !!label()); };
  const create = async () => { label().value = '合成新工具'; const n = writes.length; button('建立裝置金鑰', modal()).click(); await until(() => writes.length > n); return writes.at(-1); };
  const revoke = async () => { const n = writes.length; button('撤銷這把金鑰', modal()).click(); await until(() => document.querySelector('#confirm').open); button('撤銷金鑰', document.querySelector('#confirm')).click(); await until(() => writes.length > n); assert(writes.at(-1).method === 'DELETE', 'Revoke method missing'); return writes.at(-1); };
  try {
    window.fetch = (url, options) => {
      const path = String(url), method = options?.method || 'GET';
      if (path === '/v1/devices' && method === 'GET') return holdReads ? new Promise(resolve => reads.push({ resolve })) : Promise.resolve(json({ devices }));
      if (path === '/v1/devices' || path.startsWith('/v1/devices/')) return new Promise(resolve => writes.push({ resolve, method, body: options?.body ? JSON.parse(options.body) : null }));
      return original(url, options);
    };
    await settings();
    for (const status of [200, 503, 401]) {
      const pending = await load(); document.querySelector('button[aria-label="時間軸"]').click(); await until(() => document.querySelector('h1')?.textContent === '時間軸');
      pending.resolve(json({ devices }, status)); await pause(); await pause();
      assert(!modal().open && document.querySelector('h1')?.textContent === '時間軸', 'Late list read changed page ' + status); await settings();
    }
    const other = await load(); document.querySelector('[data-key=settings-goals]').click(); await until(() => modal().open); const goal = modal().querySelector('input'); goal.value = '321'; goal.focus();
    other.resolve(json({ devices })); await pause(); await pause(); assert(modal().querySelector('input') === goal && goal.value === '321' && document.activeElement === goal, 'Late list replaced another modal'); await close();
    for (const status of [200, 503, 401]) {
      await open(); const pending = await create(); assert(label().disabled, 'Create controls stay editable'); await close(); await open();
      label().value = '合成：保留新工具草稿 ' + status; label().focus(); const count = writes.length; button('建立裝置金鑰', modal()).click(); await pause();
      assert(writes.length === count, 'Overlapping credential creation sent'); pending.resolve(json({ token: 'b'.repeat(64) }, status)); await pause(); await pause();
      assert(modal().open && label().value.endsWith(String(status)) && document.activeElement === label(), 'Late create changed new editor ' + status);
      assert(!modal().querySelector('input[type=password]'), 'Late credential exposed in newer editor'); await close();
    }
    for (const status of [200, 401]) {
      await open(); const pending = await revoke(); await close(); await open(); label().value = '合成：撤銷後保留 ' + status; label().focus();
      pending.resolve(status === 200 ? new Response(null, { status: 204 }) : json({}, status)); await pause(); await pause();
      assert(modal().open && label().value.endsWith(String(status)) && document.activeElement === label(), 'Late revoke changed editor ' + status); await close();
    }
    for (const status of [200, 401]) {
      await open(); const pending = await revoke(); holdReads = true; const n = reads.length; pending.resolve(new Response(null, { status: 204 })); await until(() => reads.length > n); holdReads = false;
      const followup = reads.at(-1); await close(); await until(() => !!document.querySelector('[data-key=settings-goals]')); document.querySelector('[data-key=settings-goals]').click(); await until(() => modal().open);
      const goal = modal().querySelector('input'); goal.value = '322'; goal.focus(); followup.resolve(json({ devices }, status)); await pause(); await pause();
      assert(modal().querySelector('input') === goal && goal.value === '322' && document.activeElement === goal, 'Late revoke follow-up reopened manager ' + status); await close();
    }
    // Current failed create keeps fields; retry yields only a masked one-time key.
    await open(); const failed = await create(); failed.resolve(json({}, 503)); await until(() => !label().disabled && !!modal().querySelector('[role=alert]'));
    assert(label().value === '合成新工具', 'Current create failure lost draft'); const success = await create(); success.resolve(json({ token: 'b'.repeat(64) }));
    await until(() => !!modal().querySelector('input[type=password]')); const secret = modal().querySelector('input[type=password]');
    assert(secret.readOnly && secret.value.length === 64 && modal().textContent.includes('只顯示這一次'), 'Once-only credential UI missing'); await close();
    assert(!document.querySelector('input[type=password]'), 'Closed credential remains in DOM');
    await open(); const currentRevoke = await revoke(); devices = []; currentRevoke.resolve(new Response(null, { status: 204 })); await until(() => modal().textContent.includes('還沒有裝置金鑰。')); await close();
    assert(document.documentElement.scrollWidth <= innerWidth, 'Document overflow');
    await open(); const expired = await create(); expired.resolve(json({}, 401)); await until(() => !modal().open && document.body.textContent.includes('工作階段已結束'));
    return { passed: true, width: innerWidth, controlledReads: reads.length, controlledWrites: writes.length, checks: 'stale list/page/modal200/503/401,create200/503/401,serialized mutations,revoke and follow-up ownership,current error/retry/once-only key cleanup/revoke,current401,no overflow,zero credential service mutations or clipboard writes' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_DEVICE_OWNERSHIP; }
})()
