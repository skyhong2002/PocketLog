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
