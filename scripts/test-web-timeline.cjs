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

const { sharedWeekAxis } = require('../server/web/app.js');
try {
  process.env.TZ = 'America/New_York';
  assert.equal(sharedWeekAxis(['2026-03-06','2026-03-07','2026-03-08']), false);
  assert.equal(sharedWeekAxis(['2026-10-30','2026-10-31','2026-11-01']), false);
  assert.equal(sharedWeekAxis(['2026-11-09','2026-11-15']), true);
  process.env.TZ = 'Australia/Lord_Howe';
  assert.equal(sharedWeekAxis(['2026-10-02','2026-10-03','2026-10-04']), false);
  process.env.TZ = 'Asia/Taipei';
  assert.equal(sharedWeekAxis(['2026-10-03','2026-11-15']), true);
} finally { if (originalZone === undefined) delete process.env.TZ; else process.env.TZ=originalZone; }
console.log('Week clocks split for full-hour and half-hour DST transitions');

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
