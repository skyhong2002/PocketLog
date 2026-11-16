"use strict";
/* PocketLog private website. Text is always inserted as text; no HTML, Markdown or
   links from journal/model content. The owner key is never written to a URL, to
   browser storage or to a log. */
const inBrowser = typeof document !== "undefined";
const $ = (s) => document.querySelector(s);
const root = inBrowser ? $("#app") : null;
const STALE = Symbol("stale render");
const REPO = "https://github.com/skyhong2002/PocketLog";
let chatScope = "day";
let conversationId = inBrowser ? crypto.randomUUID() : "";
let chatEpoch = 0, activeAsk = null, refreshChat = null;
let chatCursor = null,
  pendingAsk = null,
  chatDraft = "",
  chatError = "";
let prefs,
  ai,
  page = "timeline",
  day = journalDate(),
  mode = "day",
  // Dense time grids remain available; phone readers start with full-size cards.
  // Only choose at initial load so resizing never overrides an explicit switch.
  view = inBrowser && matchMedia("(max-width: 860px)").matches ? "list" : "grid",
  filter = null,
  chat = [],
  flow = null,
  timerHandle,
  timelineWatch,
  renderSeq = 0,
  focusKey = null,
  mounted = [],
  setupState = null,
  refreshOnClose = false;
const drafts = {};
const inspectorMedia = inBrowser ? matchMedia("(min-width: 1100px)") : null;
let timelineInspector = null, inspectedCard = null;
// One write per activity at a time, including a cancelled/reopened editor.
const cardSaves = new Set();
let providerChangePending = false, scheduleChangePending = false, deviceChangePending = false, agentImportPending = false;
let modalEpoch = 0;
const titles = {
  timeline: "時間軸",
  daily: "每日回顧",
  weekly: "每週回顧",
  chat: "Chat",
  flow: "Flow",
  agents: "Agents",
  support: "支援",
  settings: "設定",
};
const navLabels = {
  timeline: "時間軸",
  daily: "每日",
  weekly: "每週",
  chat: "Chat",
  flow: "Flow",
  agents: "Agents",
  support: "支援",
  settings: "設定",
};
const icons = {
  timeline: "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
  daily:
    "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2.5v2M12 19.5v2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M2.5 12h2M19.5 12h2M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
  weekly: "M4 6h16v14H4zM4 10.5h16M8 3.5v4M16 3.5v4",
  chat: "M4 5h16v11H10l-5 4v-4H4z",
  flow: "M12 6a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM12 9.5V13l2.5 2M9.5 2.5h5",
  agents: "M5 8h14v10H5zM9.5 12.5h.01M14.5 12.5h.01M12 4v4M9 18v2.5M15 18v2.5",
  support:
    "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.6 9.5a2.5 2.5 0 1 1 3.6 2.2c-.8.5-1.2 1-1.2 1.9M12 17h.01",
  settings: "M4 7h9M17 7h3M4 17h3M11 17h9M15 4.5v5M9 14.5v5",
  refresh: "M19.5 11a7.5 7.5 0 1 0-2.2 5.6M19.5 4.5V11H13",
  prev: "M14.5 5.5L8 12l6.5 6.5",
  next: "M9.5 5.5L16 12l-6.5 6.5",
  done: "M5 12.5l4.5 4.5L19 7.5",
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
function check(parent, label, value = false) {
  const l = el("label", null, "check"),
    n = el("input");
  n.type = "checkbox";
  n.checked = value;
  add(l, n, el("span", label));
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
  507: "已達儲存上限；刪除不需要的紀錄後再試",
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
        : "連不上你的 PocketLog 伺服器；請確認網路、VPN／Tailscale 與伺服器狀態",
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
function sharedWeekAxis(dates) {
  // Unequal elapsed-day lengths need their own clocks, including half-hour DST.
  return dates.every(date => {
    const [start, end] = fullDayBounds(date);
    return end - start === 86400000;
  });
}
function appendTimelineTicks(lane, date) {
  for (const tick of timelineTicks(date)) {
    const hour = el("span", tick.label, "hour");
    hour.style.setProperty("--hour", tick.position);
    if (tick.offset) add(hour, el("small", tick.offset));
    add(lane, hour);
  }
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
  if (ms > 0 && ms < 30000) return "不到 1 分鐘";
  const m = Math.round(Math.max(0, ms) / 60000);
  if (m < 60) return `${m} 分鐘`;
  return m % 60
    ? `${Math.floor(m / 60)} 小時 ${m % 60} 分`
    : `${m / 60} 小時`;
}
function shortDate(date, weekday = true) {
  return new Date(date + "T12:00:00").toLocaleDateString("zh-TW", {
    month: "long",
    day: "numeric",
    ...(weekday ? { weekday: "short" } : {}),
  });
}
function dayTitle(date) {
  const today = journalDate();
  const text = shortDate(date);
  if (date === today) return `今天 · ${text}`;
  if (date === shift(today, -1)) return `昨天 · ${text}`;
  return text;
}
function stamp(ms) {
  return new Date(ms).toLocaleString("zh-TW", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
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
if (typeof module !== "undefined")
  module.exports = { timelineGroups, sharedWeekAxis, metrics, layoutLanes, duration, shift, dateKey, fullDayBounds, timelineTicks, deviceConnectionState, loadDays, classificationRecovery, exportSnapshot };

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
  chatEpoch++;
  activeAsk = refreshChat = null;
  pendingAsk = null;
  chatCursor = null;
  chatDraft = chatError = "";
  conversationId = crypto.randomUUID();
  if ($("#confirm").open) $("#confirm").close();
  if ($("#modal").open) $("#modal").close();
  $("#modal-body").replaceChildren();
  chat = [];
  flow = null;
  setupState = null;
  timelineInspector = inspectedCard = null;
  refreshOnClose = false;
  Object.keys(drafts).forEach((k) => delete drafts[k]);
  clearInterval(timerHandle);
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
      "PocketLog 是你自己伺服器上的私人活動日誌：自動整理活動、分類與時間軸，讓你回看一天做了什麼。",
    ),
    listOf(
      false,
      [
        "日誌存放在你或你信任的人架設的伺服器，沒有公開頁面與使用分析。",
        "手機記錄、畫面內容與 AI 都是選用的，預設關閉、各自需要同意。",
        "登入後連接手機、設定紀錄權限，再從時間軸查看與修正活動分類。",
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
  if (!window.isSecureContext)
    add(
      card,
      el(
        "p",
        "這個網址不是 HTTPS。瀏覽器不會保存安全工作階段，金鑰也可能在傳輸中外洩；請改用你的 HTTPS 網址再登入。",
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
      "確認這台裝置的網路，以及需要的 VPN／Tailscale 已連線。",
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

/* ---------- shared first-run setup ---------- */
function onboard(step) {
  renderSeq++;
  clearInterval(timerHandle);
  clearInterval(timelineWatch);
  const names = ["價值", "隱私", "目標", "選用 AI"];
  const main = gate();
  const p = el("section", null, "gate-card onboarding");
  add(main, p);
  const progress = el("progress");
  progress.max = 4;
  progress.value = step + 1;
  progress.setAttribute("aria-label", `初始設定進度：第 ${step + 1} 步，共 4 步`);
  add(p, brand(), el("p", `步驟 ${step + 1}／4 · ${names[step]}`, "eyebrow"), progress);
  let save = () => {};
  const heading = (text) => {
    const h = el("h1", text);
    h.tabIndex = -1;
    add(p, h);
    requestAnimationFrame(() => h.focus({ preventScroll: true }));
  };
  if (step === 0) {
    heading("你的日常，由你決定。");
    add(
      p,
      el(
        "p",
        "PocketLog 把一天整理成可回看的時間軸：自動記錄的活動卡與分類，讓你快速找回做過的事。",
      ),
      el(
        "p",
        "這份初始設定在網站與手機共用，只需完成一次；手機之後只要另外設定連線與系統權限。",
      ),
    );
  }
  if (step === 1) {
    heading("先選擇，才開始記錄。");
    add(
      p,
      el(
        "p",
        "完成這份設定不代表同意任何擷取。手機使用紀錄、畫面文字與圖片都預設關閉，需要在手機上分別同意；外部 AI 只有在你設定模型並開啟「自動摘要」後才會使用。瀏覽器不會擷取你的畫面。",
      ),
      listOf(false, [
        "活動、反思與畫面文字永久保存在你的伺服器；截圖超過容量上限時從最舊的開始刪除，只留文字。",
        "你可以設定排除的 app、暫停收集、匯出與刪除。",
        "伺服器管理者可以讀取日誌，請只使用你信任的私人伺服器。",
      ]),
    );
  }
  if (step === 2) {
    heading("為自己留一點專注。");
    add(
      p,
      el(
        "p",
        "設定每天想投入的時間，以及可接受的分心時間。這些是你選擇的目標，不是對你的一天評分；之後隨時可以修改。",
      ),
    );
    const focus = field(
        p,
        "每天專注目標（分鐘）",
        prefs.focus_minutes,
        "number",
      ),
      budget = field(
        p,
        "分心預算（分鐘）",
        prefs.distraction_minutes,
        "number",
      );
    focus.min = budget.min = "0";
    focus.max = budget.max = "1440";
    focus.inputMode = budget.inputMode = "numeric";
    save = () => {
      const a = Number(focus.value),
        b = Number(budget.value);
      if (
        !Number.isInteger(a) ||
        !Number.isInteger(b) ||
        Math.min(a, b) < 0 ||
        Math.max(a, b) > 1440
      )
        throw Error("請輸入 0–1440 的整數分鐘");
      prefs.focus_minutes = a;
      prefs.distraction_minutes = b;
    };
  }
  if (step === 3) {
    heading("AI 是選用的。");
    add(
      p,
      el(
        "p",
        "沒有 AI 也能使用時間軸、活動分類、專注統計與反思。想要摘要與 Chat 時，可以接上自己的 OpenAI 相容、Anthropic Messages 或 Gemini generateContent API。",
      ),
      el(
        "p",
        ai.enabled
          ? `目前已設定 ${ai.model}（${providerHost()}）。手機內容送出前，仍需在手機上同意這個提供者。`
          : "目前沒有設定任何外部模型，也不會傳送任何內容。之後可以到「設定」新增。",
        "callout",
      ),
      btn(ai.enabled ? "修改 AI 設定" : "現在設定我的 AI", () =>
        providerDialog(() => onboard(3)),
      ),
    );
  }
  const actions = el("div", null, "actions");
  if (step > 0)
    add(
      actions,
      btn("上一步", () => onboard(step - 1)),
    );
  add(
    actions,
    btn(
      step === 3 ? (ai.enabled ? "開始使用" : "先不用 AI，開始使用") : "繼續",
      async () => {
        save();
        if (step < 3) return onboard(step + 1);
        prefs.onboarding_version = 1;
        prefs = await api("/v1/workspace", prefs, "PUT");
        page = "timeline";
        await shell();
      },
      "primary",
    ),
  );
  add(p, actions);
}

/* ---------- getting started ---------- */
function checklistHidden() {
  // Only this display preference is stored; never credentials or journal content.
  try {
    return localStorage.getItem("pocketlog.checklist") === "hidden";
  } catch {
    return false;
  }
}
function setChecklistHidden(hidden) {
  try {
    if (hidden) localStorage.setItem("pocketlog.checklist", "hidden");
    else localStorage.removeItem("pocketlog.checklist");
  } catch {
    /* Private browsing: the checklist simply stays visible. */
  }
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
async function loadSetup() {
  const now = Date.now();
  const [devices, overview] = await Promise.all([
    soft("/v1/devices"),
    soft(`/v1/overview?from_ms=${now - 29 * 86400000}&to_ms=${now}`),
  ]);
  setupState = {
    ...deviceConnectionState(devices, now),
    activity: overview ? overview.categories.length > 0 : null,
  };
  return setupState;
}
function setupComplete(s) {
  return !!s && s.activity === true;
}
function checklist(s, onPage) {
  const box = panel(null, "checklist");
  const head = el("div", null, "checklist-head");
  const unknown = s.keys === null || s.authenticated === null || s.activity === null;
  const steps = [
    {
      done: true,
      title: "登入並完成初始設定",
      text: "你的目標與分類已保存在伺服器，手機會共用同一份設定。",
    },
    {
      done: s.keys > 0 || s.activity === true,
      title: "為手機建立裝置金鑰",
      text:
        s.keys > 0
          ? `已發出 ${s.keys} 把日誌裝置金鑰。在 Android app 填入這個網站的網址與裝置金鑰，再按「驗證並連線」。`
          : "手機不使用 Owner 金鑰。建立一把可單獨撤銷、會到期的裝置金鑰，只顯示一次。",
      extra: rich("p", "muted", "手機要填的網址：", code(location.origin)),
      action: keyed(btn(
        s.keys > 0 ? "管理裝置金鑰" : "建立裝置金鑰",
        devicesDialog,
        s.keys > 0 || s.activity === true ? "" : "primary",
      ), "setup-devices"),
    },
    {
      done: s.authenticated === true,
      title: "在手機驗證裝置金鑰",
      showCompletedDetail: true,
      text: s.authenticated === true
        ? `有效的日誌裝置金鑰最近曾在 ${stamp(s.lastAuthenticatedMs)} 通過驗證（時間約到分鐘）。這不代表手機目前在線、同步成功或錄製權限已開啟。`
        : s.authenticated === null
          ? "目前無法確認金鑰是否曾通過驗證；舊版伺服器可能沒有這項狀態。可在手機檢查連線提示，再分開確認下一步是否收到活動。"
          : s.keys > 0
            ? "尚無連線驗證紀錄。在 Android app 填入伺服器網址與日誌裝置金鑰，按「驗證並連線」，再回這裡重新檢查。金鑰遺失時可撤銷並重建。"
            : "先建立日誌裝置金鑰，再到 Android app 填入網址與金鑰，按「驗證並連線」。",
      action: keyed(btn("重新檢查連線", shell), "setup-connection-recheck"),
    },
    {
      done: s.activity === true,
      title: "收到第一筆手機活動",
      text:
        s.activity === true
          ? "時間軸已經有活動。之後可以點任何一張卡片修正標題與分類。"
          : "在手機 app 連線後，若想自動記錄，請在手機上另外同意並授予「使用紀錄」權限。Android 會自行安排同步，第一筆可能要等十五分鐘以上。畫面擷取需要在手機另外同意，並設定排除 app；不會因完成設定而自動啟用。",
      action:
        s.activity === true
          ? null
          : keyed(btn("重新檢查", shell), "setup-activity-recheck"),
    },
    {
      optional: true,
      done: !!ai.enabled,
      title: "接上自己的 AI",
      text: ai.enabled
        ? `已設定 ${ai.model}。每次摘要或提問前仍會先顯示傳送範圍。`
        : "需要每日摘要或 Chat 時再設定即可；沒有 AI 不影響時間軸與反思。",
      action: keyed(btn(ai.enabled ? "查看 AI 設定" : "設定 AI", () =>
        providerDialog(shell),
      ), "setup-ai"),
    },
  ];
  const required = steps.filter((x) => !x.optional);
  const count = required.filter((x) => x.done).length;
  const bar = el("progress");
  bar.max = required.length;
  bar.value = count;
  bar.setAttribute("aria-label", `已完成 ${count}／${required.length} 個步驟`);
  add(
    head,
    add(
      el("div"),
      el("h2", count === required.length ? "都準備好了" : "開始使用 PocketLog"),
      el(
        "p",
        unknown
          ? "部分狀態暫時讀不到，下面以未完成顯示；可稍後重新檢查。"
          : `已完成 ${count}／${required.length} 個步驟。順序不拘，也可以稍後再回來。`,
        "muted",
      ),
    ),
    bar,
  );
  add(box, head);
  const list = el("ol", null, "steps-list");
  steps.forEach((step) => {
    const li = el("li", null, step.done ? "done" : "");
    const mark = el("span", null, "mark");
    if (step.done) add(mark, icon("done"));
    add(
      mark,
      el("span", step.done ? "已完成：" : step.optional ? "選用：" : "待完成：", "sr"),
    );
    const body = el("div");
    const name = el("h3", step.title);
    if (step.optional) add(name, el("span", "可略過", "tag"));
    add(body, name, el("p", step.text, `${step.done ? "muted" : ""}${step.showCompletedDetail ? " completed-detail" : ""}`));
    if (!step.done && step.extra) add(body, step.extra);
    add(li, mark, body);
    if (step.action) add(li, add(el("div", null, "step-action"), step.action));
    add(list, li);
  });
  add(box, list);
  if (!onPage)
    add(
      box,
      row(
        btn("隱藏清單", async () => {
          setChecklistHidden(true);
          notice("清單已隱藏；隨時可在「支援」重新開啟");
          await shell();
        }),
        el("span", "之後可在「支援」找到這份清單。", "muted"),
      ),
    );
  return box;
}
/* ---------- application shell ---------- */
function restoreFocus(key, complete = false) {
  if (!key) return;
  let n = root.querySelector(`[data-key="${CSS.escape(key)}"]`);
  // The next/previous/today control can become disabled at the date boundary.
  if (n?.disabled && key.startsWith("day-"))
    n = root.querySelector('[data-key="day-date"]');
  // Only choose a fallback after activity cards have finished loading. A saved
  // category can remove the originating card from the active filter/top five.
  if (!n && complete && key.startsWith("card-")) n = $("#main");
  if (n && !n.disabled) {
    n.focus({ preventScroll: true });
    // Rebuilding the shell can reset document scrolling. Keep restored controls
    // visible, including horizontal chips/week grids and phone bottom-bar clearance.
    if (n !== $("#main"))
      n.scrollIntoView({ block: "nearest", inline: "nearest" });
  }
}
function go(next) {
  page = next;
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
        ? "已保存的日誌仍在伺服器上，沒有遺失。恢復連線後按下重試；尚未儲存的文字會保留在這個分頁。"
        : "已保存的日誌沒有受到影響。可以重試，或先切換到其他頁面。",
      "muted",
    ),
    row(
      keyed(btn("重試", shell, "primary"), "retry"),
      btn("查看支援說明", () => go("support")),
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
const dated = ["timeline", "daily", "weekly", "agents", "chat"];
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
  clearInterval(timerHandle);
  clearInterval(timelineWatch);
  const key = focusKey || document.activeElement?.dataset?.key || null;
  focusKey = null;
  mounted = [];
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
    add(b, icon(id), el("span", navLabels[id]));
    b.setAttribute("aria-label", titles[id]);
    if (page === id) b.setAttribute("aria-current", "page");
    add(nav, b);
  });
  const main = el("main");
  main.id = "main";
  main.tabIndex = -1;
  add(root, nav, main);
  const head = el("header", null, "top");
  const heading = el("div", null, "top-title");
  add(heading, el("h1", titles[page]));
  const today = journalDate(),
    oldest = JOURNAL_START;
  const weekly =
    page === "weekly" ||
    (page === "timeline" && mode === "week") ||
    (page === "chat" && chatScope === "week");
  if (dated.includes(page)) {
    const week = weekDates();
    add(
      heading,
      el(
        "p",
        weekly
          ? `${shortDate(week[0], false)} – ${shortDate(week[6], false)} · 含 ${dayTitle(day)}`
          : dayTitle(day),
        "date-line",
      ),
    );
  }
  add(head, heading);
  const controls = el("div", null, "row top-controls");
  if (dated.includes(page)) {
    const prev = keyed(
      iconBtn("prev", "前一天", () => setDay(shift(day, -1))),
      "day-prev",
    );
    prev.disabled = day <= oldest;
    const next = keyed(
      iconBtn("next", "後一天", () => setDay(shift(day, 1))),
      "day-next",
    );
    next.disabled = day >= today;
    const date = keyed(el("input"), "day-date");
    date.type = "date";
    date.value = day;
    date.min = oldest;
    date.max = today;
    date.setAttribute("aria-label", "日誌日期");
    date.onchange = () =>
      safe(async () => {
        if (date.value >= date.min && date.value <= date.max) {
          focusKey = "day-date";
          await setDay(date.value);
        }
      });
    const now = keyed(
      btn("今天", () => setDay(today)),
      "day-today",
    );
    now.disabled = day === today;
    add(controls, add(el("div", null, "date-nav"), prev, date, next), now);
  }
  add(
    controls,
    keyed(
      iconBtn("refresh", "重新整理", async () => {
        prefs = await api("/v1/workspace");
        ai = await api("/v1/ai");
        await shell();
      }),
      "refresh",
    ),
  );
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
async function loadDays(dates, seq) {
  const today = journalDate();
  const days = await Promise.all(
    dates.map(async (date) => ({
      date,
      ...(date > today ? EMPTY_DAY : await api(pathFor(date))),
    })),
  );
  const [from] = windowFor(dates[0]);
  const last = dates.filter((d) => d <= today).at(-1) || dates[0];
  const [, to] = windowFor(last);
  const reviewResponse = await api(`/v1/reviews?from_ms=${from}&to_ms=${to}`);
  const reviews = reviewResponse.records;
  live(seq);
  return { days, cards: days.flatMap((d) => d.cards), reviews, reviewsTruncated: reviewResponse.truncated === true };
}
function reviewLimitNote(parent, truncated) {
  if (truncated) add(parent, el("p",
    "回顧紀錄已達單次讀取上限；只顯示已載入的紀錄，並不代表這段時間的全部。週間可切到單日縮小範圍；若單日仍達上限，請使用私人 API 分段讀取。",
    "callout warn"));
}
function truncatedNote(parent, days, reviewsTruncated = false) {
  reviewLimitNote(parent, reviewsTruncated);
  if (days.some((d) => d.truncated))
    add(
      parent,
      el(
        "p",
        "這個範圍的資料已達顯示上限；下面的統計只涵蓋已載入的活動卡。",
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
    const viewport = root.querySelector(".timeline-panel .timeline-window, .timeline-panel .scroll");
    const position = { x: scrollX, y: scrollY, top: viewport?.scrollTop, left: viewport?.scrollLeft };
    if (!sameDay) day = journalDate();
    focusKey = "timeline-content";
    const expectedRender = renderSeq + 1;
    await shell();
    if (renderSeq !== expectedRender || page !== "timeline" || !sameDay) return;
    const updated = root.querySelector(".timeline-panel .timeline-window, .timeline-panel .scroll");
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
        show("新的一天已開始；目前仍顯示先前載入的日期。載入更新可前往今天。");
        return;
      }
      reading = true;
      try {
        const latest = await api(pathFor(date), null, undefined, current);
        if (!current()) return;
        show(fingerprint(latest) !== baseline
          ? "有新的活動或分類更新；目前仍顯示先前載入的資料。你準備好時可以載入更新。" : "");
      } catch (error) {
        if (current()) show("暫時無法檢查活動更新；目前保留先前載入的資料。可稍後載入更新重試。", true);
      } finally { reading = false; }
    }, 120000);
  });
}
async function timelinePage(parent, seq) {
  const dates = mode === "week" ? visibleDates() : [day];
  const wantSetup = !checklistHidden() && !setupComplete(setupState);
  const week = visibleDates().filter((d) => d <= journalDate());
  const [range, control, setup, weekTotals] = await Promise.all([
    loadDays(dates, seq),
    soft("/v1/collection"),
    wantSetup ? loadSetup() : null,
    mode === "week" ? null : loadDays(week, seq).catch((e) => { if (e === STALE) throw e; return null; }),
  ]);
  live(seq);
  const { days, cards, reviews, reviewsTruncated } = range;
  if (setup && !setupComplete(setup)) add(parent, checklist(setup, false));
  const tools = el("div", null, "toolbar");
  add(
    tools,
    segmented(
      "顯示範圍",
      [
        ["day", "日"],
        ["week", "週"],
      ],
      mode,
      async (id) => {
        mode = id;
        await shell();
      },
      "mode",
    ),
    segmented(
      "呈現方式",
      [
        ["grid", "時間軸"],
        ["list", "清單"],
      ],
      view,
      async (id) => {
        view = id;
        await shell();
      },
      "view",
    ),
  );
  if (control) {
    const state = keyed(
      btn(control.paused ? "收集已暫停" : "接受收集中", () => go("settings")),
      "collection",
    );
    state.className = control.paused ? "pill paused" : "pill";
    state.prepend(el("i", null, "dot"));
    state.setAttribute(
      "aria-label",
      control.paused
        ? "伺服器收集已暫停。開啟設定以恢復"
        : "伺服器接受手機收集中。開啟設定以暫停",
    );
    add(tools, state);
  }
  add(parent, tools);
  const chips = el("div", null, "chips");
  chips.setAttribute("role", "group");
  chips.setAttribute("aria-label", "依分類篩選");
  const chip = (text, id, color) => {
    const b = keyed(
      btn(
        text,
        async () => {
          filter = id === null || filter === id ? null : id;
          await shell();
        },
        filter === id ? "active chip" : "chip",
      ),
      `chip-${id}`,
    );
    b.setAttribute("aria-pressed", String(filter === id));
    if (color) {
      b.prepend(el("i", null, "dot"));
      b.style.setProperty("--cat", color);
    }
    return b;
  };
  add(chips, chip("全部", null));
  prefs.categories.forEach((c) => add(chips, chip(c.name, c.id, c.color)));
  add(chips, keyed(btn("編輯分類", categoriesDialog, "chip ghost"), "chip-edit"));
  add(parent, chips);
  truncatedNote(parent, days, reviewsTruncated);
  watchTimeline(parent, days, seq);
  const grid = el("div", null, "dashboard"),
    left = el("section", null, "timeline-panel"),
    aside = el("aside");
  keyed(left, "timeline-content");
  left.tabIndex = -1;
  left.setAttribute("aria-label", "活動時間軸");
  aside.setAttribute("aria-label", "所選日摘要");
  add(grid, left, aside);
  add(parent, grid);
  const shown = cards.filter((c) => !filter || c.category === filter);
  const selected = days.find((d) => d.date === day) || days[0];
  add(
    left,
    el(
      "p",
      "一天從 04:00 開始（這台裝置的時區）。空白時段代表沒有資料，不會被填補；點選卡片可以修正。",
      "muted",
    ),
  );
  const pendingCount = days.reduce((n, d) => n + d.cards.filter((c) => c.pending_context === true).length, 0);
  if (pendingCount) add(left, el("p", `${pendingCount} 張活動有待處理內容。${pendingContextHelp}`, "muted"));
  if (!cards.length && mode === "day") {
    const isToday = day === journalDate();
    add(
      left,
      emptyState(
        isToday ? "今天還沒有活動紀錄" : "這一天沒有活動紀錄",
        isToday
          ? "手機同步後，活動會出現在這裡；Android 會自行安排同步時間。可在手機設定檢查紀錄權限與同步狀態。"
          : "這天沒有收到手機活動。可切換日期或查看手機紀錄設定。",
        btn("寫每日反思", () => go("daily")),
        btn("手機沒有資料？", () => go("support")),
      ),
    );
  } else if (!shown.length && filter) {
    add(
      left,
      emptyState(
        `這個範圍沒有「${styleFor(filter).name}」的活動`,
        "其他分類仍有紀錄。",
        btn(
          "顯示全部分類",
          async () => {
            filter = null;
            focusKey = "chip-null";
            await shell();
          },
          "primary",
        ),
      ),
    );
  } else if (view === "list") {
    days.forEach((d) => activityList(left, d, mode === "week"));
  } else if (mode === "week") {
    const scroll = el("div", null, "scroll"),
      weekGrid = el("div", null, "week");
    scroll.tabIndex = 0;
    scroll.setAttribute("role", "group");
    scroll.setAttribute("aria-label", "一週時間軸，可上下左右捲動");
    const sharedAxis = sharedWeekAxis(days.map(d => d.date));
    weekGrid.classList.add(sharedAxis ? "shared-axis" : "daily-axes");
    if (sharedAxis) {
      const gutter = el("div", null, "week-axis");
      const heading = el("div", "時間", "week-day week-axis-heading");
      const clock = el("div", null, "timeline week-clock");
      appendTimelineTicks(clock, day);
      add(gutter, heading, clock);
      add(weekGrid, gutter);
    } else {
      add(left, el("p", "這週有時區時間切換，各日刻度分別顯示；卡片位置依當天實際經過時間排列。", "hint"));
    }
    for (const d of days) {
      const col = el("div");
      const total = metrics(d.cards).total;
      const open = btn("", async () => {
        day = d.date;
        mode = "day";
        focusKey = "mode-day";
        await shell();
      }, d.date === day ? "week-day active" : "week-day");
      add(
        open,
        el("strong", shortDate(d.date)),
        el(
          "small",
          d.date > journalDate() ? "尚未到來" : total ? duration(total) : "沒有資料",
        ),
      );
      open.setAttribute("aria-label", `${shortDate(d.date)}，開啟這一天`);
      add(col, open, track(d, true));
      add(weekGrid, col);
    }
    add(scroll, weekGrid);
    add(left, scroll);
    mounted.push(() => {
      // Focus restoration owns the viewport when returning to an activity.
      if (scroll.contains(document.activeElement) && document.activeElement.closest(".event")) return;
      const active = weekGrid.querySelector(".week-day.active");
      if (active) {
        const bounds = scroll.getBoundingClientRect(), column = active.getBoundingClientRect();
        scroll.scrollLeft += column.left - bounds.left - (sharedAxis ? 52 : 0);
      }
      const first = selected.cards.find((c) => !filter || c.category === filter);
      const [start, end] = fullDayBounds(day);
      const anchor = first ? first.start_ms : day === journalDate() ? Date.now() : start;
      // Tracks begin below the sticky date header; retain some leading context.
      scroll.scrollTop = Math.max(0, ((anchor - start) / (end - start)) * 1536 - 48);
    });
  } else {
    const viewport = el("div", null, "timeline-window");
    viewport.tabIndex = 0;
    viewport.setAttribute("role", "group");
    viewport.setAttribute("aria-label", `${dayTitle(day)}的時間軸，可上下捲動`);
    add(viewport, track(days[0], false));
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
        (viewport.scrollTop = Math.max(0, ((anchor - start) / (end - start)) * 1536 - 48)),
    );
  }
  const foot = el("div", null, "panel-foot");
  add(
    foot,
    btn("複製時間軸", () => copyTimeline(cards)),
    btn("匯出 JSON", () => exportData(days, reviews, reviewsTruncated)),
    btn("回放縮圖", replayDialog),
  );
  const totalDays = mode === "week" ? days : weekTotals?.days;
  if (totalDays) {
    const sum = totalDays.reduce((a, d) => a + metrics(d.cards).total, 0);
    add(foot, rich("span", "muted week-total", "本週已載入的記錄 ", el("strong", duration(sum))));
  }
  add(left, foot);
  installTimelineInspector(aside, left, selected ? metrics(selected.cards) : metrics([]), cards, reviews);
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
function installTimelineInspector(aside, left, totals, cards, reviews) {
  const context = `${day}:${mode}:${filter || ""}`;
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
    aside.setAttribute("aria-label", "所選日摘要");
    aside.replaceChildren();
    summary(aside, totals, reviews);
    if (focus) restoreFocus(origin, true);
  };
  const open = (c, focus) => {
    inspectedCard = { key: cardKey(c), context };
    mark(cardKey(c));
    aside.classList.add("inspecting");
    aside.setAttribute("aria-label", "所選活動詳情");
    const box = panel(null, "activity-inspector");
    box.id = "activity-inspector";
    box.style.setProperty("--cat", styleFor(c.category).color);
    const title = el("h2", c.title);
    title.tabIndex = -1;
    add(box,
      el("p", "所選活動", "eyebrow"),
      add(el("div", null, "inspector-actions"),
        keyed(btn("回到當日摘要", () => reset(true)), "inspector-close"),
        keyed(btn("編輯活動卡", () => editCard(c, cardKey(c)), "primary"), "inspector-edit"),
      ),
      title,
      facts([
        ["時間", `${stamp(c.start_ms)} – ${stamp(c.end_ms)}`],
        ["時長", duration(c.end_ms - c.start_ms)],
        ["分類", styleFor(c.category).name],
        ["App", c.app],
        ["內容來源", contentSource(c.source).label],
      ]),
      el("h3", "摘要"),
      el("p", c.summary || "這張卡片沒有摘要，可編輯補充。", "inspector-summary"),
      el("p", contentSource(c.source).detail, "muted"),
      c.pending_context === true ? el("p", pendingContextHelp, "hint") : null,
    );
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
function contentSource(source) {
  switch (source) {
    case "user": return { label: "你的修改", detail: "這張卡片使用你儲存的內容與分類；你的修改優先於自動處理。" };
    case "ai": return { label: "AI 摘要，請核對", detail: "模型根據已同意處理的活動證據產生摘要與分類，可能有誤；你可以在這裡核對並修改。" };
    case "local_excerpt": return { label: "可見文字摘錄", detail: "這份摘要由已收集的可見文字整理，沒有使用 AI 產生；摘錄可能缺少前後文。" };
    case "app_only": return { label: "僅 app 使用紀錄", detail: "這張卡片只有 app 與使用時段，無法僅憑 app 名稱確認你做了什麼。" };
    default: return { label: "來源未提供", detail: "目前資料沒有可辨識的內容來源；請自行核對標題、摘要與分類。" };
  }
}
function pendingContextLabel(c) {
  return c.pending_context === true ? "有待處理內容" : "";
}
const pendingContextHelp = "伺服器仍有內容等待處理。通常在活動結束至少 12 分鐘後處理，工作程序暫停或重試時可能更久；這不代表 AI 正在生成。請稍後重新整理。";
function eventLabel(c) {
  return `${time(c.start_ms)}–${time(c.end_ms)}，${c.title}，${styleFor(c.category).name}，${duration(c.end_ms - c.start_ms)}，${contentSource(c.source).label}${pendingContextLabel(c) ? "，" + pendingContextLabel(c) : ""}`;
}
function track(data, compact) {
  const lane = el("div", null, "timeline");
  const [start, end] = fullDayBounds(data.date);
  const length = end - start;
  lane.style.setProperty("--hour-px", `${(3600000 / length) * 1536}px`);
  appendTimelineTicks(lane, data.date);
  const px = (ms) => (ms / length) * 1536;
  const visible = data.cards.filter((c) => !filter || c.category === filter);
  for (const group of timelineGroups(visible, (24 / 1536) * length, compact ? 4 : 8)) {
    if (group.collapsed) {
      const first = group.placed[0].card;
      const label = `${group.placed.length} 筆活動 · 改用清單`;
      const b = keyed(btn(`${group.placed.length} 筆 · 清單`, async () => {
        view = "list";
        focusKey = cardKey(first);
        await shell();
      }, "event event-density"), `dense-${cardKey(first)}`);
      b.style.setProperty("--top", `${px(group.start - start)}px`);
      b.style.setProperty("--height", `${Math.max(26, px(group.end - group.start))}px`);
      b.style.setProperty("--cat", "var(--accent)");
      b.style.setProperty("--lane", 0);
      b.style.setProperty("--lanes", 1);
      b.setAttribute("aria-label", `${time(group.start)}–${time(group.end)}，${label}。活動密集，清單可逐筆檢視與編輯。`);
      b.title = b.getAttribute("aria-label");
      add(lane, b);
      continue;
    }
    for (const { card: c, lane: index, lanes } of group.placed) {
      const b = keyed(btn("", () => openCard(c), "event"), cardKey(c));
      const height = Math.max(24, px(c.end_ms - c.start_ms));
      const category = styleFor(c.category);
      b.style.setProperty("--top", `${px(c.start_ms - start)}px`);
      b.style.setProperty("--height", `${height}px`);
      b.style.setProperty("--cat", category.color);
      b.style.setProperty("--lane", index);
      b.style.setProperty("--lanes", lanes);
      b.setAttribute("aria-label", eventLabel(c));
      b.title = `${c.title} · ${time(c.start_ms)}–${time(c.end_ms)} · ${contentSource(c.source).label}${pendingContextLabel(c) ? " · " + pendingContextLabel(c) : ""}`;
      add(b, el("strong", c.title));
      if (!compact || height >= 40)
        add(b, el("small", `${time(c.start_ms)}–${time(c.end_ms)}`));
      if (height >= 52 && !compact)
        add(b, el("span", `${pendingContextLabel(c) ? pendingContextLabel(c) + " · " : ""}${c.app} · ${category.name} · ${contentSource(c.source).label}`, "event-meta"));
      if (height >= 84 && !compact && c.summary)
        add(b, el("span", c.summary, "event-summary"));
      add(lane, b);
    }
  }
  if (data.date === journalDate()) {
    const now = el("div", null, "now");
    now.setAttribute("aria-hidden", "true");
    now.style.setProperty("--top", `${px(Date.now() - start)}px`);
    if (!compact) add(now, el("span", "現在"));
    add(lane, now);
  }
  return lane;
}
function activityList(parent, data, showDate) {
  const cards = data.cards.filter((c) => !filter || c.category === filter);
  if (showDate) add(parent, el("h3", shortDate(data.date), "list-date"));
  if (!cards.length) {
    if (showDate)
      add(
        parent,
        el(
          "p",
          data.date > journalDate() ? "尚未到來。" : "沒有符合的活動。",
          "muted",
        ),
      );
    return;
  }
  const list = el("ol", null, "activity-list");
  cards.forEach((c) => {
    const category = styleFor(c.category);
    const b = keyed(btn("", () => openCard(c), "activity"), cardKey(c));
    b.style.setProperty("--cat", category.color);
    b.setAttribute("aria-label", eventLabel(c));
    add(
      b,
      el("span", `${time(c.start_ms)}–${time(c.end_ms)}`, "activity-time"),
      add(
        el("span", null, "activity-main"),
        el("strong", c.title),
        el("small", `${c.app} · ${category.name}`),
        el("small", contentSource(c.source).label),
        c.pending_context === true ? el("small", pendingContextLabel(c)) : null,
      ),
      el("span", duration(c.end_ms - c.start_ms), "activity-length"),
    );
    add(list, add(el("li"), b));
  });
  add(parent, list);
}
function meter(parent, label, value, target, budget) {
  const over = budget && target && value > target * 60000;
  const p = el("progress");
  p.max = Math.max(1, target * 60000);
  p.value = Math.min(value, p.max);
  if (over) p.className = "over";
  const text = target
    ? `${duration(value)}／${duration(target * 60000)}`
    : `${duration(value)}（未設定目標）`;
  p.setAttribute("aria-label", `${label}：${text}${over ? "，已超過預算" : ""}`);
  add(
    parent,
    add(
      el("div", null, "meter"),
      add(el("div", null, "meter-head"), el("h3", label), el("span", text, over ? "danger" : "")),
      p,
    ),
  );
}
function donut(parent, m) {
  const ring = el("div", null, "donut");
  let start = 0;
  const entries = Object.entries(m.totals).sort((a, b) => b[1] - a[1]);
  const stops = entries.map(([id, ms]) => {
    const end = start + (ms / (m.categoryTotal || 1)) * 360;
    const stop = `${styleFor(id).color} ${start}deg ${end}deg`;
    start = end;
    return stop;
  });
  if (stops.length) ring.style.background = `conic-gradient(${stops.join(",")})`;
  ring.setAttribute("role", "img");
  ring.setAttribute(
    "aria-label",
    entries.length
      ? `記錄時間 ${duration(m.total)}：${entries.map(([id, ms]) => `${styleFor(id).name} ${duration(ms)}`).join("、")}`
      : "沒有可統計的活動",
  );
  add(
    ring,
    add(el("div"), el("small", "記錄時間"), el("strong", duration(m.total))),
  );
  add(parent, ring);
  const legend = el("ul", null, "legend");
  entries.forEach(([id, ms]) => {
    const c = styleFor(id),
      line = el("li");
    line.style.setProperty("--cat", c.color);
    add(
      line,
      add(el("span"), el("i", null, "dot"), document.createTextNode(c.name)),
      el(
        "span",
        `${duration(ms)} · ${Math.round((ms / (m.categoryTotal || 1)) * 100)}%`,
      ),
    );
    add(legend, line);
  });
  add(parent, legend);
  if (entries.length) add(parent, el("p", "分類占比依各卡片時間計算；同時進行的活動可能重疊。記錄與目標時間會合併重疊時段。", "muted"));
  if (!entries.length)
    add(parent, el("p", "這天沒有可統計的活動。", "muted center"));
}
function summary(parent, m, reviews) {
  add(parent, el("p", `所選日 · ${dayTitle(day)}`, "eyebrow"));
  const goal = el("section");
  add(
    goal,
    add(el("div", null, "section-head"), el("h2", "目標"), keyed(btn("編輯", goalsDialog, "quiet"), "summary-goals")),
  );
  meter(goal, "專注", m.focus, prefs.focus_minutes, false);
  meter(goal, "分心預算", m.distraction, prefs.distraction_minutes, true);
  add(goal, el("p", "專注與分心由你設定的分類角色決定。", "muted"));
  add(parent, goal);
  const shape = panel("這一天的樣子");
  donut(shape, m);
  add(parent, shape);
  const mine = panel("我的回顧");
  const last = reviews.find((r) => r.id === `review-${day}`);
  const names = { distracted: "分心", neutral: "平衡", focused: "專注" };
  add(
    mine,
    el(
      "p",
      last ? `你把這天標記為「${names[last.rating]}」。` : "還沒有為這天留下回顧。",
      "muted",
    ),
    segmented(
      "這天的專注程度",
      Object.entries(names),
      last?.rating,
      async (rating) => {
        const [at] = windowFor(day);
        await saveRecord({
          id: `review-${day}`,
          at_ms: at,
          kind: "review",
          text: "",
          rating,
        });
        await shell();
      },
      "rating",
    ),
  );
  add(parent, mine);
  const focus = panel("專注");
  add(
    focus,
    add(
      el("div", null, "stats two"),
      stat("專注時間", duration(m.focus)),
      stat("最長連續專注", duration(m.longest)),
    ),
  );
  add(parent, focus);
}
function stat(label, value) {
  return add(el("div", null, "stat"), el("small", label), el("strong", value));
}
async function saveRecord(item) {
  await api("/v1/reviews", {
    end_ms: null,
    rating: null,
    source: null,
    ...item,
  });
}
function editCard(c, originKey = document.activeElement?.dataset?.key || focusKey) {
  let saved = false;
  $("#modal").addEventListener("close", () => {
    // Resizing can remove the inspector Edit button while this dialog is open.
    if (!saved) restoreFocus(originKey, true);
  }, { once: true });
  modal("活動卡", (p) => {
    const seq = renderSeq;
    const current = () => seq === renderSeq && p.isConnected && $("#modal").open;
    const category = styleFor(c.category);
    add(
      p,
      facts([
        ["時間", `${time(c.start_ms)}–${time(c.end_ms)}（${duration(c.end_ms - c.start_ms)}）`],
        ["App", c.app],
        ["目前分類", category.name],
        ["內容來源", contentSource(c.source).label],
      ]),
    );
    add(p, el("p", contentSource(c.source).detail, "muted"));
    if (c.pending_context === true) add(p, el("p", pendingContextHelp, "hint"));
    const title = field(p, "標題", c.title),
      note = field(p, "摘要", c.summary, "textarea"),
      choice = select(
        p,
        "分類",
        prefs.categories.map((c) => [c.id, c.name]),
        c.category,
      );
    title.maxLength = 200;
    note.maxLength = 1000;
    add(
      p,
      el("p", "你的修改會優先於自動分類與模型輸出。", "muted"),
      add(
        el("div", null, "actions"),
        btn("取消", () => close()),
        btn(
          "儲存修改",
          async () => {
            if (cardSaves.has(c.id)) {
              notice("這張活動卡的上一筆儲存仍在處理；草稿已保留，請等候後再儲存。", false, p);
              return;
            }
            const submitted = { id: c.id, title: title.value, summary: note.value, category: choice.value };
            cardSaves.add(c.id);
            try {
              await api("/v1/cards", submitted, undefined, current);
              if (!current()) return;
              if (title.value !== submitted.title || note.value !== submitted.summary || choice.value !== submitted.category) {
                notice("上一筆修改已儲存。你之後輸入的變更仍在這裡，尚未儲存；確認後可再按儲存修改。", false, p);
                return;
              }
              saved = true;
              close();
              focusKey = originKey;
              await shell();
            } catch (error) {
              if (current()) throw error;
            } finally {
              cardSaves.delete(c.id);
            }
          },
          "primary",
        ),
      ),
    );
  });
}

/* ---------- daily and weekly review ---------- */
function categoryBars(parent, m) {
  const max = Math.max(1, ...Object.values(m.totals));
  const chart = el("ul", null, "bars");
  for (const [id, ms] of Object.entries(m.totals).sort((a, b) => b[1] - a[1])) {
    const c = styleFor(id);
    const bar = add(el("div", null, "bar"), el("i"));
    bar.style.setProperty("--progress", `${(ms / max) * 100}%`);
    bar.style.setProperty("--cat", c.color);
    add(
      chart,
      add(
        el("li", null, "chart-row"),
        el("span", c.name),
        bar,
        el("small", duration(ms)),
      ),
    );
  }
  add(parent, chart);
  if (!Object.keys(m.totals).length)
    add(parent, el("p", "這個範圍沒有活動資料；空白不會被推測或填補。", "muted"));
}
async function reviewPage(parent, seq, kind) {
  const { days, cards, reviews, reviewsTruncated } = await loadDays(kind === "weekly" ? visibleDates() : [day], seq);
  const [briefFrom] = windowFor(days[0].date);
  const [, briefTo] = windowFor(
    days.filter((d) => d.date <= journalDate()).at(-1)?.date || days[0].date,
  );
  const saved = await soft(`/v1/briefs?from_ms=${briefFrom}&to_ms=${briefTo}`);
  live(seq);
  truncatedNote(parent, days, reviewsTruncated);
  const m = metrics(cards);
  add(
    parent,
    add(
      el("div", null, "stats"),
      stat("記錄時間", duration(m.total)),
      stat("專注時間", duration(m.focus)),
      stat("最長連續專注", duration(m.longest)),
      stat("分心時間", duration(m.distraction)),
    ),
  );
  const columns = el("div", null, "split");
  add(parent, columns);
  const rhythm = panel(
    kind === "weekly" ? "這週，把時間留給了什麼？" : "這一天的節奏",
  );
  add(
    rhythm,
    el("p", "直接由活動時間計算，不需要 AI。情緒與生產力由你自己判斷。", "muted"),
  );
  categoryBars(rhythm, m);
  const overview = await soft(`/v1/overview?from_ms=${briefFrom}&to_ms=${briefTo}`);
  live(seq);
  const words = overview?.title_keywords || [];
  if (words.length) {
    const chips = el("div", null, "chips");
    words.forEach(([word, count]) => add(chips, el("span", `${word} · ${count}`, "chip")));
    add(
      rhythm,
      el("h3", "主題關鍵字"),
      chips,
      el(
        "p",
        overview.keyword_mode === "local"
          ? "由活動卡標題在伺服器本機擷取。"
          : overview.keyword_mode === "mixed"
            ? "大部分由 AI 依活動卡標題標記；尚未標記的標題以本機擷取補上。"
            : "由 AI 依活動卡標題標記主題（不含 app 工具名稱）。",
        "muted",
      ),
    );
  }
  add(columns, rhythm);
  if (kind === "weekly") {
    const week = panel("七天概覽");
    const list = el("ol", null, "week-rows");
    days.forEach((d) => {
      const dm = metrics(d.cards);
      const future = d.date > journalDate();
      const li = el("li");
      const stack = el("div", null, "stack");
      Object.entries(dm.totals).forEach(([id, ms]) => {
        const seg = el("i");
        seg.style.flexGrow = String(ms);
        seg.style.setProperty("--cat", styleFor(id).color);
        add(stack, seg);
      });
      stack.setAttribute("aria-hidden", "true");
      add(
        li,
        el("strong", shortDate(d.date)),
        el(
          "span",
          future
            ? "尚未到來"
            : dm.total
              ? `記錄 ${duration(dm.total)} · 專注 ${duration(dm.focus)}`
              : "沒有活動資料",
          dm.total ? "" : "muted",
        ),
        stack,
      );
      if (!future)
        add(
          li,
          btn(
            "查看這天",
            async () => {
              day = d.date;
              mode = "day";
              page = "timeline";
              await shell();
            },
            "quiet",
          ),
        );
      add(list, li);
    });
    add(week, list);
    add(columns, week);
    const notes = panel("這週寫下的反思");
    const written = days.filter((d) => d.reflections.length);
    if (!written.length)
      add(
        notes,
        el("p", "這週還沒有反思。可以在「每日」為任何一天寫下幾句。", "muted"),
      );
    written.forEach((d) =>
      add(
        notes,
        add(
          el("article", null, "card"),
          el("h3", shortDate(d.date)),
          el("p", d.reflections.map((r) => r.text).join("\n")),
        ),
      ),
    );
    add(parent, notes);
  } else {
    const d = days[0];
    const top = panel("花最多時間的活動");
    const longest = [...d.cards]
      .sort((a, b) => b.end_ms - b.start_ms - (a.end_ms - a.start_ms))
      .slice(0, 5);
    if (!longest.length)
      add(top, el("p", "這天沒有活動資料；請檢查手機同步狀態或選擇其他日期。", "muted"));
    else {
      const list = el("ol", null, "activity-list");
      longest.forEach((c) => {
        const b = keyed(btn("", () => openCard(c), "activity"), cardKey(c));
        b.style.setProperty("--cat", styleFor(c.category).color);
        b.setAttribute("aria-label", eventLabel(c));
        add(
          b,
          el("span", `${time(c.start_ms)}–${time(c.end_ms)}`, "activity-time"),
          add(
            el("span", null, "activity-main"),
            el("strong", c.title),
            el("small", `${c.app} · ${styleFor(c.category).name}`),
            el("small", contentSource(c.source).label),
            c.pending_context === true ? el("small", pendingContextLabel(c)) : null,
          ),
          el("span", duration(c.end_ms - c.start_ms), "activity-length"),
        );
        add(list, add(el("li"), b));
      });
      add(top, list);
    }
    add(columns, top);
    const write = panel("每日反思");
    const stored = d.reflections.map((r) => r.text).join("\n");
    const reflection = field(
      write,
      `${shortDate(d.date)}的反思`,
      drafts[d.date] ?? stored,
      "textarea",
      "最多 2,000 字，保留 30 天；不會傳送給 AI。",
    );
    reflection.maxLength = 2000;
    const state = el(
      "span",
      drafts[d.date] !== undefined && drafts[d.date] !== stored
        ? "有尚未儲存的修改"
        : stored
          ? "已儲存"
          : "",
      "muted",
    );
    state.setAttribute("role", "status");
    reflection.oninput = () => {
      drafts[d.date] = reflection.value;
      state.textContent = "有尚未儲存的修改";
    };
    add(
      write,
      row(
        btn(
          "儲存反思",
          async () => {
            const [at] = windowFor(d.date);
            const submitted = reflection.value;
            await api("/v1/reflections", {
              id: `reflection-${d.date}`,
              at_ms: Math.min(at + 8 * 3600000, Date.now()),
              text: submitted,
            });
            // A response acknowledges its submitted snapshot, not newer typing.
            const newer = drafts[d.date] !== undefined && drafts[d.date] !== submitted;
            if (!newer) delete drafts[d.date];
            state.textContent = newer ? "有尚未儲存的修改" : "已儲存";
            if (reflection.isConnected)
              notice(newer ? "較早的版本已儲存；新的修改仍未儲存。" : "反思已儲存");
          },
          "primary",
        ),
        state,
      ),
    );
    add(parent, write);
  }
  briefs(parent, days, kind, saved, briefFrom, briefTo, reviews, reviewsTruncated);
}
function briefs(parent, days, kind, saved, from, to, reviews, reviewsTruncated) {
  const box = panel(kind === "weekly" ? "AI 每週回顧（選用）" : "AI 每日回顧（選用）");
  const name = kind === "weekly" ? "每週" : "每日";
  if (!ai.enabled)
    add(
      box,
      el(
        "p",
        "尚未設定 AI，沒有任何內容被傳送。上面的統計與反思都不需要 AI。",
      ),
      row(
        btn("設定自己的 AI", () =>
          providerDialog(shell),
        ),
      ),
    );
  else
    add(
      box,
      el(
        "p",
        `按下後會先顯示傳送範圍，確認才送出。提供者：${providerHost()} · 模型：${ai.model}`,
      ),
      row(
        btn(
          `產生${name}回顧…`,
          async () => {
            const ok = await ask(
              `傳送這段活動給你的 AI？`,
              [
                facts([
                  ["提供者", providerHost()],
                  ["模型", ai.model],
                  ["時間範圍", `${stamp(from)} – ${stamp(to)}`],
                  ["傳送內容", "最多 100 張活動卡的 app、時間、標題與摘要"],
                  ["不會傳送", "原始畫面與文字、感受、反思、回放縮圖"],
                  ["保存", "產生的摘要在你的伺服器保留 30 天，可隨時刪除"],
                ]),
                el(
                  "p",
                  "可能產生 API 費用；送出後無法從提供者收回。模型輸出可能有誤，請對照時間軸。",
                  "muted",
                ),
              ],
              "同意並產生",
            );
            if (!ok) return;
            await api("/v1/briefs", {
              id: crypto.randomUUID(),
              from_ms: from,
              to_ms: to,
              kind: kind === "weekly" ? "weekly" : "daily",
              provider_id: ai.provider_id,
              consent: true,
            });
            await shell();
          },
          "primary",
        ),
        btn("摘要排程", briefScheduleDialog),
      ),
    );
  add(box, el("h3", "已保存的回顧"));
  if (!saved)
    add(box, el("p", "暫時讀不到已保存的回顧；請重新整理。", "callout warn"));
  else if (!saved.briefs.length)
    add(box, el("p", "這個範圍沒有 AI 回顧。", "muted"));
  else
    saved.briefs.forEach((b) =>
      add(
        box,
        add(
          el("article", null, "card"),
          el("h3", b.kind === "weekly" ? "每週摘要" : "每日摘要"),
          el("small", `模型輸出 · ${stamp(b.from_ms)} – ${stamp(b.to_ms)}`),
          el("p", b.text, "message"),
          btn("刪除這份摘要", async () => {
            if (
              !(await ask(
                "永久刪除這份摘要？",
                "只會刪除這份 AI 摘要；活動、感受與反思不受影響。",
                "刪除摘要",
                true,
              ))
            )
              return;
            await api(`/v1/briefs/${b.id}`, null, "DELETE");
            await shell();
          }),
        ),
      ),
    );
  add(parent, box);
  add(
    parent,
    row(btn("匯出這個範圍的 JSON", () => exportData(days, reviews, reviewsTruncated))),
  );
}
async function briefScheduleDialog() {
  const seq = renderSeq, openingEpoch = modalEpoch;
  const openingCurrent = () => seq === renderSeq && openingEpoch === modalEpoch;
  const savedProvider = structuredClone(ai);
  let schedule;
  try { schedule = await api("/v1/briefs/schedule", undefined, undefined, openingCurrent); }
  catch (error) { if (openingCurrent()) throw error; else return; }
  if (!openingCurrent()) return;
  modal("每日／每週 AI 摘要排程", (p) => {
    const current = () => p.isConnected && seq === renderSeq;
    const pendingHint = el("p", "排程變更處理中時，不能同時儲存另一份排程；請稍後再試。這裡的草稿會保留。", "callout");
    pendingHint.setAttribute("role", "status");
    pendingHint.hidden = !scheduleChangePending;
    add(p, pendingHint);
    add(
      p,
      el(
        "p",
        "獨立同意：排程只傳送已完成日／週的最多 100 張活動卡（24 KB），不含原始畫面、心情或反思。使用你的模型並產生 API 費用；每期最多三次嘗試。不補跑歷史。更換模型後需重新同意。刪除日誌會停用排程。",
      ),
    );
    const enabled = check(
      p,
      `同意持續將上述活動卡送到 ${savedProvider.model || "尚未設定的模型"}（${providerHost(savedProvider)}），並保存摘要 30 天`,
      schedule.enabled && schedule.provider_id === savedProvider.provider_id,
    );
    enabled.disabled = !savedProvider.enabled;
    const timezone = field(
      p,
      "IANA 時區",
      schedule.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone,
    );
    const at = field(
      p,
      "每天產生時間（04:00–23:59）",
      `${String(Math.floor(schedule.minute_of_day / 60)).padStart(2, "0")}:${String(schedule.minute_of_day % 60).padStart(2, "0")}`,
      "time",
    );
    at.min = "04:00";
    at.max = "23:59";
    const daily = check(p, "每日摘要", schedule.daily),
      weekly = check(
        p,
        "每週摘要（上一個已完成的週一到週一）",
        schedule.weekly,
      );
    add(
      p,
      add(
        el("div", null, "actions"),
        btn("取消", () => close()),
        btn(
          "儲存排程",
          async () => {
            if (!current()) return;
            if (scheduleChangePending) {
              pendingHint.hidden = false;
              notice("先前的排程仍在儲存，請稍後再試；目前草稿已保留。", false, p);
              return;
            }
            if (enabled.checked && !savedProvider.enabled)
              throw Error("請先設定模型，再核對目的地並同意排程。");
            const parts = at.value.split(":").map(Number);
            if (parts.length !== 2 || !parts.every(Number.isInteger) ||
                parts[0] < 4 || parts[0] > 23 || parts[1] < 0 || parts[1] > 59)
              throw Error("摘要產生時間需介於 04:00–23:59，請修改後重試。");
            const body = {
              revision: schedule.revision,
              enabled: enabled.checked,
              provider_id: savedProvider.provider_id || "",
              timezone: timezone.value.trim(),
              minute_of_day: parts[0] * 60 + parts[1],
              daily: daily.checked,
              weekly: weekly.checked,
            };
            scheduleChangePending = true;
            pendingHint.hidden = false;
            const controls = [...p.querySelectorAll("input,button")].map(n => [n, n.disabled]);
            controls.forEach(([n]) => { n.disabled = true; });
            try {
              await api("/v1/briefs/schedule", body, "PUT", current);
              if (!current()) return;
              close();
              notice("摘要排程已更新");
            } catch (error) {
              if (current()) throw error;
            } finally {
              scheduleChangePending = false;
              controls.forEach(([n, disabled]) => { if (n.isConnected) n.disabled = disabled; });
              if (current()) pendingHint.hidden = true;
            }
          },
          "primary",
        ),
      ),
    );
  });
}

/* ---------- chat ---------- */
function chatDates() {
  return chatScope === "week"
    ? weekDates().filter(
        (d) => d <= journalDate() && d >= JOURNAL_START,
      )
    : [day];
}
function chatRange() {
  const dates = chatDates();
  return [windowFor(dates[0])[0], windowFor(dates.at(-1))[1]];
}
function turnsOf(thread) {
  return thread.turns.flatMap((t) => [
    { role: "user", text: t.question },
    { role: "assistant", text: t.answer },
  ]);
}
async function chatPage(parent, seq) {
  const list = await soft("/v1/conversations");
  live(seq);
  const saved = list ? list.conversations : [];
  const p = el("section", null, "chat");
  add(parent, p);
  add(
    p,
    el(
      "p",
      "向自己的 AI 詢問選定的日／週。每次提問只送出選定範圍最多 100 張活動卡的 app、時間、標題與摘要；不含原始畫面、文字、心情及反思。回答可能有誤，請核對時間軸。",
    ),
  );
  if (!ai.enabled)
    add(
      p,
      add(
        el("div", null, "callout"),
        el(
          "p",
          "尚未設定 AI，所以現在無法提問；已保存的對話仍可在下方閱讀與刪除。",
        ),
        btn("設定自己的 AI", () =>
          providerDialog(shell),
        ),
      ),
    );
  if (!list)
    add(p, el("p", "暫時讀不到已保存的對話；請重新整理。", "callout warn"));
  const history = select(
    p,
    "已保存的對話（保留 30 天）",
    [
      ["", saved.length ? "選擇要接續的對話" : "還沒有保存的對話"],
      ...saved.map((t) => [t.id, t.title]),
    ],
    saved.some((t) => t.id === conversationId) ? conversationId : "",
  );
  keyed(history, "chat-history");
  history.disabled = !saved.length;
  let historyRead = 0, historyLoading = false, sendControl = null, deleteControl = null;
  const showHistoryLoading = (value) => {
    historyLoading = value;
    renderLog();
    if (sendControl) sendControl.disabled = value || !!activeAsk;
    if (deleteControl) deleteControl.disabled = value;
  };
  history.onchange = () =>
    safe(async () => {
      const request = ++historyRead, id = history.value;
      const current = () => request === historyRead && seq === renderSeq && history.isConnected;
      if (!id) {
        history.value = saved.some((t) => t.id === conversationId) ? conversationId : "";
        showHistoryLoading(false);
        return;
      }
      if (id === conversationId && activeAsk?.epoch === chatEpoch) {
        showHistoryLoading(false);
        return;
      }
      showHistoryLoading(true);
      try {
        const thread = await api(`/v1/conversations/${id}`, null, undefined, current);
        if (!current()) return;
        chatEpoch++;
        conversationId = id;
        chatCursor = thread.next_cursor;
        pendingAsk = null;
        chatError = "";
        chat = turnsOf(thread);
        focusKey = "chat-history";
        await shell();
      } catch (error) {
        if (!current()) return;
        history.value = saved.some((t) => t.id === conversationId) ? conversationId : "";
        throw error;
      } finally {
        if (current()) showHistoryLoading(false);
      }
    });
  const existing = saved.some((t) => t.id === conversationId);
  const fresh = () => {
    chatEpoch++;
    conversationId = crypto.randomUUID();
    chatCursor = null;
    pendingAsk = null;
    chatError = "";
    chat = [];
  };
  add(
    p,
    row(
      keyed(
        btn("開啟新對話", async () => {
          fresh();
          await shell();
        }),
        "chat-new",
      ),
      existing || chat.length
        ? (deleteControl = btn("刪除此對話", async () => {
            const id = conversationId, epoch = chatEpoch;
            const owns = () => id === conversationId && epoch === chatEpoch;
            if (
              !(await ask(
                "永久刪除這個對話？",
                "這個對話在伺服器上的所有問答都會刪除，無法復原；已送到模型提供者的內容無法收回。",
                "刪除對話",
                true,
              ))
            )
              return;
            try {
              await api(`/v1/conversations/${id}`, null, "DELETE", owns);
              if (!owns()) return;
              fresh();
              focusKey = "chat-new";
              await shell();
            } catch (error) {
              if (owns()) throw error;
            }
          }))
        : null,
    ),
  );
  const log = el("div", null, "transcript");
  log.setAttribute("role", "log");
  log.setAttribute("aria-label", "對話內容");
  add(p, log);
  const waiting = () => activeAsk?.epoch === chatEpoch && activeAsk?.id === conversationId;
  const question = el("textarea");
  const renderLog = () => {
    log.replaceChildren();
    log.setAttribute("aria-busy", String(historyLoading || waiting()));
    if (historyLoading) {
      const status = el("p", "正在讀取所選對話…", "loading-state");
      status.setAttribute("role", "status");
      add(log, status);
      return;
    }
    if (chatCursor)
      add(
        log,
        btn("載入更早的問答", async () => {
          const id = conversationId, cursor = chatCursor, request = ++historyRead;
          const current = () => request === historyRead && seq === renderSeq && p.isConnected && conversationId === id;
          try {
            const thread = await api(
              `/v1/conversations/${id}?before_ms=${cursor.before_ms}&before_id=${encodeURIComponent(cursor.before_id)}`,
              null, undefined, current,
            );
            if (!current()) return;
            chatCursor = thread.next_cursor;
            chat = [...turnsOf(thread), ...chat];
            renderLog();
          } catch (error) {
            if (current()) throw error;
          }
        }),
      );
    if (!chat.length && !waiting())
      add(
        log,
        el(
          "p",
          ai.enabled
            ? "這是新的對話。下面的範例只會填入輸入框，不會自動送出。"
            : "沒有開啟中的對話。",
          "muted",
        ),
      );
    chat.forEach((m) => {
      const bubble = el("div", null, `message ${m.role}`);
      add(
        bubble,
        el("small", m.role === "user" ? "你" : "模型輸出"),
        el("p", m.text),
      );
      add(log, bubble);
    });
    if (waiting()) {
      const wait = el("div", null, "message assistant pending");
      wait.setAttribute("role", "status");
      add(wait, el("small", "模型輸出"), el("p", "正在等待模型回答（最多約 10 秒）…"));
      add(log, wait);
    }
    if (chatError) {
      const fail = el("div", null, "callout warn");
      fail.setAttribute("role", "alert");
      add(
        fail,
        el(
          "p",
          `這次提問沒有完成：${chatError}。草稿會保留；重新勾選同意後可以重試原問題，已完成的回答不會重複計費。`,
        ),
      );
      if (pendingAsk) add(fail, el("p", `原問題：${pendingAsk.body.question}`));
      add(log, fail);
    }
  };
  renderLog();
  if (!ai.enabled) return;
  const composer = el("div", null, "composer");
  add(p, composer);
  const [from, to] = chatRange();
  add(
    composer,
    row(
      segmented(
        "提問範圍",
        [
          ["day", "所選日"],
          ["week", "所選日的那一週"],
        ],
        chatScope,
        async (id) => {
          chatScope = id;
          await shell();
        },
        "chat-scope",
      ),
      btn("查看這個範圍的分類圖表", () => chatChart(from, to)),
    ),
    facts([
      ["提問範圍", `${stamp(from)} – ${stamp(to)}`],
      ["提供者／模型", `${providerHost()} · ${ai.model}`],
      ["上限", "100 張活動卡／24,000 位元組，問題 1,000 字"],
    ]),
  );
  const ideas = el("div", null, "chips");
  ideas.setAttribute("role", "group");
  ideas.setAttribute("aria-label", "範例問題（只填入輸入框）");
  [
    "這段時間我主要把時間花在哪些事情上？",
    "哪些時段最容易被打斷？",
    "幫我條列這段時間完成的事項。",
  ].forEach((text) =>
    add(
      ideas,
      btn(
        text,
        () => {
          question.value = chatDraft = text;
          question.focus();
        },
        "chip",
      ),
    ),
  );
  add(composer, ideas);
  const label = el("label", "你想問什麼？");
  question.id = "chat-question";
  label.htmlFor = question.id;
  question.maxLength = 1000;
  question.value = chatDraft;
  question.oninput = () => (chatDraft = question.value);
  keyed(question, "chat-question");
  add(composer, label, question);
  const consent = check(
    composer,
    "我同意這次將上述範圍的活動卡、問題與本對話最近最多 6 輪（6,000 字）的內容傳給這個模型，並將問答保存在私人伺服器 30 天；API 費用由我承擔。",
  );
  const send = sendControl = btn(
    "傳送這次提問",
    async () => {
      if (activeAsk) throw Error("上一個提問仍在等待回答，完成後才能再次傳送。");
      if (historyLoading) throw Error("所選對話仍在讀取，請稍候再提問。");
      if (!consent.checked) throw Error("請先勾選同意這次的傳送範圍");
      const q = question.value.trim();
      if (!q) throw Error("請輸入問題");
      const requestKey = JSON.stringify([
        conversationId,
        q,
        day,
        chatScope,
        ai.provider_id,
      ]);
      if (pendingAsk?.key !== requestKey)
        pendingAsk = {
          key: requestKey,
          body: {
            conversation_id: conversationId,
            request_id: crypto.randomUUID(),
            question: q,
            from_ms: from,
            to_ms: to,
            provider_id: ai.provider_id,
            consent: true,
          },
        };
      consent.checked = false;
      chatError = "";
      const message = { role: "user", text: q };
      const op = activeAsk = { id: conversationId, epoch: chatEpoch, retry: pendingAsk, message };
      const owns = () => op.epoch === chatEpoch && op.id === conversationId;
      chat.push(message);
      question.value = chatDraft = "";
      refreshChat();
      let stored = false;
      try {
        const result = await api("/v1/ask", op.retry.body, undefined, owns);
        stored = result.stored === true;
        if (!owns()) return;
        if (pendingAsk === op.retry) pendingAsk = null;
        chat.push({ role: "assistant", text: result.answer });
        chat = chat.slice(-20);
      } catch (e) {
        if (!owns()) return;
        // Remove exactly this optimistic turn, never another thread's last turn.
        chat = chat.filter(m => m !== op.message);
        if (!chatDraft) chatDraft = q;
        chatError = e.message;
      } finally {
        if (activeAsk === op) activeAsk = null;
        refreshChat?.();
      }
      if (owns() && stored && page === "chat") {
        if (document.activeElement === document.body) focusKey = "chat-question";
        await shell();
      }
    },
    "primary",
  );
  const busy = el("p", "", "muted");
  busy.setAttribute("role", "status");
  refreshChat = () => {
    if (seq !== renderSeq || !p.isConnected) return;
    send.disabled = historyLoading || !!activeAsk;
    send.setAttribute("aria-busy", String(!!activeAsk));
    busy.textContent = activeAsk && !waiting()
      ? "上一個對話的提問仍在等待回答；你可以先寫草稿，完成後再傳送。" : "";
    if (question.value !== chatDraft) question.value = chatDraft;
    renderLog();
  };
  add(
    composer,
    send,
    busy,
    el(
      "p",
      "對話可於網站與手機接續。更換模型設定後請開啟新對話；舊對話不會轉送給新提供者。",
      "muted",
    ),
  );
  mounted.push(refreshChat);
}
async function chatChart(from, to) {
  const data = await api(`/v1/overview?from_ms=${from}&to_ms=${to}`);
  modal("活動分類圖表", (body) => {
    add(
      body,
      el(
        "p",
        "直接從所選範圍的實際活動計算，不需要模型。重疊裝置活動可能同時計入；分類可由你修改。",
      ),
    );
    const max = Math.max(1, ...data.categories.map((c) => c.duration_ms));
    if (!data.categories.length) add(body, el("p", "這個範圍沒有活動。", "muted"));
    data.categories.forEach((c) => {
      const bar = el("progress");
      bar.max = max;
      bar.value = c.duration_ms;
      bar.setAttribute(
        "aria-label",
        `${styleFor(c.category).name} ${duration(c.duration_ms)}`,
      );
      add(
        body,
        el("p", `${styleFor(c.category).name} · ${duration(c.duration_ms)}`),
        bar,
      );
    });
  });
}
async function replayDialog() {
  const seq = renderSeq, date = day;
  const [from, to] = windowFor(date);
  let load, deleting = false, attempt = 0;
  modal("短期畫面回放", (p) => {
    add(p,
      el("p", `${dayTitle(date)} · 只顯示這一天的核准縮圖`, "eyebrow"),
      el("p", "只有在手機另外同意保存供回放的縮圖會顯示，最多 24 小時／256 張，最長邊 480 px。原本只供分析的原始畫面仍不可讀回。圖片可能含有私人資訊；預先排除並不等於 OCR 遮罩。"),
    );
    const holder = el("div", null, "replay-view");
    add(p, holder);
    const active = () => seq === renderSeq && holder.isConnected && $("#modal").open;
    load = async () => {
      if (deleting) return;
      const restore = holder.contains(document.activeElement);
      const generation = ++attempt;
      const currentLoad = () => active() && generation === attempt;
      holder.replaceChildren(el("p", "讀取這一天的縮圖清單…", "loading-state"));
      holder.setAttribute("aria-busy", "true");
      let images;
      try {
        images = (await api(`/v1/replay?from_ms=${from}&to_ms=${to}`)).images;
      } catch (e) {
        if (!currentLoad()) return;
        holder.removeAttribute("aria-busy");
        const retry = btn("重新讀取清單", load);
        holder.replaceChildren(emptyState("無法讀取縮圖清單", e.message || "請檢查網路後再試。", retry));
        if (restore) retry.focus();
        return;
      }
      if (!currentLoad()) return;
      holder.removeAttribute("aria-busy");
      const stage = el("div", null, "replay-stage"), position = el("p"), controls = row();
      position.setAttribute("role", "status");
      let index = 0;
      // These controls own persistent boundary/busy state. The generic btn helper
      // restores disabled=false after actions, which would undo boundary updates.
      const control = (label, action) => {
        const button = el("button", label);
        button.type = "button";
        button.onclick = () => safe(action, button);
        return button;
      };
      const previous = control("上一張", () => { index = Math.max(0, index - 1); render(); });
      const next = control("下一張", () => { index = Math.min(images.length - 1, index + 1); render(); });
      const remove = control("刪除這張", async () => {
        const item = images[index];
        if (!item || deleting) return;
        if (!(await ask("永久刪除這張回放縮圖？", `${stamp(item.at_ms)} · ${item.app}。只刪除這張縮圖，無法復原。`, "刪除縮圖", true)) || !active()) return;
        deleting = true;
        updateControls();
        try {
          await api(`/v1/replay/${item.id}`, null, "DELETE");
          if (!currentLoad()) return;
          images = images.filter((candidate) => candidate.id !== item.id);
          index = Math.max(0, Math.min(index, images.length - 1));
          render();
        } finally {
          deleting = false;
          if (active()) {
            updateControls();
            // The confirmed image may have been the last one; keep focus in the dialog.
            if (images.length) remove.focus();
            else { position.tabIndex = -1; position.focus(); }
          }
        }
      });
      const updateControls = () => {
        controls.hidden = !images.length;
        previous.disabled = deleting || index === 0;
        next.disabled = deleting || index >= images.length - 1;
        remove.disabled = deleting;
      };
      const render = () => {
        const action = document.activeElement;
        const restoreStage = stage.contains(action);
        stage.replaceChildren();
        updateControls();
        if (restoreStage || controls.contains(action) && action.disabled) {
          position.tabIndex = -1; position.focus();
        }
        if (!images.length) {
          position.textContent = "這一天沒有已同意且仍在保存期限內的縮圖。回放需要在手機上另外開啟。";
          return;
        }
        const item = images[index];
        position.textContent = `${index + 1} / ${images.length} · ${stamp(item.at_ms)} · ${item.app}`;
        const state = el("p", "載入核准縮圖…", "loading-state");
        state.setAttribute("role", "status");
        const image = el("img");
        image.hidden = true;
        image.alt = `${item.app} ${stamp(item.at_ms)} 的核准縮圖`;
        const current = () => currentLoad() && images[index]?.id === item.id && image.isConnected;
        image.onload = () => {
          if (!current()) return;
          state.remove();
          image.hidden = false;
        };
        image.onerror = () => {
          if (!current()) return;
          image.remove();
          state.replaceChildren(
            el("span", "縮圖無法載入，可能已到期、被刪除或連線中斷。"),
            btn("重試這張縮圖", render),
            btn("重新讀取清單", load),
          );
          state.className = "callout warn replay-error";
        };
        add(stage, state, image);
        image.src = `/v1/replay/${item.id}/image`;
      };
      add(controls, previous, next, remove);
      holder.replaceChildren(position, controls, stage);
      render();
      if (restore) { position.tabIndex = -1; position.focus(); }
    };
  });
  await load();
}

/* ---------- flow ---------- */
async function flowPage(parent, seq) {
  const [from, to] = windowFor(journalDate());
  const [session, records] = await Promise.all([
    api("/v1/focus"),
    soft(`/v1/reviews?from_ms=${from}&to_ms=${to}`),
  ]);
  live(seq);
  flow = session.session;
  const p = panel("留一段時間，做一件事。", "flow");
  add(parent, p);
  add(
    p,
    el(
      "p",
      "私人專注計時：由你開始、結束並記下成果。只有完成時才儲存這段紀錄，不會在背景擷取畫面，也不會把計時當作實際活動。",
    ),
  );
  const name = field(p, "這次想專注在什麼？", flow?.text || "");
  name.maxLength = 300;
  const minutes = field(p, "預計分鐘（1–180）", flow?.minutes || 25, "number");
  minutes.min = "1";
  minutes.max = "180";
  minutes.inputMode = "numeric";
  const timer = el("div", null, "timer");
  timer.setAttribute("role", "timer");
  timer.setAttribute("aria-live", "off");
  const status = el("p", null, "center");
  status.setAttribute("role", "status");
  add(p, timer, status);
  let announced = false;
  const update = () => {
    const planned = Math.max(0, Math.min(180, +minutes.value || 0)) * 60000;
    const remaining = flow
      ? Math.max(0, flow.at_ms + flow.minutes * 60000 - Date.now())
      : planned;
    timer.textContent = `${String(Math.floor(remaining / 60000)).padStart(2, "0")}:${String(Math.floor(remaining / 1000) % 60).padStart(2, "0")}`;
    if (flow && remaining <= 0 && !announced) {
      announced = true;
      status.textContent = "時間到了。儲存成果，或放下這次計時；尚未儲存。";
    } else if (flow && !announced && !status.textContent)
      status.textContent = `進行中，預計 ${time(flow.at_ms + flow.minutes * 60000)} 結束。可以離開這個頁面，計時保存在伺服器。`;
  };
  update();
  minutes.oninput = update;
  timerHandle = setInterval(() => {
    if (seq !== renderSeq) return clearInterval(timerHandle);
    if (!document.hidden) update();
  }, 1000);
  name.disabled = minutes.disabled = !!flow;
  const actions = row(
    keyed(
      btn(
        flow ? "儲存並結束" : "開始專注",
        async () => {
          if (flow) {
            await api("/v1/focus/finish", { id: flow.id });
            flow = null;
            notice("專注紀錄已儲存");
            await shell();
          } else {
            if (
              !Number.isInteger(+minutes.value) ||
              +minutes.value < 1 ||
              +minutes.value > 180 ||
              !name.value.trim()
            )
              throw Error("請填寫主題與 1–180 分鐘");
            const next = {
              id: crypto.randomUUID(),
              at_ms: Date.now(),
              minutes: +minutes.value,
              text: name.value,
            };
            flow = (await api("/v1/focus", next)).session;
            await shell();
          }
        },
        "primary",
      ),
      "flow-main",
    ),
  );
  if (flow)
    add(
      actions,
      btn("放下這次計時", async () => {
        if (
          !(await ask(
            "放下這次計時？",
            "這段計時不會被儲存為專注紀錄。",
            "放下計時",
            true,
          ))
        )
          return;
        await api("/v1/focus", { id: flow.id }, "DELETE");
        flow = null;
        await shell();
      }),
    );
  add(p, add(el("div", null, "center-row"), actions));
  add(
    p,
    el(
      "p",
      "計時保存在私人伺服器，可在手機與網站接續；重新整理後可恢復。這是 PocketLog 的私人專注功能，與 Dayflow 的 Flow 雲端服務無關。",
      "muted",
    ),
  );
  const done = panel("今天完成的專注");
  add(parent, done);
  if (!records)
    return add(done, el("p", "暫時讀不到今天的專注紀錄；請重新整理。", "callout warn"));
  reviewLimitNote(done, records.truncated);
  const finished = records.records.filter((r) => r.kind === "flow");
  if (!finished.length)
    add(done, el("p", records.truncated ? "已載入的紀錄中沒有完成的專注；較早紀錄可能未載入。" : "今天還沒有完成的專注紀錄。", "muted"));
  finished.forEach((r) =>
    add(
      done,
      add(
        el("article", null, "card"),
        el("h3", r.text),
        el(
          "p",
          `${time(r.at_ms)}–${time(r.end_ms)} · ${duration(r.end_ms - r.at_ms)}`,
        ),
      ),
    ),
  );
}

/* ---------- agents ---------- */
function agentImportDialog() {
  const seq = renderSeq;
  modal("預覽原生 Agent 工作紀錄", (p) => {
    const current = () => p.isConnected && seq === renderSeq;
    add(
      p,
      el(
        "p",
        "只在瀏覽器解析你選取的檔案。顯示近 30 天最後 40 則文字，每則最多 1000 字；工具輸出、附件、環境與路徑中繼資料不會匯入。請逐則檢查並移除金鑰、個資與不想保存的內容，只有勾選的文字會送到私人伺服器，不會送到 AI。",
      ),
    );
    add(p, el("p", "匯入送出後，關閉視窗不會取消。若不確定是否成功，先重新整理已核准的工作紀錄，再決定是否重試。", "muted"));
    const source = select(
      p,
      "來源",
      [
        ["codex", "Codex response_item JSONL"],
        ["claude", "Claude user/assistant JSONL"],
      ],
      "codex",
    );
    const file = field(p, "選取 JSONL（最多 512 KiB／2000 行）", "", "file");
    file.accept = ".jsonl,application/json,text/plain";
    const project = field(p, "專案／工作線名稱（不要填私人完整路徑）", ""),
      title = field(p, "工作標題", "");
    project.maxLength = 80;
    title.maxLength = 200;
    const preview = el("div");
    let choices = [];
    const upload = btn(
      "匯入勾選的文字",
      async () => {
        const selected = choices.filter((c) => c.selected.checked);
        if (!selected.length) throw Error("請先預覽並勾選要保存的文字");
        if (selected.some((c) => c.source !== source.value))
          throw Error("來源已改變，請重新預覽");
        if (!project.value.trim() || !title.value.trim())
          throw Error("請填寫專案與標題");
        const turns = selected.map((c) => ({
          ...c.t,
          text: c.text.value.trim(),
        }));
        const body = {
          id: crypto.randomUUID(),
          revision: 0,
          at_ms: turns.at(-1).at_ms,
          source: source.value,
          project: project.value.trim(),
          title: title.value.trim(),
          status: "completed",
          turns,
        };
        if (new TextEncoder().encode(JSON.stringify(body)).length > 64000)
          throw Error("選取內容超過 64 KB，請減少文字");
        if (!current()) return;
        if (agentImportPending) throw Error("先前的匯入仍在處理，請稍後再試；目前草稿已保留。");
        agentImportPending = true;
        const controls = [...p.querySelectorAll("input,select,textarea,button")].map(n => [n, n.disabled]);
        controls.forEach(([n]) => { n.disabled = true; });
        try {
          await api("/v1/agent-sessions", body, "PUT", current);
          if (!current()) return;
          close();
          await shell();
        } catch (error) { if (current()) throw error; }
        finally {
          agentImportPending = false;
          controls.forEach(([n, disabled]) => { if (n.isConnected) n.disabled = disabled; });
        }
      },
      "primary",
    );
    const count = () => {
      const n = choices.filter((c) => c.selected.checked).length;
      upload.textContent = choices.length
        ? `匯入勾選的 ${n}／${choices.length} 則文字`
        : "匯入勾選的文字";
    };
    add(
      p,
      btn("在此裝置預覽", async () => {
        const f = file.files[0];
        if (!f || f.size > 524288) throw Error("請選擇 512 KiB 內的 JSONL");
        const turns = parseAgentJsonl(await f.text(), source.value);
        preview.replaceChildren();
        choices = [];
        if (!turns.length)
          add(preview, el("p", "這個檔案沒有近 30 天可匯入的文字。", "callout"));
        else
          add(
            preview,
            el("p", "預設都不勾選。勾選並檢查每一則要保存的文字：", "muted"),
          );
        turns.forEach((t) => {
          const block = el("article", null, "card");
          add(preview, block);
          const selected = check(block, `${t.role} · ${stamp(t.at_ms)}`);
          selected.onchange = count;
          const text = field(block, "核准後保存的文字", t.text, "textarea");
          text.maxLength = 1000;
          choices.push({ t, selected, text, source: source.value });
        });
        count();
      }),
      preview,
      add(el("div", null, "actions"), btn("取消", () => close()), upload),
    );
  });
}
async function agentReplay(id) {
  const session = await api(`/v1/agent-sessions/${id}`);
  modal(session.title, (p) => {
    add(
      p,
      el(
        "p",
        `${session.project} · ${session.source} · 這些是你核准的文字紀錄；不會執行其中的工具或連結。`,
      ),
    );
    const status = select(
      p,
      "工作狀態",
      [
        ["active", "進行中"],
        ["waiting", "待接續"],
        ["completed", "已完成"],
      ],
      session.status,
    );
    add(
      p,
      btn("儲存狀態", async () => {
        await api(
          "/v1/agent-sessions",
          { ...session, status: status.value },
          "PUT",
        );
        close();
        await shell();
      }),
    );
    let index = 0;
    const stage = el("div", null, "card"),
      position = el("p");
    position.setAttribute("role", "status");
    add(p, position, stage);
    const back = btn("上一則", () => {
        index = Math.max(0, index - 1);
        render();
      }),
      forward = btn("下一則", () => {
        index = Math.min(session.turns.length - 1, index + 1);
        render();
      });
    function render() {
      const t = session.turns[index];
      position.textContent = `第 ${index + 1} 則，共 ${session.turns.length} 則 · ${t.role} · ${stamp(t.at_ms)}`;
      stage.replaceChildren(el("p", t.text, "message"));
    }
    add(p, row(back, forward));
    render();
    add(
      p,
      btn(
        "刪除整個工作紀錄",
        async () => {
          if (
            !(await ask(
              "永久刪除這個工作紀錄？",
              `「${session.title}」與其中 ${session.turns.length} 則核准的文字都會刪除，無法復原。`,
              "刪除工作紀錄",
              true,
            ))
          )
            return;
          await api(`/v1/agent-sessions/${id}`, null, "DELETE");
          close();
          await shell();
        },
        "danger",
      ),
    );
  });
}
async function agentsPage(parent, seq) {
  const [from, to] = windowFor(day);
  const [list, records] = await Promise.all([
    soft("/v1/agent-sessions"),
    soft(`/v1/reviews?from_ms=${from}&to_ms=${to}`),
  ]);
  live(seq);
  const p = panel("和 AI 一起做過的事。");
  add(parent, p);
  add(
    p,
    el(
      "p",
      "PocketLog 只保存你明確選取、逐則核准的 Codex／Claude 文字與手動摘要。它不會掃描電腦、讀取工作階段資料夾或執行任何工具；這些紀錄也不會送給 AI。Dayflow 桌面版的 Agents 儀表板沒有移植到這裡。",
    ),
    row(btn("選取 JSONL 檔並預覽…", agentImportDialog, "primary")),
  );
  const all = panel("已核准的工作紀錄");
  add(parent, all);
  add(
    all,
    el("p", "顯示所有保留中的日期（30 天），不受上方所選日期影響。", "muted"),
  );
  if (!list) add(all, el("p", "暫時讀不到工作紀錄；請重新整理。", "callout warn"));
  else if (!list.sessions.length)
    add(
      all,
      emptyState(
        "還沒有匯入的工作紀錄",
        "選取一個 Codex 或 Claude 的 JSONL 檔，在這台裝置預覽後，只匯入你勾選的文字。",
        btn("選取 JSONL 檔並預覽…", agentImportDialog),
      ),
    );
  else {
    const sessions = list.sessions;
    const projects = [...new Set(sessions.map((s) => s.project))];
    const project = select(
      all,
      "工作線／專案",
      [["", `全部專案（${sessions.length} 筆）`], ...projects.map((s) => [s, s])],
      "",
    );
    const sessionList = el("div");
    add(all, sessionList);
    const renderSessions = () => {
      sessionList.replaceChildren();
      sessions
        .filter((s) => !project.value || s.project === project.value)
        .forEach((s) =>
          add(
            sessionList,
            add(
              el("article", null, "card"),
              el("h3", s.title),
              el(
                "p",
                `${s.project} · ${s.source} · ${{ active: "進行中", waiting: "待接續", completed: "已完成" }[s.status]} · ${s.turn_count} 則`,
              ),
              btn("回放已核准的對話", () => agentReplay(s.id)),
            ),
          ),
        );
    };
    project.onchange = renderSessions;
    renderSessions();
  }
  const notes = panel(`工作摘要 · ${dayTitle(day)}`);
  add(parent, notes);
  reviewLimitNote(notes, records?.truncated);
  const written = records ? records.records.filter((r) => r.kind === "agent") : [];
  if (!records) add(notes, el("p", "暫時讀不到這天的摘要；請重新整理。", "callout warn"));
  else if (!written.length)
    add(notes, el("p", records.truncated ? "已載入的紀錄中沒有工作摘要；較早摘要可能未載入。" : "這一天沒有工作摘要。", "muted"));
  written.forEach((r) =>
    add(
      notes,
      add(
        el("article", null, "card"),
        el("h3", `${r.source} · ${time(r.at_ms)}`),
        el("p", r.text),
      ),
    ),
  );
  const text = field(
      notes,
      "記下這次工作的成果／待辦",
      "",
      "textarea",
      "儲存時間是現在，所以會出現在今天。請勿貼上金鑰或私密對話。",
    ),
    source = select(
      notes,
      "使用的工具",
      [
        ["codex", "Codex"],
        ["claude", "Claude"],
      ],
      "codex",
    );
  text.maxLength = 4000;
  add(
    notes,
    btn("儲存工作摘要", async () => {
      if (!text.value.trim()) throw Error("請填寫工作摘要");
      await saveRecord({
        id: crypto.randomUUID(),
        at_ms: Date.now(),
        kind: "agent",
        text: text.value,
        source: source.value,
      });
      day = journalDate();
      notice("工作摘要已儲存到今天");
      await shell();
    }),
  );
  const advanced = el("details", null, "panel");
  add(advanced, el("summary", "進階：匯入 PocketLog Agents JSON 摘要檔"));
  add(parent, advanced);
  const file = field(advanced, "選取 JSON（最多 48 KB／20 筆）", "", "file");
  file.accept = "application/json,.json";
  add(
    advanced,
    btn("檢查並匯入", async () => {
      const f = file.files[0];
      if (!f || f.size > 48000) throw Error("請選擇 48 KB 內的 JSON");
      let data;
      try {
        data = JSON.parse(await f.text());
      } catch {
        throw Error("這不是有效的 JSON 檔");
      }
      if (!Array.isArray(data.sessions) || data.sessions.length > 20)
        throw Error("sessions 陣列最多 20 筆");
      const sessions = data.sessions;
      if (
        !sessions.every(
          (s) =>
            typeof s.text === "string" &&
            s.text.length <= 4000 &&
            ["codex", "claude"].includes(s.source) &&
            Number.isSafeInteger(s.at_ms) &&
            typeof s.id === "string",
        )
      )
        throw Error("資料格式不符，請參考下方範例");
      if (
        !(await ask(
          `匯入 ${sessions.length} 筆工作摘要？`,
          "這些文字會存入私人日誌 30 天。請確認不含金鑰或私密對話。",
          "匯入摘要",
        ))
      )
        return;
      for (const s of sessions)
        await saveRecord({
          id: s.id,
          at_ms: s.at_ms,
          kind: "agent",
          text: s.text,
          source: s.source,
        });
      notice("已匯入工作摘要");
      await shell();
    }),
    el("p", "檔案格式範例：", "muted"),
    el(
      "pre",
      JSON.stringify(
        {
          sessions: [
            {
              id: "agent-session-id",
              at_ms: Date.now(),
              source: "codex",
              text: "專案：PocketLog\n成果：完成時間軸\n待辦：驗證手機權限",
            },
          ],
        },
        null,
        2,
      ),
    ),
  );
}

/* ---------- devices, settings and support ---------- */
function deviceAuthenticationLabel(device) {
  if (!Object.hasOwn(device, "last_authenticated_ms")) return "伺服器尚未提供連線驗證狀態。";
  return Number.isFinite(device.last_authenticated_ms) && device.last_authenticated_ms > 0
    ? `最近通過驗證：${stamp(device.last_authenticated_ms)}（約到分鐘；不代表同步或錄製成功）`
    : "尚無連線驗證紀錄。";
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
                "回到網站的開始使用清單，按「重新檢查連線」。錄製、畫面擷取與模型處理仍要在手機另外同意。",
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
async function settingsPage(parent, seq) {
  const [devices, control, status] = await Promise.all([
    soft("/v1/devices"),
    soft("/v1/collection"),
    soft("/v1/status"),
  ]);
  live(seq);
  const grid = el("div", null, "settings-grid");
  add(parent, grid);
  const link1 = panel("連線與裝置");
  if (!devices) failed(link1, "裝置金鑰");
  else {
    const active = devices.devices.filter((d) => d.expires_ms > Date.now());
    add(
      link1,
      el(
        "p",
        active.length
          ? `有 ${active.length} 把有效的裝置金鑰：${active.map((d) => d.label).join("、")}。`
          : "還沒有有效的裝置金鑰。手機需要一把才能連線。",
      ),
    );
  }
  add(
    link1,
    rich("p", "muted", "手機要填的伺服器網址：", code(location.origin)),
    row(keyed(btn("管理裝置金鑰", devicesDialog, devices && !devices.devices.length ? "primary" : ""), "settings-devices")),
  );
  add(grid, link1);
  const integration = panel("私人 API");
  add(integration,
    el("p", "把自己的工具接到同一份活動時間軸。使用獨立裝置金鑰，之後可以單獨撤銷。"),
    rich("p", "muted", "API 網址：", code(location.origin)),
    row(keyed(btn("複製 API 網址", async () => {
      await navigator.clipboard.writeText(location.origin);
      notice("已複製 API 網址；不包含金鑰");
    }), "settings-api-origin")),
  );
  const apiHelp = el("details");
  add(apiHelp, el("summary", "從 API 讀取活動，怎麼開始？"),
    listOf(true, [
      "在「管理裝置金鑰」為這個工具建立「日誌（手機 app）」權限金鑰。Owner 金鑰留作網站登入與復原。",
      "用 HTTPS 連線，將裝置金鑰放在 Authorization: Bearer 請求標頭，不要放在網址。僅收集權限不能讀取日誌；日誌權限也能寫入，請只交給信任的工具。",
      "先讀取 GET /v1/status 確認連線，再查詢時間軸。完整範例與各端點的欄位在 API 文件。",
    ]),
    rich("p", "", code("GET /v1/timeline?from_ms=…&to_ms=…")),
    el("p", "時間使用 Unix 毫秒，每次最多 26 小時。回傳的是私人活動卡，不含原始畫面／文字；若 truncated 為 true，代表結果不完整。", "muted"),
    row(keyed(btn("管理 API 裝置金鑰", devicesDialog), "settings-api-devices"),
      link("API 文件與安全的讀取範例", `${REPO}/blob/main/docs/API.md`)),
  );
  add(integration, apiHelp);
  add(grid, integration);
  const record = panel("記錄與隱私");
  if (!control) failed(record, "收集狀態");
  else {
    add(
      record,
      rich(
        "p",
        control.paused ? "status-line paused" : "status-line",
        el("i", null, "dot"),
        control.paused
          ? "伺服器收集已暫停：新的活動與情境會被拒絕。"
          : "伺服器接受收集：已在手機同意的紀錄可以上傳。",
      ),
      row(
        keyed(btn(control.paused ? "恢復伺服器收集" : "暫停所有裝置收集", async () => {
          if (
            !(await ask(
              control.paused ? "恢復接收手機紀錄？" : "暫停所有裝置的收集？",
              control.paused
                ? "只有仍在手機上保有同意與權限的裝置會繼續上傳；恢復不會開啟任何手機權限。"
                : "伺服器會立即拒絕新的活動／情境，並刪除尚待分析的原始資料（已分析的紀錄保留）。手機要到下次同步才會停止擷取；離線中的手機無法立即收到。",
              control.paused ? "恢復收集" : "暫停收集",
              !control.paused,
            ))
          )
            return;
          await api(
            "/v1/collection",
            { ...control, paused: !control.paused },
            "PUT",
          );
          await shell();
        }), "settings-collection"),
      ),
    );
  }
  add(
    record,
    el(
      "p",
      "擷取權限在 Android app 的「設定 → 手機紀錄／畫面情境」管理：使用紀錄、文字與圖片彼此獨立，每分鐘與切換 app 時擷取一次；排除清單與密碼欄位檢查會在擷取前執行。這個網站不會更改手機的系統權限。",
      "muted",
    ),
    el(
      "p",
      "手機隱私防護會在截圖前略過疑似含金鑰、密碼或長數字的畫面（預設開啟），但不是 OCR 像素遮罩；請不要因此把銀行或驗證 app 加入允許名單。",
      "muted",
    ),
  );
  add(grid, record);
  const model = panel("AI（選用）");
  add(
    model,
    el(
      "p",
      ai.enabled
        ? `已設定：${ai.model} · ${providerHost()}。自動摘要${ai.auto_summarize ? "已開啟：新的畫面擷取會自動整理成活動卡並標記主題" : "未開啟：畫面擷取只產生本機摘錄"}。Chat 與回顧在每次傳送前都會先顯示範圍。`
        : ai.available === false
          ? "這個伺服器沒有啟用 AI 設定儲存空間；請依安裝文件掛載設定目錄。"
          : "尚未設定，沒有任何內容傳送到外部模型。時間軸與反思不需要 AI。",
    ),
    row(
      keyed(btn(ai.enabled ? "修改 AI 設定" : "設定自己的 AI", () =>
        providerDialog(shell),
      ), "settings-ai"),
      ai.enabled ? keyed(btn("摘要排程", briefScheduleDialog), "settings-schedule") : null,
    ),
  );
  add(grid, model);
  const goals = panel("分類與目標");
  add(
    goals,
    el(
      "p",
      `每天專注目標 ${prefs.focus_minutes} 分鐘 · 分心預算 ${prefs.distraction_minutes} 分鐘 · ${prefs.categories.length} 個分類 · ${(prefs.rules || []).length} 條自動規則。手機與網站共用。`,
    ),
    row(keyed(btn("每日目標", goalsDialog), "settings-goals"), keyed(btn("分類與規則", categoriesDialog), "settings-categories")),
  );
  add(grid, goals);
  const data = panel("資料");
  if (!status) failed(data, "服務狀態");
  else {
    add(
      data,
      facts([
        ["活動、反思與畫面文字", status.journal_retention === "permanent" ? "永久保存" : "請更新伺服器"],
        ...(status.storage
          ? [
              ["已使用空間", `${gib(status.storage.used_bytes)} / ${gib(status.storage.limit_bytes)}`],
              ["保存中的截圖", `${status.storage.image_count.toLocaleString()} 張（${gib(status.storage.image_bytes)}）`],
              ...(status.storage.text_only_before_ms
                ? [["只剩文字", `${new Date(status.storage.text_only_before_ms).toLocaleString()} 之前的截圖已刪除`]]
                : []),
            ]
          : []),
        ["等待分析的情境", `${status.pending_context} 筆`],
      ]),
    );
    if (status.storage)
      add(data, row(keyed(btn("調整容量上限…", () => storageDialog(status.storage)), "settings-storage")));
    const tech = el("details");
    add(tech, el("summary", "技術診斷"), el("pre", JSON.stringify(status, null, 2)));
    add(data, tech);
  }
  add(
    data,
    el(
      "p",
      "匯出在「時間軸」與「每日／每週」頁面底部。匯出的檔案離開 PocketLog 後不受保留與刪除機制保護。",
      "muted",
    ),
    row(
      btn(
        "永久刪除全部日誌…",
        async () => {
          if (
            !(await ask(
              "永久刪除伺服器上的全部日誌？",
              [
                "會刪除所有活動、感受、反思、對話、AI 摘要、專注、工作紀錄與回放縮圖，無法復原。",
                "目標、分類、裝置金鑰與 AI 設定會保留。已匯出的檔案、備份與已送到模型提供者的內容不會被刪除。",
                "請先在所有手機停止記錄，否則仍連線的手機會繼續上傳新的資料。",
              ],
              "刪除全部日誌",
              true,
            ))
          )
            return;
          await api("/v1/journal", null, "DELETE");
          chat = [];
          flow = null;
          setupState = null;
          notice("伺服器上的日誌已刪除");
          await shell();
        },
        "danger",
      ),
    ),
  );
  add(grid, data);
  const browser = panel("這個瀏覽器");
  add(
    browser,
    el(
      "p",
      "工作階段最長八小時，伺服器重新啟動也會登出。在共用電腦上請記得登出。",
    ),
    row(
      btn("登出", async () => {
        await api("/session", null, "DELETE");
        login("signedout");
      }),
      btn("重新閱讀初始設定", () => onboard(0)),
      btn("開始使用清單", async () => {
        setChecklistHidden(false);
        await go("support");
      }),
    ),
  );
  add(grid, browser);
}
function goalsDialog() {
  modal("每天留給自己的時間", (p) => {
    add(p, el("p", "這是你選擇的目標，不是評分；手機與網站共用。", "muted"));
    const focus = field(p, "專注目標（分鐘，0–1440）", prefs.focus_minutes, "number"),
      budget = field(
        p,
        "分心預算（分鐘，0–1440）",
        prefs.distraction_minutes,
        "number",
      );
    focus.min = budget.min = "0";
    focus.max = budget.max = "1440";
    add(
      p,
      add(
        el("div", null, "actions"),
        btn("取消", () => close()),
        btn(
          "儲存",
          async () => {
            const a = +focus.value,
              b = +budget.value;
            if (
              !Number.isInteger(a) ||
              !Number.isInteger(b) ||
              Math.min(a, b) < 0 ||
              Math.max(a, b) > 1440
            )
              throw Error("請輸入 0–1440 的整數分鐘");
            prefs = await api(
              "/v1/workspace",
              { ...prefs, focus_minutes: a, distraction_minutes: b },
              "PUT",
            );
            close();
            await shell();
          },
          "primary",
        ),
      ),
    );
  });
}
function categoriesDialog() {
  modal("分類、顏色與專注角色", (container) => {
    const p = el("div");
    add(container, p);
    const editorSeq = renderSeq;
    const current = () => editorSeq === renderSeq && p.isConnected && $("#modal").open;
    add(
      p,
      el(
        "p",
        "這些名稱、顏色與角色會在手機和網站共用。可新增分類與自動規則；已被活動使用的分類需先重新分類才能移除。",
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
      const role = el("select");
      [
        ["focus", "專注"],
        ["neutral", "一般"],
        ["distraction", "分心"],
      ].forEach(([value, label]) => {
        const o = el("option", label);
        o.value = value;
        add(role, o);
      });
      role.value = c.role;
      role.setAttribute("aria-label", `${c.name}的角色`);
      color.oninput = () => (c.color = color.value);
      name.oninput = () => (c.name = name.value);
      role.onchange = () => (c.role = role.value);
      const wrapper = el("div");
      add(wrapper, r);
      add(categoryList, wrapper);
      add(r, color, name, role);
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
        "由上到下採第一個符合的規則。指定 app 與標題關鍵字時必須同時符合；你的手動修改永遠優先。",
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
        announceRecovery("其他設定已更新，分類草稿仍在", `最新專注目標 ${latest.focus_minutes} 分鐘、分心預算 ${latest.distraction_minutes} 分鐘。遠端分類與規則未變；可保留這些最新設定，再儲存你的分類草稿。`);
        add(recovery, btn("保留最新目標，重試儲存分類", () => saveCategories(classificationRecovery(workspaceBaseline, draft, latest, attemptedRevision).candidate), "primary"));
      } else {
        announceRecovery("遠端分類與規則也已變更", "你的草稿尚未覆蓋遠端版本。請先比較，再決定保留草稿繼續編輯，或明確取代剛讀取的遠端分類與規則。目標及其他設定會保留最新值。");
        const compare = el("details", null, "topic");
        add(compare, el("summary", "查看剛讀取的遠端分類與規則"),
          listOf(false, latest.categories.map((c) => `${c.name} · ${c.role} · ${c.color}`)),
          listOf(false, (latest.rules || []).map((r) => `${r.enabled ? "啟用" : "停用"} · ${r.app || "所有 app"} · ${r.title_contains || "所有標題"} → ${latest.categories.find((c) => c.id === r.category)?.name || r.category}`)));
        add(recovery, compare, btn("確認以我的草稿取代遠端分類", async () => {
          if (!(await ask("取代剛讀取的遠端分類與規則？", [
            "會用這個表單中的全部分類與規則取代遠端版本；其他裝置的分類修改可能被取代。",
            `保留剛讀取的專注目標 ${latest.focus_minutes} 分鐘、分心預算 ${latest.distraction_minutes} 分鐘及其他設定。不會刪除活動；使用中的分類仍不能移除。`,
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
function providerDialog(done) {
  const seq = renderSeq;
  modal("Bring your own AI", (p) => {
    const current = () => p.isConnected && seq === renderSeq;
    // Keep another editor's draft safe even if a closed editor is still saving.
    const pendingHint = el("p", "AI 設定變更處理中時，不能同時儲存其他設定；請稍後再試。這裡的草稿會保留。", "callout");
    pendingHint.setAttribute("role", "status");
    pendingHint.hidden = !providerChangePending;
    add(p, pendingHint);
    async function complete(reopen = false) {
      close();
      // Callbacks render synchronously before awaiting page data. A later page
      // or dialog owns the screen even if this refresh eventually completes.
      const refresh = done();
      const nextSeq = renderSeq;
      await refresh;
      if (renderSeq !== nextSeq || $("#modal").open) return false;
      if (reopen) providerDialog(done);
      return true;
    }
    async function changeProvider(action) {
      if (!current()) return;
      if (providerChangePending) {
        pendingHint.hidden = false;
        notice("先前的 AI 設定變更仍在處理，請稍後再試；目前草稿已保留。", false, p);
        return;
      }
      providerChangePending = true;
      pendingHint.hidden = false;
      const controls = [...p.querySelectorAll("input,select,button")].map(n => [n, n.disabled]);
      controls.forEach(([n]) => { n.disabled = true; });
      try { await action(); }
      catch (error) { if (current()) throw error; }
      finally {
        providerChangePending = false;
        controls.forEach(([n, disabled]) => { if (n.isConnected) n.disabled = disabled; });
        if (current()) pendingHint.hidden = true;
      }
    }
    add(
      p,
      el(
        "p",
        "支援 OpenAI 相容、Anthropic Messages 與 Gemini generateContent 原生 API。API key 以 HTTPS 送到你的私人伺服器，存於獨立 0600 設定檔，不會回傳給瀏覽器。伺服器管理者仍可讀取。",
      ),
    );
    if (ai.available === false)
      add(
        p,
        el(
          "p",
          "這個伺服器沒有啟用 AI 設定儲存空間，儲存會失敗；請依安裝文件掛載設定目錄後再試。",
          "callout warn",
        ),
      );
    const protocol = select(
      p,
      "API 協定",
      [
        ["openai", "OpenAI 相容"],
        ["anthropic", "Anthropic Messages"],
        ["gemini", "Gemini generateContent"],
      ],
      ai.protocol || "openai",
    );
    const endpoint = field(
      p,
      "完整模型 API HTTPS URL",
      ai.endpoint || "https://api.openai.com/v1/chat/completions",
    );
    endpoint.inputMode = "url";
    endpoint.spellcheck = false;
    add(
      p,
      row(
        btn("填入 OpenAI 網址", () => {
          protocol.value = "openai";
          endpoint.value = "https://api.openai.com/v1/chat/completions";
        }),
        btn("填入 OpenRouter 網址", () => {
          protocol.value = "openai";
          endpoint.value = "https://openrouter.ai/api/v1/chat/completions";
        }),
      ),
    );
    const model = field(p, "模型 ID", ai.model || ""),
      key = field(
        p,
        ai.has_key
          ? "API key（同端點及協定留白保留；變更則不沿用）"
          : "API key（本機模型可留白）",
        "",
        "password",
      ),
      clear = check(p, "清除已儲存的 key"),
      format = select(
        p,
        "結構化輸出模式",
        [
          ["json_schema", "JSON Schema"],
          ["json_object", "JSON Object（相容模式）"],
        ],
        ai.response_format || "json_schema",
      ),
      images = check(
        p,
        "模型支援圖片（自動摘要時會一併送出截圖；不會因此啟用手機截圖）",
        ai.image_support,
      );
    model.spellcheck = false;
    key.autocomplete = "new-password";
    add(
      p,
      el(
        "p",
        "端點從伺服器連線。HTTP 僅接受 literal loopback（127.0.0.1／[::1]）；Docker 的 localhost 是容器本身。儲存或更換設定會關閉自動摘要，儲存本身不會呼叫模型。",
        "muted",
      ),
    );
    add(
      p,
      row(
        btn(
          "儲存設定",
          async () => {
            const body = {
              protocol: protocol.value,
              endpoint: endpoint.value.trim(),
              model: model.value.trim(),
              image_support: images.checked,
              response_format: format.value,
            };
            if (clear.checked || key.value)
              body.api_key = clear.checked ? "" : key.value;
            await changeProvider(async () => {
              key.value = "";
              const saved = await api("/v1/ai", body, "PUT", current);
              if (!current()) return;
              ai = saved;
              if (await complete())
                notice("AI 設定已儲存；要自動摘要畫面擷取，請在下方重新開啟");
            });
          },
          "primary",
        ),
        btn("測試已儲存的設定", async () => {
          const saved = structuredClone(ai);
          if (!saved.enabled) throw Error("請先儲存設定，再進行測試");
          if (!saved.probe_provider_binding) throw Error("請先更新伺服器，才能確認測試目的地。尚未傳送測試文字。");
          if (
            !(await ask(
              "傳送一段固定的測試文字？",
              [
                `目的地：${providerHost(saved)} · ${saved.protocol} · 模型 ${saved.model}`,
                "使用上次儲存的設定，不包含表單中尚未儲存的修改。只送固定測試文字，不含任何日誌；可能產生少量模型費用。",
              ],
              "送出測試",
            ))
          )
            return;
          if (!current()) return;
          p.querySelector(".probe-feedback")?.remove();
          try {
            await api("/v1/ai/test", { provider_id: saved.provider_id }, "POST", current);
          } catch (error) {
            if (!current()) return;
            if (error.status !== 409) throw error;
            const recovery = el("div", null, "probe-feedback callout warn");
            recovery.setAttribute("role", "alert");
            add(recovery, el("p", "已儲存的模型已變更，沒有改向新模型重試。可保留目前編輯，或重新載入設定，再核對目的地並確認。重新載入會捨棄尚未儲存的修改。"), btn("重新載入已儲存的模型", async () => {
              const savedNow = await api("/v1/ai", undefined, undefined, current);
              if (!current()) return;
              ai = savedNow;
              await complete(true);
            }));
            add(p, recovery);
            recovery.scrollIntoView({ block: "nearest" });
            return;
          }
          if (!current()) return;
          notice("模型測試成功，未傳送日誌");
        }),
        ai.enabled || ai.has_key
          ? btn(
              "停用並刪除 key",
              async () => {
                if (
                  !(await ask(
                    "停用 AI 並刪除伺服器上的 key？",
                    "所有裝置會停止使用這個模型，摘要排程也會停止。已保存的摘要與對話不會被刪除。",
                    "停用並刪除",
                    true,
                  ))
                )
                  return;
                if (!current()) return;
                await changeProvider(async () => {
                  await api("/v1/ai", null, "DELETE", current);
                  if (!current()) return;
                  const savedNow = await api("/v1/ai", undefined, undefined, current);
                  if (!current()) return;
                  ai = savedNow;
                  await complete();
                });
              },
              "danger",
            )
          : null,
      ),
    );
    if (ai.enabled) {
      const auto = el("div", null, "callout");
      add(
        auto,
        el("h3", "自動摘要"),
        el(
          "p",
          ai.auto_summarize
            ? "已開啟：每次畫面擷取的文字（與模型支援時的截圖）會自動送到這個模型整理成活動卡，活動卡標題也會用來標記主題關鍵字。"
            : "未開啟：畫面擷取只產生本機摘錄，不會送到模型。",
        ),
        row(
          btn(
            ai.auto_summarize ? "關閉自動摘要" : "開啟自動摘要…",
            async () => {
              const enable = !ai.auto_summarize;
              if (
                enable &&
                !(await ask(
                  "自動把畫面擷取送到這個模型？",
                  [
                    `目的地：${providerHost(ai)} · ${ai.protocol} · 模型 ${ai.model}`,
                    "之後每段 app 使用的畫面文字" + (ai.image_support ? "與截圖" : "") + "都會自動送出，不再逐筆詢問；活動卡標題會另外送出以標記主題。會持續產生 API 費用。",
                    "更換模型設定後會自動關閉，需要重新開啟。",
                  ],
                  "開啟自動摘要",
                ))
              )
                return;
              if (!current()) return;
              await changeProvider(async () => {
                const saved = await api("/v1/ai/auto", { provider_id: ai.provider_id, enabled: enable }, "PUT", current);
                if (!current()) return;
                ai = saved;
                if (await complete(true)) notice(enable ? "已開啟自動摘要" : "已關閉自動摘要");
              });
            },
            ai.auto_summarize ? "" : "primary",
          ),
        ),
      );
      add(p, auto);
    }
  });
}
function gib(bytes) {
  return `${(bytes / 1024 ** 3).toFixed(bytes < 10 * 1024 ** 3 ? 2 : 1)} GB`;
}
function storageDialog(storage) {
  const seq = renderSeq;
  modal("容量上限", (p) => {
    const current = () => p.isConnected && seq === renderSeq;
    add(
      p,
      el(
        "p",
        "活動、活動卡、反思與畫面文字永久保存。伺服器使用空間超過上限時，會從最舊、已分析的截圖開始刪除，只保留文字。",
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
            notice("容量上限已儲存");
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
async function supportPage(parent, seq) {
  const setup = await loadSetup();
  live(seq);
  add(parent, checklist(setup, true));
  const help = panel("遇到狀況時");
  add(parent, help);
  add(help, el("p", "選擇最接近的情況，依序檢查。", "muted"));
  topic(
    help,
    "時間軸沒有手機活動",
    [
      "確認手機 app 已連線：設定裡是這個網站的 HTTPS 網址與一把有效的日誌裝置金鑰。",
      "在手機的「紀錄、排除與同步設定」同意使用紀錄，並在 Android 授予「使用紀錄存取」權限。只有同意之後的活動會被記錄。",
      "檢查手機上的暫停、排除清單與同步網路條件（預設只在非計量網路同步）。",
      "Android 最多每 15 分鐘安排一次收集，省電模式下會更久；可在手機按手動重試。",
      "確認伺服器沒有暫停收集。",
    ],
    btn("管理裝置金鑰", devicesDialog),
    btn("查看收集狀態", () => go("settings")),
  );
  topic(
    help,
    "手機顯示無法連線或金鑰失效",
    [
      "手機只接受有效憑證的 HTTPS 網址，且不跟隨重新導向；網址需與這個網站相同。",
      "裝置金鑰有效 1–90 天，到期或被撤銷後需要建立新的一把。",
      "若更換過 Owner 金鑰，所有裝置金鑰都會在伺服器重新啟動時失效，需要重新建立。",
      "手機要換到另一個伺服器時，需先在 app 內清除本機資料。",
    ],
    btn("建立新的裝置金鑰", devicesDialog),
  );
  topic(help, "找不到或想更換 Owner 金鑰", [
    [
      "金鑰在伺服器的 ",
      code(".secrets/production_owner_token"),
      "（開發用為 ",
      code(".secrets/owner_token"),
      "）。在那台機器執行 ",
      code("./scripts/setup-status.sh production"),
      " 可以確認檔案位置與服務狀態，不會顯示金鑰。",
    ],
    "更換金鑰：以新的隨機值取代該檔案並重新建立容器，接著重新登入並為每支手機建立新的裝置金鑰。",
    "這個網站無法顯示、寄送或重設 Owner 金鑰。",
  ]);
  topic(
    help,
    "AI 摘要或 Chat 失敗",
    [
      "沒有 AI 時，活動卡仍會顯示 app 與本機摘錄，時間軸與反思照常運作。",
      "在 AI 設定按「測試已儲存的設定」：只會送出固定測試文字。",
      "更換端點或模型後，自動摘要會關閉，需要在 AI 設定重新開啟；舊對話也不會轉送。",
      "伺服器一次只處理一個模型請求；看到忙碌訊息時稍候再試，同一個問題不會重複計費。",
    ],
    btn("開啟 AI 設定", () =>
      providerDialog(shell),
    ),
  );
  topic(help, "資料保留、匯出與刪除", [
    "活動、反思、活動卡與畫面文字永久保存；截圖受「設定 → 資料」的容量上限管理（預設 10 GB），超過時刪除最舊截圖、保留文字。對話、AI 回顧與專注紀錄保留 30 天。",
    "匯出檔含私人內容，離開 PocketLog 後不受保留與刪除保護，請存放在信任的位置。",
    "「設定 → 資料」可以永久刪除伺服器上的全部日誌；備份、已匯出的檔案與已送到模型的內容不會一併刪除。",
    "回報問題時請勿附上 Owner 金鑰、裝置金鑰、API key、真實日誌或畫面。",
  ]);
  const about = panel("功能邊界");
  add(parent, about);
  add(
    about,
    el(
      "p",
      "PocketLog 的 Flow 是私人專注計時；Agents 支援逐則核准的 Codex／Claude JSONL 文字匯入、回放與手動摘要。Dayflow 的雲端 Flow、桌面 Agents 儀表板、錄影回放與自動像素遮罩沒有移植。這個專案仍在早期開發，尚未經過獨立的安全稽核。",
    ),
    add(
      el("ul", null, "plain"),
      add(el("li"), link("原始碼與說明", REPO)),
      add(el("li"), link("安裝與第一次使用", `${REPO}/blob/main/docs/SETUP.md`)),
      add(el("li"), link("安全邊界", `${REPO}/blob/main/docs/SECURITY.md`)),
    ),
  );
}
function link(text, url) {
  const a = el("a", text);
  a.href = url;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  add(a, el("span", "（在新分頁開啟）", "sr"));
  return a;
}
async function copyTimeline(cards) {
  if (!cards.length) throw Error("這個範圍沒有可複製的活動");
  const text = cards
    .map(
      (c) => `${time(c.start_ms)}–${time(c.end_ms)} ${c.title}\n${c.summary}`,
    )
    .join("\n\n");
  await navigator.clipboard.writeText(text);
  notice("已複製私人時間軸；貼上後請留意剪貼簿內容");
}
function exportSnapshot(days, reviews, reviewsTruncated = false) {
  return { version: 1, days, reviews,
    truncated: reviewsTruncated || days.some(d => d.truncated),
    reviews_truncated: reviewsTruncated };
}
async function exportData(days, reviews, reviewsTruncated = false) {
  const snapshot = exportSnapshot(days, reviews, reviewsTruncated);
  if (
    !(await ask(
      "匯出這個範圍的私人日誌？",
      [
        `會下載一個 JSON 檔，內含 ${days.length} 天已載入的活動、感受、反思與回顧紀錄。檔案離開 PocketLog 後不受保留與刪除機制保護，請存放在信任的位置。`,
        ...(snapshot.truncated ? ["這份匯出不完整：活動或回顧紀錄已達讀取上限，只包含畫面已載入的資料。請勿把它當作完整備份；可縮小範圍或使用私人 API 分段讀取。"] : []),
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
  a.download = `pocketlog-${day}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
const pages = {
  timeline: timelinePage,
  daily: (parent, seq) => reviewPage(parent, seq, "daily"),
  weekly: (parent, seq) => reviewPage(parent, seq, "weekly"),
  chat: chatPage,
  flow: flowPage,
  agents: agentsPage,
  support: supportPage,
  settings: settingsPage,
};
if (inBrowser) {
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
