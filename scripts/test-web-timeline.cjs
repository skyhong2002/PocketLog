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
