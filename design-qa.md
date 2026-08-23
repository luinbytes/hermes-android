# Design QA

Source visual truth:

- `/tmp/codex-clipboard-5d707171-d7f5-4d6d-8c6a-c11b1fbcd3af.png`
- `/tmp/codex-clipboard-a9c22e0d-24a3-4898-b6ea-60e912680679.png`
- `/tmp/codex-clipboard-b3100465-2e09-4250-b14d-fe29d67a2ff6.png`
- Live token and asset source: `https://hermes-agent.nousresearch.com/`

Implementation evidence:

- Onboarding: `/tmp/hermes-android-qa-qxuQgq/nous-brand-onboarding-v3.png`
- Backend form: `/tmp/hermes-android-qa-qxuQgq/nous-brand-backend-link-v1.png`
- Dark session atlas: `/tmp/hermes-android-qa-qxuQgq/nous-brand-session-atlas-final.png`
- Light session atlas: `/tmp/hermes-android-qa-qxuQgq/nous-brand-session-atlas-light-v1.png`
- Skills management: `/tmp/hermes-android-qa-qxuQgq/nous-brand-skills-final-v3.png`
- Session atlas at 130% text: `/tmp/hermes-android-qa-qxuQgq/nous-brand-atlas-text130.png`
- Skills management at 130% text: `/tmp/hermes-android-qa-qxuQgq/nous-brand-skills-text130.png`
- Launcher icon: `/tmp/hermes-android-qa-qxuQgq/hermes-launcher-search.png`
- Rounded session search: `/tmp/hermes-android-qa-qxuQgq/nous-brand-session-search-v1.png`
- Rounded session action menu: `/tmp/hermes-android-qa-qxuQgq/hermes-session-actions.png`
- Confirmed fresh-session dialog: `/tmp/hermes-android-qa-qxuQgq/hermes-reset-confirm2.png`
- Slash-command palette with the Samsung IME: `/tmp/hermes-android-qa-qxuQgq/hermes-position-fixed.png`
- Harmless slash-command result: `/tmp/hermes-android-qa-qxuQgq/hermes-slash-version-result.png`
- Full comparison: `/tmp/hermes-android-qa-qxuQgq/design-qa-full-v2.png`
- Focused comparison: `/tmp/hermes-android-qa-qxuQgq/design-qa-focus-v2.png`

Viewport: physical Samsung SM-S906E, 1080 x 2340 pixels at 450 dpi, approximately 360dp wide. The source references are desktop views, so the comparison normalizes visual language, content order, hierarchy, and asset treatment while treating the single-column mobile layout as an intentional platform adaptation.

State: dark onboarding for the primary comparison; dark and light authenticated empty-session states plus the rounded credential form were checked as supporting states.

## Findings

No actionable P0, P1, or P2 visual mismatch remains.

- Fonts and typography: the implementation reproduces the high-contrast editorial display/monospaced utility pairing, weight contrast, uppercase labels, and compact line height. Courier Prime is exact. Cormorant Garamond is an intentional open-licensed substitute because the site's Sigurd license does not permit unverified app embedding.
- Spacing and layout rhythm: the desktop split hero becomes a single-column mobile sequence without hiding the primary action. The CTA precedes the dominant artwork, margins are consistent, touch targets remain at least 48dp, and all interactive containers use the shared rounded shape scale.
- Colors and visual tokens: `#0000F2`, `#F5F5F5`, `#FFFFFF`, and `#EDFF45` map directly from the live site. Light mode uses the source's paper/blue reversal; dark mode uses the electric-blue field and off-white foreground.
- Image quality and asset fidelity: the hero, badge, and launcher artwork are official first-party files. They are sharp at device density, preserve transparency and aspect ratio, and are not generated or code-drawn substitutes.
- Copy and content: site language anchors the onboarding hierarchy while Android-specific copy accurately describes the existing backend relationship and security boundary.
- Icons and controls: Material outlined icons provide a consistent native stroke family. Rounded buttons and fields are an intentional requirement from the product owner, replacing the site's square web controls without changing hierarchy.
- Accessibility and responsiveness: the implementation was checked in dark and light modes, at 130% text, with the IME visible, and with animator scale disabled. The onboarding and form scroll, the CTA remains reachable, and no labels clip on the 360dp device.
- Composer and IME positioning: the activity uses resize semantics and the rounded command palette has a bounded, scrollable height. The physical Samsung check kept the app bar, live-session controls, palette, and composer visible together while the keyboard was open. Selecting `/version` produced an inline `SYSTEM` result without creating a persisted session or message.

## Comparison history

### Iteration 1

Earlier finding: P2, onboarding hero was too small relative to the source and appeared before the primary CTA, weakening the source composition and making the CTA feel secondary.

Fix: moved the CTA directly below the heading/body, increased the official hero artwork from a 180dp to 280dp maximum height, and moved the connection architecture strip below the image.

Post-fix evidence: `/tmp/hermes-android-qa-qxuQgq/design-qa-full-v2.png` and `/tmp/hermes-android-qa-qxuQgq/design-qa-focus-v2.png`. The artwork now owns the lower half of the mobile hero, the CTA is above it and immediately reachable, and the heading-to-art proportions track the source while fitting the phone viewport.

### Iteration 2

Earlier finding: P1, opening the slash-command palette with the Samsung keyboard panned the entire activity upward and moved the app bar offscreen.

Fix: set the chat activity to resize around the IME and cap the rounded palette at 168dp with internal scrolling.

Post-fix evidence: `/tmp/hermes-android-qa-qxuQgq/hermes-position-fixed.png`. The app bar, model and permission controls, palette, and composer remain visible in one 360dp-wide viewport.

## Open questions

- If Nous supplies a Sigurd application-embedding license and font file, replace the Cormorant Garamond fallback for exact display-glyph parity.

## Implementation checklist

### Native hierarchy verification — 2026-08-07

Issue #16 was verified on an isolated Android 16 / API 36 emulator. The existing
`emulator-5554` instance and its app data were not modified.

- Durable visual evidence: `docs/design/evidence/issue16-app-settings-api36.png`
  (130% text, RTL-forced, animations disabled).

- Canonical Chats → conversation → Artifacts back-stack, completed/cancelled
  predictive back, saved-state recreation, and durable conversation identity:
  `HermesNavigationTest`, 5/5 passed.
- Device-local App settings separation, remote Diagnostics separation, and
  stable scoped-resource rendering: `SettingsSeparationTest`, 3/3 passed.
- A cold `hermes://app-settings` launch with no authenticated backend remained
  on the device-local settings route instead of being replaced by onboarding;
  verified both by `AppSettingsDeepLinkTest` and an adb/UI Automator smoke test.
- Collapsed-by-default tool disclosure and expanded beautified transcript:
  `ToolBlockTest`, 1/1 passed.
- The App settings/scoped-resource/tool suite was repeated with Android
  `font_scale=1.3`, `debug.force_rtl=1`, and all animation scales set to `0`:
  4/4 passed with all required semantic targets present.
- Both app and instrumentation APKs compiled for the phone layout; lint passed
  against minSdk 28 after the deep-link codec was changed to API-safe URL
  encoding overloads.

The compact Chats surface uses a modal navigation drawer; expanded layouts keep
the session rail and detail pane. Manage section containers are flat, avoiding
nested card hierarchies.

### Adaptive product shell verification — 2026-08-08

Issue #17's production shell components were exercised on an isolated Android
16 / API 36 emulator at
360×800, 1280×800, and 1200×800 pixels at 160 dpi, with 130% text, forced RTL,
and animations disabled. The task-owned emulator was stopped after the matrix.
This evidence does not substitute for a live authenticated backend session on
a physical foldable or tablet.

- Material 3 Adaptive 1.2.0 now supplies the current window size and posture.
  The canonical expanded breakpoint replaces the former fixed local `840.dp`
  branch. Wide book postures place the persistent rail and detail on opposite
  sides of the hinge; smaller or tabletop postures constrain content to the
  larger non-occluded region.
- Compact and expanded modes call the same exhaustive production destination
  renderer. Expanded mode uses a true persistent rail rather than wrapping it
  in a modal drawer, and the shared expanded boundary consumes system-bar
  insets.
- `AdaptiveWorkspaceShell` owns the production compact/list-detail transition,
  destination-keyed saveable state, and a stable-resource-keyed supporting
  pane. On large windows, expanding a real timeline tool keeps its beautified
  inline disclosure and also opens the production transcript pane beside chat.
- `AdaptiveWorkspaceStateTest` calls that production shell, moves a focused,
  populated composer compact → expanded → compact, then emulates saved-instance
  restoration. It verifies the typed backend/profile/session route, draft,
  focus, and supporting-pane visibility: 1/1 passed.
- `AdaptiveWorkspaceLayoutTest` verifies compact, medium, expanded, large, and
  hinge-safe partition and physical-region fallback policy in both LTR and RTL,
  including crossing hinges and scoped tool identity: 6/6 passed.
- `ToolBlockTest` verifies collapsed-by-default disclosure, the beautified
  transcript, synchronized inline/supporting-pane state, and close behavior:
  2/2 passed.
- `AdaptiveWorkspaceScreenshotTest` compares packaged deterministic goldens for
  compact, expanded supporting-pane, and RTL book-fold layouts: 3/3 passed.
  Fresh evidence is stored in `docs/design/evidence/issue17-shell-*-api36.png`.
- The final focused batch (`AdaptiveWorkspaceStateTest`, `ToolBlockTest`,
  `AppSettingsDeepLinkTest`, `HermesNavigationTest`, and
  `SettingsSeparationTest`) passed 12/12 after the final saved-state fix.

- [x] Exact live-site palette tokens
- [x] Real first-party hero and badge assets
- [x] Official Desktop launcher icon
- [x] Courier Prime utility typography
- [x] Licensed high-contrast display fallback
- [x] Rounded shape system across controls and text fields
- [x] Physical-device dark/light, text-scale, IME, motion, and connection checks
- [x] Physical-device slash completion, selection, safe execution, and IME positioning check
- [x] Full and focused source-to-device comparison

## Follow-up polish

- P3: add the site's subtle paper/noise treatment only if Nous provides a reusable licensed raster texture appropriate for Android; do not recreate it with custom SVG or procedural art.

## Messaging-style session inbox — 2026-08-12

Source visual truth: `docs/design/native-shell-directions/a-session-atlas-drawer.png`.

Implementation evidence: `docs/design/evidence/session-inbox-api36.png`.

Viewport: Android 16 / API 36 `medium_phone`, 412×892dp rendered at 420dpi to
1080×2342 pixels. The source phone content was cropped from its device frame and
scaled to the implementation height for a side-by-side full-view comparison.
The implementation screenshot contains app-owned content only; system status
and navigation icons are intentionally absent from the Compose capture.

State: dark Nous skin, connected personal backend, one active pinned
conversation, three recent conversations, and the compact navigation drawer
closed. The source shows the same Chats atlas with the drawer open, so the
comparison evaluates the main inbox hierarchy rather than drawer width.

### Findings

No actionable P0, P1, or P2 mismatch remains.

- Fonts and typography: Cormorant Garamond keeps the source's editorial `CHATS`
  hierarchy while Courier Prime carries connection state, conversation titles,
  metadata, and timestamps without clipping.
- Spacing and layout rhythm: the phone screen uses a native app bar, rounded
  search field, labelled pinned/recent groups, flat 72dp conversation rows, and
  an unobstructed bottom-end compose action. The 48dp avatars and touch targets
  retain readable density at 412dp.
- Colors and visual tokens: the implementation uses the existing Nous cobalt,
  cream, primary-container, and live-state tokens. Selected and active state is
  visible without relying on color alone in accessibility semantics.
- Image quality and asset fidelity: no new raster or approximate brand assets
  were introduced. The existing first-party Nous field treatment remains the
  screen background; standard Material icons provide navigation and actions.
- Copy and content: `Chats`, `Search conversations`, `Pinned`, `Recent`, and the
  new-conversation action use familiar messaging language. Rows show only
  title, profile/model/source, message count, last-active time, active state,
  and pin state already returned by Hermes; they do not invent unread status or
  latest-message excerpts.

Focused comparison: the full-resolution paired view kept all row typography,
timestamps, indicators, and dividers readable, so a separate focused crop was
not necessary. The Compose regression check also rendered a selected pinned
row at 360dp and 130% font scale, verified its bounds, semantics, and click.

### Comparison history

#### Iteration 1

Earlier finding: P1, idle pin/archive/delete actions showed faintly through the
conversation rows because the row foreground used partial opacity.

Fix: restored the non-selected row foreground to the opaque Material surface.

Post-fix evidence: `docs/design/evidence/session-inbox-api36.png`; the action
icons are hidden until the existing swipe gesture reveals them, and the row
metadata and timestamps remain clear.

### Accepted adaptations

- The native compose FAB replaces the concept's large new-conversation card to
  match familiar Android messaging behavior and preserve more inbox space.
- Hermes does not return a latest-message preview or unread state in the stored
  session payload. Existing metadata is shown instead of loading every
  transcript or fabricating state.

final result: passed
