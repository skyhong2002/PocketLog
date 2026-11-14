"use strict";
const $ = (s) => document.querySelector(s);
const root = $("#app");
let chatScope = "day";
let conversationId = crypto.randomUUID();
let chatCursor = null,
  pendingAsk = null;
let prefs,
  ai,
  page = "timeline",
  day = journalDate(),
  mode = "day",
  filter = null,
  current = [],
  reviewRows = [],
  chat = [],
  flow = null,
  timerHandle;
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
const symbols = ["◷", "☀", "▥", "☏", "◉", "⌘", "?", "⚙"];
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
function btn(text, fn, cls) {
  const n = el("button", text, cls);
  n.type = "button";
  n.onclick = () =>
    safe(async () => {
      n.disabled = true;
      try {
        await fn();
      } finally {
        n.disabled = false;
      }
    });
  return n;
}
function field(parent, label, value = "", type = "text") {
  const id = "field-" + crypto.randomUUID();
  const l = el("label", label);
  l.htmlFor = id;
  const n = el(type === "textarea" ? "textarea" : "input");
  n.id = id;
  if (type !== "textarea") n.type = type;
  n.value = value;
  n.autocomplete = "off";
  add(parent, l, n);
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
function notice(text) {
  const n = $("#notice");
  n.textContent = text;
  n.style.display = "block";
  clearTimeout(notice.timeout);
  notice.timeout = setTimeout(() => (n.style.display = "none"), 7000);
}
async function safe(fn) {
  try {
    await fn();
  } catch (e) {
    notice(e.message || "操作失敗，請重試");
  }
}
async function api(path, body, method = body ? "POST" : "GET") {
  const r = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-PocketLog": "1" },
    body: body ? JSON.stringify(body) : undefined,
    cache: "no-store",
    redirect: "error",
  });
  if (r.status === 401) {
    login();
    throw Error("請重新登入私人日誌");
  }
  if (!r.ok)
    throw Error(
      r.status === 409
        ? "設定已更新，請重新載入後重試"
        : r.status === 429
          ? "模型或伺服器忙碌，請稍後再試"
          : r.status === 502
            ? "模型請求未成功，請檢查設定或稍後重試"
            : `操作未完成 (${r.status})`,
    );
  return r.status === 204 ? {} : r.json();
}
function logo() {
  const n = el("img");
  n.src = "/logo.png";
  n.alt = "PocketLog";
  return n;
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
  const m = Math.round(ms / 60000);
  return m >= 60 ? `${Math.floor(m / 60)} 小時 ${m % 60} 分` : `${m} 分鐘`;
}
function styleFor(id) {
  return prefs.categories.find((c) => c.id === id) || prefs.categories.at(-1);
}
function row(...nodes) {
  return add(el("div", null, "row"), ...nodes);
}
function modal(title, build) {
  const p = $("#modal-body");
  const content = el("div");
  p.replaceChildren(el("h2", title), content);
  // Late async builders retain a detached container after close, never a newer dialog.
  safe(() => build(content));
  $("#modal").showModal();
}
function close() {
  $("#modal").close();
  $("#modal-body").replaceChildren();
}
function login() {
  pendingAsk = null;
  chatCursor = null;
  conversationId = crypto.randomUUID();
  $("#modal").close();
  $("#modal-body").replaceChildren();
  current = [];
  reviewRows = [];
  chat = [];
  flow = null;
  clearInterval(timerHandle);
  root.replaceChildren();
  const panel = el("section", null, "welcome");
  add(root, panel);
  add(
    panel,
    logo(),
    el("h1", "把一天，收進口袋。"),
    el(
      "p",
      "連接你的私人日誌。登入後可在這裡回看活動、記下感受，並設定自己的 AI。",
    ),
  );
  const key = field(panel, "Owner token", "", "password");
  key.autocomplete = "current-password";
  const form = el("form");
  const submit = btn("開啟我的日誌", () => {}, "primary");
  submit.type = "submit";
  add(form, key, submit);
  add(
    panel,
    form,
    el(
      "p",
      "請使用 HTTPS 或 localhost。金鑰只用於建立八小時的私人工作階段，不會儲存在瀏覽器。",
      "muted",
    ),
  );
  form.onsubmit = (e) => {
    e.preventDefault();
    safe(async () => {
      const token = key.value;
      key.value = "";
      await api("/session", { token });
      await boot();
    });
  };
}
async function boot() {
  prefs = await api("/v1/workspace");
  ai = await api("/v1/ai");
  if (!prefs.onboarding_version) onboard(0);
  else await shell();
}
function onboard(step) {
  root.replaceChildren();
  const p = el("section", null, "welcome");
  add(root, p);
  const steps = el("div", null, "steps");
  for (let i = 0; i < 4; i++)
    add(steps, el("i", null, i <= step ? "done" : ""));
  add(p, logo(), steps);
  let save = () => {};
  if (step === 0) {
    add(
      p,
      el("h1", "你的日常，由你決定。"),
      el(
        "p",
        "PocketLog 將手機活動整理成可回看的時間軸。從活動卡到每日反思，保留讓你理解生活節奏的線索。",
      ),
      el("p", "這份初始設定會在網站與手機共用；手機只需再設定連線和系統權限。"),
    );
  }
  if (step === 1) {
    add(
      p,
      el("h1", "先選擇，才開始記錄。"),
      el(
        "p",
        "手機使用紀錄、畫面文字、圖片與外部 AI 都需要分別同意，預設關閉。瀏覽器不會擷取你的畫面。",
      ),
      el(
        "p",
        "原始畫面／文字最多保留 24 小時，活動與反思保留 30 天。你可設定排除 app、暫停、匯出與刪除。伺服器管理者可以讀取日誌，請使用你信任的私人伺服器。",
      ),
    );
  }
  if (step === 2) {
    add(
      p,
      el("h1", "為自己留一點專注。"),
      el(
        "p",
        "設定每天想投入的時間，以及可接受的分心時間。這些是你選擇的目標，不是對你的一天評分。",
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
    save = () => {
      prefs.focus_minutes = Number(focus.value);
      prefs.distraction_minutes = Number(budget.value);
      if (
        !Number.isInteger(prefs.focus_minutes) ||
        !Number.isInteger(prefs.distraction_minutes) ||
        Math.min(prefs.focus_minutes, prefs.distraction_minutes) < 0 ||
        Math.max(prefs.focus_minutes, prefs.distraction_minutes) > 1440
      )
        throw Error("請輸入 0–1440 的整數分鐘");
    };
  }
  if (step === 3) {
    add(
      p,
      el("h1", "帶上你自己的 AI。"),
      el(
        "p",
        "支援 OpenAI 相容、Anthropic Messages 與 Gemini generateContent API。也可以先使用無 AI 的活動卡。",
      ),
      el(
        "p",
        ai.enabled
          ? `已設定 ${ai.model}。畫面送出前仍需在手機同意這個提供者。`
          : "尚未設定外部模型。你可以隨時到設定新增。",
      ),
    );
    add(
      p,
      btn("設定我的 AI", () =>
        providerDialog(async () => {
          ai = await api("/v1/ai");
          onboard(3);
        }),
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
      step === 3 ? "開始我的時間軸" : "繼續",
      async () => {
        save();
        if (step < 3) onboard(step + 1);
        else {
          prefs.onboarding_version = 1;
          prefs = await api("/v1/workspace", prefs, "PUT");
          await shell();
        }
      },
      "primary",
    ),
  );
  add(p, actions);
}
async function shell() {
  clearInterval(timerHandle);
  root.replaceChildren();
  const nav = el("nav");
  nav.setAttribute("aria-label", "主要導覽");
  add(nav, logo());
  Object.entries(titles).forEach(([key, label], i) => {
    const b = btn(
      "",
      async () => {
        page = key;
        await shell();
      },
      page === key ? "active" : "",
    );
    add(b, el("span", symbols[i]), document.createTextNode(label));
    b.setAttribute("aria-label", label);
    add(nav, b);
  });
  const main = el("main");
  add(root, nav, main);
  const head = el("header");
  add(head, el("h1", titles[page]));
  const controls = el("div", null, "row");
  if (["timeline", "daily", "weekly", "agents", "chat"].includes(page)) {
    const prev = btn("‹", async () => {
      day = shift(day, -1);
      await shell();
    });
    prev.setAttribute("aria-label", "前一天");
    prev.disabled = day <= shift(journalDate(), -28);
    const next = btn("›", async () => {
      day = shift(day, 1);
      await shell();
    });
    next.setAttribute("aria-label", "後一天");
    next.disabled = day >= journalDate();
    const date = el("input");
    date.type = "date";
    date.value = day;
    date.min = shift(journalDate(), -28);
    date.max = journalDate();
    date.setAttribute("aria-label", "日誌日期");
    date.onchange = () =>
      safe(async () => {
        if (date.value >= date.min && date.value <= date.max) {
          day = date.value;
          await shell();
        }
      });
    add(
      controls,
      prev,
      next,
      date,
      btn("今天", async () => {
        day = journalDate();
        await shell();
      }),
    );
  }
  add(
    controls,
    btn("重新整理", async () => {
      prefs = await api("/v1/workspace");
      ai = await api("/v1/ai");
      await shell();
    }),
  );
  add(head, controls);
  add(main, head);
  if (page === "settings") {
    await settings(main);
    return;
  }
  if (page === "support") {
    support(main);
    return;
  }
  if (page === "flow") {
    await flowPage(main);
    return;
  }
  const dates =
    page === "weekly" ||
    (page === "timeline" && mode === "week") ||
    (page === "chat" && chatScope === "week")
      ? weekDates().filter((d) => d >= shift(journalDate(), -28))
      : [day];
  const days = await Promise.all(
    dates.map(async (date) => ({
      date,
      ...(date > journalDate()
        ? {
            cards: [],
            activities: [],
            checkins: [],
            reflections: [],
            truncated: false,
          }
        : await api(pathFor(date))),
    })),
  );
  current = days.flatMap((d) => d.cards);
  const [from] = windowFor(dates[0]);
  const [, to] = windowFor(dates.at(-1));
  reviewRows = (await api(`/v1/reviews?from_ms=${from}&to_ms=${to}`)).records;
  if (days.some((d) => d.truncated))
    add(
      main,
      el("p", "資料已達顯示上限；統計僅涵蓋目前載入的活動卡。", "danger"),
    );
  if (page === "timeline") timeline(main, days);
  if (page === "daily" || page === "weekly") await review(main, days);
  if (page === "chat") await chatPage(main);
  if (page === "agents") await agents(main);
}
function metrics(cards) {
  const totals = {};
  let focus = 0,
    distraction = 0;
  const intervals = [];
  for (const c of cards) {
    const ms = c.end_ms - c.start_ms;
    totals[c.category] = (totals[c.category] || 0) + ms;
    const role = styleFor(c.category).role;
    if (role === "focus") {
      focus += ms;
      intervals.push([c.start_ms, c.end_ms]);
    }
    if (role === "distraction") distraction += ms;
  }
  intervals.sort((a, b) => a[0] - b[0]);
  let longest = 0,
    start = 0,
    end = 0;
  for (const [s, e] of intervals) {
    if (s > end) {
      start = s;
      end = e;
    } else end = Math.max(end, e);
    longest = Math.max(longest, end - start);
  }
  return {
    totals,
    focus,
    distraction,
    longest,
    total: Object.values(totals).reduce((a, b) => a + b, 0),
  };
}
function stats(parent, m) {
  const box = el("div", null, "stats");
  for (const [label, value] of [
    ["記錄時間", duration(m.total)],
    ["專注時間", duration(m.focus)],
    ["最長連續專注", duration(m.longest)],
  ])
    add(
      box,
      add(el("div", null, "stat"), el("small", label), el("strong", value)),
    );
  add(parent, box);
}
function timeline(parent, days) {
  const controls = row(
    btn(
      "日",
      async () => {
        mode = "day";
        await shell();
      },
      mode === "day" ? "active" : "",
    ),
    btn(
      "週",
      async () => {
        mode = "week";
        await shell();
      },
      mode === "week" ? "active" : "",
    ),
    btn("查看已同意保存的縮圖", replayDialog),
    btn("複製時間軸", copyTimeline),
    btn("匯出 JSON", () => exportData(days)),
  );
  add(
    parent,
    controls,
    el(
      "p",
      "一天從 04:00 開始。空白時段代表沒有活動資料；卡片可點選修改。",
      "muted",
    ),
  );
  const chips = el("div", null, "chips");
  add(
    chips,
    btn(
      "全部",
      async () => {
        filter = null;
        await shell();
      },
      filter === null ? "active chip" : "chip",
    ),
  );
  prefs.categories.forEach((c) => {
    const b = btn(
      c.name,
      async () => {
        filter = filter === c.id ? null : c.id;
        await shell();
      },
      filter === c.id ? "active chip" : "chip",
    );
    b.prepend(el("i", null, "dot"));
    b.style.setProperty("--cat", c.color);
    add(chips, b);
  });
  add(chips, btn("編輯分類", categoriesDialog, "chip"));
  add(parent, chips);
  const grid = el("div", null, "dashboard"),
    left = el("section"),
    aside = el("aside");
  add(grid, left, aside);
  add(parent, grid);
  if (!current.length)
    add(
      left,
      el(
        "div",
        "這天還沒有活動。請在手機設定中同意紀錄並授予系統權限；你也可以先寫下每日反思。",
        "empty",
      ),
    );
  if (mode === "week") {
    const scroll = el("div", null, "scroll"),
      week = el("div", null, "week");
    for (const d of days) {
      const col = el("div");
      add(col, el("h3", d.date.slice(5)), track(d));
      add(week, col);
    }
    add(scroll, week);
    add(left, scroll);
  } else {
    const viewport = el("div", null, "timeline-window");
    add(viewport, track(days[0]));
    add(left, viewport);
    const first = days[0].cards.find((c) => !filter || c.category === filter);
    if (first)
      requestAnimationFrame(
        () =>
          (viewport.scrollTop = Math.max(
            0,
            ((first.start_ms - windowFor(days[0].date)[0]) / 3600000) * 60 - 40,
          )),
      );
  }
  summary(aside, metrics(days.find((d) => d.date === day)?.cards || current));
}
function track(data) {
  const track = el("div", null, "timeline");
  for (let i = 0; i < 24; i++) {
    const hour = el(
      "span",
      `${String((i + 4) % 24).padStart(2, "0")}:00`,
      "hour",
    );
    hour.style.setProperty("--hour", i);
    add(track, hour);
  }
  const [start, end] = windowFor(data.date);
  const length = new Date(shift(data.date, 1) + "T04:00:00").getTime() - start;
  const visible = data.cards.filter((c) => !filter || c.category === filter);
  let active = [];
  for (const c of visible) {
    active = active.filter((x) => x.end > c.start_ms);
    let lane = 0;
    while (active.some((x) => x.lane === lane)) lane++;
    active.push({ lane, end: c.end_ms });
    const b = btn("", () => editCard(c), "event");
    b.style.setProperty("--top", `${((c.start_ms - start) / length) * 1440}px`);
    b.style.setProperty(
      "--height",
      `${Math.max(20, ((c.end_ms - c.start_ms) / length) * 1440)}px`,
    );
    b.style.setProperty("--cat", styleFor(c.category).color);
    b.style.left = `${10 + Math.min(120, lane * 14)}px`;
    b.style.right = "0px";
    b.setAttribute(
      "aria-label",
      `${time(c.start_ms)}–${time(c.end_ms)} ${c.title}`,
    );
    add(
      b,
      el("strong", c.title),
      el("small", `${time(c.start_ms)}–${time(c.end_ms)}`),
    );
    add(track, b);
  }
  return track;
}
function summary(parent, m) {
  const goal = el("section");
  add(goal, row(el("h2", "所選日的目標"), btn("編輯", goalsDialog)));
  for (const [label, value, target] of [
    ["專注", m.focus, prefs.focus_minutes],
    ["分心預算", m.distraction, prefs.distraction_minutes],
  ]) {
    const progress = add(el("div", null, "bar"), el("i"));
    progress.style.setProperty(
      "--progress",
      `${target ? Math.min(100, (value / (target * 60000)) * 100) : 0}%`,
    );
    add(
      goal,
      el("h3", label),
      el("small", `${duration(value)} / ${target} 分鐘`),
      progress,
    );
  }
  add(parent, goal);
  const panel = el("section");
  add(parent, panel);
  add(panel, el("h2", "這一天的樣子"));
  const donut = el("div", null, "donut");
  let start = 0;
  const stops = Object.entries(m.totals).map(([id, ms]) => {
    const end = start + (ms / (m.total || 1)) * 360;
    const stop = `${styleFor(id).color} ${start}deg ${end}deg`;
    start = end;
    return stop;
  });
  if (stops.length)
    donut.style.background = `conic-gradient(${stops.join(",")})`;
  add(
    donut,
    add(el("div"), el("small", "記錄時間"), el("strong", duration(m.total))),
  );
  add(panel, donut);
  Object.entries(m.totals)
    .sort((a, b) => b[1] - a[1])
    .forEach(([id, ms]) => {
      const c = styleFor(id),
        line = el("div", null, "legend");
      line.style.setProperty("--cat", c.color);
      add(
        line,
        add(el("span"), el("i", null, "dot"), document.createTextNode(c.name)),
        el("span", duration(ms)),
      );
      add(panel, line);
    });
  add(panel, el("h3", "我的回顧"));
  const last = reviewRows.find((r) => r.kind === "review");
  add(
    panel,
    el(
      "p",
      last
        ? { focused: "專注", neutral: "平衡", distracted: "分心" }[last.rating]
        : "還沒有留下回顧。",
    ),
  );
  add(
    panel,
    row(
      ...[
        ["distracted", "分心"],
        ["neutral", "平衡"],
        ["focused", "專注"],
      ].map(([rating, label]) =>
        btn(label, async () => {
          const [at] = windowFor(day);
          await saveRecord({
            id: `review-${day}`,
            at_ms: at,
            kind: "review",
            text: "",
            rating,
          });
          await shell();
        }),
      ),
    ),
  );
  add(
    panel,
    el(
      "p",
      `最長專注 ${duration(m.longest)} · 分心 ${duration(m.distraction)}。分類角色由你設定。`,
      "muted",
    ),
  );
}
async function saveRecord(item) {
  await api("/v1/reviews", {
    end_ms: null,
    rating: null,
    source: null,
    ...item,
  });
}
function editCard(c) {
  modal("編輯活動卡", (p) => {
    add(p, el("p", `${time(c.start_ms)}–${time(c.end_ms)} · ${c.app}`));
    const title = field(p, "標題", c.title),
      summary = field(p, "摘要", c.summary, "textarea"),
      category = select(
        p,
        "分類",
        prefs.categories.map((c) => [c.id, c.name]),
        c.category,
      );
    title.maxLength = 200;
    summary.maxLength = 1000;
    add(
      p,
      btn(
        "儲存",
        async () => {
          await api("/v1/cards", {
            id: c.id,
            title: title.value,
            summary: summary.value,
            category: category.value,
          });
          close();
          await shell();
        },
        "primary",
      ),
    );
  });
}
async function review(parent, days) {
  const m = metrics(current);
  stats(parent, m);
  const panel = el("section");
  add(parent, panel);
  add(
    panel,
    el("h2", page === "weekly" ? "這週，把時間留給了什麼？" : "回看今天的節奏"),
    el("p", "以下為活動時間的統計整理。情緒與生產力需要你自己判斷。"),
  );
  const chart = el("div");
  const max = Math.max(1, ...Object.values(m.totals));
  for (const [id, ms] of Object.entries(m.totals).sort((a, b) => b[1] - a[1])) {
    const bar = add(el("div", null, "bar"), el("i"));
    bar.style.setProperty("--progress", `${(ms / max) * 100}%`);
    add(
      chart,
      add(
        el("div", null, "chart-row"),
        el("span", styleFor(id).name),
        bar,
        el("small", duration(ms)),
      ),
    );
  }
  add(panel, chart);
  for (const d of days) {
    const block = el("div", null, "card");
    add(block, el("h3", d.date));
    const top = [...d.cards]
      .sort((a, b) => b.end_ms - b.start_ms - (a.end_ms - a.start_ms))
      .slice(0, 3);
    add(
      block,
      el(
        "p",
        top.length
          ? top
              .map((c) => `${c.title} · ${duration(c.end_ms - c.start_ms)}`)
              .join("\n")
          : "這天沒有活動資料。",
      ),
    );
    const checkins = d.checkins;
    add(
      block,
      el(
        "small",
        checkins.length
          ? `你留下 ${checkins.length} 筆感受：${checkins.map((c) => `${time(c.at_ms)} 心情 ${c.mood}/5、精神 ${c.energy}/5`).join("；")}`
          : "沒有感受紀錄。",
      ),
    );
    if (page === "daily") {
      const reflection = field(
        block,
        "每日反思",
        d.reflections.map((r) => r.text).join("\n"),
        "textarea",
      );
      reflection.maxLength = 2000;
      add(
        block,
        btn("儲存反思", async () => {
          const [at] = windowFor(d.date);
          await api("/v1/reflections", {
            id: `reflection-${d.date}`,
            at_ms: Math.min(at + 8 * 3600000, Date.now()),
            text: reflection.value,
          });
          notice("反思已儲存");
        }),
      );
    } else d.reflections.forEach((r) => add(block, el("p", r.text)));
    add(panel, block);
  }
  if (page === "daily") {
    const mood = select(
        panel,
        "現在的心情",
        [1, 2, 3, 4, 5].map((n) => [String(n), String(n)]),
        "3",
      ),
      energy = select(
        panel,
        "現在的精神",
        [1, 2, 3, 4, 5].map((n) => [String(n), String(n)]),
        "3",
      );
    add(
      panel,
      btn("記下此刻", async () => {
        await api("/v1/checkins", {
          id: crypto.randomUUID(),
          at_ms: Date.now(),
          mood: +mood.value,
          energy: +energy.value,
        });
        notice("感受已記下");
        await shell();
      }),
    );
  }
  add(
    panel,
    row(
      btn("產生並保存 AI 回顧", async () => {
        if (!ai.enabled) throw Error("請先設定自己的 AI");
        if (
          !confirm(
            `這次傳送最多 100 張活動卡（不含原始畫面、感受、反思）到 ${ai.model}，並保存摘要 30 天；可能產生 API 費用。繼續？`,
          )
        )
          return;
        const [from] = windowFor(days[0].date);
        const [, to] = windowFor(days.at(-1).date);
        await api("/v1/briefs", {
          id: crypto.randomUUID(),
          from_ms: from,
          to_ms: to,
          kind: page === "weekly" ? "weekly" : "daily",
          provider_id: ai.provider_id,
          consent: true,
        });
        await shell();
      }),
      btn("摘要排程", briefScheduleDialog),
      btn("匯出 JSON", () => exportData(days)),
    ),
  );
  const [briefFrom] = windowFor(days[0].date);
  const [, briefTo] = windowFor(days.at(-1).date);
  const savedBriefs = await api(
    `/v1/briefs?from_ms=${briefFrom}&to_ms=${briefTo}`,
  );
  add(panel, el("h3", "已保存的 AI 回顧"));
  if (!savedBriefs.briefs.length)
    add(panel, el("p", "尚無摘要；可自行產生，或明確開啟排程。"));
  savedBriefs.briefs.forEach((b) =>
    add(
      panel,
      add(
        el("article", null, "card"),
        el("h3", b.kind === "weekly" ? "每週摘要" : "每日摘要"),
        el(
          "small",
          `${new Date(b.from_ms).toLocaleString()} – ${new Date(b.to_ms).toLocaleString()}`,
        ),
        el("p", b.text, "message"),
        btn("刪除這份摘要", async () => {
          if (!confirm("永久刪除這份摘要？")) return;
          await api(`/v1/briefs/${b.id}`, null, "DELETE");
          await shell();
        }),
      ),
    ),
  );
}
async function briefScheduleDialog() {
  const schedule = await api("/v1/briefs/schedule");
  modal("每日／每週 AI 摘要排程", (p) => {
    add(
      p,
      el(
        "p",
        "獨立同意：排程只傳送已完成日／週的最多 100 張活動卡（24 KB），不含原始畫面、心情或反思。使用你的模型並產生 API 費用；每期最多三次嘗試。不補跑歷史。更換模型後需重新同意。刪除日誌會停用排程。",
      ),
    );
    const enabled = check(
      p,
      `同意持續將上述活動卡送到 ${ai.model || "尚未設定的模型"}，並保存摘要 30 天`,
      schedule.enabled && schedule.provider_id === ai.provider_id,
    );
    const timezone = field(
      p,
      "IANA 時區",
      schedule.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone,
    );
    const time = field(
      p,
      "每天產生時間（04:00–23:59）",
      `${String(Math.floor(schedule.minute_of_day / 60)).padStart(2, "0")}:${String(schedule.minute_of_day % 60).padStart(2, "0")}`,
      "time",
    );
    const daily = check(p, "每日摘要", schedule.daily),
      weekly = check(
        p,
        "每週摘要（上一個已完成的週一到週一）",
        schedule.weekly,
      );
    add(
      p,
      btn(
        "儲存排程",
        async () => {
          const parts = time.value.split(":").map(Number);
          if (parts.length !== 2 || !parts.every(Number.isInteger))
            throw Error("請填寫有效時間");
          await api(
            "/v1/briefs/schedule",
            {
              revision: schedule.revision,
              enabled: enabled.checked,
              provider_id: ai.provider_id || "",
              timezone: timezone.value.trim(),
              minute_of_day: parts[0] * 60 + parts[1],
              daily: daily.checked,
              weekly: weekly.checked,
            },
            "PUT",
          );
          close();
          notice("摘要排程已更新");
        },
        "primary",
      ),
    );
  });
}
async function chatPage(parent) {
  const p = el("section");
  add(parent, p);
  add(
    p,
    el(
      "p",
      "向自己的 AI 詢問選定的日／週。每次提問僅送出選定範圍最多 100 張活動卡的 app、時間、標題與摘要；不含原始畫面、文字、心情及反思。回答可能有誤，請核對時間軸。",
    ),
  );
  if (!ai.enabled) {
    add(
      p,
      el("p", "先設定你自己的 AI，才可開始對話。"),
      btn("設定 AI", () =>
        providerDialog(async () => {
          ai = await api("/v1/ai");
          await shell();
        }),
      ),
    );
    return;
  }
  add(p, el("p", `${ai.model} · ${ai.endpoint}`, "muted"));
  const saved = (await api("/v1/conversations")).conversations;
  const history = select(
    p,
    "接續已保存的對話",
    [["", "選擇對話"], ...saved.map((t) => [t.id, t.title])],
    saved.some((t) => t.id === conversationId) ? conversationId : "",
  );
  history.onchange = () =>
    safe(async () => {
      if (!history.value) return;
      conversationId = history.value;
      const thread = await api(`/v1/conversations/${conversationId}`);
      chatCursor = thread.next_cursor;
      chat = thread.turns.flatMap((t) => [
        { role: "user", text: t.question },
        { role: "assistant", text: t.answer },
      ]);
      await shell();
    });
  add(
    p,
    btn("開啟新對話", async () => {
      conversationId = crypto.randomUUID();
      chatCursor = null;
      pendingAsk = null;
      chat = [];
      await shell();
    }),
  );
  if (chatCursor)
    add(
      p,
      btn("載入更早的問答", async () => {
        const thread = await api(
          `/v1/conversations/${conversationId}?before_ms=${chatCursor.before_ms}&before_id=${encodeURIComponent(chatCursor.before_id)}`,
        );
        chatCursor = thread.next_cursor;
        chat = [
          ...thread.turns.flatMap((t) => [
            { role: "user", text: t.question },
            { role: "assistant", text: t.answer },
          ]),
          ...chat,
        ];
        await shell();
      }),
    );
  chat.forEach((m) => add(p, el("div", m.text, `message ${m.role}`)));
  add(
    p,
    btn(chatScope === "day" ? "切換為本週" : "切換為所選日", async () => {
      chatScope = chatScope === "day" ? "week" : "day";
      await shell();
    }),
    el(
      "p",
      chatScope === "week" ? "提問範圍：所選日期的本週" : "提問範圍：所選日期",
      "muted",
    ),
  );
  add(
    p,
    btn("查看這次範圍的分類圖表", async () => {
      const dates =
        chatScope === "week"
          ? weekDates().filter(
              (d) => d <= journalDate() && d >= shift(journalDate(), -28),
            )
          : [day];
      const [from] = windowFor(dates[0]);
      const [, to] = windowFor(dates.at(-1));
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
        if (!data.categories.length) add(body, el("p", "這個範圍沒有活動。"));
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
            el(
              "p",
              `${styleFor(c.category).name} · ${duration(c.duration_ms)}`,
            ),
            bar,
          );
        });
      });
    }),
  );
  const question = field(
    p,
    "你想問什麼？",
    chat.at(-1)?.role === "user" ? chat.at(-1).text : "",
    "textarea",
  );
  question.maxLength = 1000;
  const consent = check(
    p,
    "我同意這次將活動卡、問題與本對話最近最多 6 輪（6,000 字）的內容傳給這個模型，並將問答保存在私人伺服器 30 天；API 費用由我承擔。",
  );
  add(
    p,
    btn(
      "傳送這次提問",
      async () => {
        if (!consent.checked) throw Error("請先同意這次傳送範圍");
        const q = question.value.trim();
        if (!q) throw Error("請輸入問題");
        const dates =
          chatScope === "week"
            ? weekDates().filter(
                (d) => d <= journalDate() && d >= shift(journalDate(), -28),
              )
            : [day];
        const [from] = windowFor(dates[0]);
        const [, to] = windowFor(dates.at(-1));
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
        const result = await api("/v1/ask", pendingAsk.body);
        pendingAsk = null;
        chat.push(
          { role: "user", text: q },
          { role: "assistant", text: result.answer },
        );
        chat = chat.slice(-20);
        await shell();
      },
      "primary",
    ),
    btn("刪除此對話", async () => {
      if (!confirm("永久刪除這個對話的所有問答？")) return;
      await api(`/v1/conversations/${conversationId}`, null, "DELETE");
      conversationId = crypto.randomUUID();
      chatCursor = null;
      pendingAsk = null;
      chat = [];
      await shell();
    }),
    el(
      "p",
      "對話保留 30 天，可於網站與手機接續。更換模型設定後，請開啟新對話；舊對話不會轉送給新提供者。",
      "muted",
    ),
  );
}
async function replayDialog() {
  const [from, to] = windowFor(day);
  const images = (await api(`/v1/replay?from_ms=${from}&to_ms=${to}`)).images;
  modal("短期畫面回放", (p) => {
    add(
      p,
      el(
        "p",
        "只有在手機另外同意保存供回放的縮圖會顯示，最多 24 小時／256 張，最長邊 480 px。原本只供分析的原始畫面仍不可讀回。圖片可能含有私人資訊；預先排除並不等於 OCR 遮罩。",
      ),
    );
    const stage = el("div"),
      position = el("p");
    add(p, position, stage);
    let index = 0;
    const render = () => {
      stage.replaceChildren();
      if (!images.length) {
        position.textContent = "沒有已同意且仍在保存期限內的縮圖。";
        return;
      }
      const item = images[index];
      position.textContent = `${index + 1} / ${images.length} · ${time(item.at_ms)} · ${item.app}`;
      const image = el("img");
      image.src = `/v1/replay/${item.id}/image`;
      image.alt = `${item.app} ${time(item.at_ms)} 的核准縮圖`;
      image.style.maxWidth = "100%";
      add(stage, image);
    };
    add(
      p,
      row(
        btn("上一張", () => {
          index = Math.max(0, index - 1);
          render();
        }),
        btn("下一張", () => {
          index = Math.min(images.length - 1, index + 1);
          render();
        }),
        btn("刪除這張", async () => {
          if (!images.length || !confirm("永久刪除這張回放縮圖？")) return;
          await api(`/v1/replay/${images[index].id}`, null, "DELETE");
          images.splice(index, 1);
          index = Math.max(0, Math.min(index, images.length - 1));
          render();
        }),
      ),
    );
    render();
  });
}
async function flowPage(parent) {
  flow = (await api("/v1/focus")).session;
  const p = el("section");
  add(parent, p);
  add(
    p,
    el("h2", "留一段時間，做一件事。"),
    el(
      "p",
      "私人專注計時：由你開始、結束並記下成果。只有完成時儲存這段紀錄，不會在背景擷取畫面，也不會把計時當作實際活動。",
    ),
  );
  const name = field(p, "這次想專注在什麼？", flow?.text || "");
  name.maxLength = 300;
  const minutes = field(p, "預計分鐘", flow?.minutes || 25, "number");
  minutes.min = "1";
  minutes.max = "180";
  const timer = el("div", null, "timer");
  add(p, timer);
  const update = () => {
    const remaining = flow
      ? Math.max(0, flow.at_ms + flow.minutes * 60000 - Date.now())
      : +minutes.value * 60000;
    timer.textContent = `${String(Math.floor(remaining / 60000)).padStart(2, "0")}:${String(Math.floor(remaining / 1000) % 60).padStart(2, "0")}`;
    if (flow && remaining <= 0)
      timer.setAttribute("aria-label", "計時已結束，可以儲存成果");
  };
  update();
  timerHandle = setInterval(update, 1000);
  name.disabled = minutes.disabled = !!flow;
  add(
    p,
    row(
      btn(
        flow ? "儲存並結束" : "開始專注",
        async () => {
          if (flow) {
            await api("/v1/focus/finish", { id: flow.id });
            flow = null;
            await shell();
          } else {
            if (
              !Number.isInteger(+minutes.value) ||
              +minutes.value < 1 ||
              +minutes.value > 180 ||
              !name.value.trim()
            )
              throw Error("請填寫主題與 1–180 分鐘");
            const session = {
              id: crypto.randomUUID(),
              at_ms: Date.now(),
              minutes: +minutes.value,
              text: name.value,
            };
            flow = (await api("/v1/focus", session)).session;
            await shell();
          }
        },
        "primary",
      ),
      btn("放下這次計時", async () => {
        if (flow) await api("/v1/focus", { id: flow.id }, "DELETE");
        flow = null;
        await shell();
      }),
    ),
  );
  add(
    p,
    el(
      "p",
      "計時保存在私人伺服器，可在手機與網站接續；重新整理後可恢復。完成時才會儲存為專注紀錄。這是 PocketLog 的私人專注功能；Dayflow 的 Flow 雲端服務未與本工具連接。",
      "muted",
    ),
  );
  const [from, to] = windowFor(journalDate());
  const records = (await api(`/v1/reviews?from_ms=${from}&to_ms=${to}`))
    .records;
  records
    .filter((r) => r.kind === "flow")
    .forEach((r) =>
      add(
        p,
        add(
          el("div", null, "card"),
          el("h3", r.text),
          el(
            "p",
            `${time(r.at_ms)}–${time(r.end_ms)} · ${duration(r.end_ms - r.at_ms)}`,
          ),
        ),
      ),
    );
}
function agentImportDialog() {
  modal("預覽原生 Agent 工作紀錄", (p) => {
    add(
      p,
      el(
        "p",
        "只在瀏覽器解析你選取的檔案。顯示近 30 天最後 40 則文字，每則最多 1000 字；工具輸出、附件、環境與路徑中繼資料不會匯入。請逐則檢查並移除金鑰、個資與不想保存的內容，只有勾選的文字會送到私人伺服器，不會送到 AI。",
      ),
    );
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
    add(p, preview);
    let choices = [];
    add(
      p,
      btn("在此裝置預覽", async () => {
        const f = file.files[0];
        if (!f || f.size > 524288) throw Error("請選擇 512 KiB 內的 JSONL");
        const turns = parseAgentJsonl(await f.text(), source.value);
        preview.replaceChildren();
        choices = [];
        if (!turns.length) add(preview, el("p", "沒有近 30 天可匯入的文字。"));
        turns.forEach((t) => {
          const block = el("article", null, "card");
          add(preview, block);
          const selected = check(
            block,
            `${t.role} · ${new Date(t.at_ms).toLocaleString()}`,
          );
          const text = field(block, "核准後保存的文字", t.text, "textarea");
          text.maxLength = 1000;
          choices.push({ t, selected, text, source: source.value });
        });
      }),
      btn(
        "只匯入勾選的文字",
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
          await api("/v1/agent-sessions", body, "PUT");
          close();
          await shell();
        },
        "primary",
      ),
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
    add(p, position, stage);
    const render = () => {
      const t = session.turns[index];
      position.textContent = `${index + 1} / ${session.turns.length} · ${t.role} · ${new Date(t.at_ms).toLocaleString()}`;
      stage.replaceChildren(el("p", t.text, "message"));
    };
    add(
      p,
      row(
        btn("上一則", () => {
          index = Math.max(0, index - 1);
          render();
        }),
        btn("下一則", () => {
          index = Math.min(session.turns.length - 1, index + 1);
          render();
        }),
      ),
    );
    render();
    add(
      p,
      btn("刪除整個工作紀錄", async () => {
        if (!confirm("永久刪除這個工作紀錄與核准的對話？")) return;
        await api(`/v1/agent-sessions/${id}`, null, "DELETE");
        close();
        await shell();
      }),
    );
  });
}
async function agents(parent) {
  const p = el("section");
  add(parent, p);
  add(
    p,
    el("h2", "和 AI 一起做過的事。"),
    el(
      "p",
      "官方 Dayflow 的 Agents 會讀取桌面的 Codex／Claude 工作紀錄。PocketLog 目前提供明確選取 JSON 匯入與手動摘要，讓你決定哪些工作內容進入私人日誌；不會掃描電腦或取得工具執行權限。",
    ),
  );
  add(p, btn("選取 Codex／Claude 原生 JSONL 並預覽", agentImportDialog));
  const sessions = (await api("/v1/agent-sessions")).sessions;
  const projects = [...new Set(sessions.map((s) => s.project))];
  const project = select(
    p,
    "工作線／專案",
    [["", "全部專案"], ...projects.map((s) => [s, s])],
    "",
  );
  const sessionList = el("div");
  add(p, sessionList);
  function renderSessions() {
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
  }
  project.onchange = renderSessions;
  renderSessions();
  const file = field(p, "匯入 PocketLog Agents JSON", "", "file");
  file.accept = "application/json,.json";
  add(
    p,
    btn("檢查並匯入", async () => {
      const f = file.files[0];
      if (!f || f.size > 48000) throw Error("請選擇 48 KB 內的 JSON");
      const data = JSON.parse(await f.text());
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
        !confirm(
          `將 ${sessions.length} 筆你選取的工作摘要存入私人日誌？請確認不含金鑰或私密對話。`,
        )
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
  );
  const example = el(
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
  );
  example.style.whiteSpace = "pre-wrap";
  add(p, example);
  const text = field(p, "記下這次工作的成果／待辦", "", "textarea"),
    source = select(
      p,
      "使用的工具",
      [
        ["codex", "Codex"],
        ["claude", "Claude"],
      ],
      "codex",
    );
  text.maxLength = 4000;
  add(
    p,
    btn("儲存工作摘要", async () => {
      if (!text.value.trim()) throw Error("請填寫工作摘要");
      await saveRecord({
        id: crypto.randomUUID(),
        at_ms: Date.now(),
        kind: "agent",
        text: text.value,
        source: source.value,
      });
      await shell();
    }),
  );
  const records = reviewRows.filter((r) => r.kind === "agent");
  if (!records.length)
    add(p, el("div", "所選日期還沒有匯入的工作紀錄。", "empty"));
  records.forEach((r) =>
    add(
      p,
      add(
        el("div", null, "card"),
        el("h3", `${r.source} · ${time(r.at_ms)}`),
        el("p", r.text),
      ),
    ),
  );
}
async function devicesDialog() {
  const data = await api("/v1/devices");
  modal("裝置連線與撤銷", (p) => {
    add(
      p,
      el(
        "p",
        "為每台手機建立獨立金鑰，最多 32 個，有效 1–90 天。日誌權限可操作私人日誌與 AI 設定，但不能管理裝置或登入 owner 網站；收集權限只可上傳活動／情境及讀取暫停與模型資訊。撤銷後的新請求立即失效，已開始的請求無法撤回；離線手機待同步後才會知道。",
      ),
    );
    data.devices.forEach((d) =>
      add(
        p,
        add(
          el("article", null, "card"),
          el("h3", d.label),
          el(
            "p",
            `${d.scope} · 到期 ${new Date(d.expires_ms).toLocaleString()}`,
          ),
          btn("撤銷此裝置", async () => {
            if (!confirm(`撤銷 ${d.label} 的連線？已存入的日誌仍保留。`))
              return;
            await api(`/v1/devices/${d.id}`, null, "DELETE");
            close();
            await devicesDialog();
          }),
        ),
      ),
    );
    const label = field(p, "裝置名稱", ""),
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
    add(
      p,
      btn(
        "建立獨立裝置金鑰",
        async () => {
          const result = await api("/v1/devices", {
            label: label.value.trim(),
            scope: scope.value,
            days: +days.value,
          });
          close();
          modal("金鑰僅顯示這一次", (body) => {
            add(
              body,
              el(
                "p",
                "在手機連線設定填入私人伺服器網址與這個裝置金鑰。金鑰具有上述權限，請妥善保管；關閉後不能再次讀取，可撤銷並重建。",
              ),
            );
            const secret = field(body, "裝置金鑰", result.token, "password");
            secret.readOnly = true;
            add(
              body,
              btn("複製金鑰", async () => {
                await navigator.clipboard.writeText(secret.value);
                notice("已複製裝置金鑰；請妥善保管剪貼簿內容");
              }),
              btn("關閉", () => close()),
            );
          });
        },
        "primary",
      ),
    );
  });
}
async function settings(parent) {
  const p = el("section");
  add(parent, p);
  add(
    p,
    el("h2", "讓 PocketLog 適合你的節奏。"),
    row(
      btn("裝置金鑰與撤銷", devicesDialog),
      btn("每日目標", goalsDialog),
      btn("分類與專注角色", categoriesDialog),
      btn("Bring your own AI", () =>
        providerDialog(async () => {
          ai = await api("/v1/ai");
          await shell();
        }),
      ),
      btn("重新閱讀 onboarding", () => onboard(0)),
    ),
  );
  const control = await api("/v1/collection");
  add(
    p,
    el("h3", control.paused ? "伺服器活動收集已暫停" : "伺服器接受活動收集"),
    el(
      "p",
      "暫停會立即拒絕新的活動／情境並刪除待分析原始資料。手機在既有同步時段取得狀態後停止擷取、清除待傳活動；離線手機無法立即收到遠端暫停。恢復不會開啟手機權限或取代同意。",
    ),
    btn(control.paused ? "恢復伺服器收集" : "暫停所有裝置收集", async () => {
      if (
        !confirm(
          control.paused
            ? "恢復接收仍有本機同意的手機紀錄？"
            : "暫停所有裝置的活動收集並刪除待分析情境？",
        )
      )
        return;
      await api(
        "/v1/collection",
        { ...control, paused: !control.paused },
        "PUT",
      );
      await shell();
    }),
  );
  const privacy = el("section");
  add(parent, privacy);
  add(
    privacy,
    el("h2", "資料與權限"),
    el(
      "p",
      "手機擷取權限在 Android 的「紀錄、排除與同步設定」管理：使用紀錄、文字、圖片、AI 同意彼此獨立。排除、允許名單與密碼／可編輯欄位檢查會在擷取前執行。這個網站不會更改手機的系統權限。",
    ),
    el(
      "p",
      "手機隱私防護在截圖前排除疑似金鑰、密碼值與長數字的可見文字視窗（預設開啟）；仍未提供 Dayflow 式 OCR 像素遮罩。請勿把它視為可放心擷取密碼、銀行或敏感 app 的保證。",
    ),
    btn("查看服務狀態", async () =>
      modal("服務狀態", async (body) => {
        const status = await api("/v1/status");
        add(body, el("pre", JSON.stringify(status, null, 2)));
      }),
    ),
    btn("登出", async () => {
      await api("/session", null, "DELETE");
      chat = [];
      flow = null;
      login();
    }),
  );
  add(
    privacy,
    btn(
      "永久刪除全部日誌",
      async () => {
        if (
          !confirm(
            "會刪除全部活動、感受、反思、專注與工作紀錄，無法復原。請先在所有手機停止紀錄。確定繼續？",
          )
        )
          return;
        await api("/v1/journal", null, "DELETE");
        chat = [];
        flow = null;
        notice("伺服器日誌已刪除");
        await shell();
      },
      "danger",
    ),
  );
}
function goalsDialog() {
  modal("每天留給自己的時間", (p) => {
    const focus = field(p, "專注目標（分鐘）", prefs.focus_minutes, "number"),
      budget = field(
        p,
        "分心預算（分鐘）",
        prefs.distraction_minutes,
        "number",
      );
    add(
      p,
      btn(
        "儲存",
        async () => {
          const next = {
            ...prefs,
            focus_minutes: +focus.value,
            distraction_minutes: +budget.value,
          };
          prefs = await api("/v1/workspace", next, "PUT");
          close();
          await shell();
        },
        "primary",
      ),
    );
  });
}
function categoriesDialog() {
  modal("分類、顏色與專注角色", (p) => {
    add(
      p,
      el(
        "p",
        "這些名稱、顏色與角色會在手機和網站共用。可新增分類與自動規則；已被活動使用的分類需先重新分類才能移除。",
      ),
    );
    const draft = structuredClone(prefs);
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
      role.setAttribute("aria-label", "分類角色");
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
          btn("移除此分類", () => {
            draft.categories = draft.categories.filter((x) => x.id !== c.id);
            draft.rules = draft.rules.filter((x) => x.category !== c.id);
            wrapper.remove();
            renderRules();
          }),
        );
    }
    draft.categories.forEach(renderCategory);
    add(
      p,
      btn("新增分類", () => {
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
      }),
    );
    add(
      p,
      el("h3", "自動分類規則"),
      el(
        "p",
        "由上到下採第一個符合的規則。指定 app 與標題關鍵字時必須同時符合；你的手動修改永遠優先。",
      ),
    );
    const ruleList = el("div");
    add(p, ruleList);
    function renderRules() {
      ruleList.replaceChildren();
      draft.rules.forEach((r, i) => {
        const block = el("div", null, "card");
        add(ruleList, block);
        const enabled = check(block, `規則 ${i + 1}：啟用`, r.enabled);
        enabled.onchange = () => (r.enabled = enabled.checked);
        const app = field(block, "完整 app 套件名稱（留白不限）", r.app || "");
        app.oninput = () => (r.app = app.value.trim() || null);
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
        add(
          block,
          row(
            btn("上移", () => {
              if (i) {
                [draft.rules[i - 1], draft.rules[i]] = [
                  draft.rules[i],
                  draft.rules[i - 1],
                ];
                renderRules();
              }
            }),
            btn("刪除規則", () => {
              draft.rules.splice(i, 1);
              renderRules();
            }),
          ),
        );
      });
    }
    renderRules();
    add(
      p,
      btn("新增規則", () => {
        if (draft.rules.length >= 32) throw Error("最多 32 條規則");
        draft.rules.push({
          id: crypto.randomUUID(),
          category: draft.categories[0].id,
          app: null,
          title_contains: null,
          enabled: true,
        });
        renderRules();
      }),
    );
    add(
      p,
      btn(
        "儲存分類",
        async () => {
          prefs = await api("/v1/workspace", draft, "PUT");
          close();
          await shell();
        },
        "primary",
      ),
    );
  });
}
function providerDialog(done) {
  modal("Bring your own AI", (p) => {
    add(
      p,
      el(
        "p",
        "支援 OpenAI 相容、Anthropic Messages 與 Gemini generateContent 原生 API。API key 以 HTTPS 送到你的私人伺服器，存於獨立 0600 設定檔，不會回傳給瀏覽器。伺服器管理者仍可讀取。",
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
    add(
      p,
      row(
        btn("OpenAI", () => {
          protocol.value = "openai";
          endpoint.value = "https://api.openai.com/v1/chat/completions";
        }),
        btn("OpenRouter", () => {
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
        "模型支援圖片（不會因此啟用手機截圖或 AI 同意）",
        ai.image_support,
      );
    add(
      p,
      el(
        "p",
        "端點從伺服器連線。HTTP 僅接受 literal loopback（127.0.0.1／[::1]）；Docker 的 localhost 是容器本身。儲存或更換設定會要求手機重新同意，儲存本身不會呼叫模型。",
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
            key.value = "";
            ai = await api("/v1/ai", body, "PUT");
            close();
            await done();
            notice("AI 設定已儲存；手機需重新同意才能傳送擷取內容");
          },
          "primary",
        ),
        btn("測試已儲存的設定", async () => {
          if (
            !confirm("只傳送固定測試文字，不含日誌；可能產生模型費用。繼續？")
          )
            return;
          await api("/v1/ai/test", {});
          notice("模型測試成功，未傳送日誌");
        }),
        btn("停用並刪除 key", async () => {
          if (!confirm("所有裝置停止使用這個模型並刪除伺服器 key？")) return;
          await api("/v1/ai", null, "DELETE");
          ai = await api("/v1/ai");
          close();
          await done();
        }),
      ),
    );
  });
}
function support(parent) {
  add(
    parent,
    add(
      el("section"),
      el("h2", "私人日誌，也需要清楚的邊界。"),
      el(
        "p",
        "若沒有活動，請先在手機同意使用紀錄，授予 Usage Access，並檢查排除清單、暫停狀態與同步網路條件。文字／圖片還需要個別授權與 app 允許名單。",
      ),
      el(
        "p",
        "AI 無法使用時，活動卡仍能顯示 app 與本機摘錄。請先測試模型設定，再於手機重新同意最新提供者。",
      ),
      el(
        "p",
        "日誌保留 30 天。匯出含私人衍生資料，請存放於信任的位置。回報問題時請勿附 owner token、API key、真實日誌或畫面。",
      ),
      link(
        "PocketLog 原始碼與使用文件",
        "https://github.com/skyhong2002/PocketLog",
      ),
      el(
        "p",
        "PocketLog 的 Flow 是私人專注紀錄；Agents 目前支援明確摘要匯入。Dayflow 的雲端 Flow、CLI 自動掃描、錄影回放與自動遮罩尚未移植。",
        "muted",
      ),
    ),
  );
}
function link(text, url) {
  const a = el("a", text);
  a.href = url;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  return a;
}
async function copyTimeline() {
  const text = current
    .map(
      (c) => `${time(c.start_ms)}–${time(c.end_ms)} ${c.title}\n${c.summary}`,
    )
    .join("\n\n");
  await navigator.clipboard.writeText(text || "尚無活動");
  notice("已複製私人時間軸");
}
function exportData(days) {
  if (
    !confirm(
      "匯出含私人活動、感受與反思；離開 app 後不受保留與刪除機制保護。繼續？",
    )
  )
    return;
  const blob = new Blob(
    [JSON.stringify({ version: 1, days, reviews: reviewRows }, null, 2)],
    { type: "application/json" },
  );
  const url = URL.createObjectURL(blob);
  const a = el("a");
  a.href = url;
  a.download = `pocketlog-${day}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
$("#modal").addEventListener("close", () => {
  if (!$("#modal").open) $("#modal-body").replaceChildren();
});
boot().catch((error) => {
  if (error.message !== "請重新登入私人日誌")
    notice(error.message || "無法載入，請重新整理");
});
