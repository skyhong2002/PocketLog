"use strict";
/* PocketLog private website. Text is always inserted as text; no HTML, Markdown or
   links from journal/model content. The owner key is never written to a URL, to
   browser storage or to a log. */
const inBrowser = typeof document !== "undefined";
const $ = (s) => document.querySelector(s);
const root = inBrowser ? $("#app") : null;
const STALE = Symbol("stale render");
const REPO = "https://github.com/skyhong2002/PocketLog";
const PROMISE = "手機自動記下你看過的畫面，你的 AI 幫你整理成可回顧的一天。";
let prefs,
  ai,
  page = "timeline",
  day = journalDate(),
  // 回顧 shows the selected day or the Monday–Sunday week that contains it.
  reviewMode = "day",
  // null until the owner picks 時間表/清單; then that choice holds for the tab.
  view = null,
  filter = null,
  // Open timeline search: {q, days, category, fromMs, toMs, cards, cursor, scanned, done, error, ran}.
  search = null,
  timelineWatch,
  renderSeq = 0,
  focusKey = null,
  mounted = [],
  refreshOnClose = false;
// 問 AI keeps one conversation per selected period. A retry of the same question
// reuses its request ID, so a completed paid answer is never requested twice.
let askState = null, askEpoch = 0, activeAsk = null, refreshAsk = null;
const inspectorMedia = inBrowser ? matchMedia("(min-width: 1100px)") : null;
const narrowMedia = inBrowser ? matchMedia("(max-width: 860px)") : null;
/* Narrow windows default to the chronological card list, wide ones to the hourly table. */
function currentView() {
  return narrowMedia?.matches ? "list" : view || "grid";
}
let timelineInspector = null, inspectedCard = null;
// One write per activity at a time, including a cancelled/reopened editor.
const cardSaves = new Set();
let providerChangePending = false, scheduleChangePending = false, deviceChangePending = false;
let modalEpoch = 0, switchFocus = null;
const titles = {
  timeline: "時間軸",
  review: "回顧",
  settings: "設定",
};
const icons = {
  timeline: "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
  review: "M4 6h16v14H4zM4 10.5h16M8 3.5v4M16 3.5v4",
  info: "M12 4.5a7.5 7.5 0 1 0 0 15a7.5 7.5 0 1 0 0-15zM12 11v5M12 8h.01",
  check: "M5 12.5l4.5 4.5L19 7.5",
  settings: "M4 7h9M17 7h3M4 17h3M11 17h9M15 4.5v5M9 14.5v5",
  search: "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13zM15.3 15.3L20 20",
  refresh: "M19.5 11a7.5 7.5 0 1 0-2.2 5.6M19.5 4.5V11H13",
  prev: "M14.5 5.5L8 12l6.5 6.5",
  next: "M9.5 5.5L16 12l-6.5 6.5",
  done: "M5 12.5l4.5 4.5L19 7.5",
  close: "M6.5 6.5l11 11M17.5 6.5l-11 11",
  edit: "M4.5 19.5l1-4 10-10 3 3-10 10zM14 7l3 3",
  clock: "M12 4.5a7.5 7.5 0 1 0 0 15a7.5 7.5 0 1 0 0-15zM12 8v4.5l3 2",
  shield: "M12 3.5l7 2.8v5.2c0 4.3-3 7.4-7 9-4-1.6-7-4.7-7-9V6.3z",
  external: "M14 5h5v5M19 5l-8 8M17 13.5V19H5V7h5.5",
  send: "M4.5 12h13M12 6.5l5.5 5.5-5.5 5.5",
  copy: "M8 8h11v12H8zM5 16V4h11",
};
function icon(name) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  const path = document.createElementNS(ns, "path");
  path.setAttribute("d", icons[name]);
  svg.append(path);
  return svg;
}
function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined && text !== null) n.textContent = text;
  if (cls) n.className = cls;
  return n;
}
function add(parent, ...nodes) {
  nodes.forEach((n) => n && parent.append(n));
  return parent;
}
function keyed(node, key) {
  node.dataset.key = key;
  return node;
}
function btn(text, fn, cls) {
  const n = el("button", text, cls);
  n.type = "button";
  n.onclick = () =>
    safe(async () => {
      focusKey = n.dataset.key || null;
      n.closest("dialog")?.querySelector(".dialog-feedback")?.remove();
      const hadFocus = document.activeElement === n;
      n.disabled = true;
      n.setAttribute("aria-busy", "true");
      try {
        await fn();
      } finally {
        n.disabled = false;
        n.removeAttribute("aria-busy");
        // Native dialog restoration runs while this async opener is disabled.
        // Restore only when nothing else claimed focus and the opener survived.
        if (hadFocus && n.isConnected && document.activeElement === document.body)
          n.focus({ preventScroll: true });
      }
    }, n);
  return n;
}
function iconBtn(name, label, fn, cls) {
  const b = btn("", fn, cls ? `icon-btn ${cls}` : "icon-btn");
  b.setAttribute("aria-label", label);
  b.title = label;
  add(b, icon(name));
  return b;
}
function field(parent, label, value = "", type = "text", hint) {
  const id = "field-" + crypto.randomUUID();
  const l = el("label", label);
  l.htmlFor = id;
  const n = el(type === "textarea" ? "textarea" : "input");
  n.id = id;
  if (type !== "textarea") n.type = type;
  n.value = value;
  n.autocomplete = "off";
  add(parent, l);
  if (hint) {
    const h = el("p", hint, "hint");
    h.id = id + "-hint";
    n.setAttribute("aria-describedby", h.id);
    add(parent, h);
  }
  add(parent, n);
  return n;
}
function select(parent, label, items, value) {
  const id = "field-" + crypto.randomUUID();
  const l = el("label", label);
  l.htmlFor = id;
  const n = el("select");
  n.id = id;
  items.forEach(([key, text]) => {
    const o = el("option", text);
    o.value = key;
    add(n, o);
  });
  n.value = value;
  add(parent, l, n);
  return n;
}
function check(parent, label, value = false, hint) {
  const l = el("label", null, "check"),
    n = el("input");
  n.type = "checkbox";
  n.setAttribute("role", "switch");
  n.className = "switch-input";
  n.checked = value;
  const text = add(el("span", null, "switch-text"), el("span", label, "switch-label"));
  if (hint) {
    const h = el("small", hint);
    h.id = "hint-" + crypto.randomUUID();
    n.setAttribute("aria-describedby", h.id);
    add(text, h);
  }
  add(l, n, text);
  add(parent, l);
  return n;
}
/* A labelled group of mutually exclusive buttons; the state is exposed with aria-pressed. */
function segmented(label, items, value, change, key) {
  const group = el("div", null, "segmented");
  group.setAttribute("role", "group");
  group.setAttribute("aria-label", label);
  items.forEach(([id, text]) => {
    const b = btn(text, () => change(id), id === value ? "active" : "");
    b.setAttribute("aria-pressed", String(id === value));
    if (key) keyed(b, `${key}-${id}`);
    add(group, b);
  });
  return group;
}
function code(text) {
  return el("code", text);
}
/* Builds a text element from strings and nodes without interpreting markup. */
function rich(tag, cls, ...parts) {
  const n = el(tag, null, cls);
  parts.forEach((p) =>
    n.append(typeof p === "string" ? document.createTextNode(p) : p),
  );
  return n;
}
function listOf(ordered, items, cls) {
  const l = el(ordered ? "ol" : "ul", null, cls);
  items.forEach((item) =>
    add(l, Array.isArray(item) ? rich("li", null, ...item) : el("li", item)),
  );
  return l;
}
function row(...nodes) {
  return add(el("div", null, "row"), ...nodes);
}
function panel(title, cls) {
  const s = el("section", null, cls);
  if (title) add(s, el("h2", title));
  return s;
}
function notice(text, error = false, source) {
  // A detached originating control belongs to an old page/dialog, never a new one.
  if (source && !source.isConnected) return;
  const dialog = source
    ? source.closest("dialog[open]")
    : $("#confirm").open ? $("#confirm") : $("#modal").open ? $("#modal") : null;
  if (dialog) {
    let feedback = dialog.querySelector(".dialog-feedback");
    if (!feedback) {
      feedback = el("p", null, "callout dialog-feedback");
      add(dialog.querySelector("#modal-body, #confirm-body"), feedback);
    }
    feedback.classList.toggle("warn", error);
    feedback.setAttribute("role", error ? "alert" : "status");
    feedback.textContent = text;
    if (error) {
      feedback.tabIndex = -1;
      feedback.focus();
    }
    return;
  }
  const n = $("#notice");
  n.textContent = text;
  n.hidden = false;
  clearTimeout(notice.timeout);
  notice.timeout = setTimeout(() => (n.hidden = true), 7000);
}
async function safe(fn, source) {
  try {
    await fn();
  } catch (e) {
    if (e !== STALE) notice(e.message || "操作失敗，請重試", true, source);
  }
}
class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}
const statusText = {
  400: "內容或日期範圍不被接受，請檢查後重試",
  403: "這個請求被拒絕；請直接從你的 PocketLog 網址操作",
  404: "找不到這筆資料，可能已經刪除或過期",
  409: "資料已在其他裝置更新，請重新整理後再試",
  413: "內容太大，請減少後重試",
  423: "伺服器收集已暫停；到設定恢復後再試",
  429: "已達數量上限或伺服器忙碌，請稍後再試",
  502: "伺服器前方的代理暫時連不上 PocketLog 服務，請稍後再試",
  503: "伺服器暫時忙碌，請稍後再試",
  504: "伺服器回應逾時，請稍後再試",
  507: "已達儲存上限；刪除不需要的記錄後再試",
};
async function request(path, body, method = body ? "POST" : "GET") {
  try {
    return await fetch(path, {
      method,
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-PocketLog": "1" },
      body: body ? JSON.stringify(body) : undefined,
      cache: "no-store",
      redirect: "error",
    });
  } catch {
    throw new ApiError(
      navigator.onLine === false
        ? "這台裝置目前離線；恢復網路後再試"
        : `連不上你的 PocketLog 伺服器；請確認網路、${PRIVATE_NETWORKS}與伺服器狀態`,
      0,
    );
  }
}
async function api(path, body, method, isCurrent) {
  const seq = renderSeq;
  const current = isCurrent || (() => seq === renderSeq);
  const read = (method || (body ? "POST" : "GET")) === "GET";
  const r = await request(path, body, method);
  // Ordinary reads and errors belong to their dispatch page. Successful writes
  // retain completion bookkeeping (notably logout/deletion) across navigation;
  // dialog/draft callers can explicitly own every response more narrowly.
  if ((isCurrent || read || !r.ok) && !current()) throw STALE;
  if (r.status === 401) {
    login("expired");
    throw new ApiError("請重新登入私人日誌", 401);
  }
  if (!r.ok)
    throw new ApiError(
      r.status === 502 && /^\/v1\/(ask|briefs|ai\/test)/.test(path)
        ? "模型請求未成功，請檢查 AI 設定或稍後重試"
        : statusText[r.status] || `操作未完成 (${r.status})`,
      r.status,
    );
  const value = r.status === 204 ? {} : await r.json();
  if ((isCurrent || read) && !current()) throw STALE;
  return value;
}
/* Optional data: a failure leaves the page usable and is reported beside the control. */
async function soft(path) {
  try {
    return await api(path);
  } catch (e) {
    if (e === STALE || e.status === 401) throw STALE;
    return null;
  }
}
function live(seq) {
  if (seq !== renderSeq) throw STALE;
}
function logo() {
  const n = el("img");
  n.src = "/logo.png";
  n.alt = "";
  n.width = 256;
  n.height = 256;
  return n;
}
function brand() {
  const lockup = el("div", null, "brand-lockup");
  const name = el("div", "PocketLog", "brand-wordmark");
  add(name, el("span", "口袋手記", "brand-subtitle"));
  add(lockup, logo(), name);
  return lockup;
}
function journalDate() {
  const d = new Date();
  if (d.getHours() < 4) d.setDate(d.getDate() - 1);
  return dateKey(d);
}
function dateKey(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
function shift(date, n) {
  const d = new Date(date + "T12:00:00");
  d.setDate(d.getDate() + n);
  return dateKey(d);
}
function windowFor(date) {
  const start = new Date(date + "T04:00:00");
  const next = new Date(start);
  next.setDate(next.getDate() + 1);
  return [start.getTime(), Math.min(next.getTime(), Date.now())];
}
function fullDayBounds(date) {
  return [new Date(date + "T04:00:00").getTime(), new Date(shift(date, 1) + "T04:00:00").getTime()];
}
function timelineTicks(date) {
  const [start, end] = fullDayBounds(date);
  const dst = end - start !== 86400000;
  const formatter = new Intl.DateTimeFormat("zh-TW", { timeZoneName: "shortOffset" });
  const ticks = [];
  for (let at = start; at < end; at += 3600000) {
    const offset = dst ? formatter.formatToParts(new Date(at)).find(p => p.type === "timeZoneName")?.value : "";
    ticks.push({ at, label: time(at), offset, position: ((at - start) / (end - start)) * 24 });
  }
  return ticks;
}
/* Vertical axis for one journal day. Hours with any card (or the current
   moment) keep HOUR_PX; every run of empty hours collapses into one
   thin 沒有記錄 gap. Returns the segments and y(ms), the pixel offset of a time. */
const HOUR_PX = 96, GAP_PX = 18;
function timeAxis(bounds, cards, { minEmpty = 1, now = null, hourPx = HOUR_PX, gapPx = GAP_PX, splitAt = [] } = {}) {
  const splits = new Set(splitAt);
  const busy = bounds.slice(0, -1).map((from, i) => {
    const to = bounds[i + 1];
    return cards.some((c) => c.start_ms < to && Math.max(c.end_ms, c.start_ms + 1) > from) ||
      (now !== null && now >= from && now < to);
  });
  const segments = [];
  let top = 0;
  for (let i = 0; i < busy.length;) {
    let j = i;
    while (j < busy.length && busy[j] === busy[i] && !(j > i && !busy[i] && splits.has(bounds[j]))) j++;
    const from = bounds[i], to = bounds[j];
    if (!busy[i] && j - i >= minEmpty) {
      segments.push({ kind: "gap", from, to, top, height: gapPx });
      top += gapPx;
    } else {
      const height = (j - i) * hourPx;
      const last = segments.at(-1);
      // Short empty runs stay visible and join their neighbouring hours.
      if (last?.kind === "time") {
        last.to = to;
        last.height += height;
      } else segments.push({ kind: "time", from, to, top, height });
      top += height;
    }
    i = j;
  }
  const y = (ms) => {
    const seg = segments.find((x) => ms >= x.from && ms < x.to) || (ms < bounds[0] ? segments[0] : segments.at(-1));
    if (!seg) return 0;
    const at = Math.min(Math.max(ms, seg.from), seg.to);
    return seg.top + (seg.kind === "gap" ? 0 : ((at - seg.from) / (seg.to - seg.from)) * seg.height);
  };
  return { segments, height: top, y };
}
function pathFor(date) {
  const [from, to] = windowFor(date);
  return `/v1/timeline?from_ms=${from}&to_ms=${to}`;
}
function weekDates() {
  const d = new Date(day + "T12:00:00");
  const offset = (d.getDay() + 6) % 7;
  return Array.from({ length: 7 }, (_, i) => shift(day, i - offset));
}
function time(ms) {
  return new Date(ms).toLocaleTimeString("zh-TW", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}
function duration(ms) {
  if (ms > 0 && ms < 60000) return "不到 1 分";
  const m = Math.round(Math.max(0, ms) / 60000);
  if (m < 60) return `${m} 分`;
  return m % 60
    ? `${Math.floor(m / 60)} 小時 ${m % 60} 分`
    : `${m / 60} 小時`;
}
const WEEKDAYS = "日一二三四五六";
/* zh-TW dates without locale data differences: 11月16日（週一）. */
function shortDate(date, weekday = true) {
  const d = new Date(date + "T12:00:00");
  return `${d.getMonth() + 1}月${d.getDate()}日${weekday ? `（週${WEEKDAYS[d.getDay()]}）` : ""}`;
}
/* 11月16日 – 22日, or 11月30日 – 12月6日 across months. */
function weekRange(first, last) {
  const a = new Date(first + "T12:00:00"), b = new Date(last + "T12:00:00");
  return a.getMonth() === b.getMonth()
    ? `${a.getMonth() + 1}月${a.getDate()}日 – ${b.getDate()}日`
    : `${shortDate(first, false)} – ${shortDate(last, false)}`;
}
function dayTitle(date) {
  const today = journalDate();
  const text = shortDate(date);
  if (date === today) return `今天 · ${text}`;
  if (date === shift(today, -1)) return `昨天 · ${text}`;
  return text;
}
function stamp(ms) {
  return `${shortDate(dateKey(new Date(ms)), false)} ${time(ms)}`;
}
/* A zero-length card reads 01:26 · 不到 1 分; a collapsed group reads 3 次 · 不到 1 分. */
function cardLength(c) {
  if (c.members) return `${c.members.length} 次 · ${duration(Math.max(1, c.members.reduce((n, m) => n + Math.max(0, m.end_ms - m.start_ms), 0)))}`;
  return duration(Math.max(1, c.end_ms - c.start_ms));
}
function cardTimes(c) {
  const from = time(c.start_ms), to = time(c.end_ms);
  return c.end_ms > c.start_ms && from !== to ? `${from}–${to}` : from;
}
function styleFor(id) {
  return prefs.categories.find((c) => c.id === id) || prefs.categories.at(-1);
}
function providerHost(config = ai) {
  try {
    return new URL(config.endpoint).host;
  } catch {
    return "尚未設定";
  }
}
/* Side-by-side columns for overlapping intervals, so no card hides another. */
function layoutLanes(cards, minMs = 0) {
  const sorted = [...cards].sort(
    (a, b) => a.start_ms - b.start_ms || b.end_ms - a.end_ms,
  );
  const out = [];
  let cluster = [],
    ends = [],
    clusterEnd = -Infinity,
    group = 0;
  const flush = () => {
    cluster.forEach((item) => { item.lanes = ends.length; item.group = group; });
    group++;
    cluster = [];
    ends = [];
    clusterEnd = -Infinity;
  };
  for (const card of sorted) {
    const end = Math.max(card.end_ms, card.start_ms + minMs);
    if (cluster.length && card.start_ms >= clusterEnd) flush();
    let lane = ends.findIndex((e) => e <= card.start_ms);
    if (lane < 0) lane = ends.length;
    ends[lane] = end;
    const item = { card, lane, lanes: 1 };
    cluster.push(item);
    out.push(item);
    clusterEnd = Math.max(clusterEnd, end);
  }
  flush();
  return out;
}
// Dense groups keep every record available through the existing readable list.
function timelineGroups(cards, minMs, maxLanes) {
  const groups = [];
  for (const item of layoutLanes(cards, minMs)) {
    let group = groups.at(-1);
    if (!group || group.id !== item.group) {
      group = { id: item.group, placed: [], start: item.card.start_ms, end: item.card.end_ms, collapsed: item.lanes > maxLanes };
      groups.push(group);
    }
    group.placed.push(item);
    group.end = Math.max(group.end, item.card.end_ms);
  }
  return groups;
}
// Elapsed time is a union; category attribution may legitimately overlap.
function mergedTime(cards) {
  let total = 0, longest = 0, start, end;
  for (const c of [...cards].sort((a, b) => a.start_ms - b.start_ms)) {
    if (c.end_ms <= c.start_ms) continue;
    if (end === undefined || c.start_ms > end) {
      if (end !== undefined) total += end - start;
      start = c.start_ms;
      end = c.end_ms;
    } else end = Math.max(end, c.end_ms);
    longest = Math.max(longest, end - start);
  }
  if (end !== undefined) total += end - start;
  return { total, longest };
}
function metrics(cards, roleFor = (id) => styleFor(id).role) {
  const totals = {};
  for (const c of cards) {
    const ms = Math.max(0, c.end_ms - c.start_ms);
    totals[c.category] = (totals[c.category] || 0) + ms;
  }
  const focus = mergedTime(cards.filter((c) => roleFor(c.category) === "focus"));
  return {
    totals,
    focus: focus.total,
    distraction: mergedTime(cards.filter((c) => roleFor(c.category) === "distraction")).total,
    longest: focus.longest,
    total: mergedTime(cards).total,
    categoryTotal: Object.values(totals).reduce((a, b) => a + b, 0),
  };
}
/* Bounded search over GET /v1/activities. A page can hold few or no matches while
   more rows remain, so keep reading until enough matches, the end of the range,
   or the request budget; the returned cursor resumes exactly where this stopped. */
async function searchActivities(fetchPage, { q, category, fromMs, toMs, cursor = null, want = 50, maxRequests = 5 }) {
  const cards = [];
  let scanned = 0, requests = 0, next = cursor;
  do {
    const query = new URLSearchParams({ from_ms: String(fromMs), to_ms: String(toMs), limit: String(Math.min(200, want - cards.length)) });
    if (q) query.set("q", q);
    if (category) query.set("category", category);
    if (next) {
      query.set("after_start_ms", String(next.after_start_ms));
      query.set("after_id", next.after_id);
      query.set("epoch", String(next.epoch));
    }
    const page = await fetchPage(`/v1/activities?${query}`);
    requests++;
    scanned += page.scanned;
    cards.push(...page.cards);
    next = page.next_cursor ? { ...page.next_cursor, epoch: page.journal_epoch } : null;
  } while (next && cards.length < want && requests < maxRequests);
  return { cards, cursor: next, scanned, done: !next };
}

/* Only classification fields belong to this editor; unrelated settings stay current. */
function classificationRecovery(base, draft, latest, attemptedRevision = draft.revision) {
  const key = (w) => JSON.stringify([
    w.categories.map((c) => [c.id, c.name, c.color, c.role]),
    (w.rules || []).map((r) => [r.id, r.app ?? null, r.title_contains ?? null, r.category, r.enabled]),
  ]);
  return {
    kind: latest.revision === attemptedRevision ? "used" : key(base) === key(latest) ? "other" : "classification",
    candidate: { ...structuredClone(latest), categories: structuredClone(draft.categories), rules: structuredClone(draft.rules || []) },
  };
}

/* ---------- dialogs ---------- */
function modal(title, build) {
  modalEpoch++;
  const p = $("#modal-body");
  const content = el("div");
  const heading = el("h2", title);
  heading.id = "modal-title";
  p.replaceChildren(heading, content);
  // Late async builders retain a detached container after close, never a newer dialog.
  safe(() => build(content), content);
  if (!$("#modal").open) {
    // Async openers may still be disabled when showModal records native focus.
    $("#modal").dataset.returnKey = focusKey || document.activeElement?.dataset?.key || "";
    $("#modal").showModal();
  }
  // Announce the dialog by its title instead of landing on the close button.
  heading.tabIndex = -1;
  heading.focus();
}
function restoreModalFocus() {
  if (document.activeElement === document.body)
    restoreFocus($("#modal").dataset.returnKey, true);
}
function close() {
  $("#modal").close();
  $("#modal-body").replaceChildren();
  // Save handlers can rebuild the shell before the asynchronous close event.
  restoreModalFocus();
}
/* Specific confirmation; resolves false when dismissed with Escape or Cancel. */
function ask(title, lines, action, danger) {
  return new Promise((resolve) => {
    const d = $("#confirm");
    const body = $("#confirm-body");
    const heading = el("h2", title);
    heading.id = "confirm-title";
    body.replaceChildren(heading);
    [].concat(lines).forEach((line) =>
      add(body, typeof line === "string" ? el("p", line) : line),
    );
    const cancel = el("button", "取消");
    cancel.type = "button";
    const ok = el("button", action, danger ? "danger-fill" : "primary");
    ok.type = "button";
    let result = false;
    cancel.onclick = () => d.close();
    ok.onclick = () => {
      result = true;
      d.close();
    };
    add(body, add(el("div", null, "actions"), cancel, ok));
    d.onclose = () => {
      body.replaceChildren();
      resolve(result);
    };
    d.showModal();
    cancel.focus();
  });
}
function facts(items) {
  const dl = el("dl", null, "facts");
  items.forEach(([k, v]) => add(dl, el("dt", k), el("dd", v)));
  return dl;
}

/* ---------- sign-in ---------- */
function resetPrivateState() {
  renderSeq++;
  askEpoch++;
  activeAsk = refreshAsk = null;
  askState = null;
  if ($("#confirm").open) $("#confirm").close();
  if ($("#modal").open) $("#modal").close();
  $("#modal-body").replaceChildren();
  timelineInspector = inspectedCard = null;
  refreshOnClose = false;
  search = null;
  clearInterval(timelineWatch);
}
function gate() {
  root.replaceChildren();
  const main = el("main", null, "gate");
  main.id = "main";
  add(root, main);
  return main;
}
function keyHelp() {
  const box = panel("Owner 金鑰在哪裡？", "gate-help");
  add(
    box,
    el(
      "p",
      "PocketLog 沒有帳號註冊，也不會寄送密碼。每個伺服器只有一把 Owner 金鑰，由架設的人保管。",
    ),
  );
  const body = el("div", null, "help-body");
  body.id = "key-help-body";
  const guides = {
    deployer: () => [
      listOf(true, [
        [
          "在架設 PocketLog 的機器上，進入專案資料夾執行 ",
          code("./scripts/setup-status.sh production"),
          "（開發用實例改成 ",
          code("development"),
          "）。它只檢查狀態並列出下一步，不會讀取或顯示金鑰。",
        ],
        [
          "金鑰檔案是 ",
          code(".secrets/production_owner_token"),
          "（開發用為 ",
          code(".secrets/owner_token"),
          "）。還沒有這個檔案時，先執行 ",
          code("./scripts/init-secret.sh production"),
          "。",
        ],
        "在伺服器上私下開啟檔案，透過 SSH 終端機或密碼管理器把 64 個字元帶到這台裝置，貼進上方欄位。",
        "請勿把金鑰放進網址、截圖、聊天訊息、QR code 或 Git。",
      ]),
      rich(
        "p",
        "muted",
        "完整步驟請見 ",
        link("安裝文件 docs/SETUP.md", `${REPO}/blob/main/docs/SETUP.md`),
        "。",
      ),
    ],
    invited: () => [
      listOf(false, [
        "請直接向架設這個伺服器的人索取存取方式，並使用當面或加密的私人管道。這個頁面無法申請、重設或寄送金鑰。",
        "PocketLog 目前是單一擁有者的服務：持有 Owner 金鑰的人看到的是同一本日誌，也能管理裝置與刪除資料。",
        "想要只屬於自己的日誌，需要自己的 PocketLog 伺服器。",
        "裝置金鑰不能登入這個網站。「日誌」權限可供 Android app 或私人 API 工具使用；「僅收集」權限供上傳工具使用。",
      ]),
    ],
    phone: () => [
      listOf(true, [
        "先用 Owner 金鑰登入這個網站。",
        "到「設定 → 連線與裝置」建立一把日誌裝置金鑰；它只會顯示一次。",
        "在 Android app 填入這個網站的 HTTPS 網址與那把裝置金鑰。Owner 金鑰留作復原用，不必輸入到每支手機。",
      ]),
      el(
        "p",
        "連上之後，手機記錄仍需要你在手機上另外同意並授予系統權限；登入網站不會開始任何擷取。",
        "muted",
      ),
    ],
  };
  let chosen = "deployer";
  const tabs = el("div");
  const render = () => {
    tabs.replaceChildren(
      segmented(
        "你的情況",
        [
          ["deployer", "我自己架設的"],
          ["invited", "別人請我來用"],
          ["phone", "我要連接手機"],
        ],
        chosen,
        (id) => {
          chosen = id;
          render();
          tabs.querySelector('[aria-pressed="true"]').focus();
        },
      ),
    );
    body.replaceChildren(...guides[chosen]());
  };
  render();
  add(box, tabs, body);
  return box;
}
function login(reason) {
  resetPrivateState();
  const main = gate();
  const intro = el("div", null, "gate-intro");
  add(
    intro,
    brand(),
    el("h1", "生活中，值得收藏的小事。"),
    el(
      "p",
      `PocketLog 是放在你自己伺服器上的私人記錄。${PROMISE}`,
    ),
    listOf(
      false,
      [
        "日誌存放在你或你信任的人架設的伺服器，沒有公開頁面與使用分析。",
        "手機記錄、畫面內容與 AI 都是選用的，預設關閉、各自需要同意。",
        "登入後連接你的 AI 和 Android 手機，就能在時間軸回顧每一天。",
      ],
      "points",
    ),
  );
  const card = panel("開啟你的私人日誌", "gate-card");
  if (reason) {
    const status = el(
      "p",
      {
        expired:
          "工作階段已結束（最長八小時，伺服器重新啟動也會登出）。請再輸入一次 Owner 金鑰。",
        signedout: "已登出，這個瀏覽器不再保有工作階段。",
        cookie:
          "金鑰正確，但瀏覽器沒有保存工作階段。請確認使用 HTTPS 網址，且沒有封鎖這個網站的 Cookie。",
      }[reason],
      "callout",
    );
    status.setAttribute("role", "status");
    add(card, status);
  }
  if (location.protocol === "http:" && isPrivateHost(location.hostname))
    add(card, httpNote("http-note"));
  else if (!window.isSecureContext)
    add(
      card,
      el(
        "p",
        "這個網址不是 HTTPS。瀏覽器不會保存安全工作階段，金鑰也可能在傳輸中外洩；請改用 HTTPS，或在你的 VPN／私人網路裡使用。",
        "callout warn",
      ),
    );
  const form = el("form");
  form.noValidate = true;
  const key = field(
    form,
    "Owner 金鑰",
    "",
    "password",
    "架設伺服器時產生的 64 個字元，也是這個網站的密碼。",
  );
  key.autocomplete = "current-password";
  key.spellcheck = false;
  key.autocapitalize = "off";
  key.name = "owner-key";
  const error = el("p", null, "field-error");
  error.setAttribute("role", "alert");
  error.id = key.id + "-error";
  error.hidden = true;
  const fail = (text) => {
    error.textContent = text;
    error.hidden = false;
    key.setAttribute("aria-invalid", "true");
    key.setAttribute(
      "aria-describedby",
      `${key.id}-hint ${key.id}-error`,
    );
    key.focus();
  };
  const reveal = check(form, "顯示金鑰（確認周圍沒有人或螢幕分享）");
  reveal.onchange = () => (key.type = reveal.checked ? "text" : "password");
  const submit = el("button", "開啟我的日誌", "primary wide");
  submit.type = "submit";
  add(form, error, submit);
  add(
    card,
    form,
    el(
      "p",
      "金鑰只用來建立最長八小時的工作階段，不會寫進網址或瀏覽器儲存空間。用完可在「設定」登出。",
      "muted",
    ),
  );
  form.onsubmit = (e) => {
    e.preventDefault();
    safe(async () => {
      const token = key.value.trim();
      error.hidden = true;
      key.removeAttribute("aria-invalid");
      if (!token) return fail("請貼上 Owner 金鑰。不知道金鑰在哪裡？請看下方說明。");
      if (!/^[0-9a-f]{64}$/.test(token))
        return fail(
          "格式不符：Owner 金鑰是 64 個小寫十六進位字元（0–9、a–f）。請確認沒有漏字，或貼到別的密碼。",
        );
      key.value = "";
      reveal.checked = false;
      key.type = "password";
      submit.disabled = true;
      submit.textContent = "正在確認…";
      let r;
      try {
        r = await request("/session", { token });
      } catch (err) {
        return fail(err.message);
      } finally {
        submit.disabled = false;
        submit.textContent = "開啟我的日誌";
      }
      if (r.status === 401)
        return fail(
          "這把金鑰不正確。請重新從伺服器上的金鑰檔案複製；手機用的裝置金鑰無法登入網站。",
        );
      if (r.status === 429)
        return fail(
          "已有太多瀏覽器登入中（上限 32 個）。請在其他瀏覽器登出，或等待工作階段到期。",
        );
      if (r.status === 403)
        return fail(
          "瀏覽器把這次登入視為跨站請求。請直接在網址列開啟你的 PocketLog 網址再試。",
        );
      if (!r.ok) return fail(statusText[r.status] || `登入未完成 (${r.status})`);
      await boot(true);
    });
  };
  add(main, intro, add(el("div", null, "gate-side"), card, keyHelp()));
  if (reason) key.focus();
}
function unreachable(error) {
  resetPrivateState();
  const main = gate();
  const card = panel("暫時連不上你的日誌", "gate-card");
  add(
    card,
    el("p", error.message || "無法載入，請再試一次。"),
    listOf(false, [
      `確認這台裝置的網路，以及需要的${PRIVATE_NETWORKS}已連線。`,
      [
        "如果你是架設者：在伺服器上執行 ",
        code("./scripts/setup-status.sh production"),
        " 檢查服務是否正在回應。",
      ],
      "這個頁面沒有保存任何日誌內容；恢復連線後重新載入即可。",
    ]),
    btn("再試一次", start, "primary"),
  );
  add(main, add(el("div", null, "gate-intro"), brand()), card);
}
async function boot(fresh) {
  const seq = ++renderSeq;
  const r = await request("/v1/workspace");
  if (r.status === 401) return login(fresh ? "cookie" : undefined);
  if (!r.ok)
    throw new ApiError(statusText[r.status] || `無法載入 (${r.status})`, r.status);
  prefs = await r.json();
  ai = await api("/v1/ai");
  live(seq);
  if (!prefs.onboarding_version) onboard(0);
  else await shell();
}
function start() {
  return boot().catch((error) => {
    if (error !== STALE && error.status !== 401) unreachable(error);
  });
}

/* ---------- first run: promise → AI → phone (three skippable steps) ---------- */
async function finishOnboarding() {
  if (!prefs.onboarding_version)
    prefs = await api("/v1/workspace", { ...prefs, onboarding_version: 1 }, "PUT");
  page = "timeline";
  await shell();
}
function onboard(step) {
  renderSeq++;
  clearInterval(timelineWatch);
  const names = ["PocketLog 會做什麼", "連接你的 AI", "連接 Android 手機"];
  const main = gate();
  const p = el("section", null, "gate-card onboarding");
  add(main, p);
  const progress = el("progress");
  progress.max = 3;
  progress.value = step + 1;
  progress.setAttribute("aria-label", `初始設定：第 ${step + 1} 步，共 3 步`);
  add(p, brand(), el("p", `第 ${step + 1}／3 步 · ${names[step]}`, "eyebrow"), progress);
  const heading = (text) => {
    const h = el("h1", text);
    h.tabIndex = -1;
    add(p, h);
    requestAnimationFrame(() => h.focus({ preventScroll: true }));
  };
  const actions = el("div", null, "actions");
  const skip = keyed(btn("略過設定", finishOnboarding, "quiet"), "onboard-skip");
  if (step === 0) {
    heading("看過的畫面，自動變成可回顧的一天。");
    add(
      p,
      el("p", PROMISE, "lead"),
      add(
        el("div", null, "two-lists"),
        add(el("div"), el("h2", "會記下"), listOf(false, [
          "你允許的 app 什麼時候在使用",
          "這些 app 畫面上的文字和截圖（每 10 秒，以及切換 app 時）",
          "AI 整理出的活動卡：標題、一句摘要、分類",
        ])),
        add(el("div"), el("h2", "不會記下"), listOf(false, [
          "密碼、正在輸入的欄位和鎖定畫面",
          "你排除的應用程式",
          "受保護畫面（例如影音或銀行 app）的截圖，只留文字",
          "這台電腦的畫面：網站不會擷取任何東西",
        ])),
      ),
      el("p", "內容只存在你自己的伺服器。手機要在 app 裡另外同意，才會開始記錄。", "muted"),
    );
    add(actions, skip, keyed(btn("開始設定", () => onboard(1), "primary"), "onboard-next"));
  }
  if (step === 1) {
    heading("連接你的 AI");
    add(p, el("p", "AI 會看截圖和畫面文字，替每段時間寫標題和一句摘要。使用你自己的 API 金鑰，費用由提供者計算。沒有 AI 也能用，只是卡片只會顯示 app 名稱。"));
    const holder = el("div", null, "ai-holder");
    add(p, holder);
    if (ai.enabled) {
      add(holder, el("p", `已連接：${aiLabel(ai)}`, "state-line ok"));
      aiSwitches(holder, () => onboard(1));
      add(holder, row(keyed(btn("更換 AI", () => providerDialog(() => onboard(1))), "onboard-ai-change")));
    } else aiForm(holder, () => onboard(1));
    add(
      actions,
      btn("上一步", () => onboard(0)),
      keyed(btn(ai.enabled ? "下一步" : "稍後再說", () => onboard(2), ai.enabled ? "primary" : ""), "onboard-next"),
    );
  }
  if (step === 2) {
    heading("安裝並連接 Android app");
    add(
      p,
      listOf(true, [
        "在手機安裝 PocketLog Android app。",
        "在這裡建立一把手機金鑰；它只會顯示一次。",
        ["在 app 填入伺服器網址 ", code(location.origin), " 和那把金鑰，按「驗證並連線」。"],
        "在手機打開「自動記錄」，依照畫面一步步授予權限。",
      ]),
      row(keyed(btn("建立手機金鑰", devicesDialog, "primary"), "onboard-devices")),
      el("p", "之後可以在「設定 › 連線與裝置」管理或撤銷金鑰。", "muted"),
    );
    add(actions, btn("上一步", () => onboard(1)), keyed(btn("完成", finishOnboarding), "onboard-done"));
  }
  add(p, actions);
}
function deviceConnectionState(devices, now) {
  if (!devices) return { keys: null, authenticated: null, lastAuthenticatedMs: null };
  const active = devices.devices.filter((d) => d.scope === "journal" && d.expires_ms > now);
  const times = active.map((d) => d.last_authenticated_ms).filter((at) => Number.isFinite(at) && at > 0);
  return {
    keys: active.length,
    authenticated: times.length > 0 ? true : active.every((d) => Object.hasOwn(d, "last_authenticated_ms")) ? false : null,
    lastAuthenticatedMs: times.length ? Math.max(...times) : null,
  };
}
/* The single most important capture state for the timeline status line. Inputs
   are server facts: collection pause, device keys (with last authentication),
   AI settings and how many loaded cards still have evidence waiting for AI.
   null means "could not read"; an unknown fact never produces a warning. */
function captureStatus({ paused, devices, ai: config, pending = 0, now }) {
  if (paused === true) return { kind: "paused", text: "已暫停", target: "record" };
  if (devices) {
    const journal = devices.filter((d) => d.scope === "journal");
    const active = journal.filter((d) => d.expires_ms > now);
    if (journal.length && !active.length)
      return { kind: "expired", text: "金鑰失效，請重新連線", target: "devices" };
    if (!active.length) return { kind: "not-started", text: "尚未開始記錄", target: "start" };
    const state = deviceConnectionState({ devices }, now);
    if (state.authenticated === false)
      return { kind: "not-started", text: "尚未開始記錄", target: "start" };
    if (state.lastAuthenticatedMs && now - state.lastAuthenticatedMs > 86400000)
      return { kind: "stale", text: "手機超過一天沒有連線", target: "record" };
  }
  if (config && !config.enabled)
    return { kind: "no-ai", text: "AI 未設定，只顯示 app 名稱", target: "ai" };
  if (config && !config.auto_summarize)
    return { kind: "ai-off", text: "AI 自動整理已關閉", target: "ai" };
  if (pending > 0)
    return { kind: "processing", text: `AI 整理中（${pending} 張）`, target: "record" };
  return { kind: "ok", text: "記錄中", target: "record" };
}
function openStatusTarget(target) {
  if (target === "start") return onboard(2);
  return go("settings", `settings-${target}`);
}
function statusLine(status) {
  const b = keyed(btn("", () => openStatusTarget(status.target), `status-line status-${status.kind}`), "status-line");
  add(b, el("i", null, "dot"), el("span", status.text), el("span", "›", "status-go"));
  b.setAttribute("aria-label", `${status.text}。開啟${status.target === "start" ? "連接手機的步驟" : "設定"}`);
  return b;
}
/* ---------- application shell ---------- */
function restoreFocus(key, complete = false) {
  if (!key) return;
  let n = root.querySelector(`[data-key="${CSS.escape(key)}"]`);
  // The next/previous/today control can become disabled at the date boundary.
  if ((!n || n.disabled) && key.startsWith("day-"))
    n = root.querySelector('[data-key="day-date"]');
  // Only choose a fallback after activity cards have finished loading. A saved
  // category can remove the originating card from the active filter.
  if (!n && complete && key.startsWith("card-")) n = $("#main");
  if (n && !n.disabled) {
    n.focus({ preventScroll: true, focusVisible: false });
    // Rebuilding the shell can reset document scrolling. Keep restored controls
    // visible, including horizontal chips and phone bottom-bar clearance.
    if (n !== $("#main"))
      n.scrollIntoView({ block: "nearest", inline: "nearest" });
  }
}
function go(next, key) {
  page = next;
  if (key) focusKey = key;
  return shell();
}
function errorPanel(error) {
  const box = panel("這個畫面暫時無法載入", "state");
  box.setAttribute("role", "alert");
  add(
    box,
    el("p", error.message || "發生未預期的問題。"),
    el(
      "p",
      error.status === 0
        ? "已保存的記錄仍在伺服器上，沒有遺失。恢復連線後按重試。"
        : "已保存的記錄沒有受到影響。可以重試，或先切換到其他頁面。",
      "muted",
    ),
    row(
      keyed(btn("重試", shell, "primary"), "retry"),
      btn("查看說明", () => go("settings", "settings-help")),
    ),
  );
  return box;
}
function loadingPanel() {
  const box = el("div", null, "loading-state");
  box.setAttribute("role", "status");
  add(box, el("span", `正在載入${titles[page]}…`), el("i"), el("i"), el("i"));
  return box;
}
function emptyState(title, text, ...actions) {
  const box = el("div", null, "empty");
  add(box, el("h3", title), el("p", text));
  if (actions.some(Boolean)) add(box, row(...actions));
  return box;
}
const dated = ["timeline", "review"];
// The journal is retained permanently; this only bounds the date picker.
const JOURNAL_START = "2020-01-01";
function setDay(next) {
  day = next;
  return shell();
}
async function shell() {
  const seq = ++renderSeq;
  timelineInspector = null;
  if (page !== "timeline") inspectedCard = null;
  clearInterval(timelineWatch);
  const key = focusKey || document.activeElement?.dataset?.key || null;
  focusKey = null;
  mounted = [];
  refreshAsk = null;
  root.replaceChildren();
  const nav = el("nav");
  nav.setAttribute("aria-label", "主要導覽");
  const home = add(el("div", null, "nav-brand"), logo());
  add(nav, home);
  Object.keys(titles).forEach((id) => {
    const b = keyed(
      btn("", () => go(id), page === id ? "active" : ""),
      `nav-${id}`,
    );
    add(b, icon(id), el("span", titles[id]));
    b.setAttribute("aria-label", titles[id]);
    if (page === id) b.setAttribute("aria-current", "page");
    add(nav, b);
  });
  const main = el("main", null, `main-${page}`);
  main.id = "main";
  main.tabIndex = -1;
  add(root, nav, main);
  const head = el("header", null, "top");
  const heading = el("div", null, "top-title");
  add(heading, el("h1", titles[page]));
  const today = journalDate(),
    oldest = JOURNAL_START;
  add(head, heading);
  const controls = el("div", null, "row top-controls");
  if (dated.includes(page)) {
    const weekly = page === "review" && reviewMode === "week";
    const step = weekly ? 7 : 1;
    const unit = weekly ? "週" : "天";
    const prev = keyed(
      iconBtn("prev", `前一${unit}`, () => setDay(shift(day, -step) < oldest ? oldest : shift(day, -step))),
      "day-prev",
    );
    prev.disabled = day <= oldest;
    const next = keyed(
      iconBtn("next", `後一${unit}`, () => setDay(shift(day, step) > today ? today : shift(day, step))),
      "day-next",
    );
    next.disabled = weekly ? weekDates().at(-1) >= today : day >= today;
    // The native date input renders in the browser's locale (11/16/2026). Show a
    // zh-TW label and open the picker of a visually hidden input instead.
    const week = weekDates();
    // The short date/period stays readable in the sticky header (今天 shows as a button elsewhere).
    const label = weekly ? weekRange(week[0], week[6]) : shortDate(day);
    const picker = el("span", null, "date-picker");
    const input = el("input");
    input.type = "date";
    input.value = day;
    input.min = oldest;
    input.max = today;
    input.tabIndex = -1;
    input.setAttribute("aria-hidden", "true");
    const date = keyed(btn(label, () => {
      try {
        input.showPicker();
      } catch {
        input.focus();
      }
    }, "date-button"), "day-date");
    date.setAttribute("aria-label", `${label}，選擇日期`);
    input.onchange = () =>
      safe(async () => {
        if (input.value >= input.min && input.value <= input.max) {
          focusKey = "day-date";
          await setDay(input.value);
        }
      });
    add(picker, date, input);
    // 回顧: the 日/週 segment comes first, then the date navigation (same as Android).
    if (page === "review")
      add(controls, segmented("回顧範圍", [["day", "日"], ["week", "週"]], reviewMode, async (id) => {
        reviewMode = id;
        await shell();
      }, "review-mode"));
    add(controls, add(el("div", null, "date-nav"), prev, picker, next));
    if (day !== today && !(weekly && week.includes(today)))
      add(controls, keyed(btn(weekly ? "本週" : "今天", () => setDay(today)), "day-today"));
    add(
      controls,
      keyed(
        iconBtn("refresh", "重新整理", async () => {
          prefs = await api("/v1/workspace");
          ai = await api("/v1/ai");
          await shell();
        }, "refresh-btn"),
        "refresh",
      ),
    );
  }
  add(head, controls);
  const body = el("div", null, "page");
  body.setAttribute("aria-busy", "true");
  add(body, loadingPanel());
  add(main, head, body);
  restoreFocus(key);
  const content = el("div", null, `page page-${page}`);
  try {
    await pages[page](content, seq);
    live(seq);
    body.replaceWith(content);
    mounted.forEach((fn) => fn());
    if (!root.contains(document.activeElement) || document.activeElement === document.body)
      restoreFocus(key, true);
  } catch (e) {
    if (e === STALE || seq !== renderSeq) return;
    body.replaceWith(errorPanel(e));
    restoreFocus("retry");
  }
}
const EMPTY_DAY = {
  cards: [],
  activities: [],
  checkins: [],
  reflections: [],
  truncated: false,
};
/* Timeline reads for several journal days. Legacy review records are only read
   when a caller (the JSON export) needs them. */
async function loadDays(dates, seq, withReviews = true) {
  const today = journalDate();
  const days = await Promise.all(
    dates.map(async (date) => ({
      date,
      ...(date > today ? EMPTY_DAY : await api(pathFor(date))),
    })),
  );
  if (!withReviews) {
    live(seq);
    return { days, cards: days.flatMap((d) => d.cards), reviews: [], reviewsTruncated: false };
  }
  const [from] = windowFor(dates[0]);
  const last = dates.filter((d) => d <= today).at(-1) || dates[0];
  const [, to] = windowFor(last);
  const reviewResponse = await api(`/v1/reviews?from_ms=${from}&to_ms=${to}`);
  const reviews = reviewResponse.records;
  live(seq);
  return { days, cards: days.flatMap((d) => d.cards), reviews, reviewsTruncated: reviewResponse.truncated === true };
}
function truncatedNote(parent, days) {
  if (days.some((d) => d.truncated))
    add(
      parent,
      el(
        "p",
        "這段時間的活動太多，畫面只顯示了一部分；下面的統計只算已顯示的活動卡。",
        "callout warn",
      ),
    );
}
function visibleDates() {
  return weekDates().filter((d) => d >= JOURNAL_START);
}

/* ---------- timeline ---------- */
function watchTimeline(parent, days, seq) {
  const date = journalDate(), snapshot = days.find(d => d.date === date);
  if (!snapshot) return;
  const fingerprint = data => JSON.stringify([data.cards, data.truncated === true]);
  const baseline = fingerprint(snapshot);
  const banner = el("div", null, "callout");
  banner.hidden = true;
  const status = el("p");
  status.setAttribute("role", "status");
  let reading = false;
  const current = () => seq === renderSeq && page === "timeline" && banner.isConnected;
  const reload = keyed(btn("載入更新", async () => {
    if (!current()) return;
    const [nextPrefs, nextAi] = await Promise.all([
      api("/v1/workspace", null, undefined, current),
      api("/v1/ai", null, undefined, current),
    ]);
    if (!current()) return;
    prefs = nextPrefs; ai = nextAi;
    const sameDay = date === journalDate();
    const viewport = root.querySelector(".timeline-panel .timeline-window");
    const position = { x: scrollX, y: scrollY, top: viewport?.scrollTop, left: viewport?.scrollLeft };
    if (!sameDay) day = journalDate();
    focusKey = "timeline-content";
    const expectedRender = renderSeq + 1;
    await shell();
    if (renderSeq !== expectedRender || page !== "timeline" || !sameDay) return;
    const updated = root.querySelector(".timeline-panel .timeline-window");
    if (updated && position.top !== undefined) {
      updated.scrollTop = position.top; updated.scrollLeft = position.left;
    }
    window.scrollTo(position.x, position.y);
  }), "timeline-load-updates");
  add(banner, status, reload);
  add(parent, banner);
  const show = (message, warning = false) => {
    if (status.textContent !== message) status.textContent = message;
    banner.className = warning ? "callout warn" : "callout";
    banner.hidden = !message;
  };
  mounted.push(() => {
    timelineWatch = setInterval(async () => {
      if (!current() || reading || document.visibilityState !== "visible" ||
          navigator.onLine === false || $("dialog[open]") ||
          document.activeElement?.matches("input,textarea,select,[contenteditable=true]")) return;
      if (date !== journalDate()) {
        show("新的一天開始了；畫面仍是先前的日期。按「載入更新」前往今天。");
        return;
      }
      reading = true;
      try {
        const latest = await api(pathFor(date), null, undefined, current);
        if (!current()) return;
        show(fingerprint(latest) !== baseline
          ? "有新的活動卡或更新。準備好時按「載入更新」。" : "");
      } catch (error) {
        if (current()) show("暫時無法檢查新的活動；畫面保留先前的內容。可稍後按「載入更新」重試。", true);
      } finally { reading = false; }
    }, 120000);
  });
}
async function timelinePage(parent, seq) {
  const [data, control, devices] = await Promise.all([
    api(pathFor(day)),
    soft("/v1/collection"),
    soft("/v1/devices"),
  ]);
  live(seq);
  const days = [{ date: day, ...data }];
  const cards = data.cards;
  const pending = cards.filter((c) => c.pending_context === true).length;
  const status = captureStatus({
    paused: control ? control.paused : null,
    devices: devices ? devices.devices : null,
    ai,
    pending,
    now: Date.now(),
  });
  add(parent, statusLine(status));
  if (cards.length) add(parent, daySummary(cards));
  const tools = el("div", null, "timeline-tools");
  // One compact category filter; only categories present that day (plus an active one).
  const present = new Set(cards.map((c) => c.category));
  const filterSelect = keyed(el("select", null, "category-filter"), "category-filter");
  filterSelect.setAttribute("aria-label", "依分類篩選");
  [["", "全部分類"], ...prefs.categories.filter((c) => present.has(c.id) || c.id === filter).map((c) => [c.id, c.name])]
    .forEach(([id, name]) => {
      const o = el("option", name);
      o.value = id;
      add(filterSelect, o);
    });
  filterSelect.value = filter || "";
  filterSelect.onchange = () =>
    safe(async () => {
      filter = filterSelect.value || null;
      focusKey = "category-filter";
      await shell();
    });
  const searchToggle = keyed(
    iconBtn("search", search ? "關閉搜尋" : "搜尋活動", async () => {
      search = search ? null : { q: "", days: 7, category: "", cards: [], cursor: null, scanned: 0, done: false, error: "", ran: false };
      focusKey = search ? "search-q" : "search-toggle";
      await shell();
    }, search ? "active" : ""),
    "search-toggle",
  );
  searchToggle.setAttribute("aria-pressed", String(!!search));
  add(
    tools,
    filterSelect,
    add(
      el("div", null, "tool-buttons"),
      segmented("呈現方式", [["grid", "時間表"], ["list", "清單"]], currentView(), async (id) => {
        view = id;
        await shell();
      }, "view"),
      searchToggle,
    ),
  );
  // An empty day has nothing to filter; search stays reachable while it is open.
  if (cards.length || search) add(parent, tools);
  if (search) searchPanel(parent);
  truncatedNote(parent, days);
  watchTimeline(parent, days, seq);
  const grid = el("div", null, "dashboard"),
    left = el("section", null, "timeline-panel"),
    aside = el("aside");
  keyed(left, "timeline-content");
  left.tabIndex = -1;
  left.setAttribute("aria-label", "活動時間軸");
  aside.setAttribute("aria-label", "活動詳情");
  add(grid, left, aside);
  add(parent, grid);
  const shown = cards.filter((c) => !filter || c.category === filter);
  if (!cards.length) {
    const isToday = day === journalDate();
    const action =
      status.target === "start"
        ? keyed(btn("連接手機開始記錄", () => onboard(2), "primary"), "empty-start")
        : status.kind === "no-ai"
          ? keyed(btn("設定 AI", () => go("settings", "settings-ai"), "primary"), "empty-ai")
          : null;
    add(
      left,
      emptyState(
        isToday ? "今天還沒有活動卡" : "這一天沒有活動卡",
        action
          ? PROMISE
          : isToday
            ? `${PROMISE}手機會在背景記錄，AI 整理後會出現在這裡。`
            : "這天沒有收到手機的記錄。",
        action,
      ),
    );
  } else if (!shown.length) {
    add(
      left,
      emptyState(
        `這一天沒有「${styleFor(filter).name}」的活動`,
        "其他分類仍有記錄。",
        btn(
          "顯示全部分類",
          async () => {
            filter = null;
            focusKey = "category-filter";
            await shell();
          },
          "primary",
        ),
      ),
    );
  } else if (currentView() === "list") {
    // Separate cards, not rows inside one panel.
    left.classList.add("list-mode");
    activityList(left, collapseAppOnly(shown));
  } else {
    const viewport = el("div", null, "timeline-window");
    viewport.tabIndex = 0;
    viewport.setAttribute("role", "group");
    viewport.setAttribute("aria-label", `${dayTitle(day)}的時間軸，可上下捲動`);
    const lane = track(days[0]);
    add(viewport, lane);
    add(left, viewport);
    const first = shown[0];
    const [start, end] = fullDayBounds(day);
    const anchor = first
      ? first.start_ms
      : day === journalDate()
        ? Date.now()
        : start;
    mounted.push(
      () =>
        (viewport.scrollTop = Math.max(0, lane.axis.y(Math.max(start, Math.min(end - 1, anchor))) - 48)),
    );
  }
  installTimelineInspector(aside, left, collapseAppOnly(cards.filter((c) => !filter || c.category === filter)));
}
/* Recorded time and one stacked category bar; the labels are the legend. */
function daySummary(cards) {
  const m = metrics(cards);
  const box = el("section", null, "day-summary");
  box.setAttribute("aria-label", "這一天的摘要");
  add(box, rich("p", "day-total", "已記錄 ", el("strong", duration(m.total))));
  const entries = Object.entries(m.totals).filter(([, ms]) => ms > 0).sort((a, b) => b[1] - a[1]);
  if (!entries.length) return box;
  const top = entries.slice(0, 4);
  const rest = entries.slice(4).reduce((n, [, ms]) => n + ms, 0);
  const parts = [...top.map(([id, ms]) => ({ name: styleFor(id).name, color: styleFor(id).color, ms })),
    ...(rest ? [{ name: "其他", color: "#b7c2b5", ms: rest }] : [])];
  const bar = el("div", null, "stack day-stack");
  bar.setAttribute("role", "img");
  bar.setAttribute("aria-label", parts.map((x) => `${x.name} ${duration(x.ms)}`).join("、"));
  const legend = el("ul", null, "stack-legend");
  legend.setAttribute("aria-hidden", "true");
  for (const part of parts) {
    const seg = el("i");
    seg.style.setProperty("flex-grow", String(part.ms));
    seg.style.setProperty("--cat", part.color);
    add(bar, seg);
    const item = el("li");
    item.style.setProperty("--cat", part.color);
    add(item, el("i", null, "dot"), el("span", part.name), el("small", duration(part.ms)));
    add(legend, item);
  }
  add(box, bar, legend);
  return box;
}
/* Consecutive app-only cards of one app with at most five minutes between them become
   one card `N 次 · 時長`; the detail lists every visit. Never mutates the input. */
function collapseAppOnly(cards, gapMs = 300000) {
  const out = [];
  for (const c of [...cards].sort((a, b) => a.start_ms - b.start_ms)) {
    const last = out.at(-1);
    const lastCard = last?.members ? last.members.at(-1) : last;
    const joinable = (x) => x && x.source === "app_only" && x.pending_context !== true;
    if (joinable(c) && joinable(lastCard) && lastCard.app === c.app && c.start_ms - Math.max(lastCard.end_ms, lastCard.start_ms) <= gapMs) {
      if (last.members) {
        last.members.push(c);
        last.end_ms = Math.max(last.end_ms, c.end_ms);
      } else {
        out[out.length - 1] = {
          ...last, members: [last, c], end_ms: Math.max(last.end_ms, c.end_ms),
          protected_screen: last.protected_screen === true || c.protected_screen === true,
        };
      }
      continue;
    }
    out.push(c);
  }
  return out;
}
function cardKey(c) {
  return `card-${c.id}-${c.start_ms}`;
}
function openCard(c) {
  if (page === "timeline" && inspectorMedia.matches && timelineInspector) {
    if (timelineInspector.key === cardKey(c)) timelineInspector.reset(true);
    else timelineInspector.open(c, true);
  } else editCard(c);
}
function installTimelineInspector(aside, left, cards) {
  const context = `${day}:${filter || ""}`;
  const seq = renderSeq;
  const mark = (key) => {
    left.querySelectorAll('button[data-key^="card-"]').forEach((b) => {
      const selected = b.dataset.key === key;
      b.classList.toggle("is-selected", selected);
      if (inspectorMedia.matches) b.setAttribute("aria-pressed", String(selected));
      else b.removeAttribute("aria-pressed");
      if (selected) b.setAttribute("aria-controls", "activity-inspector");
      else b.removeAttribute("aria-controls");
    });
  };
  const reset = (focus) => {
    const origin = inspectedCard?.key;
    inspectedCard = null;
    mark(null);
    aside.classList.remove("inspecting");
    aside.replaceChildren(
      add(el("div", null, "inspector-hint"),
        el("p", cards.length ? "點一張活動卡，在這裡查看摘要、改分類或修改文字。" : "活動卡出現後，點一下就能在這裡查看與修改。", "muted")),
    );
    if (focus) restoreFocus(origin, true);
  };
  const open = (c, focus) => {
    inspectedCard = { key: cardKey(c), context };
    mark(cardKey(c));
    aside.classList.add("inspecting");
    const box = el("section", null, "activity-inspector");
    box.id = "activity-inspector";
    box.style.setProperty("--cat", styleFor(c.category).color);
    const current = () => seq === renderSeq && box.isConnected;
    const title = cardDetail(box, c, {
      closer: keyed(iconBtn("close", "關閉活動詳情", () => reset(true), "quiet-icon"), "inspector-close"),
      current,
      saved: async (key) => {
        focusKey = key;
        await shell();
      },
    });
    aside.replaceChildren(box);
    if (focus) {
      title.focus({ preventScroll: true });
      title.scrollIntoView({ block: "nearest", inline: "nearest" });
    }
  };
  timelineInspector = { open, reset, get key() { return inspectedCard?.key; }, get ownsFocus() { return aside.contains(document.activeElement); } };
  const retained = inspectedCard?.context === context && inspectorMedia.matches
    ? cards.find((c) => cardKey(c) === inspectedCard.key && (!filter || c.category === filter))
    : null;
  if (retained) open(retained, false);
  else reset(false);
}
/* Card source labels in plain words (spec: AI 整理 / 畫面文字 / 只有 app / 你改過). */
function contentSource(source) {
  switch (source) {
    case "user": return { label: "你改過", detail: "這是你改過的內容；之後的自動整理不會蓋掉它。" };
    case "ai": return { label: "AI 整理", detail: "AI 根據截圖和畫面文字整理，可能有誤，可以直接改。" };
    case "local_excerpt": return { label: "畫面文字", detail: "從畫面文字摘錄，沒有經過 AI。" };
    case "app_only": return { label: "只有應用程式", detail: "只知道用了哪個應用程式、用了多久。" };
    default: return { label: "來源不明", detail: "這張卡片沒有標示來源；請自行核對。" };
  }
}
const PENDING_LABEL = "AI 整理中";
const PENDING_HELP = "伺服器還有這段時間的截圖等著整理，通常在活動結束十幾分鐘後完成。";
const PROTECTED_HELP = "這個 app 不允許截圖，所以只用 app 名稱和畫面文字整理。";
/* Only exceptions are labelled; the normal AI 整理 source has no label. Compact
   rows show at most one, by priority; AI 整理中 replaces 只有 app. */
function cardBadges(c, compact = true) {
  const pending = c.pending_context === true;
  const all = [
    pending ? PENDING_LABEL : null,
    c.protected_screen === true ? (compact ? "受保護畫面" : "受保護畫面（無截圖）") : null,
    c.source && c.source !== "ai" && !(pending && c.source === "app_only") ? contentSource(c.source).label : null,
  ].filter(Boolean);
  return compact ? all.slice(0, 1) : all;
}
function badgeClass(label) {
  return label === PENDING_LABEL ? "badge pending" : label.startsWith("受保護畫面") ? "badge protected" : "badge";
}
const APP_ONLY_SUMMARY = "只知道使用了這個應用程式";
function catChip(category) {
  const chip = add(el("span", null, "cat-chip"), el("i", null, "dot"), document.createTextNode(category.name));
  chip.style.setProperty("--cat", category.color);
  return chip;
}
function cardSummary(c) {
  if (c.summary) return c.summary;
  if (c.pending_context === true) return "正在整理這段活動…";
  return c.source === "app_only" ? APP_ONLY_SUMMARY : "";
}
function eventLabel(c) {
  return [cardTimes(c), cardTitle(c), styleFor(c.category).name, cardLength(c), ...cardBadges(c)].join("，");
}
function track(data) {
  const lane = el("div", null, "timeline");
  const [start, end] = fullDayBounds(data.date);
  const ticks = timelineTicks(data.date);
  const today = data.date === journalDate();
  const visible = collapseAppOnly(data.cards.filter((c) => !filter || c.category === filter));
  const midnight = new Date(data.date + "T12:00:00");
  midnight.setDate(midnight.getDate() + 1);
  midnight.setHours(0, 0, 0, 0);
  const axis = timeAxis([...ticks.map((t) => t.at), end], visible, { now: today ? Date.now() : null, splitAt: [midnight.getTime()] });
  lane.style.setProperty("height", `${axis.height}px`);
  for (const tick of ticks) {
    const seg = axis.segments.find((x) => x.kind === "time" && tick.at >= x.from && tick.at < x.to);
    if (!seg) continue;
    const y = axis.y(tick.at);
    const rule = el("span", null, "hour-rule");
    rule.style.setProperty("--y", `${y}px`);
    // A label right after a collapsed gap sits below the gap instead of over it.
    const hour = el("span", tick.label, seg.from === tick.at && seg.top > 0 ? "hour after-gap" : "hour");
    hour.style.setProperty("--y", `${y}px`);
    if (tick.offset) add(hour, el("small", tick.offset));
    add(lane, rule, hour);
  }
  if (axis.segments[0]?.kind === "gap") {
    const first = el("span", ticks[0].label, "hour");
    first.style.setProperty("--y", "0px");
    add(lane, first);
  }
  for (const seg of axis.segments.filter((x) => x.kind === "gap")) {
    const gap = el("div", null, "axis-gap");
    gap.style.setProperty("--y", `${seg.top}px`);
    gap.style.setProperty("--h", `${seg.height}px`);
    gap.title = `${time(seg.from)}–${time(seg.to)} 沒有記錄`;
    gap.setAttribute("role", "img");
    gap.setAttribute("aria-label", gap.title);
    add(lane, gap);
  }
  // Mark where the next calendar day begins inside the 04:00–04:00 journal day.
  if (midnight.getTime() > start && midnight.getTime() < end) {
    const mark = el("div", null, "midnight");
    mark.style.setProperty("--y", `${axis.y(midnight.getTime())}px`);
    add(mark, el("span", "隔天凌晨"));
    add(lane, mark);
  }
  // Columns split only for true overlaps (a one-minute floor keeps zero-length
  // cards apart). The minimum height then shrinks to the gap before the next
  // card in the same column, so adjacent activities never force a split.
  for (const group of timelineGroups(visible, 60000, 8)) {
    if (group.collapsed) {
      const first = group.placed[0].card;
      const label = `${group.placed.length} 筆活動 · 改用清單`;
      const b = keyed(btn(`${group.placed.length} 筆 · 清單`, async () => {
        view = "list";
        focusKey = cardKey(first);
        await shell();
      }, "event event-density"), `dense-${cardKey(first)}`);
      b.style.setProperty("--top", `${axis.y(group.start)}px`);
      b.style.setProperty("--height", `${Math.max(26, axis.y(group.end) - axis.y(group.start))}px`);
      b.style.setProperty("--cat", "var(--accent)");
      b.style.setProperty("--lane", 0);
      b.style.setProperty("--lanes", 1);
      b.setAttribute("aria-label", `${time(group.start)}–${time(group.end)}，${label}。活動密集，清單可逐筆查看與修改。`);
      b.title = b.getAttribute("aria-label");
      add(lane, b);
      continue;
    }
    const nextInLane = new Map();
    for (const item of [...group.placed].reverse()) {
      item.next = nextInLane.get(item.lane);
      nextInLane.set(item.lane, item.card.start_ms);
    }
    for (const { card: c, lane: index, lanes, next: following } of group.placed) {
      const b = keyed(btn("", () => openCard(c), "event"), cardKey(c));
      const top = axis.y(c.start_ms);
      const room = following === undefined ? Infinity : axis.y(following) - top;
      const height = Math.max(axis.y(c.end_ms) - top, Math.max(18, Math.min(40, room)));
      const category = styleFor(c.category);
      b.style.setProperty("--top", `${top}px`);
      b.style.setProperty("--height", `${height}px`);
      b.style.setProperty("--cat", category.color);
      b.style.setProperty("--lane", index);
      b.style.setProperty("--lanes", lanes);
      b.setAttribute("aria-label", eventLabel(c));
      b.title = eventLabel(c).replaceAll("，", " · ");
      if (height >= 46) {
        add(b, add(el("span", null, "event-head"),
          el("span", `${cardTimes(c)} · ${cardLength(c)}`, "event-time"),
          c.pending_context === true ? null : catChip(category),
          ...cardBadges(c).map((x) => el("span", x, badgeClass(x)))));
        add(b, el("strong", cardTitle(c)));
        if (height >= 80 && cardSummary(c)) add(b, el("span", cardSummary(c), "event-summary"));
      } else {
        b.classList.add("compact");
        const wide = lanes === 1;
        const badge = cardBadges(c)[0];
        if (badge) {
          const kind = badge === PENDING_LABEL ? "pending" : badge.startsWith("受保護畫面") ? "protected" : "plain";
          const mark = el("span", null, `badge-icon ${kind}${wide ? " labelled" : ""}`);
          if (kind !== "plain") add(mark, icon(kind === "pending" ? "clock" : "shield"));
          if (wide || kind === "plain") add(mark, el("span", badge));
          mark.title = badge;
          mark.setAttribute("aria-label", badge);
          mark.setAttribute("role", "img");
          add(b, mark);
        }
        add(b, el("strong", cardTitle(c)));
        if (wide && c.pending_context !== true) add(b, catChip(category));
        if (height >= 18) add(b, el("small", `${cardTimes(c)} · ${cardLength(c)}`));
      }
      add(lane, b);
    }
  }
  if (today) {
    const now = el("div", null, "now");
    now.setAttribute("aria-hidden", "true");
    now.style.setProperty("--top", `${axis.y(Date.now())}px`);
    add(now, el("span", "現在"));
    add(lane, now);
  }
  lane.axis = axis;
  return lane;
}
function searchPanel(parent) {
  const box = el("section", null, "search-panel");
  box.setAttribute("aria-label", "搜尋活動");
  const form = el("form", null, "search-form");
  form.setAttribute("role", "search");
  const cell = (cls) => add(form, el("div", null, cls ? `search-field ${cls}` : "search-field")).lastChild;
  const q = keyed(field(cell("search-query"), "關鍵字", search.q, "search"), "search-q");
  q.maxLength = 100;
  q.required = true;
  const hint = el("p", "搜尋卡片標題、摘要和 app；不搜尋截圖或畫面原文。", "hint");
  hint.id = q.id + "-hint";
  q.setAttribute("aria-describedby", hint.id);
  const days = keyed(select(cell(), "期間", [["1", "今天與昨天"], ["7", "最近 7 天"], ["30", "最近 30 天"]], String(search.days)), "search-days");
  const category = keyed(select(cell(), "分類", [["", "全部分類"], ...prefs.categories.map((c) => [c.id, c.name])], search.category), "search-category");
  const run = async (more) => {
    const text = q.value.trim();
    if (!text) {
      search.error = "請輸入關鍵字。";
      focusKey = "search-q";
      return shell();
    }
    if (!more) {
      const toMs = Date.now();
      Object.assign(search, { q: text, days: Number(days.value), category: category.value, cards: [], cursor: null, scanned: 0, done: false, toMs, fromMs: toMs - Number(days.value) * 86_400_000 });
    }
    const owner = search;
    owner.error = "";
    try {
      const result = await searchActivities((path) => api(path, undefined, "GET", () => search === owner), {
        q: search.q, category: search.category, fromMs: search.fromMs, toMs: search.toMs, cursor: more ? search.cursor : null,
      });
      owner.cards.push(...result.cards);
      Object.assign(owner, { cursor: result.cursor, scanned: search.scanned + result.scanned, done: result.done, ran: true });
    } catch (e) {
      if (e === STALE) throw e;
      owner.error =
        e.status === 409 ? "日誌在搜尋期間被刪除或重設；請重新搜尋。"
        : e.status === 404 || e.status === 405 ? "這台伺服器版本還不支援搜尋；請更新 PocketLog 伺服器。"
        : e.message || "搜尋失敗，請重試。";
      if (e.status === 409) Object.assign(owner, { cards: [], cursor: null, done: false, ran: false });
    }
    if (search !== owner) return;
    focusKey = more ? "search-more" : "search-results";
    await shell();
  };
  form.onsubmit = (event) => {
    event.preventDefault();
    safe(() => run(false));
  };
  const submit = keyed(el("button", "搜尋", "primary"), "search-submit");
  submit.type = "submit";
  add(form, submit);
  add(box, form, hint);
  if (search.error) {
    const error = el("p", search.error, "error");
    error.setAttribute("role", "alert");
    add(box, error);
  }
  if (search.ran) {
    const results = keyed(el("div", null, "search-results"), "search-results");
    results.tabIndex = -1;
    const status = el(
      "p",
      search.cards.length
        ? `找到 ${search.cards.length} 張卡片${search.done ? "，已搜尋整個期間。" : "，期間內還有尚未檢查的記錄。"}`
        : search.done ? "這段期間沒有符合的活動。" : `已檢查 ${search.scanned} 筆記錄，尚未找到符合的活動；可以繼續搜尋。`,
      "muted",
    );
    status.setAttribute("role", "status");
    add(results, status);
    if (search.cards.length) {
      const list = el("ol", null, "activity-list");
      search.cards.forEach((c) => {
        const style = styleFor(c.category);
        const b = keyed(btn("", () => editCard(c), "activity"), "search-" + cardKey(c));
        b.style.setProperty("--cat", style.color);
        b.setAttribute("aria-label", `${shortDate(dateKey(new Date(c.start_ms)))} ${eventLabel(c)}`);
        add(
          b,
          add(
            el("span", null, "activity-head"),
            el("span", `${shortDate(dateKey(new Date(c.start_ms)), false)} ${cardTimes(c)} · ${cardLength(c)}`, "activity-time"),
            catChip(style),
            ...cardBadges(c).map((x) => el("span", x, badgeClass(x))),
          ),
          el("strong", cardTitle(c), "activity-title"),
          cardSummary(c) ? el("span", cardSummary(c), "activity-summary") : null,
        );
        add(list, add(el("li"), b));
      });
      add(results, list);
    }
    if (!search.done) add(results, keyed(btn("繼續搜尋", () => run(true)), "search-more"));
    add(box, results);
  }
  add(parent, box);
}
/* Package names are what the phone reports; show a readable name instead. */
const APP_NAMES = {
  "com.android.chrome": "Chrome",
  "org.mozilla.firefox": "Firefox",
  "com.google.android.youtube": "YouTube",
  "com.google.android.gm": "Gmail",
  "com.google.android.apps.maps": "Google 地圖",
  "com.google.android.apps.docs": "Google 雲端硬碟",
  "com.google.android.apps.photos": "Google 相簿",
  "com.google.android.calendar": "Google 日曆",
  "com.google.android.keep": "Keep",
  "com.instagram.android": "Instagram",
  "com.facebook.katana": "Facebook",
  "com.facebook.orca": "Messenger",
  "com.whatsapp": "WhatsApp",
  "jp.naver.line.android": "LINE",
  "org.telegram.messenger": "Telegram",
  "com.discord": "Discord",
  "com.Slack": "Slack",
  "com.twitter.android": "X",
  "com.reddit.frontpage": "Reddit",
  "com.zhiliaoapp.musically": "TikTok",
  "com.ss.android.ugc.trill": "TikTok",
  "com.spotify.music": "Spotify",
  "com.netflix.mediaclient": "Netflix",
  "com.microsoft.teams": "Teams",
  "com.notion.id": "Notion",
  "md.obsidian": "Obsidian",
};
const PACKAGE_NOISE = new Set(["com", "org", "net", "io", "tw", "jp", "app", "apps", "android", "mobile", "client", "www"]);
function appName(pkg) {
  if (!pkg) return "未知的 app";
  if (APP_NAMES[pkg]) return APP_NAMES[pkg];
  const parts = pkg.split(".").filter((p) => p && !PACKAGE_NOISE.has(p.toLowerCase()));
  const last = parts.at(-1) || pkg;
  return last.charAt(0).toUpperCase() + last.slice(1);
}
/* An app-only card's stored title is the package name; show the app's name. */
function cardTitle(c) {
  return !c.title || c.title === c.app ? appName(c.app) : c.title;
}
/* Shared card structure: row 1 時間 · 時長 + category + one exception badge;
   row 2 title; row 3 one-line summary. */
function activityList(parent, cards) {
  const list = el("ol", null, "activity-list");
  let divided = false;
  cards.forEach((c) => {
    // A journal day runs 04:00–04:00; mark where the next calendar day begins.
    if (!divided && dateKey(new Date(c.start_ms)) > day) {
      divided = true;
      const divider = el("li", null, "list-divider");
      add(divider, el("span", "隔天凌晨"));
      add(list, divider);
    }
    const category = styleFor(c.category);
    const b = keyed(btn("", () => openCard(c), "activity"), cardKey(c));
    b.style.setProperty("--cat", category.color);
    b.setAttribute("aria-label", eventLabel(c));
    const summary = cardSummary(c);
    add(
      b,
      add(
        el("span", null, "activity-head"),
        el("span", `${cardTimes(c)} · ${cardLength(c)}`, "activity-time"),
        c.pending_context === true ? null : catChip(category),
        ...cardBadges(c).map((x) => el("span", x, badgeClass(x))),
      ),
      el("strong", cardTitle(c), "activity-title"),
      summary ? el("span", summary, "activity-summary") : null,
    );
    add(list, add(el("li"), b));
  });
  add(parent, list);
}
/* One write per card; manual edits always win over later automatic processing. */
async function saveCard(c, changes, current) {
  if (cardSaves.has(c.id))
    throw Error("這張卡片的上一次修改還在儲存，請稍候再試。");
  const submitted = { id: c.id, title: c.title, summary: c.summary, category: c.category, ...changes };
  cardSaves.add(c.id);
  try {
    await api("/v1/cards", submitted, undefined, current);
  } catch (error) {
    if (current()) throw error;
    return false;
  } finally {
    cardSaves.delete(c.id);
  }
  Object.assign(c, submitted, { source: "user" });
  // Saved user edits always win, so open search results can reflect them directly.
  if (search) search.cards = search.cards.map((x) => (x.id === c.id ? { ...x, ...submitted, source: "user" } : x));
  return true;
}
/* Card detail shared by the desktop inspector and the card dialog. Returns the
   element that should receive focus when the detail opens. */
const DISCLAIMER = "AI 整理，可能有誤，請核對。";
function cardDetail(box, c, { current, saved, heading = true, closer = null }) {
  let title = null;
  if (heading) {
    // Read-only; text editing lives behind 修改文字. The close button shares the title row.
    title = el("h2", cardTitle(c), "detail-title");
    title.tabIndex = -1;
    add(box, add(el("div", null, "detail-head"), title, closer));
  }
  // The date is already in the page header, so the meta line keeps time and length together.
  const meta = el("p", null, "detail-meta");
  add(meta, document.createTextNode(`${appName(c.app)} · `), el("span", `${cardTimes(c)} · ${cardLength(c)}`, "nowrap"));
  add(box, meta);
  const badges = cardBadges(c, false);
  if (badges.length) add(box, add(el("div", null, "badges"), ...badges.map((x) => el("span", x, badgeClass(x)))));
  if (c.members) {
    // A collapsed group lists every visit; each opens its own detail.
    const visits = el("ol", null, "visit-list");
    visits.setAttribute("aria-label", "每一次使用");
    c.members.forEach((m) => {
      const v = keyed(btn(`${cardTimes(m)} · ${cardLength(m)}`, () => {
        if (page === "timeline" && inspectorMedia.matches && timelineInspector) timelineInspector.open(m, true);
        else editCard(m);
      }, "visit"), `visit-${m.id}`);
      add(visits, add(el("li"), v));
    });
    add(box, visits);
  }
  add(box, el("p", cardSummary(c) || "這張卡片還沒有摘要。", c.summary ? "detail-summary" : "detail-summary muted"));
  if (c.source === "ai" || c.source === "local_excerpt") add(box, el("p", DISCLAIMER, "hint"));
  if (c.protected_screen === true) add(box, el("p", PROTECTED_HELP, "hint"));
  if (c.pending_context === true) add(box, el("p", PENDING_HELP, "hint"));
  const picker = el("div", null, "category-picker");
  picker.setAttribute("role", "group");
  picker.setAttribute("aria-label", "分類，點一下就會儲存");
  prefs.categories.forEach((cat) => {
    const chosen = cat.id === c.category;
    const b = keyed(btn(cat.name, async () => {
      if (cat.id === c.category) return;
      // A collapsed group recategorises every visit.
      for (const target of c.members || [c])
        if (!(await saveCard(target, { category: cat.id }, current)) || !current()) return;
      if (c.members) c.category = cat.id;
      await saved(`pick-${cat.id}`);
    }, chosen ? "chip active" : "chip"), `pick-${cat.id}`);
    b.setAttribute("aria-pressed", String(chosen));
    b.prepend(chosen ? add(el("span", null, "chip-check"), icon("check")) : el("i", null, "dot"));
    b.style.setProperty("--cat", cat.color);
    add(picker, b);
  });
  add(box, el("h3", "分類", "picker-title"), el("p", "你選的分類會優先於自動分類。點一下就會儲存。", "hint picker-help"), picker);
  const editor = el("div", null, "text-editor");
  const toggle = keyed(btn("", () => {
    toggle.hidden = true;
    const t = field(editor, "標題", cardTitle(c));
    const s = field(editor, "摘要", c.summary, "textarea");
    t.maxLength = 200;
    s.maxLength = 1000;
    add(
      editor,
      add(
        el("div", null, "actions"),
        btn("取消", () => {
          editor.replaceChildren();
          toggle.hidden = false;
          toggle.focus();
        }),
        keyed(btn("儲存文字", async () => {
          const submitted = { title: t.value.trim(), summary: s.value };
          if (!submitted.title) throw Error("標題不能空白。");
          if (!(await saveCard(c, submitted, current)) || !current()) return;
          if (t.value.trim() !== submitted.title || s.value !== submitted.summary) {
            notice("先前的文字已儲存。之後輸入的修改還沒儲存；確認後再按一次「儲存文字」。", false, t);
            return;
          }
          await saved("edit-text");
        }, "primary"), "save-text"),
      ),
    );
    t.focus();
  }, "icon-text"), "edit-text");
  add(toggle, icon("edit"), el("span", "修改文字"));
  // A collapsed group has no single text to edit; each visit opens its own detail.
  if (!c.members) add(box, toggle, editor);
  const thumbs = el("div", null, "thumbs");
  add(box, thumbs);
  cardThumbs(thumbs, c, current).catch(() => {});
  return title;
}
/* Replay thumbnails exist only when the phone separately kept them (24 hours). */
async function cardThumbs(holder, c, current) {
  const now = Date.now();
  const from = c.start_ms, to = Math.min(c.end_ms, now);
  if (to <= from || now - from > 23.5 * 3600000) return;
  const list = await api(`/v1/replay?from_ms=${from}&to_ms=${to}`, null, undefined, current);
  const images = (list.images || []).filter((i) => i.app === c.app).slice(0, 6);
  if (!images.length || !current()) return;
  const grid = el("div", null, "thumb-grid");
  images.forEach((i) => {
    const img = el("img");
    img.src = `/v1/replay/${encodeURIComponent(i.id)}/image`;
    img.alt = `${appName(i.app)} ${time(i.at_ms)} 的截圖`;
    img.loading = "lazy";
    add(grid, img);
  });
  add(holder, el("h3", "截圖"), grid, el("p", "手機另外同意保存的回放縮圖，保留 24 小時。", "hint"));
}
function editCard(c) {
  modal(cardTitle(c), (p) => {
    const seq = renderSeq;
    const current = () => seq === renderSeq && p.isConnected && $("#modal").open;
    const render = () => {
      p.replaceChildren();
      $("#modal-title").textContent = cardTitle(c);
      cardDetail(p, c, {
        current,
        heading: false,
        saved: async (key) => {
          // The timeline behind the dialog refreshes once the dialog closes.
          refreshOnClose = true;
          render();
          p.querySelector(`[data-key="${CSS.escape(key)}"]`)?.focus();
          notice("已儲存", false, p);
        },
      });
    };
    render();
  });
}

/* ---------- 回顧: day / week overview, AI recap and asking the AI ---------- */
function reviewRange() {
  const today = journalDate();
  const dates = reviewMode === "week" ? visibleDates().filter((d) => d <= today) : [day];
  return { dates, from: windowFor(dates[0])[0], to: windowFor(dates.at(-1))[1] };
}
async function reviewPage(parent, seq) {
  const { from, to } = reviewRange();
  const [week, overview, saved] = await Promise.all([
    reviewMode === "week" ? loadDays(visibleDates(), seq, false) : null,
    soft(`/v1/overview?from_ms=${from}&to_ms=${to}`),
    soft(`/v1/briefs?from_ms=${from}&to_ms=${to}`),
  ]);
  live(seq);
  // Two balanced columns on wide screens; on phones the columns dissolve and
  // the cards follow the reading order AI 回顧 → 這週每天 → 時間分配 → 常用 app → 主題 → 問 AI.
  const layout = el("div", null, "review-layout");
  const main = el("div", null, "review-col"), side = el("div", null, "review-col");
  add(layout, main, side);
  add(parent, layout);
  briefCard(main, saved, from, to);
  if (week) weekBars(main, week.days);
  timeSplit(side, overview);
  topApps(side, overview);
  topics(side, overview);
  askPanel(main, from, to);
}
function askDisclosure(host) {
  return `會把問題和這段時間的活動卡送到 ${host}（不含截圖）。`;
}
function askExamples(mode) {
  return mode === "week"
    ? ["這週花最多時間在哪？", "這週學了什麼？", "這週有哪些分心的時段？"]
    : ["今天花最多時間在哪？", "今天學了什麼？", "有哪些分心的時段？"];
}
function periodWord() {
  if (reviewMode === "week") return "這週";
  return day === journalDate() ? "今天" : "這天";
}
function briefCard(parent, saved, from, to) {
  const kind = reviewMode === "week" ? "weekly" : "daily";
  const box = panel(null, "brief-card");
  add(parent, box);
  const head = keyed(el("h2", "AI 回顧"), "brief-title");
  head.tabIndex = -1;
  add(box, head);
  const brief = saved?.briefs.find((b) => b.kind === kind && b.from_ms < to && b.to_ms > from);
  if (!ai.enabled) {
    add(
      box,
      el("p", brief ? brief.text : `設定 AI 之後，它會幫你把${periodWord()}寫成一段回顧。`, brief ? "brief-text" : "muted"),
      row(keyed(btn("先設定 AI", () => go("settings", "settings-ai"), "primary"), "brief-setup")),
    );
    return;
  }
  const generate = async () => {
    try {
      await api("/v1/briefs", {
        id: crypto.randomUUID(),
        from_ms: from,
        to_ms: to,
        kind,
        provider_id: ai.provider_id,
        consent: true,
      });
    } catch (error) {
      if (error.status !== 409) throw error;
      // The saved AI changed elsewhere; nothing was sent. Show the new destination.
      ai = await api("/v1/ai");
      focusKey = "brief-generate";
      await shell();
      notice("AI 設定剛被更改，回顧沒有產生。請確認後再按一次。", true);
      return;
    }
    focusKey = "brief-title";
    await shell();
  };
  if (!saved) add(box, el("p", "暫時讀不到已保存的回顧；請重新整理。", "callout warn"));
  if (brief) {
    add(
      box,
      el("p", brief.text, "brief-text"),
      el("p", "AI 產生，可能有誤，請對照時間軸。", "hint"),
      row(
        keyed(btn("重新產生", generate), "brief-generate"),
        keyed(btn("刪除", async () => {
          if (!(await ask("刪除這份 AI 回顧？無法復原", "只會刪除這一份 AI 回顧；活動卡和其他回顧不受影響。", "刪除", true))) return;
          await api(`/v1/briefs/${encodeURIComponent(brief.id)}`, null, "DELETE");
          focusKey = "brief-generate";
          await shell();
        }, "text-btn"), "brief-delete"),
      ),
    );
  } else {
    add(
      box,
      el("p", `還沒有${periodWord()}的回顧。`, "muted"),
      row(keyed(btn("產生 AI 回顧", generate, "primary generate"), "brief-generate")),
    );
  }
  add(box, el("p", `會把這段時間的活動卡送到 ${providerHost()}`, "disclosure"));
}
function timeSplit(parent, overview) {
  const box = panel("時間分配", "split-card");
  add(parent, box);
  if (!overview) return failed(box, "時間分配");
  const rows = overview.categories.filter((c) => c.duration_ms > 0).sort((a, b) => b.duration_ms - a.duration_ms);
  const total = rows.reduce((n, c) => n + c.duration_ms, 0);
  if (!rows.length) return add(box, el("p", "這段時間沒有活動卡；空白不會被猜測或填補。", "muted"));
  const chart = el("ul", null, "bars");
  rows.forEach((c) => {
    const style = styleFor(c.category);
    const bar = add(el("div", null, "bar"), el("i"));
    bar.style.setProperty("--progress", `${(c.duration_ms / total) * 100}%`);
    bar.style.setProperty("--cat", style.color);
    add(chart, add(
      el("li", null, "chart-row"),
      catChip(style),
      bar,
      el("small", `${duration(c.duration_ms)} · ${Math.round((c.duration_ms / total) * 100)}%`),
    ));
  });
  add(box, chart);
}
function topApps(parent, overview) {
  const box = panel("常用應用程式", "apps-card");
  add(parent, box);
  if (!overview) return failed(box, "常用應用程式");
  const apps = overview.apps.filter((a) => a.duration_ms > 0).slice(0, 5);
  if (!apps.length) return add(box, el("p", "這段時間沒有 app 記錄。", "muted"));
  const list = el("ol", null, "app-list");
  apps.forEach((a) => add(list, add(el("li"), el("span", appName(a.app)), el("small", duration(a.duration_ms)))));
  add(box, list);
}
/* Topics only when the server labelled them with the model; local keywords are just card titles. */
function topicsVisible(overview) {
  return ["model", "mixed"].includes(overview?.keyword_mode) && (overview.title_keywords || []).length > 0;
}
function topics(parent, overview) {
  if (!topicsVisible(overview)) return;
  const words = overview.title_keywords;
  const box = panel("主題", "topics-card");
  const chips = el("div", null, "chips topic-chips");
  words.slice(0, 12).forEach(([word]) => add(chips, el("span", word, "chip")));
  add(box, chips);
  add(parent, box);
}
function weekBars(parent, days) {
  const box = panel("這週每天", "week-card");
  add(parent, box);
  const today = journalDate();
  const totals = days.map((d) => metrics(d.cards).total);
  const max = Math.max(1, ...totals);
  const list = el("ol", null, "week-bars");
  days.forEach((d, i) => {
    const future = d.date > today;
    const m = metrics(d.cards);
    const empty = future || !totals[i];
    const column = el("span", null, empty ? "week-column is-empty" : "week-column");
    const fill = el("span", null, "week-fill");
    fill.style.setProperty("--fill", `${(totals[i] / max) * 100}%`);
    Object.entries(m.totals).sort((a, b) => b[1] - a[1]).forEach(([id, ms]) => {
      const seg = el("i");
      seg.style.setProperty("flex-grow", String(ms));
      seg.style.setProperty("--cat", styleFor(id).color);
      add(fill, seg);
    });
    const value = el("span", empty ? "—" : weekHours(totals[i]), "week-value");
    add(column, value, fill);
    const label = `週${WEEKDAYS[new Date(d.date + "T12:00:00").getDay()]}`;
    const item = el("li");
    const parts = [column, el("span", label, d.date === today ? "week-label today" : "week-label")];
    if (future) {
      const b = el("div", null, "week-day-bar future");
      b.setAttribute("role", "img");
      b.setAttribute("aria-label", `${shortDate(d.date)}：尚未到來`);
      add(b, ...parts);
      add(item, b);
    } else {
      const b = keyed(btn("", async () => {
        day = d.date;
        reviewMode = "day";
        focusKey = "review-mode-day";
        await shell();
      }, "week-day-bar"), `week-${d.date}`);
      b.setAttribute("aria-label", `${shortDate(d.date)}：${totals[i] ? `記錄 ${duration(totals[i])}` : "沒有記錄"}。開啟這一天的回顧`);
      add(b, ...parts);
      add(item, b);
    }
    add(list, item);
  });
  add(box, list);
  const elapsed = days.filter((d) => d.date <= today).length;
  const sum = totals.reduce((n, ms, i) => n + (days[i].date <= today ? ms : 0), 0);
  if (elapsed) add(box, el("p", weekAverage(sum, elapsed), "week-average"));
}
function weekAverage(sum, elapsed) {
  return `平均每天 ${duration(sum / elapsed)}（已過 ${elapsed} 天）`;
}
/* Week bar values: one decimal hour, e.g. 4.1 時. */
function weekHours(ms) {
  return `${(Math.max(0, ms) / 3600000).toFixed(1)} 時`;
}
function askPanel(parent, from, to) {
  const box = panel(null, "ask-card");
  add(parent, box);
  add(box, el("h2", "問 AI"));
  if (!ai.enabled) {
    add(
      box,
      el("p", "設定 AI 之後，就能問它關於這段時間的問題。", "muted"),
      row(keyed(btn("先設定 AI", () => go("settings", "settings-ai")), "ask-setup")),
    );
    return;
  }
  // The period identity, not its end: today's range grows on every render.
  const key = `${reviewMode}:${reviewMode === "week" ? weekDates()[0] : day}`;
  if (!askState || askState.key !== key)
    askState = { key, conversationId: crypto.randomUUID(), messages: [], pending: null, error: "", draft: askState?.draft || "" };
  const state = askState;
  const log = el("div", null, "ask-log");
  log.setAttribute("role", "log");
  log.setAttribute("aria-label", "問答");
  const form = el("form", null, "ask-form");
  const label = el("label", "問問這段時間", "sr");
  const input = keyed(el("input"), "ask-input");
  input.id = "ask-input";
  label.htmlFor = input.id;
  input.type = "text";
  input.maxLength = 1000;
  input.placeholder = "問問這段時間…";
  input.autocomplete = "off";
  input.value = state.draft;
  input.oninput = () => (state.draft = input.value);
  const send = keyed(el("button", null, "send-btn"), "ask-send");
  send.setAttribute("aria-label", "送出");
  send.title = "送出";
  add(send, icon("send"));
  send.type = "submit";
  add(form, label, input, send);
  const ideas = el("div", null, "chips ask-ideas");
  ideas.setAttribute("role", "group");
  ideas.setAttribute("aria-label", "範例問題（只會填入輸入框）");
  askExamples(reviewMode).forEach((text) =>
    add(ideas, btn(text, () => {
      input.value = state.draft = text;
      input.focus();
    }, "chip")),
  );
  const waiting = () => activeAsk?.state === state;
  const renderLog = () => {
    log.replaceChildren();
    log.setAttribute("aria-busy", String(waiting()));
    state.messages.forEach((m) => {
      const bubble = el("div", null, `message ${m.role}`);
      add(bubble, el("small", m.role === "user" ? "你" : "AI"), el("p", m.text));
      add(log, bubble);
    });
    if (waiting()) {
      const wait = el("div", null, "message assistant pending");
      wait.setAttribute("role", "status");
      add(wait, el("small", "AI"), el("p", "正在回答…"));
      add(log, wait);
    }
    if (state.error) {
      const fail = el("p", state.error, "callout warn");
      fail.setAttribute("role", "alert");
      add(log, fail);
    }
    if (!log.childElementCount) add(log, el("p", "問題和回答會出現在這裡", "ask-empty"));
  };
  const refresh = refreshAsk = () => {
    if (!box.isConnected) return;
    send.disabled = !!activeAsk;
    send.setAttribute("aria-busy", String(waiting()));
    if (input.value !== state.draft) input.value = state.draft;
    renderLog();
  };
  form.onsubmit = (event) => {
    event.preventDefault();
    safe(async () => {
      if (activeAsk) throw Error("上一個問題還在等回答，完成後才能再問。");
      const q = input.value.trim();
      if (!q) throw Error("請先輸入問題。");
      // The question is sent only now, after an explicit send, to the saved provider.
      const requestKey = JSON.stringify([state.conversationId, q, from, to, ai.provider_id]);
      if (state.pending?.key !== requestKey)
        state.pending = {
          key: requestKey,
          body: {
            conversation_id: state.conversationId,
            request_id: crypto.randomUUID(),
            question: q,
            from_ms: from,
            to_ms: to,
            provider_id: ai.provider_id,
            consent: true,
          },
        };
      const message = { role: "user", text: q };
      const op = activeAsk = { state, epoch: askEpoch, retry: state.pending, message };
      const owns = () => op.epoch === askEpoch && askState === state;
      state.error = "";
      state.messages.push(message);
      state.draft = "";
      refresh();
      let conflict = false;
      try {
        const result = await api("/v1/ask", op.retry.body, undefined, owns);
        if (!owns()) return;
        if (state.pending === op.retry) state.pending = null;
        state.messages.push({ role: "assistant", text: result.answer });
        state.messages = state.messages.slice(-20);
      } catch (e) {
        if (!owns()) return;
        // Remove exactly this optimistic turn and give the question back.
        state.messages = state.messages.filter((m) => m !== message);
        if (!state.draft) state.draft = q;
        if (e.status === 409) {
          // Changed AI settings or a reused request: never retry automatically.
          conflict = true;
          state.pending = null;
          state.conversationId = crypto.randomUUID();
          state.messages = [];
          state.error = "AI 設定剛被更改，這個問題沒有送出。確認後再送一次。";
        } else state.error = `這次沒有問成功：${e.message}。問題已放回輸入框，可以再送一次。`;
      } finally {
        if (activeAsk === op) activeAsk = null;
        refreshAsk?.();
      }
      if (conflict && owns() && page === "review") {
        ai = await api("/v1/ai");
        focusKey = "ask-input";
        await shell();
      }
    }, send);
  };
  add(
    box,
    log,
    form,
    ideas,
    add(el("p", null, "disclosure"),
      document.createTextNode(askDisclosure(providerHost()).replace("（不含截圖）。", "")),
      el("span", "（不含截圖）。", "nowrap")),
  );
  // The page is built off-screen, so render the retained conversation directly.
  send.disabled = !!activeAsk;
  renderLog();
}

/* ---------- devices, settings and support ---------- */
function deviceAuthenticationLabel(device) {
  if (!Object.hasOwn(device, "last_authenticated_ms")) return "伺服器尚未提供連線驗證狀態。";
  return Number.isFinite(device.last_authenticated_ms) && device.last_authenticated_ms > 0
    ? `最近通過驗證：${stamp(device.last_authenticated_ms)}（約到分鐘；不代表同步或錄製成功）`
    : "尚無連線驗證記錄。";
}
async function devicesDialog() {
  const seq = renderSeq, openingEpoch = modalEpoch;
  const priorContent = $("#modal").open ? $("#modal-body").lastElementChild : null;
  const openingCurrent = () => seq === renderSeq && openingEpoch === modalEpoch &&
    (!priorContent || ($("#modal").open && priorContent.isConnected));
  let data;
  try { data = await api("/v1/devices", undefined, undefined, openingCurrent); }
  catch (error) { if (openingCurrent()) throw error; else return; }
  if (!openingCurrent()) return;
  modal("裝置金鑰", (p) => {
    const current = () => p.isConnected && seq === renderSeq;
    const pendingHint = el("p", "金鑰變更處理中時，不能同時送出另一筆變更；請稍後再試。這裡的草稿會保留。", "callout");
    pendingHint.setAttribute("role", "status");
    pendingHint.hidden = !deviceChangePending;
    add(p, pendingHint);
    async function changeDevice(action) {
      if (!current()) return;
      if (deviceChangePending) {
        pendingHint.hidden = false;
        notice("先前的金鑰變更仍在處理，請稍後再試；目前草稿已保留。", false, p);
        return;
      }
      deviceChangePending = true;
      pendingHint.hidden = false;
      const controls = [...p.querySelectorAll("input,select,button")].map(n => [n, n.disabled]);
      controls.forEach(([n]) => { n.disabled = true; });
      try { await action(); }
      catch (error) { if (current()) throw error; }
      finally {
        deviceChangePending = false;
        controls.forEach(([n, disabled]) => { if (n.isConnected) n.disabled = disabled; });
        if (current()) pendingHint.hidden = true;
      }
    }
    add(
      p,
      el(
        "p",
        "為每支手機或每個私人 API 工具建立獨立金鑰（最多 32 把，有效 1–90 天）。Owner 金鑰留作網站登入與復原，不必交給手機或整合工具。",
      ),
    );
    topic(p, "了解權限與撤銷", [
      "日誌權限：手機 app 或私人 API 工具使用，可讀寫私人日誌與 AI 設定，但不能管理裝置或登入這個網站。",
      "僅收集權限：只能上傳活動／情境，並讀取暫停與模型資訊。",
      "撤銷後新的請求立即失效；離線中的手機要到下次同步才會知道。",
    ]);
    const issued = el("details", null, "topic device-issued");
    add(issued, el("summary", `管理已發出的金鑰（${data.devices.length}）`));
    if (!data.devices.length)
      add(issued, el("p", "還沒有裝置金鑰。", "muted"));
    data.devices.forEach((d) =>
      add(
        issued,
        add(
          el("article", null, "card"),
          el("h3", d.label),
          el(
            "p",
            `${d.scope === "journal" ? "日誌" : "僅收集"} · ${d.expires_ms < Date.now() ? "已到期" : "到期"} ${stamp(d.expires_ms)}`,
          ),
          el("p", deviceAuthenticationLabel(d), "muted"),
          btn("撤銷這把金鑰", async () => {
            if (
              !(await ask(
                `撤銷「${d.label}」？`,
                "這支裝置將無法再連線；已存入的日誌仍然保留。需要時可以重新建立金鑰。",
                "撤銷金鑰",
                true,
              ))
            )
              return;
            if (!current()) return;
            await changeDevice(async () => {
              await api(`/v1/devices/${d.id}`, null, "DELETE", current);
              if (!current()) return;
              refreshOnClose = true;
              await devicesDialog();
            });
          }),
        ),
      ),
    );
    add(p, el("h3", "建立新的金鑰"), el("p", "建立請求送出後，關閉視窗不會取消。若沒有看到一次性金鑰，請重新開啟清單，撤銷那把金鑰後再建立。", "muted"));
    const label = field(p, "裝置或工具名稱", "", "text", "例如「我的 Pixel」或「私人時間軸工具」，方便之後辨識與撤銷。"),
      scope = select(
        p,
        "權限",
        [
          ["journal", "日誌（手機 app）"],
          ["collector", "僅收集（上傳工具）"],
        ],
        "journal",
      ),
      days = field(p, "有效天數（1–90）", 30, "number");
    label.maxLength = 80;
    days.min = "1";
    days.max = "90";
    days.inputMode = "numeric";
    add(
      p,
      add(el("div", null, "actions"), btn(
        "建立裝置金鑰",
        async () => {
          if (!label.value.trim()) throw Error("請填寫裝置名稱");
          if (!Number.isInteger(+days.value) || +days.value < 1 || +days.value > 90)
            throw Error("有效天數需為 1–90 的整數");
          const payload = { label: label.value.trim(), scope: scope.value, days: +days.value };
          await changeDevice(async () => {
            const result = await api("/v1/devices", payload, "POST", current);
            if (!current()) return;
            refreshOnClose = true;
            modal("這把金鑰只顯示這一次", (body) => {
              const journal = payload.scope === "journal";
              add(body,
                el("p", journal ? "日誌金鑰 · 手機 app 或私人 API 工具" : "僅收集金鑰 · 私人上傳工具"),
                el("p", "關閉後無法再次讀取這把金鑰；請先私下保存。遺失時從金鑰清單撤銷並重建。", "muted"),
                rich("p", "", "伺服器網址：", code(location.origin)),
              );
              const secret = field(body, "裝置金鑰", result.token, "password");
              secret.readOnly = true;
              const show = check(body, "顯示金鑰");
              show.onchange = () => (secret.type = show.checked ? "text" : "password");
              add(
                body,
                el(
                  "p",
                  "請用私人方式帶到手機或工具（例如密碼管理器）；不要放進網址、截圖或聊天訊息。",
                  "muted",
                ),
              );
              if (journal) topic(body, "連接 Android 手機", [
                "安裝並開啟 PocketLog app，填入上方伺服器網址與這把日誌金鑰，按「驗證並連線」。",
                "回到網站的時間軸，最上面的狀態列會顯示手機是否已連線。記錄、截圖與 AI 整理仍要在手機另外同意。",
              ]);
              topic(body, journal ? "連接私人 API 工具" : "設定私人上傳工具", [
                ["以 HTTPS 連線至上方伺服器，透過 ", code("Authorization: Bearer <裝置金鑰>"), " 請求標頭帶入金鑰；不要放進網址。"],
                journal
                  ? "這把金鑰可讀寫私人日誌與 AI 設定；不能管理金鑰或登入網站。若要讀取時間軸，請遵守 API 文件中的時間範圍與讀取上限。"
                  : "只可 POST /v1/activities、POST /v1/observations，及 GET /v1/collection、GET /v1/ai。不能讀取日誌、用於 Android app 或登入網站。",
                "金鑰本身不代表同意擷取或模型處理；上傳活動與情境前仍須有使用者的明確同意。",
              ], link("API 文件與安全連線範例", `${REPO}/blob/main/docs/API.md`));
              add(body,
                add(
                  el("div", null, "actions"),
                  btn("複製金鑰", async () => {
                    await navigator.clipboard.writeText(secret.value);
                    notice("已複製裝置金鑰；用完請清除剪貼簿");
                  }),
                  btn("我已保存，關閉", () => close(), "primary"),
                ),
              );
            });
          });
        },
        "primary",
      )),
    );
    add(p, issued);
  });
}
function failed(parent, what) {
  add(parent, el("p", `暫時讀不到${what}；請重新整理。`, "callout warn"));
}
function settingsSection(parent, id, title, state, stateClass = "", open = null) {
  const s = el("section", null, open ? "settings-section linked" : "settings-section");
  s.dataset.section = id;
  s.setAttribute("aria-labelledby", `settings-${id}-title`);
  const h = keyed(el("h2", open ? null : title), `settings-${id}`);
  h.id = `settings-${id}-title`;
  h.tabIndex = -1;
  if (open) add(h, add(keyed(btn("", open.action, "section-link"), open.key), el("span", title), el("span", "›", "chevron")));
  add(s, h);
  if (state) add(s, el("p", state, `state-line ${stateClass}`.trim()));
  add(parent, s);
  return s;
}
/* Last contact in compact form, e.g. 11/17 03:17. */
function shortStamp(ms) {
  const d = new Date(ms);
  return `${d.getMonth() + 1}/${d.getDate()} ${time(ms)}`;
}
function latestContact(phones) {
  return Math.max(0, ...phones.map((d) => (Number.isFinite(d.last_authenticated_ms) ? d.last_authenticated_ms : 0)));
}
function phoneStale(phones, now) {
  const last = latestContact(phones);
  return last > 0 && now - last > 86400000;
}
/* The web never claims 記錄中 from last-seen alone. */
/* The section line only covers what is not a device's own status. */
function recordState(control, phones) {
  if (!control) return "暫時讀不到記錄狀態";
  if (control.paused) return "已暫停：伺服器不接受新的記錄";
  if (!phones) return "暫時讀不到手機狀態";
  if (!phones.length) return "還沒有連接手機";
  return "";
}
/* Each device's own status: 最後連線 11/17 03:17, stale or never connected. */
function deviceStatus(device, now) {
  if (!Object.hasOwn(device, "last_authenticated_ms")) return "伺服器沒有提供連線時間";
  const last = device.last_authenticated_ms;
  if (!(Number.isFinite(last) && last > 0)) return "還沒連線過";
  return now - last > 86400000 ? `超過一天沒有連線（最後 ${shortStamp(last)}）` : `最後連線 ${shortStamp(last)}`;
}
/* A navigation row: one line of state, a chevron, opens a dialog. */
function navRow(text, key, open, primary = false) {
  const b = keyed(btn("", open, primary ? "nav-row primary" : "nav-row"), key);
  add(b, el("span", text), el("span", "›", "chevron"));
  return b;
}
const SETTINGS_SECTIONS = [["record", "記錄"], ["ai", "AI"], ["categories", "分類"], ["devices", "連線與裝置"], ["data", "資料"], ["help", "說明"]];
async function settingsPage(parent, seq) {
  const [devices, control, status, schedule] = await Promise.all([
    soft("/v1/devices"),
    soft("/v1/collection"),
    soft("/v1/status"),
    ai.enabled ? soft("/v1/briefs/schedule") : null,
  ]);
  live(seq);
  const now = Date.now();
  const phones = devices ? devices.devices.filter((d) => d.scope === "journal") : [];
  const activePhones = phones.filter((d) => d.expires_ms > now);

  // Every section, including 說明, is one tap away.
  const jump = el("div", null, "section-jump");
  jump.setAttribute("role", "navigation");
  jump.setAttribute("aria-label", "設定的段落");
  SETTINGS_SECTIONS.forEach(([id, name]) =>
    add(jump, keyed(btn(name, () => restoreFocus(`settings-${id}`), "chip"), `jump-${id}`)));
  add(parent, jump);
  // The chip of the section currently in view is marked as the current position.
  mounted.push(() => {
    if (typeof IntersectionObserver === "undefined") return;
    const mark = (id) => jump.querySelectorAll("button").forEach((b) => {
      const on = b.dataset.key === `jump-${id}`;
      b.classList.toggle("current", on);
      if (on) {
        b.setAttribute("aria-current", "location");
        // Scroll only the chip row sideways; never move the page.
        if (jump.scrollWidth > jump.clientWidth)
          jump.scrollLeft = Math.max(0, b.offsetLeft - jump.offsetLeft - 14);
      } else b.removeAttribute("aria-current");
    });
    const seen = new Map();
    const observer = new IntersectionObserver((entries) => {
      if (!jump.isConnected) return observer.disconnect();
      entries.forEach((e) => seen.set(e.target.dataset.section, e.isIntersecting ? e.boundingClientRect.top : null));
      const visible = SETTINGS_SECTIONS.map(([id]) => id).find((id) => seen.get(id) !== null && seen.get(id) !== undefined);
      if (visible) mark(visible);
    }, { rootMargin: "-10% 0px -55% 0px" });
    parent.querySelectorAll(".settings-section").forEach((section) => observer.observe(section));
  });

  /* 1. 記錄 — the web shows each phone's last contact instead of capture switches. */
  const record = settingsSection(
    parent,
    "record",
    "記錄",
    recordState(control, devices ? activePhones : null),
    control?.paused || (activePhones.length && phoneStale(activePhones, now)) ? "warn" : "",
  );
  if (!devices) failed(record, "裝置");
  else if (!activePhones.length)
    add(record, el("p", phones.length ? "手機金鑰都已過期，請在「連線與裝置」建立新的。" : "還沒有連接手機。", "muted"));
  else {
    const list = el("ul", null, "device-list");
    // With one phone the state line already says 最後連線; repeat it per phone only when there are several.
    activePhones.forEach((d) => {
      const stale = now - (d.last_authenticated_ms || 0) > 86400000 && d.last_authenticated_ms > 0;
      add(list, add(el("li"), el("strong", d.label), el("small", deviceStatus(d, now), stale ? "stale" : "")));
    });
    add(record, list);
  }
  add(record, el("p", "記錄開關、截圖和排除的應用程式在 Android 手機上調整。", "hint"));
  if (control)
    add(record, toggleSwitch(
      "暫停所有裝置",
      control.paused ? "已暫停：伺服器拒絕新的記錄。" : "暫停後伺服器會拒絕新的記錄。",
      control.paused,
      "settings-collection",
      async (pause) => {
        if (
          !(await ask(
            pause ? "暫停所有裝置？" : "恢復接收手機記錄？",
            pause
              ? "伺服器會立刻拒絕新的記錄，並刪除還沒整理的截圖和畫面文字（已整理的活動卡會保留）。手機要到下次同步才會停止。"
              : "手機會在下次同步時繼續上傳。恢復不會替手機打開任何權限。",
            pause ? "暫停" : "恢復記錄",
            pause,
          ))
        )
          return;
        await api("/v1/collection", { ...control, paused: pause }, "PUT");
        focusKey = "settings-collection";
        await shell();
      },
    ));

  /* 2. AI — the card header opens the AI page; title = provider, subtitle = model and automation. */
  const model = settingsSection(
    parent,
    "ai",
    "AI",
    ai.enabled
      ? null
      : ai.available === false
        ? "這台伺服器還沒有地方保存 AI 設定"
        : "尚未設定 · 活動卡只顯示應用程式名稱和畫面文字",
    "",
    { key: "settings-ai-setup", action: () => providerDialog(shell) },
  );
  if (ai.enabled) {
    const brief = schedule ? schedule.enabled && schedule.provider_id === ai.provider_id : null;
    add(
      model,
      el("p", providerName(ai), "row-title"),
      el("p", aiSummary(ai.model, !!ai.auto_summarize, brief), "row-subtitle"),
    );
  } else add(model, row(keyed(btn("設定 AI", () => providerDialog(shell), "primary"), "settings-ai-start")));

  /* 3. 分類 */
  const sorting = settingsSection(
    parent,
    "categories",
    "分類與規則",
    `${prefs.categories.length} 個分類 · ${(prefs.rules || []).length} 條自動規則`,
    "",
    { key: "settings-categories-edit", action: categoriesDialog },
  );
  const swatches = el("ul", null, "swatches");
  swatches.setAttribute("aria-label", "目前的分類");
  prefs.categories.forEach((c) => {
    const item = el("li");
    item.style.setProperty("--cat", c.color);
    add(item, el("i", null, "dot"), el("span", c.name));
    add(swatches, item);
  });
  add(sorting, swatches);

  /* 4. 連線與裝置 */
  const link1 = settingsSection(
    parent,
    "devices",
    "連線與裝置",
    devices ? `${activePhones.length} 支手機 · ${devices.devices.filter((d) => d.expires_ms > now).length} 把有效的裝置金鑰` : "暫時讀不到裝置金鑰",
  );
  add(
    link1,
    el("p", "手機要填的伺服器網址", "field-label"),
    add(
      el("div", null, "copy-row"),
      el("span", location.origin, "server-url"),
      keyed(btn("複製", async () => {
        await navigator.clipboard.writeText(location.origin);
        notice("已複製伺服器網址");
      }), "settings-copy-origin"),
    ),
    ...originHints(location).map((text) => text === HTTP_NOTE ? httpNote("http-note") : el("p", text, "hint")),
    navRow("管理裝置金鑰", "settings-devices-manage", devicesDialog, !!devices && !devices.devices.length),
    row(keyed(btn("登出這個瀏覽器", async () => {
      await api("/session", null, "DELETE");
      login("signedout");
    }, "link-btn"), "settings-logout")),
  );

  /* 5. 資料 */
  const storage = status?.storage;
  const data = settingsSection(
    parent,
    "data",
    "資料",
    storage ? storageLine(storage) : "暫時讀不到儲存空間",
  );
  if (storage) {
    const used = el("progress");
    used.max = storage.limit_bytes;
    used.value = Math.min(storage.limit_bytes, storage.used_bytes);
    used.setAttribute("aria-label", `儲存空間：已使用 ${gib(storage.used_bytes)}，上限 ${gib(storage.limit_bytes)}`);
    add(
      data,
      used,
      el("p", `截圖 ${storage.image_count.toLocaleString()} 張；超過上限時先刪最舊的截圖，文字永久保留。`, "hint"),
    );
    if (storage.text_only_before_ms)
      add(data, el("p", `${stamp(storage.text_only_before_ms)} 以前只剩文字。`, "hint"));
    add(data, navRow("空間上限", "settings-storage", () => storageDialog(storage)));
  }
  const range = el("select");
  range.id = "export-range";
  keyed(range, "settings-export-range");
  [["1", "今天"], ["7", "最近 7 天"], ["30", "最近 30 天"]].forEach(([v, t]) => {
    const o = el("option", t);
    o.value = v;
    add(range, o);
  });
  range.value = "7";
  const rangeLabel = el("label", "匯出", "field-label");
  rangeLabel.htmlFor = range.id;
  add(
    data,
    rangeLabel,
    add(
      el("div", null, "export-row"),
      range,
      keyed(btn("匯出", async () => {
        const today = journalDate();
        const count = Number(range.value);
        const dates = Array.from({ length: count }, (_, i) => shift(today, i - count + 1)).filter((d) => d >= JOURNAL_START);
        const snapshot = await loadDays(dates, renderSeq);
        await exportData(snapshot.days, snapshot.reviews, snapshot.reviewsTruncated, `${dates[0]}_${today}`);
      }), "settings-export"),
    ),
    el("p", "檔案含私人內容，不含截圖；請自行保管。", "hint"),
    // Destructive actions stay last, low emphasis, behind a 無法復原 confirmation.
    add(el("div", null, "danger-zone"), keyed(btn("刪除全部記錄…", async () => {
      if (
        !(await ask(
          "刪除伺服器上的全部記錄？無法復原",
          [
            "會刪除所有活動卡、截圖、畫面文字、AI 回顧和問答，無法復原。",
            "分類、裝置金鑰和 AI 設定會保留。已匯出的檔案、備份和已送到 AI 提供者的內容不會一起刪除。",
            "請先在手機停止記錄，否則仍連線的手機會繼續上傳。",
          ],
          "刪除全部記錄",
          true,
        ))
      )
        return;
      await api("/v1/journal", null, "DELETE");
      askEpoch++;
      askState = null;
      notice("伺服器上的記錄已刪除");
      await shell();
    }, "text-btn danger-text"), "settings-delete")),
  );

  /* 6. 說明 */
  const help = settingsSection(parent, "help", "說明");
  add(help, el("p", PROMISE, "hint"));
  topic(help, "時間軸沒有活動卡", [
    "確認手機填的是這個網址和一把有效的金鑰（「記錄」會顯示最後連線時間）。",
    "在手機打開「自動記錄」，並授予「使用情況存取」和「輔助使用」。",
    "截圖在充電時才上傳；app 使用時段約每 15 分鐘同步。",
  ]);
  topic(help, "手機連不上或金鑰失效", [
    `手機要用和這個網站相同的網址：HTTPS 可以在任何地方使用；http:// 只限${PRIVATE_NETWORKS}裡的位址。`,
    "金鑰有效 1–90 天，過期或撤銷後請建立新的。",
    "更換 Owner 金鑰後，所有手機金鑰都會失效。",
  ]);
  topic(help, "AI 沒有整理", [
    "在「AI 設定」打開「自動整理新截圖」；更換 AI 後它會自動關閉。",
    "「測試並儲存」只會送一段固定的測試文字。",
  ]);
  topic(help, "找不到 Owner 金鑰", [
    ["在伺服器執行 ", code("./scripts/setup-status.sh production"), " 檢查狀態（不會顯示金鑰）。"],
    "這個網站無法顯示、寄送或重設 Owner 金鑰。",
  ]);
  topic(help, "用自己的程式讀取記錄", [
    "在「管理裝置金鑰」建立一把「日誌」權限的金鑰。",
    ["以 HTTPS 連線，金鑰放在 ", code("Authorization: Bearer"), " 標頭，不要放進網址。"],
    ["用 ", code("GET /v1/activities"), " 分頁讀取活動卡。"],
  ], link("API 文件", `${REPO}/blob/main/docs/API.md`));
  if (status) {
    const tech = el("details", null, "topic");
    add(tech, el("summary", "技術資訊"), el("pre", JSON.stringify(status, null, 2)));
    add(help, tech);
  }
  add(
    help,
    add(
      el("ul", null, "plain links"),
      add(el("li"), link("原始碼與說明", REPO)),
      add(el("li"), link("安裝與第一次使用", `${REPO}/blob/main/docs/SETUP.md`)),
      add(el("li"), link("安全與隱私邊界", `${REPO}/blob/main/docs/SECURITY.md`)),
    ),
    row(keyed(btn("重新看一次介紹", () => onboard(0), "text-btn"), "settings-onboarding")),
  );
}

/* ---------- networks ---------- */
/* Hosts that may use plain http: loopback, RFC 1918, 100.64/10, IPv6 ULA and
   link-local, localhost, *.local/.lan/.internal/.home.arpa and single-label names. */
function isPrivateHost(hostname) {
  const host = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, "").replace(/\.$/, "");
  if (!host) return false;
  const v4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    if (v4.slice(1).some((x) => Number(x) > 255)) return false;
    return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  if (host.includes(":")) return host === "::1" || /^f[cd][0-9a-f]{0,2}:/.test(host) || /^fe[89ab][0-9a-f]?:/.test(host);
  if (host === "localhost" || !host.includes(".")) return true;
  return [".local", ".lan", ".internal", ".home.arpa", ".localhost"].some((suffix) => host.endsWith(suffix));
}
function isLoopbackHost(hostname) {
  const host = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, "");
  return host === "localhost" || host === "::1" || /^127\./.test(host);
}
/* One line under the phone address: loopback cannot be reached from the phone;
   plain http relies on the VPN／private network. */
function originHints(loc) {
  const hints = [];
  if (isLoopbackHost(loc.hostname)) hints.push("這是本機位址；手機請用 VPN／區網位址");
  if (loc.protocol === "http:") hints.push(HTTP_NOTE);
  return hints;
}
const HTTP_NOTE = "這個連線沒有 HTTPS，資料保護依賴你的 VPN／私人網路";
/* The warning row, kept from breaking inside VPN／私人網路. */
function httpNote(cls) {
  const row = add(el("p", null, `${cls} notice-row`), icon("info"));
  add(row, add(el("span"), document.createTextNode("這個連線沒有 HTTPS，資料保護依賴你的 "), el("span", "VPN／私人網路", "nowrap")));
  return row;
}
const PRIVATE_NETWORKS = "VPN／私人網路（例如 Tailscale、ZeroTier、Headscale、WireGuard、區網）";

/* ---------- AI connection (AI 服務 → 金鑰 → 模型 → 測試並儲存 → 自動化) ---------- */
const AI_PROVIDERS = [
  { id: "openai", name: "OpenAI", protocol: "openai", endpoint: "https://api.openai.com/v1/chat/completions", model: "gpt-5-mini", format: "json_schema", keyUrl: "https://platform.openai.com/api-keys" },
  { id: "anthropic", name: "Anthropic", protocol: "anthropic", endpoint: "https://api.anthropic.com/v1/messages", model: "claude-haiku-4-5", format: "json_schema", keyUrl: "https://console.anthropic.com/settings/keys" },
  { id: "gemini", name: "Google Gemini", protocol: "gemini", endpoint: "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", model: "gemini-2.5-flash", format: "json_schema", keyUrl: "https://aistudio.google.com/apikey" },
  { id: "openrouter", name: "OpenRouter", protocol: "openai", endpoint: "https://openrouter.ai/api/v1/chat/completions", model: "openai/gpt-5-mini", format: "json_object", keyUrl: "https://openrouter.ai/keys" },
  { id: "deepseek", name: "DeepSeek", protocol: "openai", endpoint: "https://api.deepseek.com/chat/completions", model: "deepseek-chat", format: "json_object", images: false, keyUrl: "https://platform.deepseek.com/api_keys" },
  { id: "custom", name: "自訂", note: "OpenAI 相容（CLIProxyAPI、LiteLLM、Ollama…）", protocol: "openai", endpoint: "", model: "", format: "json_object" },
];
/* Turns a provider choice into the saved protocol/endpoint. Endpoints are fixed
   per provider and only typed for 自訂. Throws a user-facing Error when invalid. */
function providerSettings(id, modelInput, customEndpoint = "") {
  const preset = AI_PROVIDERS.find((p) => p.id === id);
  if (!preset) throw Error("請先選擇 AI 提供者。");
  const model = String(modelInput || "").trim();
  if (!model) throw Error("請填寫模型名稱。");
  let endpoint = preset.endpoint;
  if (preset.protocol === "gemini") {
    if (!/^[A-Za-z0-9._-]+$/.test(model)) throw Error("Gemini 模型名稱只能包含英文字母、數字、點、底線或連字號。");
    endpoint = endpoint.replace("{model}", model);
  }
  if (id === "custom") {
    endpoint = String(customEndpoint || "").trim();
    let url;
    try {
      url = new URL(endpoint);
    } catch {
      throw Error("請填寫完整的 API 網址，例如 https://example.com/v1/chat/completions。");
    }
    if (url.protocol !== "https:" && !(url.protocol === "http:" && isPrivateHost(url.hostname)))
      throw Error("API 網址要用 HTTPS；http:// 只接受本機或私人網路裡的位址。");
    if (url.username || url.password) throw Error("API 網址不能包含帳號或密碼；金鑰請填在金鑰欄位。");
  }
  return { protocol: preset.protocol, endpoint, model, response_format: preset.format };
}
/* The base URL shown for a saved OpenAI-compatible endpoint (the server appends /chat/completions). */
function proxyBase(endpoint) {
  return String(endpoint || "").replace(/\/chat\/completions\/?$/, "");
}
/* Body for POST /v1/ai/models. Gemini needs a model in its path, so the typed
   or suggested model is used; an empty key reuses the saved one server-side. */
function modelsRequest(id, modelInput, customEndpoint, key) {
  const preset = AI_PROVIDERS.find((p) => p.id === id);
  if (!preset) throw Error("請先選擇 AI 服務。");
  const model = /^[A-Za-z0-9._-]+$/.test(String(modelInput || "").trim()) ? String(modelInput).trim() : preset.model || "model";
  const settings = providerSettings(id, model, customEndpoint);
  const body = { protocol: settings.protocol, endpoint: settings.endpoint };
  if (key) body.api_key = key;
  return body;
}
/* Model IDs from the server: unique, without Gemini's models/ prefix, at most 300. */
function parseModels(response) {
  const seen = new Set();
  for (const raw of Array.isArray(response?.models) ? response.models : []) {
    const id = typeof raw === "string" ? raw.trim().replace(/^models\//, "") : "";
    if (id && id.length <= 200) seen.add(id);
  }
  return [...seen].slice(0, 300);
}
/* Which provider a saved configuration corresponds to (null when unknown). */
function presetFor(config) {
  if (!config?.endpoint) return null;
  const protocol = config.protocol || "openai";
  for (const p of AI_PROVIDERS) {
    if (p.id === "custom" || p.protocol !== protocol) continue;
    const expected = p.protocol === "gemini" ? p.endpoint.replace("{model}", config.model || "") : p.endpoint;
    if (config.endpoint === expected) return p.id;
  }
  return protocol === "openai" ? "custom" : null;
}
function providerName(config) {
  const preset = AI_PROVIDERS.find((p) => p.id === presetFor(config));
  return preset ? preset.name : providerHost(config);
}
/* claude-haiku-4-5 · 自動整理開啟 · 每日回顧關閉 */
function aiSummary(model, auto, brief) {
  return `${model} · 自動整理${auto ? "開啟" : "關閉"} · 每日回顧${brief === null ? "—" : brief ? "開啟" : "關閉"}`;
}
function aiLabel(config) {
  const preset = AI_PROVIDERS.find((p) => p.id === presetFor(config));
  const name = preset ? preset.name : providerHost(config);
  return `${name} · ${config.model}`;
}
/* Only one AI settings change at a time, across dialogs and pages. */
async function changeProvider(p, current, action) {
  if (!current()) return;
  if (providerChangePending) {
    notice("先前的 AI 設定變更還在處理，請稍候再試；表單內容會保留。", false, p);
    return;
  }
  providerChangePending = true;
  const controls = [...p.querySelectorAll("input,select,button")].map((n) => [n, n.disabled]);
  controls.forEach(([n]) => { n.disabled = true; });
  try { await action(); }
  catch (error) { if (current()) throw error; }
  finally {
    providerChangePending = false;
    controls.forEach(([n, disabled]) => { if (n.isConnected) n.disabled = disabled; });
  }
}
function aiForm(p, saved) {
  const seq = renderSeq;
  const current = () => p.isConnected && seq === renderSeq;
  const initial = presetFor(ai);
  let chosen = ai.enabled ? initial : null;
  if (ai.available === false)
    add(p, el("p", "這台伺服器還沒有地方保存 AI 設定，儲存會失敗。請依安裝文件掛載設定資料夾後再試。", "callout warn"));
  if (ai.enabled && !initial)
    add(p, el("p", `目前使用 ${providerHost()} 的自訂位址。選擇 AI 服務後會取代它。`, "hint"));
  const picker = el("div", null, "provider-list");
  picker.setAttribute("role", "group");
  picker.setAttribute("aria-labelledby", "ai-service-title");
  const form = el("div", null, "ai-form");
  const title = el("h3", "AI 服務");
  title.id = "ai-service-title";
  add(p, title, picker, form);
  const renderPicker = () => {
    picker.replaceChildren(...AI_PROVIDERS.map((preset) => {
      const b = keyed(btn("", () => {
        chosen = preset.id;
        renderPicker();
        renderForm();
        picker.querySelector(`[data-key="provider-${preset.id}"]`)?.focus({ focusVisible: false });
      }, chosen === preset.id ? "provider active" : "provider"), `provider-${preset.id}`);
      add(b, add(el("span", null, "provider-name"), chosen === preset.id ? add(el("span", null, "chip-check"), icon("check")) : null, el("span", preset.name)),
        preset.note ? el("small", preset.note) : null);
      if (preset.note) b.classList.add("wide-tile");
      b.setAttribute("aria-pressed", String(chosen === preset.id));
      return b;
    }));
  };
  const renderForm = () => {
    form.replaceChildren();
    if (!chosen) return;
    const preset = AI_PROVIDERS.find((x) => x.id === chosen);
    const same = chosen === initial && ai.enabled;
    const custom = chosen === "custom";
    let endpoint = null;
    if (custom) {
      endpoint = field(form, "API 網址", same ? proxyBase(ai.endpoint) : "", "url",
        "OpenAI 相容的網址（到 /v1 即可）；http:// 只限本機或私人網路。");
      endpoint.placeholder = "http://127.0.0.1:8317/v1";
      endpoint.inputMode = "url";
      endpoint.spellcheck = false;
    }
    const key = field(
      form,
      "API 金鑰",
      "",
      "password",
      custom ? "代理不需要金鑰可留白。" : same && ai.has_key ? undefined : "只存在你的伺服器，不會再顯示。",
    );
    key.autocomplete = "new-password";
    key.spellcheck = false;
    if (same && ai.has_key) key.placeholder = "已儲存金鑰 ••••，貼上新的會取代";
    if (preset.keyUrl) {
      const keyLink = link(`取得 ${preset.name} API 金鑰`, preset.keyUrl);
      keyLink.className = "external-link";
      add(keyLink, icon("external"));
      add(form, add(el("p", null, "key-link"), keyLink));
    }
    const model = field(form, "模型", same ? ai.model : preset.model, "text", custom ? "例如 gpt-5-mini 或 llama3.2-vision；也可以載入清單選擇。" : "已填入建議的模型，可以改，或載入清單選擇。");
    model.spellcheck = false;
    model.autocapitalize = "off";
    // 載入模型清單: a pickable list; free text stays as the fallback.
    const listBox = el("div", null, "model-list");
    const loadModels = keyed(btn("載入模型清單", async () => {
      loadModels.textContent = "正在載入…";
      try {
        await readModels();
      } finally {
        loadModels.textContent = "載入模型清單";
      }
    }, "secondary"), "ai-load-models");
    const readModels = async () => {
      listBox.replaceChildren();
      let body;
      try {
        body = modelsRequest(chosen, model.value, endpoint?.value, key.value.trim());
      } catch (error) {
        listBox.replaceChildren(el("p", error.message, "field-error"));
        return;
      }
      let models;
      try {
        models = parseModels(await api("/v1/ai/models", body, "POST", current));
      } catch (error) {
        if (!current()) return;
        const text = error.status === 429 ? "伺服器正忙，請稍後再試。"
          : error.status === 502 ? "讀不到模型清單，請確認網址和金鑰；也可以直接輸入模型名稱。"
          : error.message;
        const fail = el("p", text, "field-error");
        fail.setAttribute("role", "alert");
        listBox.replaceChildren(fail);
        return;
      }
      if (!current()) return;
      if (!models.length) {
        listBox.replaceChildren(el("p", "這個服務沒有回傳模型；請直接輸入模型名稱。", "hint"));
        return;
      }
      const choose = select(listBox, `選擇模型（${models.length} 個）`, [["", "請選擇"], ...models.map((m) => [m, m])], models.includes(model.value.trim()) ? model.value.trim() : "");
      keyed(choose, "model-choice");
      listBox.replaceChildren(choose.previousElementSibling, choose);
      choose.onchange = () => {
        if (choose.value) model.value = choose.value;
      };
      choose.focus({ focusVisible: false });
    };
    add(form, row(loadModels), listBox);
    const images = check(form, "讓 AI 看截圖", same ? ai.image_support : preset.images !== false, "模型要能讀圖片；關閉時只送畫面文字。");
    let format = null;
    if (custom) {
      const advanced = el("details", null, "topic");
      add(advanced, el("summary", "進階"));
      format = select(advanced, "結構化輸出", [["json_object", "JSON Object（相容性較好）"], ["json_schema", "JSON Schema"]], same ? ai.response_format || "json_object" : "json_object");
      add(form, advanced);
    }
    const disclosure = el("p", "", "disclosure");
    const describe = () => {
      let host = "";
      try { host = new URL(custom ? endpoint.value.trim() : preset.endpoint.replace("{model}", "m")).host; } catch { host = "你填的網址"; }
      disclosure.textContent = `先儲存，再送一段固定測試文字到 ${host}（不含記錄，可能有極少費用）。`;
    };
    describe();
    if (endpoint) endpoint.oninput = describe;
    const feedback = el("div", null, "ai-feedback");
    const submit = keyed(btn("測試並儲存", async () => {
      feedback.replaceChildren();
      const settings = providerSettings(chosen, model.value, endpoint?.value);
      if (!custom && !(same && ai.has_key) && !key.value.trim()) throw Error("請貼上金鑰。");
      const body = { ...settings, image_support: images.checked };
      if (format) body.response_format = format.value;
      if (key.value.trim()) body.api_key = key.value.trim();
      await changeProvider(p, current, async () => {
        key.value = "";
        const next = await api("/v1/ai", body, "PUT", current);
        if (!current()) return;
        ai = next;
        let failure = null;
        // The bound probe tests exactly the configuration just saved.
        if (next.enabled && next.probe_provider_binding) {
          try {
            await api("/v1/ai/test", { provider_id: next.provider_id }, "POST", current);
          } catch (error) {
            if (!current()) return;
            failure = error.status === 409
              ? "設定剛在別處被更改，沒有送出測試。請重新開啟後再試。"
              : error.message || "測試沒有成功。";
          }
        }
        if (!current()) return;
        if (failure) {
          const box = el("div", null, "callout warn");
          box.setAttribute("role", "alert");
          add(box, el("p", `設定已儲存，但測試沒有成功：${failure}`), el("p", "請檢查金鑰和模型名稱，修改後再按一次「測試並儲存」。", "hint"));
          feedback.replaceChildren(box);
          box.tabIndex = -1;
          box.focus();
          return;
        }
        await saved();
        notice(`已連接：${aiLabel(ai)}`);
      });
    }, "primary large"), "ai-save");
    add(form, row(submit), disclosure, feedback);
  };
  renderPicker();
  renderForm();
}
/* The AI page: AI 服務 → 金鑰 → 模型 → 讓 AI 看截圖 → 測試並儲存 → 自動化 → 停用. */
function providerDialog(done) {
  modal("AI", (p) => {
    const rerender = (key) => {
      // The settings summary behind the dialog refreshes when it closes.
      refreshOnClose = true;
      providerDialog(done);
      const target = key && $(`#modal [data-key="${key}"]`);
      // The recap switch renders after its schedule read; focus it once it exists.
      if (target) target.focus();
      else switchFocus = key || null;
    };
    const finish = async () => {
      close();
      await done();
    };
    if (!ai.enabled) add(p, el("p", "AI 會看截圖和畫面文字，替每段時間寫標題和摘要。", "hint"));
    const formBox = el("div");
    add(p, formBox);
    aiForm(formBox, finish);
    if (!ai.enabled) return;
    const auto = el("section", null, "ai-automation");
    add(auto, el("h3", "自動化"));
    aiSwitches(auto, rerender);
    add(p, auto, add(el("div", null, "danger-zone"), keyed(btn("停用並刪除金鑰", async () => {
      if (await removeProvider()) await finish();
    }, "text-btn danger-text"), "ai-remove")));
  });
}
async function removeProvider() {
  if (
    !(await ask(
      "停用 AI 並刪除伺服器上的金鑰？無法復原",
      "會刪除伺服器上這把 API 金鑰（之後要重新貼上），並停止自動整理和每天的回顧。已保存的活動卡、回顧和問答不會被刪除。",
      "停用並刪除",
      true,
    ))
  )
    return false;
  if (providerChangePending) throw Error("先前的 AI 設定變更還在處理，請稍候再試。");
  providerChangePending = true;
  try {
    await api("/v1/ai", null, "DELETE");
    ai = await api("/v1/ai");
  } finally {
    providerChangePending = false;
  }
  focusKey = "settings-ai";
  return true;
}
/* Accessible on/off switch with a 44 px target; changes only on explicit activation. */
function toggleSwitch(label, hint, on, key, change) {
  const wrap = el("div", null, "switch-row");
  const id = "switch-" + crypto.randomUUID();
  const b = keyed(btn("", () => change(!on), on ? "switch on" : "switch"), key);
  b.setAttribute("role", "switch");
  b.setAttribute("aria-checked", String(on));
  b.setAttribute("aria-labelledby", id + "-label");
  b.setAttribute("aria-describedby", id + "-hint");
  add(b, add(el("span", null, "track"), el("i")));
  const text = el("div", null, "switch-text");
  const name = el("span", label, "switch-label");
  name.id = id + "-label";
  const help = el("small", hint);
  help.id = id + "-hint";
  add(text, name, help);
  add(wrap, text, b);
  return wrap;
}
/* 自動整理新截圖 (provider-bound auto switch) and 每天自動產生回顧 (brief schedule). */
function aiSwitches(parent, rerender, schedule) {
  const box = el("div", null, "switches");
  add(parent, box);
  add(box, toggleSwitch(
    "自動整理新截圖",
    ai.auto_summarize ? "手機上傳的截圖和畫面文字會自動送到你的 AI。" : "關閉中：活動卡只顯示 app 名稱和畫面文字。",
    !!ai.auto_summarize,
    "switch-auto",
    async (enable) => {
      if (
        enable &&
        !(await ask(
          "讓 AI 自動整理新截圖？",
          [
            `目的地：${aiLabel(ai)}（${providerHost()}）`,
            `之後手機上傳的畫面文字${ai.image_support ? "和截圖" : ""}會自動送出，不再逐筆詢問，會持續產生 API 費用。`,
            "更換 AI 後會自動關閉，需要重新打開。",
          ],
          "開啟",
        ))
      )
        return;
      await changeProvider(box, () => box.isConnected, async () => {
        ai = await api("/v1/ai/auto", { provider_id: ai.provider_id, enabled: enable }, "PUT");
        focusKey = "switch-auto";
        await rerender("switch-auto");
        notice(enable ? "已開啟自動整理" : "已關閉自動整理");
      });
    },
  ));
  const scheduleRow = el("div");
  add(box, scheduleRow);
  const renderSchedule = (current) => {
    if (!current) {
      scheduleRow.replaceChildren(el("p", "暫時讀不到每天回顧的設定。", "muted"));
      return;
    }
    const on = current.enabled && current.provider_id === ai.provider_id;
    const at = `${String(Math.floor(current.minute_of_day / 60)).padStart(2, "0")}:${String(current.minute_of_day % 60).padStart(2, "0")}`;
    scheduleRow.replaceChildren(toggleSwitch(
      "每天自動產生回顧",
      on ? `每天 ${at} 整理前一天，週一另外整理上一週。` : "關閉中：需要時可在「回顧」手動產生。",
      on,
      "switch-brief",
      async (enable) => {
        if (
          enable &&
          !(await ask(
            "每天自動產生回顧？",
            [
              `每天 ${at} 把前一天最多 100 張活動卡（標題、摘要、app、時間）送到 ${aiLabel(ai)}（${providerHost()}）；週一另外整理上一週。`,
              "不會送截圖或畫面原文。回顧在你的伺服器保存 30 天，會產生 API 費用。",
            ],
            "開啟",
          ))
        )
          return;
        if (scheduleChangePending) throw Error("先前的變更還在處理，請稍候再試。");
        scheduleChangePending = true;
        try {
          const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || current.timezone;
          await api("/v1/briefs/schedule", {
            ...current,
            enabled: enable,
            provider_id: enable ? ai.provider_id : current.provider_id,
            timezone: enable ? timezone : current.timezone,
            daily: enable ? true : current.daily,
            weekly: enable ? true : current.weekly,
          }, "PUT");
        } catch (error) {
          if (error.status === 409) throw Error("設定剛在別處被更改，請重新整理後再試。");
          throw error;
        } finally {
          scheduleChangePending = false;
        }
        focusKey = "switch-brief";
        await rerender("switch-brief");
        notice(enable ? "已開啟每天回顧" : "已關閉每天回顧");
      },
    ));
  };
  if (schedule !== undefined) renderSchedule(schedule);
  else soft("/v1/briefs/schedule").then((s) => {
    if (!box.isConnected) return;
    renderSchedule(s);
    if (switchFocus === "switch-brief") box.querySelector('[data-key="switch-brief"]')?.focus();
    switchFocus = null;
  }).catch(() => {});
}
function categoriesDialog() {
  modal("分類與自動規則", (container) => {
    const p = el("div");
    add(container, p);
    const editorSeq = renderSeq;
    const current = () => editorSeq === renderSeq && p.isConnected && $("#modal").open;
    add(
      p,
      el(
        "p",
        "分類的名稱和顏色在手機和網站共用。已經有活動卡使用的分類，要先把那些卡片改成別的分類才能移除。",
      ),
    );
    add(p, btn("試算規則（不儲存）", () => {
      preview.scrollIntoView({ block: "start", inline: "nearest" });
      sampleApp.focus({ preventScroll: true });
    }, "quiet"));
    const workspaceBaseline = structuredClone(prefs);
    const draft = structuredClone(workspaceBaseline);
    let invalidatePreview = () => {};
    draft.rules ||= [];
    const categoryList = el("div");
    add(p, categoryList);
    function renderCategory(c) {
      const r = el("div", null, "category-row"),
        color = el("input");
      color.type = "color";
      color.value = c.color;
      color.setAttribute("aria-label", `${c.name}的顏色`);
      const name = el("input");
      name.value = c.name;
      name.maxLength = 40;
      name.setAttribute("aria-label", "分類名稱");
      name.dataset.categoryId = c.id;
      // Roles stay stored for compatibility; the simplified UI has no focus goals.
      color.oninput = () => (c.color = color.value);
      name.oninput = () => (c.name = name.value);
      const wrapper = el("div");
      add(wrapper, r);
      add(categoryList, wrapper);
      add(r, color, name);
      if (c.id.startsWith("custom_"))
        add(
          wrapper,
          btn(
            "移除此分類",
            () => {
              const index = draft.categories.indexOf(c);
              const neighbor = draft.categories[index + 1] || draft.categories[index - 1];
              draft.categories = draft.categories.filter((x) => x.id !== c.id);
              draft.rules = draft.rules.filter((x) => x.category !== c.id);
              wrapper.remove();
              renderRules();
              (categoryList.querySelector(`[data-category-id="${neighbor?.id}"]`) || addCategory).focus();
            },
            "quiet",
          ),
        );
    }
    draft.categories.forEach(renderCategory);
    const addCategory = btn("新增分類", () => {
      if (draft.categories.length >= 40) throw Error("最多 40 個分類");
      const c = {
        id: "custom_" + crypto.randomUUID().replaceAll("-", ""),
        name: "新分類",
        color: "#9bbce1",
        role: "neutral",
      };
      draft.categories.push(c);
      renderCategory(c);
      renderRules();
      categoryList.querySelector(`[data-category-id="${c.id}"]`).focus();
    });
    add(p, addCategory);
    add(
      p,
      el("h3", "自動分類規則"),
      el(
        "p",
        "由上到下，第一條符合的規則生效。同時指定 app 和標題關鍵字時，兩者都要符合。你手動改過的卡片不會被規則改掉。",
      ),
    );
    const ruleList = el("div");
    const ruleStatus = el("p", "", "muted");
    ruleStatus.setAttribute("role", "status");
    add(p, ruleStatus, ruleList);
    function renderRules(focusId, focusApp = false) {
      invalidatePreview();
      ruleList.replaceChildren();
      if (!draft.rules.length)
        add(ruleList, el("p", "還沒有自動規則。", "muted"));
      draft.rules.forEach((r, i) => {
        const block = el("div", null, "card");
        add(ruleList, block);
        block.setAttribute("role", "group");
        block.setAttribute("aria-label", `規則 ${i + 1}`);
        const enabled = check(block, `規則 ${i + 1}：啟用`, r.enabled);
        enabled.onchange = () => (r.enabled = enabled.checked);
        const app = field(block, "完整 app 套件名稱（留白不限）", r.app || "");
        app.oninput = () => (r.app = app.value.trim() || null);
        const choices = el("div");
        choices.hidden = true;
        choices.id = "rule-apps-" + crypto.randomUUID();
        const pick = btn("從最近活動選擇 app", async () => {
          const seq = renderSeq;
          const current = () => seq === renderSeq && block.isConnected && $("#modal").open;
          if (choices.childElementCount && !choices.dataset.failed) {
            choices.hidden = !choices.hidden;
            pick.setAttribute("aria-expanded", String(!choices.hidden));
            return;
          }
          choices.hidden = false;
          pick.setAttribute("aria-expanded", "true");
          const state = el("p", "正在讀取最近活動中的 app…", "muted");
          state.setAttribute("role", "status");
          choices.replaceChildren(state);
          const to = Date.now(), from = to - 29 * 86400000;
          try {
            const overview = await api(`/v1/overview?from_ms=${from}&to_ms=${to}`, null, undefined, current);
            if (!current()) return;
            delete choices.dataset.failed;
            choices.replaceChildren();
            add(choices, el("p", "只列出私人日誌近 29 天活動時間最多的 20 個 app 套件，不是手機安裝清單；未列出的 app 仍可手動輸入。選擇後只填入這條尚未儲存的規則。", "hint"));
            if (!overview.apps.length) {
              add(choices, el("p", "目前沒有已記錄的 app。你可以手動輸入，或收到手機活動後重新開啟這個表單。", "muted"));
              return;
            }
            const choose = select(choices, "選擇已記錄的 app", [["", "請選擇（不會清除手動輸入）"], ...overview.apps.slice(0, 20).map(a => [a.app, a.app])], "");
            const filled = el("p", "", "muted");
            filled.setAttribute("role", "status");
            add(choices, filled);
            choose.onchange = () => {
              if (!choose.value || !current()) return;
              app.value = choose.value;
              app.dispatchEvent(new Event("input", { bubbles: true }));
              filled.textContent = "已填入這條規則；按表單的儲存才會套用。";
            };
            // A slow read must not interrupt typing in another draft field.
            if (document.activeElement === pick || document.activeElement === document.body) {
              choose.focus();
              choose.scrollIntoView({ block: "nearest" });
            }
          } catch (error) {
            if (!current()) return;
            choices.dataset.failed = "true";
            state.textContent = error.status === 404
              ? "伺服器暫時無法提供最近 app；請更新伺服器或手動輸入。再按選擇按鈕可重試。"
              : "暫時讀不到最近 app；手動輸入與草稿仍保留。再按選擇按鈕可重試。";
          }
        });
        pick.setAttribute("aria-controls", choices.id);
        pick.setAttribute("aria-expanded", "false");
        add(block, pick, choices);
        const title = field(
          block,
          "標題包含（留白不限，忽略大小寫）",
          r.title_contains || "",
        );
        title.maxLength = 80;
        title.oninput = () => (r.title_contains = title.value.trim() || null);
        const category = select(
          block,
          "套用分類",
          draft.categories.map((c) => [c.id, c.name]),
          r.category,
        );
        category.onchange = () => (r.category = category.value);
        const moveUp = btn("上移", () => {
          if (i) {
            [draft.rules[i - 1], draft.rules[i]] = [
              draft.rules[i],
              draft.rules[i - 1],
            ];
            ruleStatus.textContent = `規則已移到第 ${i} 條；由上到下採第一個符合的規則。`;
            renderRules(r.id);
          }
        });
        moveUp.disabled = i === 0;
        moveUp.setAttribute("aria-label", `上移規則 ${i + 1}`);
        const remove = btn("刪除規則", () => {
          draft.rules.splice(i, 1);
          const neighbor = draft.rules[i] || draft.rules[i - 1];
          ruleStatus.textContent = `已刪除規則，剩下 ${draft.rules.length} 條。尚未儲存。`;
          renderRules(neighbor?.id);
          if (!neighbor) addRule.focus();
        });
        remove.setAttribute("aria-label", `刪除規則 ${i + 1}`);
        add(block, row(moveUp, remove));
        if (r.id === focusId) (focusApp ? app : enabled).focus();
      });
    }
    renderRules();
    const addRule = btn("新增規則", () => {
      if (draft.rules.length >= 32) throw Error("最多 32 條規則");
      const id = crypto.randomUUID();
      draft.rules.push({
        id,
        category: draft.categories[0].id,
        app: null,
        title_contains: null,
        enabled: true,
      });
      ruleStatus.textContent = `已新增第 ${draft.rules.length} 條規則，請填入 app 或標題條件。尚未儲存。`;
      renderRules(id, true);
    });
    add(p, addRule);
    const preview = panel("先試算，再儲存", "rule-preview");
    add(p, preview);
    add(preview, el("p", "輸入一個範例，看看目前規則會如何分類。不會加入日誌、儲存設定或呼叫 AI；手動修改的活動永遠保留原分類。", "muted"));
    const sampleApp = field(preview, "範例 app 套件名稱", "", "text", "例如 org.mozilla.firefox；需與規則完全相同，區分大小寫。");
    sampleApp.maxLength = 200;
    const sampleTitle = field(preview, "範例活動標題", "", "text", "標題關鍵字忽略大小寫；可留白。");
    sampleTitle.maxLength = 200;
    const baseline = select(preview, "沒有命中規則時的分類", draft.categories.map((c) => [c.id, c.name]), "unsorted");
    const result = el("div", null, "rule-preview-result");
    result.setAttribute("role", "status");
    let epoch = 0;
    invalidatePreview = () => {
      epoch++;
      if (result.textContent) result.replaceChildren(el("p", "設定或範例已變更，請重新試算。", "muted"));
      const selected = baseline.value;
      baseline.replaceChildren(...draft.categories.map((c) => { const option = el("option", c.name); option.value = c.id; return option; }));
      baseline.value = draft.categories.some((c) => c.id === selected) ? selected : "unsorted";
    };
    p.addEventListener("input", invalidatePreview);
    p.addEventListener("change", invalidatePreview);
    add(preview, btn("試算規則", async () => {
      if (!/^[A-Za-z0-9._-]{1,200}$/.test(sampleApp.value.trim())) {
        sampleApp.setCustomValidity("請填完整 app 套件名稱，只含英數字、點、底線或連字號。");
        sampleApp.reportValidity();
        sampleApp.oninput = () => sampleApp.setCustomValidity("");
        return;
      }
      const token = ++epoch;
      const payload = { workspace: structuredClone(draft), sample: { app: sampleApp.value.trim(), title: sampleTitle.value, category: baseline.value, edited: false } };
      result.replaceChildren(el("p", "正在試算目前規則…", "muted"));
      try {
        const response = await api("/v1/workspace/preview", payload, undefined, () => current() && token === epoch);
        if (!current() || token !== epoch) return;
        const name = draft.categories.find((c) => c.id === response.category)?.name || response.category;
        const index = draft.rules.findIndex((r) => r.id === response.matched_rule_id);
        result.replaceChildren(
          el("p", `範例將分類為「${name}」`, "preview-outcome"),
          el("p", index >= 0 ? `命中第 ${index + 1} 條規則；後面的規則不再套用。` : "沒有命中已啟用的規則，使用範例原分類。"),
          el("p", "這是尚未儲存的試算；請按「儲存分類」才會同步設定。", "muted"),
        );
        result.scrollIntoView({ block: "nearest", inline: "nearest" });
      } catch (error) {
        if (!current() || token !== epoch) return;
        result.replaceChildren(el("p", error.status === 404 ? "伺服器尚未支援規則試算，請更新伺服器。分類仍可照常儲存。" : error.status === 400 ? "規則或範例尚未完整，請檢查 app、關鍵字與分類後重試。" : error.message || "試算未完成，請重試。", "callout warn"));
      }
    }), result);
    const recovery = el("section", null, "callout warn");
    recovery.hidden = true;
    recovery.setAttribute("aria-label", "分類儲存復原");
    add(p, recovery);
    let saving = false, recoverySeq = 0;
    function announceRecovery(title, message) {
      recovery.hidden = false;
      const heading = el("h3", title);
      heading.tabIndex = -1;
      recovery.replaceChildren(heading, el("p", message));
      heading.focus({ preventScroll: true });
      requestAnimationFrame(() => {
        if (heading.isConnected && document.activeElement === heading)
          heading.scrollIntoView({ block: "start", inline: "nearest" });
      });
    }
    async function recover(attemptedRevision) {
      const seq = ++recoverySeq;
      announceRecovery("分類草稿已保留", "正在核對私人伺服器的最新設定，尚未重試儲存。");
      let latest;
      try { latest = await api("/v1/workspace", null, undefined, () => current() && seq === recoverySeq); }
      catch {
        if (!current() || seq !== recoverySeq) return;
        announceRecovery("暫時讀不到最新設定", "分類與規則草稿仍在這個表單中。恢復連線後可重新核對；不需要重新整理整個頁面。");
        add(recovery, btn("重新核對最新設定", () => recover(attemptedRevision)));
        return;
      }
      if (!current() || seq !== recoverySeq) return;
      const plan = classificationRecovery(workspaceBaseline, draft, latest, attemptedRevision);
      if (plan.kind === "used") {
        announceRecovery("分類仍被活動使用", "這次不是設定版本衝突。移除的分類仍被已儲存活動使用；請取消移除或先重新分類。其他草稿修改已保留。");
        if (latest.categories.some((c) => !draft.categories.some((d) => d.id === c.id)))
          add(recovery, btn("還原移除的分類，保留其他草稿", () => {
            const removed = latest.categories.filter((c) => !draft.categories.some((d) => d.id === c.id));
            if (draft.categories.length + removed.length > 40) throw Error("還原後會超過 40 個分類；請先移除未使用的新分類，再試一次。");
            removed.forEach((c) => { const restored = structuredClone(c); draft.categories.push(restored); renderCategory(restored); });
            renderRules();
            recovery.hidden = true;
            notice("已還原移除的分類；其他草稿保留。請檢查規則後再儲存。", false, p);
            categoryList.lastElementChild?.querySelector("input")?.focus();
          }));
      } else if (plan.kind === "other") {
        announceRecovery("其他設定已更新，分類草稿仍在", "伺服器上的分類與規則沒有變；可以保留其他最新設定，再儲存你的分類草稿。");
        add(recovery, btn("保留其他最新設定，重試儲存分類", () => saveCategories(classificationRecovery(workspaceBaseline, draft, latest, attemptedRevision).candidate), "primary"));
      } else {
        announceRecovery("遠端分類與規則也已變更", "你的草稿尚未覆蓋遠端版本。請先比較，再決定保留草稿繼續編輯，或明確取代剛讀取的遠端分類與規則。其他設定會保留最新值。");
        const compare = el("details", null, "topic");
        add(compare, el("summary", "查看剛讀取的遠端分類與規則"),
          listOf(false, latest.categories.map((c) => `${c.name} · ${c.color}`)),
          listOf(false, (latest.rules || []).map((r) => `${r.enabled ? "啟用" : "停用"} · ${r.app || "所有 app"} · ${r.title_contains || "所有標題"} → ${latest.categories.find((c) => c.id === r.category)?.name || r.category}`)));
        add(recovery, compare, btn("確認以我的草稿取代遠端分類", async () => {
          if (!(await ask("取代剛讀取的遠端分類與規則？", [
            "會用這個表單中的全部分類與規則取代遠端版本；其他裝置的分類修改可能被取代。",
            "保留剛讀取的其他設定。不會刪除活動；使用中的分類仍不能移除。",
            "如果遠端又有變更，會重新核對，不會自動改向更新的版本儲存。",
          ], "取代分類與規則", true))) return;
          if (p.isConnected) await saveCategories(classificationRecovery(workspaceBaseline, draft, latest, attemptedRevision).candidate);
        }, "danger"));
      }
      add(recovery, btn("繼續編輯草稿", () => {
        recoverySeq++;
        recovery.hidden = true;
        p.querySelector("input")?.focus();
      }), btn("重新核對最新設定", () => recover(attemptedRevision)));
    }
    async function saveCategories(candidate = draft) {
      if (saving) return;
      saving = true;
      recoverySeq++;
      const payload = structuredClone(candidate);
      const controls = [...p.querySelectorAll("input,select,button")].map((n) => [n, n.disabled]);
      controls.forEach(([n]) => { n.disabled = true; });
      try {
        const saved = await api("/v1/workspace", payload, "PUT", current);
        if (!current()) return;
        prefs = saved;
        close();
        await shell();
      } catch (error) {
        if (!current()) return;
        if (error.status !== 409) throw error;
        await recover(payload.revision);
      } finally {
        saving = false;
        controls.forEach(([n, disabled]) => { if (n.isConnected) n.disabled = disabled; });
      }
    }
    add(
      p,
      add(
        el("div", null, "actions"),
        btn("取消", () => close()),
        btn(
          "儲存分類",
          () => saveCategories(),
          "primary",
        ),
      ),
    );
  });
}
function storageLine(storage) {
  return `已用 ${gib(storage.used_bytes)} / ${gib(storage.limit_bytes)}`;
}
/* Storage: MB without decimals under 1 GB, otherwise GB with one decimal. */
function gib(bytes) {
  return bytes < 1024 ** 3 ? `${Math.round(Math.max(0, bytes) / 1024 ** 2)} MB` : `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}
function storageDialog(storage) {
  const seq = renderSeq;
  modal("空間上限", (p) => {
    const current = () => p.isConnected && seq === renderSeq;
    add(
      p,
      el(
        "p",
        "活動卡和畫面文字永久保存。伺服器使用的空間超過上限時，會從最舊、已整理過的截圖開始刪除，只保留文字。",
      ),
      facts([
        ["目前使用", gib(storage.used_bytes)],
        ["截圖", `${storage.image_count.toLocaleString()} 張（${gib(storage.image_bytes)}）`],
      ]),
    );
    const min = storage.min_limit_bytes / 1024 ** 3, max = storage.max_limit_bytes / 1024 ** 3;
    const size = field(p, `上限（GB，${min}–${max}）`, String(Math.round(storage.limit_bytes / 1024 ** 3)), "number");
    size.min = String(min);
    size.max = String(max);
    size.step = "1";
    add(
      p,
      row(
        btn(
          "儲存",
          async () => {
            const gb = Number(size.value);
            if (!Number.isInteger(gb) || gb < min || gb > max) throw Error(`請輸入 ${min}–${max} 之間的整數`);
            if (gb * 1024 ** 3 < storage.used_bytes && !(await ask("立即刪除較舊的截圖？", "新的上限低於目前使用量，背景工作會刪除最舊的截圖直到低於上限；文字會保留。", "儲存上限", true)))
              return;
            if (!current()) return;
            try {
              await api("/v1/storage", { revision: storage.revision, limit_bytes: gb * 1024 ** 3 }, "PUT", current);
            } catch (error) {
              if (error.status === 409) throw Error("容量設定已在其他裝置變更；請關閉後重新開啟");
              throw error;
            }
            if (!current()) return;
            close();
            notice("空間上限已儲存");
            await shell();
          },
          "primary",
        ),
      ),
    );
  });
}
function topic(parent, title, items, ...actions) {
  const d = el("details", null, "topic");
  add(d, el("summary", title), listOf(true, items));
  if (actions.length) add(d, row(...actions));
  add(parent, d);
  return d;
}
function link(text, url) {
  const a = el("a", text);
  a.href = url;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  add(a, el("span", "（在新分頁開啟）", "sr"));
  return a;
}
function exportSnapshot(days, reviews, reviewsTruncated = false) {
  return { version: 1, days, reviews,
    truncated: reviewsTruncated || days.some(d => d.truncated),
    reviews_truncated: reviewsTruncated };
}
async function exportData(days, reviews, reviewsTruncated = false, name = day) {
  const snapshot = exportSnapshot(days, reviews, reviewsTruncated);
  if (
    !(await ask(
      "匯出這段時間的私人記錄？",
      [
        `會下載一個 JSON 檔，內含 ${days.length} 天的活動卡與舊版回顧記錄（不含截圖）。檔案離開 PocketLog 後不受保存與刪除規則保護，請存放在信任的位置。`,
        ...(snapshot.truncated ? ["這份匯出不完整：有些天的記錄超過單次讀取上限，只包含讀到的部分。請勿把它當作完整備份；可縮小範圍或使用私人 API 分段讀取。"] : []),
      ],
      "下載 JSON",
    ))
  )
    return;
  const blob = new Blob(
    [JSON.stringify(snapshot, null, 2)],
    { type: "application/json" },
  );
  const url = URL.createObjectURL(blob);
  const a = el("a");
  a.href = url;
  a.download = `pocketlog-${name}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
const pages = {
  timeline: timelinePage,
  review: reviewPage,
  settings: settingsPage,
};
if (typeof module !== "undefined")
  module.exports = {
    searchActivities, timelineGroups, metrics, layoutLanes, duration, shift, dateKey, fullDayBounds,
    timelineTicks, deviceConnectionState, loadDays, classificationRecovery, exportSnapshot,
    captureStatus, providerSettings, presetFor, aiLabel, appName, cardTitle, AI_PROVIDERS,
    timeAxis, isPrivateHost, originHints, proxyBase, collapseAppOnly, aiSummary, providerName, recordState, deviceStatus, storageLine, weekAverage, askDisclosure, badgeClass, modelsRequest, parseModels, topicsVisible,
    askExamples, weekHours, gib, cardSummary,
    shortDate, weekRange, cardLength, cardTimes, cardBadges,
  };
if (inBrowser) {
  const modality = (value) => () => { document.documentElement.dataset.modality = value; };
  addEventListener("pointerdown", modality("pointer"), true);
  addEventListener("keydown", modality("keyboard"), true);
  inspectorMedia.addEventListener("change", (event) => {
    if (timelineInspector)
      timelineInspector.reset(!event.matches && timelineInspector.ownsFocus && !$("#modal").open);
  });
  $("#modal").addEventListener("close", () => {
    if (!$("#modal").open) {
      $("#modal-body").replaceChildren();
      restoreModalFocus();
    }
    if (refreshOnClose && root.querySelector("nav")) {
      refreshOnClose = false;
      safe(shell);
    }
  });
  const offline = $("#offline");
  const connection = () => (offline.hidden = navigator.onLine !== false);
  addEventListener("online", connection);
  addEventListener("offline", connection);
  connection();
  start();
}
