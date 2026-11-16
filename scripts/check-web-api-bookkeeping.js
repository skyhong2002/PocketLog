/* Disposable fixture regression: both destructive successes are synthetic local
 * responses, and 問 AI answers are synthetic. No journal deletion, logout or model
 * call is sent to the service by this script. */
(async () => {
  if (!window.POCKETLOG_SYNTHETIC_API_BOOKKEEPING || location.hostname !== 'localhost' || location.port !== '18751') throw Error('Disposable fixture required');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const pause = () => new Promise(r => setTimeout(r, 30));
  const until = async check => { for (let i = 0; i < 150; i++) { if (check()) return; await pause(); } throw Error('Timed out'); };
  const original = window.fetch.bind(window), held = [];
  const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
  const button = text => [...document.querySelectorAll('button')].find(n => n.textContent === text);
  const marker = '合成：刪除後不得保留的問答';
  const provider = { available: true, enabled: true, has_key: true, probe_provider_binding: true, auto_summarize: true, protocol: 'openai', endpoint: 'https://api.openai.com/v1/chat/completions', model: 'synthetic-model', image_support: true, response_format: 'json_schema', provider_id: 'f'.repeat(64) };
  const log = () => document.querySelector('.ask-log');
  const settings = async () => { document.querySelector('button[aria-label="設定"]').click(); await until(() => !!document.querySelector('[data-key=settings-logout]')); };
  const review = async () => { document.querySelector('button[aria-label="回顧"]').click(); await until(() => !!document.querySelector('[data-key=ask-input]')); };
  try {
    window.fetch = (url, options) => {
      const path = String(url);
      if (path === '/v1/ai') return Promise.resolve(json(provider));
      if (path === '/v1/ask') { const body = JSON.parse(options.body); assert(body.consent === true && body.provider_id === provider.provider_id && body.conversation_id && body.request_id, 'Ask lost consent/provider binding'); return Promise.resolve(json({ answer: '合成回答', scope: {}, stored: true })); }
      if ((path === '/v1/journal' || path === '/session') && options?.method === 'DELETE') return new Promise(resolve => held.push({ path, resolve }));
      return original(url, options);
    };
    // Re-read the (synthetic) AI settings so 問 AI is available.
    document.querySelector('[data-key=refresh]').click(); await pause();
    await review(); const input = document.querySelector('[data-key=ask-input]'); input.value = marker; input.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector('[data-key=ask-send]').click();
    await until(() => log()?.textContent.includes(marker) && log().textContent.includes('合成回答'));
    await settings(); button('刪除全部記錄…').click(); await until(() => document.querySelector('#confirm').open); button('刪除全部記錄').click(); await until(() => held.length === 1);
    await review(); await until(() => log()?.textContent.includes(marker));
    held[0].resolve(new Response(null, { status: 204 }));
    await until(() => !!document.querySelector('[data-key=ask-input]') && !log()?.textContent.includes(marker));
    assert(!log()?.textContent.includes('合成回答'), 'Deleted reply retained in memory/UI');
    await settings(); document.querySelector('[data-key=settings-logout]').click(); await until(() => held.length === 2); button('重新看一次介紹').click();
    await until(() => document.body.textContent.includes('第 1／3 步'));
    held[1].resolve(new Response(null, { status: 204 }));
    await until(() => document.body.textContent.includes('已登出') && document.body.textContent.includes('Owner 金鑰'));
    assert(!document.querySelector('nav') && !document.querySelector('.ask-log'), 'Successful logout left private shell');
    return { passed: true, width: innerWidth, controlledDeletes: held.length, checks: 'successful journal deletion clears retained 問 AI answers across navigation,successful logout resets private UI after onboarding navigation,zero service deletes or model calls' };
  } finally { window.fetch = original; delete window.POCKETLOG_SYNTHETIC_API_BOOKKEEPING; }
})()
