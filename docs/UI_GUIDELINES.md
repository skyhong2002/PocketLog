# PocketLog interface guidelines

PocketLog should feel like a warm personal notebook: a dimensional pocket-and-phone
brand mark, warm beige accents, quiet dark surfaces, readable colored activity
cards and enough information to understand a day. Use the existing logo assets;
do not replace them with an unrelated minimal mark or copy Dayflow artwork.

- Keep Timeline, Daily, Weekly, Chat, Flow, Agents, Support and Settings discoverable.
  Desktop uses a narrow left rail, a flexible timeline and a selected-day summary.
  Small screens stack summaries and keep navigation in a horizontally scrollable
  bottom rail. Native Android keeps platform controls and accessible card actions.
- Day begins at 04:00 in each client's system timezone; make this visible. Use actual
  start/end times and proportional interval cards. Never fill empty hours with
  invented activity. Overlapping intervals remain distinct selectable cards.
- Use a charcoal canvas (#1c1d21), slightly lighter panels (#24252a), warm accent
  (#dfbc92), muted text (#a9a7a2), and restrained pastel category colors. Category
  names accompany color; focus/distraction roles are explicit, editable choices.
- Prefer system fonts, 26–30 px page titles, 16–21 px section titles and 14–16 px
  body text. Hour labels and metadata may be smaller in dense timelines. Labels,
  keyboard focus indicators and an accessible activity list matter more than icons.
- Onboarding explains value, privacy, goals and optional BYO AI in that order.
  Share completion/settings across clients. Ask for OS access at the point where
  the phone needs it; completing product onboarding never means consenting to capture.
- Put the exact provider/model and outgoing data scope before AI consent. Saving
  credentials, testing synthetic text, future-capture consent and a one-off Chat
  request are separate actions. Distinguish model output, excerpts and user edits.
- Empty, offline, truncated, loading and failed states must be real states, with
  recovery actions. Do not display demo journals in an owner's production account.
- Private exports and deletion need clear, specific confirmation. Keep routine
  reading/editing smooth. Never expose configuration paths or technical credentials
  in ordinary timeline cards. Never add analytics to measure UI use.

Current visual polish and native accessibility still need real-device review; the
native hourly canvas has equivalent editable text cards for accessibility. The
website respects reduced-motion preferences and has no autoplay/remote resources.
