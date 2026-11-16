/* Disposable synthetic fixture. Holds page reads and local collection-pause
 * responses; no journal mutation, configuration change or model call reaches the service. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_API_RENDER_OWNERSHIP || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 160; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), statusReads = [], bodies = [], writes = [];
  const statusSnapshot = await (await original('/v1/status')).json(), workspace = await (await original('/v1/workspace')).json();
  let holdStatus = false, holdWorkspaceBody = false, aiReads = 0, syntheticCollection = null;
  const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  const modal = () => document.querySelector('#modal');
  const button = (text, scope = document) => [...scope.querySelectorAll('button')].find(n => n.textContent === text);
  const close = async () => { if (modal().open) { modal().querySelector('.close').click(); await until(() => !modal().open); await pause(); } };
  const nav = async (label, ready) => { await close(); document.querySelector('button[aria-label="' + label + '"]').click(); await until(ready); };
  const settings = () => nav('設定', () => !!document.querySelector('[data-key=settings-categories-edit]'));
  const timeline = () => nav('時間軸', () => document.querySelector('h1')?.textContent === '時間軸');
  const categoryDraft = async text => { document.querySelector('[data-key=settings-categories-edit]').click(); await until(() => !!modal().querySelector('[data-category-id]')); const n = modal().querySelector('[data-category-id]'); n.value = text; n.dispatchEvent(new Event('input', { bubbles: true })); n.focus(); return n; };
  const pauseAll = async () => { const n = writes.length; document.querySelector('[data-key=settings-collection]').click(); await until(() => document.querySelector('#confirm').open); document.querySelector('#confirm .danger-fill, #confirm .primary').click(); await until(() => writes.length > n); return writes.at(-1); };
  try {
    window.fetch = (url, options) => {
      const path = String(url), method = options?.method || 'GET';
      if (path === '/v1/status' && holdStatus) return new Promise(resolve => statusReads.push({ resolve }));
      if (path === '/v1/workspace' && holdWorkspaceBody) {
        const response = json(workspace); response.json = () => new Promise(resolve => bodies.push({ resolve })); return Promise.resolve(response);
      }
      if (path === '/v1/ai') aiReads++;
      if (path === '/v1/collection' && method === 'GET' && syntheticCollection) return Promise.resolve(json(syntheticCollection));
      if (path === '/v1/collection' && method === 'PUT') return new Promise(resolve => writes.push({ resolve, path, body: JSON.parse(options.body) }));
      return original(url, options);
    };
    // Default optional GET ownership applies before status/error/session handling.
    for (const code of [200, 503, 401]) {
      holdStatus = true; const n = statusReads.length; document.querySelector('button[aria-label="設定"]').click(); await until(() => statusReads.length > n); holdStatus = false;
      await timeline(); const heading = document.querySelector('h1'); statusReads.at(-1).resolve(json(statusSnapshot, code)); await pause(); await pause();
      assert(document.querySelector('h1') === heading && !document.body.textContent.includes('工作階段已結束'), 'Stale GET changed current page ' + code);
    }
    // An already accepted200 with a delayed JSON body is cancelled before assignment.
    holdWorkspaceBody = true; const n = bodies.length; document.querySelector('[data-key=refresh]').click(); await until(() => bodies.length > n); holdWorkspaceBody = false;
    await settings(); const draft = await categoryDraft('合成：慢速本文之後保留'); const heading = document.querySelector('h1'), count = aiReads;
    bodies.at(-1).resolve({ ...workspace, categories: workspace.categories.map((c, i) => (i ? c : { ...c, name: '合成：過期本文' })) }); await pause(); await pause();
    assert(aiReads === count && document.querySelector('h1') === heading && draft.isConnected && document.activeElement === draft, 'Slow stale body continued global refresh');
    await close(); document.querySelector('[data-key=settings-categories-edit]').click(); await until(() => modal().open && !!modal().querySelector('[data-category-id]'));
    assert(modal().querySelector('[data-category-id]').value === workspace.categories[0].name, 'Slow stale body installed workspace'); await close();
    // Default write errors cannot expire or disturb a newer editor. Successful
    // default writes keep their existing cross-page completion bookkeeping.
    for (const code of [503, 401]) {
      await settings(); const pending = await pauseAll(); await timeline(); await settings();
      const draft = await categoryDraft('合成：離開暫停後保留 ' + code); const heading = document.querySelector('h1');
      pending.resolve(json(pending.body, code)); await pause(); await pause();
      assert(modal().open && draft.isConnected && draft.value.endsWith(String(code)) && document.activeElement === draft && document.querySelector('h1') === heading, 'Stale mutation changed page/editor ' + code); await close();
    }
    // An ordinary current pause still updates and renders correctly (served locally).
    await settings(); const current = await pauseAll(); syntheticCollection = { ...current.body, revision: current.body.revision + 1 };
    current.resolve(json(syntheticCollection)); await until(() => document.querySelector('[data-key=settings-collection]')?.getAttribute('aria-checked') === 'true');
    assert(document.querySelector('.settings-section')?.textContent.includes('已暫停'), 'Paused state missing');
    assert(document.documentElement.scrollWidth <= innerWidth, 'Document overflow');
    // Current optional GET401 must still expire and explain the real UI session.
    holdStatus = true; const k = statusReads.length; document.querySelector('button[aria-label="設定"]').click(); await until(() => statusReads.length > k); holdStatus = false;
    statusReads.at(-1).resolve(json({}, 401)); await until(() => document.body.textContent.includes('工作階段已結束'));
    return { passed: true, width: innerWidth, controlledStatusReads: statusReads.length, slowBodies: bodies.length, controlledWrites: writes.length, checks: 'stale GET200/503/401,slow JSON body,no stale read continuation,stale PUT503/401,current collection pause,current GET401,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_API_RENDER_OWNERSHIP; }
})()
