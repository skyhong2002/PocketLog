/* Run only on an authenticated disposable loopback fixture. Controls overview
 * reads and workspace writes; no provider calls or production journal changes. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_RULE_PICKER_CHECK || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Synthetic fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const until = async predicate => { for (let i = 0; i < 160; i++) { if (predicate()) return; await new Promise(r => setTimeout(r, 25)); } throw Error('Timed out'); };
  const original = window.fetch.bind(window);
  let reads = 0, writes = 0, mode = 'apps', release, query;
  const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  const button = text => [...document.querySelectorAll('button')].find(n => n.textContent === text);
  const group = () => document.querySelector('#modal [role=group][aria-label="規則 1"]');
  const input = () => group().querySelector('input:not([type=checkbox])');
  const chooser = () => group().querySelector('[id^=rule-apps-] select');
  const pick = () => [...group().querySelectorAll('button')].find(n => n.textContent === '從最近活動選擇 app');
  const edit = (node, value) => { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); };
  const open = async () => {
    document.querySelector('[data-key=settings-categories]').click();
    await until(() => document.querySelector('#modal').open);
    if (!group()) button('新增規則').click();
    await until(group);
  };
  const close = () => document.querySelector('#modal .close').click();
  try {
    window.fetch = async (url, options) => {
      const path = String(url);
      if (path === '/v1/workspace' && options?.method === 'PUT') { writes++; throw Error('Unexpected workspace write'); }
      if (!path.startsWith('/v1/overview?')) return original(url, options);
      reads++; query = new URL(path, location.origin).searchParams;
      if (mode === 'held') return new Promise(resolve => { release = resolve; });
      if (mode === 'error') return json({ error: 'synthetic' }, 503);
      if (mode === 'old') return json({ error: 'synthetic' }, 404);
      return json({ apps: mode === 'empty' ? [] : Array.from({ length: 23 }, (_, i) => ({ app: 'example.synthetic.app' + i, duration_ms: 10000 })) });
    };
    document.querySelector('button[aria-label="設定"]').click();
    await until(() => !!document.querySelector('[data-key=settings-categories]'));
    await open();
    edit(input(), 'manual.unknown');
    const category = document.querySelector('#modal input[data-category-id]');
    edit(category, '合成分類草稿');
    assert(reads === 0, 'implicit overview read');
    pick().focus(); pick().click();
    await until(() => !!group().querySelector('[id^=rule-apps-] select'));
    assert(chooser().options.length === 21, 'top20 bound');
    assert(Number(query.get('to_ms')) - Number(query.get('from_ms')) === 29 * 86400000, 'range');
    assert(input().value === 'manual.unknown', 'opening changed manual draft');
    chooser().value = 'example.synthetic.app4'; chooser().dispatchEvent(new Event('change', { bubbles: true }));
    assert(input().value === 'example.synthetic.app4', 'selection failed');
    chooser().value = ''; chooser().dispatchEvent(new Event('change', { bubbles: true }));
    assert(input().value === 'example.synthetic.app4', 'placeholder cleared draft');
    pick().click(); await until(() => pick().getAttribute('aria-expanded') === 'false');
    pick().click(); await until(() => pick().getAttribute('aria-expanded') === 'true');
    assert(reads === 1 && category.value === '合成分類草稿', 'toggle read or lost category');
    assert(document.documentElement.scrollWidth <= innerWidth, 'document overflow');
    assert(group().scrollWidth <= group().clientWidth + 1, 'rule overflow');
    close(); await open();
    for (const failure of ['error', 'old']) {
      mode = failure; edit(input(), 'keep.unknown'); pick().click();
      await until(() => !!group().querySelector('[data-failed]'));
      assert(input().value === 'keep.unknown', 'error lost draft');
      mode = 'empty'; pick().click();
      await until(() => group().textContent.includes('目前沒有已記錄的 app'));
      assert(input().value === 'keep.unknown', 'empty changed draft');
      close(); await open();
    }
    mode = 'held'; release = null; pick().focus(); pick().click(); await until(() => !!release);
    edit(input(), 'typing.during.read'); input().focus();
    release(json({ apps: [{ app: 'example.synthetic.late', duration_ms: 1 }] }));
    await until(() => !!group().querySelector('[id^=rule-apps-] select'));
    assert(document.activeElement === input() && input().value === 'typing.during.read', 'late read stole typing');
    close(); await open(); mode = 'held'; release = null; pick().click(); await until(() => !!release);
    close(); await open(); edit(input(), 'new.dialog.draft'); const active = input(); active.focus();
    release(json({ apps: [{ app: 'example.synthetic.detached', duration_ms: 1 }] }));
    await new Promise(r => setTimeout(r, 100));
    assert(input().value === 'new.dialog.draft' && document.activeElement === active && !group().querySelector('[id^=rule-apps-] select'), 'stale completion touched new dialog');
    assert(writes === 0, 'saved workspace changed');
    close();
    return { passed: true, width: innerWidth, reads, writes, checks: 'explicit 29d/top20 reads,selection/manual/placeholder,category draft,toggle,503/404 retry,empty,typing focus,closed-dialog stale response,no writes,no overflow' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_RULE_PICKER_CHECK; }
})()
