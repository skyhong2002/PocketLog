# PocketLog interface guidelines

PocketLog should feel like a warm personal notebook: a rounded sewn pocket-and-phone
brand mark, sage green accents, quiet green-tinted paper surfaces, readable colored activity
cards and enough information to understand a day. Use the existing logo assets;
do not replace them with an unrelated minimal mark or copy Dayflow artwork.

- Navigation has exactly three destinations: 時間軸, 回顧 and 設定
  ([simplified product](ux/SIMPLIFIED_IA.md)). The website already follows it;
  Android still shows its older tabs until its own simplification. Desktop web uses
  a narrow left rail, a flexible timeline and a card inspector; small screens use a
  three-item bottom bar. Native Android keeps platform controls and accessible card actions.
- Day begins at 04:00 in each client's system timezone; make this visible. Use actual
  start/end times and proportional interval cards. Never fill empty hours with
  invented activity. Overlapping intervals remain distinct selectable cards.
- The web uses a sage canvas (#f2f5ef), paper panels (#fcfdf9), green accent
  (#52765f), dark text (#303a32), and restrained pastel category colors. Category
  names accompany color; focus/distraction roles are explicit, editable choices.
- The selected web direction C uses self-hosted Noto Sans + Noto Sans TC, with
  real lowercase and regular 400 weight for the wordmark and primary headings.
  Use 26–30 px page titles, 16–21 px section titles and 14–16 px body text.
  Native Android uses the same sage palette and retains platform typography.
  Hour labels and metadata may be smaller in dense timelines. Labels,
  keyboard focus indicators and an accessible activity list matter more than icons.
- Onboarding has at most three skippable steps: the promise and what is / is never
  captured, connecting your AI, then connecting the phone. Share completion/settings
  across clients. Ask for OS access at the point where the phone needs it;
  completing product onboarding never means consenting to capture.
- Put the exact provider/model and outgoing data scope before AI consent. Saving
  credentials, testing synthetic text, future-capture consent and a one-off 問 AI
  request are separate actions (the website's 測試並儲存 saves first, then sends only
  the fixed test text, and says so beside the button). Distinguish model output, excerpts and user edits.
- Empty, offline, truncated, loading and failed states must be real states, with
  recovery actions. Do not display demo journals in an owner's production account.
- Treat each asynchronous read/write as belonging to its initiating page, dialog,
  selected data and submitted draft. Closing or replacing that context must prevent
  old responses from closing new editors, overwriting newer typing or expiring a
  newer UI. A successful save must distinguish submitted values from later edits.
- Arrival of new activity should preserve the current reading snapshot and offer
  an explicit reload. Foreground update checks must be bounded, stop while hidden
  and avoid interrupting editing. This is a display read, not capture or AI consent.
- Private exports and deletion need clear, specific confirmation. Keep routine
  reading/editing smooth. Never expose configuration paths or technical credentials
  in ordinary timeline cards. Never add analytics to measure UI use.

Current visual polish and native accessibility still need real-device review; the
native hourly canvas has equivalent editable text cards for accessibility. The
website respects reduced-motion preferences and has no autoplay/remote resources.

Canonical logo source: `docs/assets/logo.svg`. Regenerate web, documentation and
Android brand assets with `uv run --with pillow --with cairosvg --with fonttools
--with brotli scripts/generate-icons.py`. Documentation wordmarks outline the
selected Noto Sans 400 glyphs so the font does not depend on the viewer's system.
Activity category colors remain distinct and user-editable; red still identifies
destructive actions rather than serving as a brand accent.

### Web phone presentation

On initial load at860px or narrower, prefer the activity list: its full-size
cards keep short events readable and touchable. Desktop opens the proportional
grid. Both presentations remain explicitly selectable; resizing or changing pages
must retain the current selection within the tab. The opt-in dense grid still has
small short-event targets, so it is not the default phone reading experience.
