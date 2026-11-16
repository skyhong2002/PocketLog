#!/usr/bin/env node
'use strict';
// Every interval that shares visible time must occupy a different column.
const assert = require('node:assert/strict');
const { layoutLanes, fullDayBounds, timelineTicks } = require('../server/web/app.js');
function check(intervals, minMs = 0) {
  const cards = intervals.map(([start_ms,end_ms],id) => ({id,start_ms,end_ms}));
  const copy = JSON.stringify(cards);
  const placed = layoutLanes(cards, minMs);
  assert.equal(placed.length, cards.length);
  assert.equal(JSON.stringify(cards), copy, 'layout must not mutate the journal');
  for (let i=0;i<placed.length;i++) {
    const a=placed[i]; assert.ok(a.lane>=0 && a.lane<a.lanes);
    for (let j=i+1;j<placed.length;j++) {
      const b=placed[j];
      if (a.card.start_ms < Math.max(b.card.end_ms,b.card.start_ms+minMs) && b.card.start_ms < Math.max(a.card.end_ms,a.card.start_ms+minMs)) {
        assert.notEqual(a.lane,b.lane,'overlapping cards must remain individually visible');
        assert.equal(a.lanes,b.lanes,'a connected overlap group has consistent column widths');
      }
    }
  }
  return placed;
}
assert.deepEqual(check([]), []);
check([[0,100],[10,20],[30,40],[50,60]]); // Nested cards reuse a free column.
check([[0,100],[0,100],[0,100]]); // Identical intervals.
check([[120,200],[0,100],[50,150],[300,400]]); // Transitive overlaps, unsorted input.
assert.ok(check([[0,10],[10,20],[20,30]]).every(x=>x.lanes===1));
check([[0,1],[2,3],[4,5]],10); // Minimum visual height must not obscure a short event.
assert.equal(check([[0,100],[0,10],[11,20],[101,110]])[3].lanes,1);
console.log('Web timeline overlap and journal immutability checks passed');

const originalZone = process.env.TZ;
try {
  process.env.TZ = 'America/New_York';
  for (const [date,hours] of [['2026-03-07',23],['2026-10-31',25]]) {
    const [start,end] = fullDayBounds(date);
    assert.equal(end-start,hours*3600000);
    const ticks=timelineTicks(date); assert.equal(ticks.length,hours);
    assert.ok(ticks.every(t=>t.position>=0 && t.position<24 && t.offset));
    assert.equal(ticks[0].label,'04:00');
  }
  const repeated=timelineTicks('2026-10-31').filter(t=>t.label==='01:00');
  assert.equal(repeated.length,2); assert.notEqual(repeated[0].offset,repeated[1].offset);
  process.env.TZ = 'Australia/Lord_Howe';
  const [start,end]=fullDayBounds('2026-10-03'); assert.equal(end-start,23.5*3600000);
  process.env.TZ = 'Asia/Taipei';
  assert.equal(timelineTicks('2026-10-31').length,24);
} finally { if (originalZone === undefined) delete process.env.TZ; else process.env.TZ=originalZone; }
console.log('Web 04:00 DST labels, offsets and half-hour transition checks passed');

const { metrics } = require('../server/web/app.js');
const cards = [[0,60,'work'],[30,90,'learn'],[75,120,'rest'],[100,130,'social']]
  .map(([start_ms,end_ms,category])=>({start_ms,end_ms,category}));
const before = JSON.stringify(cards);
const m = metrics(cards, c => ['work','learn'].includes(c) ? 'focus' : 'distraction');
assert.equal(m.total,130);
assert.equal(m.focus,90);
assert.equal(m.distraction,55);
assert.equal(m.longest,90);
assert.equal(m.categoryTotal,195);
assert.equal(JSON.stringify(cards),before);
assert.equal(metrics([],()=> 'neutral').total,0);
assert.equal(metrics([{start_ms:-30,end_ms:-10,category:'work'}],()=> 'focus').longest,20);
console.log('Elapsed and role totals merge overlaps; category attribution remains explicit');

// Key issuance, validation and capture readiness are distinct facts.
const { deviceConnectionState } = require('../server/web/app.js');
const now = 180000;
const key = (scope, expires_ms, metadata = {}) => ({scope,expires_ms,...metadata});
assert.deepEqual(deviceConnectionState(null,now),{keys:null,authenticated:null,lastAuthenticatedMs:null});
assert.deepEqual(deviceConnectionState({devices:[]},now),{keys:0,authenticated:false,lastAuthenticatedMs:null});
assert.equal(deviceConnectionState({devices:[key('journal',now+1)]},now).authenticated,null,'old server stays unknown');
assert.equal(deviceConnectionState({devices:[key('journal',now+1,{last_authenticated_ms:null})]},now).authenticated,false,'issued is not authenticated');
const state=deviceConnectionState({devices:[key('collector',now+1,{last_authenticated_ms:120000}),key('journal',now,{last_authenticated_ms:120000}),key('journal',now+1,{last_authenticated_ms:null})]},now);
assert.deepEqual(state,{keys:1,authenticated:false,lastAuthenticatedMs:null},'collector and expired keys do not prove journal pairing');
assert.deepEqual(deviceConnectionState({devices:[key('journal',now+1,{last_authenticated_ms:60000}),key('journal',now+1,{last_authenticated_ms:120000})]},now),{keys:2,authenticated:true,lastAuthenticatedMs:120000});
console.log('Device setup distinguishes issued, unknown and authenticated journal keys');

// The footer must never replace the primary range, regardless of response order.
(async () => {
  const { loadDays } = require('../server/web/app.js');
  const savedFetch = global.fetch;
  const watchdog = setTimeout(() => {
    console.error('Day/week request test did not settle its response gates');
    process.exitCode = 1;
  }, 5000);
  try {
    for (const weekFirst of [false, true]) {
      const waiting = {};
      let arrived;
      const bothArrived = new Promise(resolve => { arrived = resolve; });
      global.fetch = async (path) => {
        const u = new URL(path, 'http://synthetic.invalid');
        const from = Number(u.searchParams.get('from_ms'));
        const to = Number(u.searchParams.get('to_ms'));
        const response = value => ({ ok: true, status: 200, json: async () => value });
        if (u.pathname === '/v1/reviews') {
          const scope = to - from > 86400000 ? 'week' : 'day';
          const result = new Promise(resolve => { waiting[scope] = () => resolve(response({ records: [{ id: scope }], truncated: scope === 'week' })); });
          if (waiting.day && waiting.week) arrived();
          return result;
        }
        const emptyDayStart = fullDayBounds('2026-11-10')[0];
        return response({ cards: from === emptyDayStart ? [] : [{ id: 'other-day', start_ms: from, end_ms: from + 60000 }], reflections: [], checkins: [] });
      };
      const dayRange = loadDays(['2026-11-10'], 0);
      const weekRange = loadDays(['2026-11-09','2026-11-10'], 0);
      await bothArrived;
      waiting[weekFirst ? 'week' : 'day']();
      const first = await (weekFirst ? weekRange : dayRange);
      const firstSnapshot = JSON.stringify(first);
      waiting[weekFirst ? 'day' : 'week']();
      const [day, week] = await Promise.all([dayRange, weekRange]);
      assert.equal(JSON.stringify(first), firstSnapshot, 'later response must not change the earlier snapshot');
      assert.equal(day.cards.length, 0, 'selected empty day stays empty');
      assert.equal(week.cards.length, 1, 'footer retains other-day activity');
      assert.deepEqual(day.reviews, [{ id: 'day' }]);
      assert.deepEqual(week.reviews, [{ id: 'week' }]);
      assert.equal(day.reviewsTruncated, false);
      assert.equal(week.reviewsTruncated, true);
      assert.equal(day.days.length, 1);
      const previousFetch = global.fetch;
      global.fetch = async path => {
        if (path.startsWith('/v1/reviews')) return { ok: true, status: 200, json: async () => ({ records: [] }) };
        return previousFetch(path);
      };
      await assert.rejects(loadDays(['2026-11-10'], 1), error => String(error) === 'Symbol(stale render)');
    }
  } finally { global.fetch = savedFetch; clearTimeout(watchdog); }
  console.log('Independent day/week snapshots survive both response orders and stale renders');
})().catch(error => { console.error(error); process.exitCode = 1; });

// An explicit classification retry must retain current unrelated preferences.
{
  const { classificationRecovery } = require('../server/web/app.js');
  const base = {revision: 10, focus_minutes: 270, distraction_minutes: 60, onboarding_version: 1,
    categories: [{id:'work',name:'Work',color:'#123456',role:'focus'}],
    rules: [{id:'rule-a',app:null,title_contains:'code',category:'work',enabled:true}]};
  const draft=structuredClone(base);draft.categories[0].name='Local';draft.rules[0].title_contains='edited';
  const remote=structuredClone(base);remote.revision=11;remote.focus_minutes=333;remote.distraction_minutes=77;
  const original=JSON.stringify([base,draft,remote]);
  const result=classificationRecovery(base,draft,remote);
  assert.equal(result.kind,'other');assert.equal(result.candidate.revision,11);
  assert.equal(result.candidate.focus_minutes,333);assert.equal(result.candidate.distraction_minutes,77);
  assert.equal(result.candidate.onboarding_version,1);assert.deepEqual(result.candidate.categories,draft.categories);assert.deepEqual(result.candidate.rules,draft.rules);
  assert.equal(JSON.stringify([base,draft,remote]),original,'recovery must not mutate original/server/draft snapshots');
  result.candidate.categories[0].name='Isolated';assert.equal(draft.categories[0].name,'Local');
  const changed=structuredClone(remote);changed.categories[0].color='#abcdef';
  assert.equal(classificationRecovery(base,draft,changed).kind,'classification','remote classification changes require a separate explicit choice');
  const ruleChange=structuredClone(remote);ruleChange.rules[0].enabled=false;
  assert.equal(classificationRecovery(base,draft,ruleChange).kind,'classification');
  assert.equal(classificationRecovery(base,draft,base).kind,'used','same-revision409 is distinct from optimistic conflict');
  assert.equal(classificationRecovery(base,draft,remote,11).kind,'used','a rebased write may separately hit used-category protection');
  const reordered=structuredClone(remote);reordered.categories=[{role:'focus',color:'#123456',name:'Work',id:'work'}];
  assert.equal(classificationRecovery(base,draft,reordered).kind,'other','object key order must not create a false classification conflict');
  console.log('Classification conflict recovery preserves drafts, remote goals, revision and independent snapshots');
}


const { timelineGroups } = require('../server/web/app.js');
const dense = Array.from({length:1000},(_,id)=>({id,start_ms:id*1000,end_ms:(id+1)*1000}));
const denseCopy = JSON.stringify(dense);
const groups = timelineGroups(dense, 1350000, 4);
assert.equal(groups.length,1);
assert.equal(groups[0].collapsed,true);
assert.equal(groups[0].placed.length,1000);
assert.equal(groups[0].start,0);
assert.equal(groups[0].end,1000000);
assert.equal(JSON.stringify(dense),denseCopy);
const mixed = timelineGroups([...dense,{id:'later',start_ms:4000000,end_ms:4100000}],1350000,4);
assert.deepEqual(mixed.map(g=>[g.collapsed,g.placed.length]),[[true,1000],[false,1]]);
assert.equal(new Set(mixed.flatMap(g=>g.placed.map(p=>p.card.id))).size,1001);
assert.equal(timelineGroups([{id:1,start_ms:0,end_ms:100},{id:2,start_ms:10,end_ms:20}],0,4)[0].collapsed,false);
assert.deepEqual(timelineGroups([],0,4),[]);
console.log('Dense display groups preserve every record, true bounds and ordinary overlaps');

const { duration } = require('../server/web/app.js');
assert.equal(duration(0),'0 分鐘');
assert.equal(duration(-1000),'0 分鐘');
assert.equal(duration(1),'不到 1 分鐘');
assert.equal(duration(29999),'不到 1 分鐘');
assert.equal(duration(30000),'1 分鐘');
assert.equal(duration(89999),'1 分鐘');
assert.equal(duration(90000),'2 分鐘');
assert.equal(duration(3600000),'1 小時');
assert.equal(duration(3660000),'1 小時 1 分');
console.log('Positive brief durations stay distinct from zero; minute/hour rounding retained');

const { exportSnapshot } = require('../server/web/app.js');
const exportDays = [{date:'2026-11-10',cards:[{id:'synthetic-card'}],truncated:false}];
const exportReviews = [{id:'synthetic-review'}];
const beforeExport = JSON.stringify([exportDays,exportReviews]);
for (const [cardLimit,reviewLimit] of [[false,false],[true,false],[false,true],[true,true]]) {
  const days = exportDays.map(d=>({...d,truncated:cardLimit}));
  const snapshot = exportSnapshot(days,exportReviews,reviewLimit);
  assert.equal(snapshot.version,1);
  assert.equal(snapshot.truncated,cardLimit||reviewLimit);
  assert.equal(snapshot.reviews_truncated,reviewLimit);
  assert.deepEqual(snapshot.days,days);
  assert.deepEqual(snapshot.reviews,exportReviews);
}
assert.equal(JSON.stringify([exportDays,exportReviews]),beforeExport);
assert.equal(exportSnapshot(exportDays,exportReviews).reviews_truncated,false);
console.log('Journal exports retain activity/review completeness without changing data');

// Bounded search keeps reading sparse pages, resumes from its cursor and stops at its budget.
{
  const { searchActivities } = require('../server/web/app.js');
  const pages = (all, per) => {
    const seen = [];
    const fetchPage = async (path) => {
      const q = new URL(path, 'https://journal.invalid').searchParams;
      seen.push(Object.fromEntries(q));
      const start = q.has('after_id') ? Number(q.get('after_id').slice(1)) + 1 : 0;
      assert.equal(q.has('after_id'), q.has('epoch'), 'cursor always carries its epoch');
      const rows = all.slice(start, start + per);
      const end = start + rows.length;
      return {
        cards: rows.filter((r) => r.match).map((r) => ({ id: r.id })),
        scanned: rows.length,
        journal_epoch: 3,
        next_cursor: end < all.length ? { after_start_ms: end, after_id: 'r' + (end - 1) } : null,
      };
    };
    return { fetchPage, seen };
  };
  const rows = Array.from({ length: 10 }, (_, i) => ({ id: 'r' + i, match: i === 1 || i === 8 }));
  (async () => {
    let { fetchPage, seen } = pages(rows, 3);
    let r = await searchActivities(fetchPage, { q: 'report', category: 'work', fromMs: 1, toMs: 2 });
    assert.deepEqual(r.cards.map((c) => c.id), ['r1', 'r8']);
    assert.equal(r.done, true); assert.equal(r.scanned, 10); assert.equal(seen.length, 4);
    assert.equal(seen[0].q, 'report'); assert.equal(seen[0].category, 'work');
    assert.ok(!('after_id' in seen[0]));
    ({ fetchPage, seen } = pages(rows, 3));
    r = await searchActivities(fetchPage, { q: 'x', fromMs: 1, toMs: 2, maxRequests: 2 });
    assert.deepEqual(r.cards.map((c) => c.id), ['r1']);
    assert.equal(r.done, false, 'request budget stops before the end without claiming no results');
    assert.deepEqual(r.cursor, { after_start_ms: 6, after_id: 'r5', epoch: 3 });
    const rest = await searchActivities(fetchPage, { q: 'x', fromMs: 1, toMs: 2, cursor: r.cursor });
    assert.deepEqual(rest.cards.map((c) => c.id), ['r8']);
    assert.equal(seen[2].after_id, 'r5'); assert.equal(seen[2].epoch, '3');
    ({ fetchPage, seen } = pages(rows, 10));
    r = await searchActivities(fetchPage, { q: 'x', fromMs: 1, toMs: 2, want: 1 });
    assert.equal(seen[0].limit, '1');
    console.log('Web bounded search pagination checks passed');
  })().catch((e) => { console.error(e); process.exit(1); });
}

// The timeline status line shows one state, in priority order, from server facts.
{
  const { captureStatus } = require('../server/web/app.js');
  const now = 10 * 86400000;
  const phone = (extra = {}) => ({ scope: 'journal', expires_ms: now + 1, last_authenticated_ms: now - 60000, ...extra });
  const on = { enabled: true, auto_summarize: true };
  const kind = (input) => captureStatus({ now, ai: on, pending: 0, paused: false, devices: [phone()], ...input }).kind;
  assert.equal(kind({}), 'ok');
  assert.equal(captureStatus({ now, ai: on, paused: false, devices: [phone()] }).text, '記錄中 · AI 自動整理');
  assert.equal(kind({ paused: true, devices: [] }), 'paused', 'pause wins over everything');
  assert.equal(kind({ devices: [] }), 'not-started');
  assert.equal(kind({ devices: [phone({ scope: 'collector' })] }), 'not-started', 'collector keys are not phones');
  assert.equal(kind({ devices: [phone({ expires_ms: now - 1 })] }), 'expired');
  assert.equal(captureStatus({ now, ai: on, devices: [phone({ expires_ms: now })] }).text, '金鑰失效，請重新連線');
  assert.equal(kind({ devices: [phone({ last_authenticated_ms: null })] }), 'not-started', 'issued but never connected');
  assert.equal(kind({ devices: [phone({ last_authenticated_ms: now - 2 * 86400000 })] }), 'stale');
  assert.equal(kind({ devices: [phone({ last_authenticated_ms: undefined })].map(({ last_authenticated_ms, ...d }) => d) }), 'ok', 'older servers without last-seen are not warned about');
  assert.equal(kind({ ai: { enabled: false } }), 'no-ai');
  assert.equal(captureStatus({ now, ai: { enabled: false }, devices: [phone()] }).target, 'ai');
  assert.equal(kind({ ai: { enabled: true, auto_summarize: false } }), 'ai-off');
  const busy = captureStatus({ now, ai: on, devices: [phone()], pending: 3 });
  assert.equal(busy.kind, 'processing'); assert.equal(busy.text, '記錄中 · AI 整理中（3 張）');
  // Unknown facts (failed reads) never produce a warning of their own.
  assert.equal(captureStatus({ now, paused: null, devices: null, ai: on }).kind, 'ok');
  assert.equal(captureStatus({ now, paused: null, devices: null, ai: null }).kind, 'ok');
  console.log('Status line chooses one state: paused, keys, phone contact, AI, then pending cards');
}

// AI presets fill in the endpoint and protocol; only 自訂 takes a typed endpoint.
{
  const { providerSettings, presetFor, aiLabel, AI_PROVIDERS } = require('../server/web/app.js');
  assert.deepEqual(AI_PROVIDERS.map(p => p.id), ['openai', 'anthropic', 'gemini', 'openrouter', 'deepseek', 'custom']);
  const cases = [
    ['openai', 'gpt-x', 'openai', 'https://api.openai.com/v1/chat/completions'],
    ['anthropic', 'claude-x', 'anthropic', 'https://api.anthropic.com/v1/messages'],
    ['gemini', 'gemini-2.5-flash', 'gemini', 'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent'],
    ['openrouter', 'vendor/model', 'openai', 'https://openrouter.ai/api/v1/chat/completions'],
    ['deepseek', 'deepseek-chat', 'openai', 'https://api.deepseek.com/chat/completions'],
  ];
  for (const [id, model, protocol, endpoint] of cases) {
    const settings = providerSettings(id, `  ${model} `, 'https://ignored.example/v1');
    assert.equal(settings.protocol, protocol, id);
    assert.equal(settings.endpoint, endpoint, id);
    assert.equal(settings.model, model, 'model is trimmed');
    assert.equal(presetFor(settings), id, 'saved settings map back to their provider');
  }
  assert.equal(providerSettings('openai', 'm').response_format, 'json_schema');
  assert.equal(providerSettings('deepseek', 'm').response_format, 'json_object');
  const custom = providerSettings('custom', 'llama3.2-vision', ' http://127.0.0.1:11434/v1/chat/completions ');
  assert.deepEqual([custom.protocol, custom.endpoint], ['openai', 'http://127.0.0.1:11434/v1/chat/completions']);
  assert.equal(presetFor(custom), 'custom');
  assert.equal(providerSettings('custom', 'm', 'http://[::1]:8080/v1/chat/completions').endpoint, 'http://[::1]:8080/v1/chat/completions');
  assert.equal(providerSettings('custom', 'm', 'https://llm.example/v1/chat/completions').endpoint, 'https://llm.example/v1/chat/completions');
  assert.throws(() => providerSettings('custom', 'm', 'http://localhost:11434/v1/chat/completions'), /HTTPS/);
  assert.throws(() => providerSettings('custom', 'm', 'http://192.168.1.2/v1'), /HTTPS/);
  assert.throws(() => providerSettings('custom', 'm', 'https://user:pw@llm.example/v1'), /帳號或密碼/);
  assert.throws(() => providerSettings('custom', 'm', 'not a url'), /完整/);
  assert.throws(() => providerSettings('gemini', 'gemini/../x'), /Gemini/);
  assert.throws(() => providerSettings('openai', '   '), /模型/);
  assert.throws(() => providerSettings('nope', 'm'), /提供者/);
  assert.equal(presetFor({ protocol: 'anthropic', endpoint: 'https://proxy.example/v1/messages', model: 'm' }), null, 'unknown native endpoints are not relabelled');
  assert.equal(presetFor({ protocol: 'gemini', endpoint: cases[2][3], model: 'other' }), null, 'Gemini endpoint must match its model');
  assert.equal(presetFor({}), null);
  assert.equal(aiLabel({ protocol: 'anthropic', endpoint: cases[1][3], model: 'claude-x' }), 'Anthropic · claude-x');
  assert.equal(aiLabel({ protocol: 'openai', endpoint: 'https://llm.example/v1/chat/completions', model: 'm' }), '自訂 · m');
  console.log('AI provider presets map to fixed endpoints/protocols and back; custom endpoints are validated');
}

// Package names become readable app names; app-only card titles fall back to them.
{
  const { appName, cardTitle } = require('../server/web/app.js');
  assert.equal(appName('com.google.android.youtube'), 'YouTube');
  assert.equal(appName('com.example.notes'), 'Notes');
  assert.equal(appName('org.example.app'), 'Example');
  assert.equal(appName(''), '未知的 app');
  assert.equal(cardTitle({ app: 'com.example.notes', title: 'com.example.notes' }), 'Notes');
  assert.equal(cardTitle({ app: 'com.example.notes', title: '' }), 'Notes');
  assert.equal(cardTitle({ app: 'com.example.notes', title: '寫週報' }), '寫週報');
  console.log('App display names and app-only title fallback');
}
