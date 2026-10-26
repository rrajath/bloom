---
name: Bloom Launch Site
description: The marketing landing page and docs for Bloom, a native Android org-mode note app — the site written as an org-mode buffer.
colors:
  warm-paper: "#f8f3ee"
  aged-paper: "#f0e9e3"
  toasted-paper: "#e6ddd6"
  ink-black: "#4a4466"
  warm-umber: "#625b7a"
  faded-umber: "#6b647e"
  ghost-taupe: "#9893ab"
  parchment-line: "#ebe2db"
  org-amber: "#98555d"
  amber-wash: "#f1e1dd"
  moss-olive: "#387078"
  terracotta-red: "#96536a"
  slate-blue: "#396d8e"
  dusty-plum: "#725f87"
  warm-paper-dark: "#25223a"
  aged-paper-dark: "#2b2842"
  toasted-paper-dark: "#3b3756"
  ink-black-dark: "#e2dff2"
  warm-umber-dark: "#a9a4c4"
  faded-umber-dark: "#9c97b3"
  ghost-taupe-dark: "#736e8f"
  parchment-line-dark: "#34304c"
  org-amber-dark: "#eba8a2"
  amber-wash-dark: "#45374b"
  moss-olive-dark: "#9fcfc4"
  terracotta-red-dark: "#e57d99"
  slate-blue-dark: "#a3d0da"
  dusty-plum-dark: "#c4a9e7"
typography:
  display:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "52px"
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "-0.02em"
  headline:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "26px"
    fontWeight: 700
  title:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "21px"
    fontWeight: 500
  body:
    fontFamily: "Source Serif 4, serif"
    fontSize: "19px"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 2
rounded:
  chip: "4px"
  button: "6px"
  source-card: "8px"
  card: "10px"
  card-inner: "5px"
  hero-card: "18px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "40px"
  2xl: "56px"
  3xl: "64px"
components:
  button-primary:
    backgroundColor: "{colors.org-amber}"
    textColor: "{colors.warm-paper}"
    typography: "{typography.label}"
    rounded: "{rounded.button}"
    padding: "13px 22px"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.ink-black}"
    typography: "{typography.label}"
    rounded: "{rounded.button}"
    padding: "13px 22px"
  chip:
    backgroundColor: "{colors.toasted-paper}"
    textColor: "{colors.org-amber}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
---

# Design System: Bloom Launch Site

## Overview

**Creative North Star: "The Buffer"**

The site's organizing conceit, set by the design handoff and carried through the implementation without deviation: *the website is itself an org file.* `#+TITLE:` and `#+SUBTITLE:` keyword lines open the hero, `* Features` and `** Capture` are real section headings, `:tags:` sit in a right-hand margin column, feature sections fold and unfold on `TAB` exactly like org headlines do in Emacs, and the page closes on a literal `#+END`. Nothing is a decorative reference to org-mode — the syntax is the layout vocabulary itself.

The mood is warm, editorial, and plaintext-nerd affectionate: a page that reads like a well-kept personal notes file, not a SaaS product tour. Warm paper backgrounds instead of clinical white or dark-tech black, a serif voice for anything meant to be read rather than scanned, and a monospace voice for anything that is structurally "org." The explicit anti-reference is the generic cold, minimalist tech-startup landing page — gradients, glassy cards, big rounded hero illustrations, marketing-speak. Bloom's site earns trust by looking like a tool built by someone who actually lives in plain text, not by looking impressive.

Light is the default theme (friendlier to first-time visitors), even though the Bloom Android app itself ships dark by default. Both themes are derived from the app's own palette so the site and the app read as the same product.

**Key Characteristics:**
- Org-mode syntax as literal, load-bearing UI language, not visual flavor
- Warm paper backgrounds (light) / warm near-black (dark), never cool neutrals
- Serif for reading, monospace for structure — a strict two-voice split
- Flat by default; the one shadow token exists solely to lift screenshot cards
- Hairline borders (`1px var(--line)`) as the only structural divider, everywhere
- No illustration, no icon set; the only mark is the Bloom app icon (favicon, landing buffer-bar wordmark, and Starlight docs-header logo), a squircle tile carrying the five-spoke org asterisk

## Colors

A warm, paper-and-ink palette carrying a small set of muted org-syntax accent hues — never a bright, saturated "tech" palette.

Values follow the app's Bloom Light / Bloom Dark ("Rosewater") themes. The role names below predate that switch and are kept as stable names; light-mode text hues are darkened along the same hue to clear AA.

### Primary
- **Org Amber** (`#98555d` / dark: `#eba8a2`): links, primary buttons, the `TODO` keyword, and org keyword lines (`#+TITLE:` etc.). The single accent color the eye is meant to land on.

### Secondary (org-syntax accents)
- **Moss Olive** (`#387078` / dark: `#9fcfc4`): headline stars (`*`, `**`), `:tags:`, list bullet dashes. Reads as "structure," not emphasis.
- **Terracotta Red** (`#96536a` / dark: `#e57d99`): `[#A]` priority markers, `DEADLINE:`. Reserved for org semantics the app itself uses for urgency — never a generic "danger" or error color.
- **Slate Blue** (`#396d8e` / dark: `#a3d0da`): `SCHEDULED:` and timestamps.
- **Dusty Plum** (`#725f87` / dark: `#c4a9e7`): `%^{prompt}` template placeholders. The rarest accent — capture-template copy only.

### Neutral
- **Warm Paper** (`#f8f3ee` / dark: `#25223a`): page background.
- **Aged Paper** (`#f0e9e3` / dark: `#2b2842`): raised panels, code blocks, the footer/CTA band.
- **Toasted Paper** (`#e6ddd6` / dark: `#3b3756`): inset chips, input wells, the theme-toggle pill fill.
- **Ink Black** (`#4a4466` / dark: `#e2dff2`): primary text, headings.
- **Warm Umber** (`#625b7a` / dark: `#a9a4c4`): body text, secondary copy.
- **Faded Umber** (`#6b647e` / dark: `#9c97b3`): muted text, tag labels, `#` comment lines.
- **Ghost Taupe** (`#9893ab` / dark: `#736e8f`): deliberately faded — see the Ghost Drawer Rule below.
- **Parchment Line** (`#ebe2db` / dark: `#34304c`): every border and rule in the system.
- **Amber Wash** (`#f1e1dd` / dark: `#45374b`): accent chip backgrounds only (the theme pill's amber text sits on Toasted Paper, not Amber Wash — Amber Wash is reserved for accent-tinted chips elsewhere).

`tokens.css` also declares `--green-soft`, `--red-soft`, and `--blue-soft` (light-mode paper-tinted washes paired with Moss Olive / Terracotta Red / Slate Blue). They are reserved and not yet applied anywhere in the implemented UI — do not treat their existence as license to invent a new "soft chip" pattern without checking back here first; document the pattern here when one is added.

### Named Rules
**The Ghost Drawer Rule.** Ghost Taupe (`--ink4`) is the one color in the system that does not clear WCAG AA against its background, and that is intentional — it exists solely to mimic Emacs' faded `:PROPERTIES:`/`:LOGBOOK:` drawer face. Never use it for text a reader needs; every other `--ink*`, `--accent`, and `--green` value was deliberately darkened (light mode) during design review specifically to clear AA (4.5:1), and must not be lightened back toward the Android app's brighter in-product values.

**The One Amber Rule.** Org Amber is the only color that means "act on this" (links, primary CTA, `TODO`). The other four accents are strictly org-syntax semantics, not available as general-purpose UI accents — don't reach for Moss Olive or Slate Blue to color a new UI element just because it's a pleasant hue.

## Typography

**Display/Structure Font:** JetBrains Mono (400/500/700)
**Body Font:** Source Serif 4 (400/600/700, optical sizing 8–60)
**Base Fallback:** Public Sans (400/500/600/700) — set on `body` as the base font-family; in practice almost every visible piece of text overrides to the serif/mono pair below, so treat Public Sans as a safety fallback and Starlight-chrome default, not an active voice in new design work.

**Character:** A strict two-voice split, not a blend. JetBrains Mono is the entire org-mode theme — every heading, every piece of metadata, every button label is typed in it, so it must never be substituted casually or swapped for a generic UI sans "for readability." Source Serif 4 is reserved for prose meant to be read at length; it is the site's one warm, human voice against the mono scaffold.

### Hierarchy
- **Display** (700, 52px / 36px below 700px, line-height 1.1, `letter-spacing: -.02em`, mono): the hero title (`Bloom`) only.
- **Headline** (700, 26px, mono): top-level section headings (`* Features`).
- **Title** (500, 21px, mono): feature row headlines (`** Capture`).
- **Body** (400, serif, line-height 1.55–1.6, `text-wrap: pretty`): hero prose at 22px (max-width 52ch), feature prose at 19px, CTA prose at 20px (max-width 60ch). Same voice, three sizes by context — never introduce a fourth without a reason.
- **Label** (400 unless noted, mono): org source/metadata lines at 15px, feature bullet lists at 13px (line-height 2), tag column/comments at 12–12.5px, filetag copy at 14.5px, code blocks at 12.5px (line-height 1.85). Button labels use this same face at 14px/500 — buttons are structurally a "label," not a "title." Two smaller one-off contexts also belong to this role: the hero/feature-row org-headline stars (`*`, `**`) at 18px, and the hero's uppercase "what you wrote"/"what Bloom shows" column labels at 11px.

### Named Rules
**The No-Substitution Rule.** If a piece of copy is org syntax, metadata, or UI chrome, it is JetBrains Mono — full stop. If it is prose meant to be read, it is Source Serif 4. A new component that blends the two within a single text run breaks the buffer conceit.

## Layout

Single-column band layout: the landing page is a stack of full-width sections (buffer bar, hero, filetags band, features, CTA band), each a direct child of the page, separated by `1px solid var(--line)` rules rather than whitespace alone — the page reads as one continuous buffer, not a set of floating cards.

Two responsive breakpoints, used consistently everywhere; do not introduce a third:
- **`max-width: 1000px`**: the hero collapses to one column (the source/rendered comparison pair moves below the text and sits side by side rather than stacked in a rail); feature-row bodies drop to a single prose column with screenshots moving into a horizontal scroller (`display: flex; overflow-x: auto`).
- **`max-width: 700px`**: section padding drops `56px → 24px`; hero title drops `52px → 36px`; feature-row body indent drops `40px → 16px`; the filetags band stacks (border-right rules become border-bottom); the buffer bar's nav collapses into a `☰` dropdown; hero cards return to a single column.

Section padding is `56px` horizontal everywhere, `64px` top on the hero specifically and `56px` top on other bands. Feature-row headlines take `22px 0` vertical padding; feature-row bodies take `0 0 40px 40px` (the 40px left indent is the org outline indent, and it is load-bearing — it's what makes the fold read as a sub-level of the headline above it).

## Elevation & Depth

Flat by default. Every panel, card, chip, and band is separated from its neighbors by a `1px solid var(--line)` hairline, never a shadow — this matches a plaintext buffer, which has no depth.

### Shadow Vocabulary
- **`--shadow`** (light: `0 1px 2px rgba(60,45,25,.05), 0 12px 32px -12px rgba(60,45,25,.16)`; dark: `0 1px 2px rgba(0,0,0,.4), 0 16px 40px -14px rgba(0,0,0,.6)`): reserved for the hero's "what Bloom shows" screenshot card only. It is the single deliberate exception to flatness — the rendered screenshot is meant to lift off the page like a physical photo next to the flat "what you wrote" source buffer beside it. The generic feature-row screenshot mount (`.shot-card`) does not use this token; it relies on its dark background + padding treatment instead.

### Named Rules
**The One Shadow Rule.** `var(--shadow)` has exactly one job: lifting the hero's rendered-screenshot card. Do not reach for it to add "polish" to a new card or panel — a new elevated surface should first ask whether it's actually a photographic screenshot moment, because if not, it should stay flat.

## Shapes

Corners are small and functional, never a "soft card" signature — they read as just enough rounding to keep a hairline-bordered box from looking like a hard-edged wireframe.

- **Chips/badges** (`4px`): the theme-toggle pill, filetag-style small elements.
- **Buttons/inputs/dropdowns** (`6px`): primary/secondary CTA buttons, the mobile nav dropdown panel.
- **Hero source card** (`8px`): `.hero__source-card`, the "what you wrote" fake org buffer — its own step between button/input radius and the screenshot-card family, since it's neither.
- **Generic screenshot cards** (`10px` outer / `5px` inner image): the feature-row `.shot-card` dark mount.
- **Hero comparison card** (`18px`): `.hero__shot-card` — notably larger than every other card radius in the system. `docs/DESIGN_SYSTEM.md` flags this as a known, temporary divergence from mid-replacement hero work, not an intentional second card language; treat `10px`/`5px` as the system's actual card radius and reconcile the hero card back to it rather than treating `18px` as a second valid option.

Borders are always the single `1px solid var(--line)` hairline; the system has no second border weight or color.

## Components

Every component reads as precise and typewriter-restrained: small mono labels, small radii, hairline borders, no gradients, no glows. Hover states are a quiet opacity dip or a border-color shift to Org Amber — never a scale, shadow, or color-swap transform. Nothing in this system is decorative; every visual choice maps to either an org-mode convention or a plain functional need.

### Buttons
- **Shape:** `6px` radius, `13px 22px` padding, JetBrains Mono 14px/500 label.
- **Primary:** Org Amber fill, Warm Paper text. Hover: `opacity: 0.88`, text color unchanged — no color swap.
- **Secondary:** transparent fill, `1px solid var(--line)` border, Ink Black text. Hover: border color shifts to Org Amber; text stays Ink Black.
- There is no tertiary/ghost variant; the system only ever needs one CTA pairing per section (e.g. "★ Star on GitHub" + "Read the docs →").

### Chips
- **Theme-toggle pill** (`.bloom-theme-pill`, shared verbatim between the landing buffer bar and the Starlight docs header): Toasted Paper background, `1px solid var(--line)` border, `4px` radius, `4px 10px` padding, Org Amber text for the `M-x` prefix, Ink Black for the current-theme label. Hover: border shifts to Org Amber. This exact component must never be restyled independently in one surface without updating the other — they are required to look identical.
- **Filetag cells** (the three-column `:plaintext:`/`:offline:`/`:yours:` band): not a chip shape at all — a full-width three-column band on an Aged Paper background, each cell separated by a `1px solid var(--line)` rule (border-bottom on mobile). The tag itself is Moss Olive mono 12px; body copy below it is 14.5px serif-adjacent sans at Warm Umber.

### Cards / Containers
- **Corner style:** see Shapes above — `10px`/`5px` outer/inner for the standard screenshot mount, `18px` for the (flagged, temporary) hero exception.
- **Background:** generic screenshot mount is a hardcoded dark `#12100e` regardless of theme (every capture is of the app's own dark theme); this is intentional, not a token to theme-swap.
- **Shadow:** see Elevation & Depth — flat except the hero comparison card.
- **Border:** the generic mount has no border (the dark background itself is the boundary); the hero card has a `1px solid #140e06` edge border, a deliberate divergence noted alongside its radius exception above.
- **Internal padding:** `8px` on the generic screenshot mount.

### Navigation
- **Buffer bar** (top strip, `Aged Paper` background, `1px solid var(--line)` bottom border, `14px 28px` padding): wordmark (`bloom.org` preceded by the 18px Bloom app icon — `bloom-icon-light.svg` / `bloom-icon-dark.svg`, swapped by `[data-theme]`) left, nav links + theme pill right. Nav links are Warm Umber, no visible active state (the site is a single scroll, not a multi-page nav). Below `700px`, the inline nav collapses behind a `☰` toggle into an absolutely-positioned dropdown panel (`6px` radius, `Aged Paper` background, same hairline border).
- **Starlight docs sidebar:** inherits Bloom's tokens via `starlight-overrides.css` remapping `--sl-color-*`/`--sl-font*` onto Bloom's own variables — it is not a bespoke component, it is Starlight's stock sidebar re-skinned to the same palette/type system.
- **Blog** (`/blog/`, [starlight-blog](https://github.com/HiDeoo/starlight-blog) 0.29.x): release-notes posts in `src/content/docs/blog/`, one per release. Reached from the buffer bar's `blog` link, a `Blog` docs-sidebar entry, and the plugin's header link, which our `ThemeSelect` override renders beside the theme pill and `starlight-overrides.css` sets in JetBrains Mono. The post list, tags and author pages are the plugin's stock layouts re-skinned through the same Starlight tokens; RSS is at `/blog/rss.xml`.

### Feature Row (signature component)
The five foldable feature sections are the site's signature interactive pattern and its clearest expression of "the website is an org file." Each row is a real `<button>` headline (`▾`/`▸` arrow, `**` stars in Moss Olive, mono title, right-aligned `:tags:` column) controlling an `aria-expanded`/`aria-controls` panel with **no fold/unfold transition** — it snaps open and closed instantly, matching real org-mode's own `TAB` behavior, not a smooth accordion animation. Plain `TAB` on a focused headline toggles that headline; `Shift+TAB` cycles every row's fold state globally (open → closed → open). All rows render open by default in the raw HTML; JavaScript is what makes them collapsible, so the page is fully readable with JavaScript disabled.

## Do's and Don'ts

### Do:
- **Do** use JetBrains Mono for every piece of org syntax, metadata, label, or button — never substitute a generic UI sans for these roles ("The No-Substitution Rule").
- **Do** keep Ghost Taupe (`--ink4`) decorative-only, reserved for faded drawer lines, and never use it for text a reader needs to read ("The Ghost Drawer Rule").
- **Do** build every divider, card edge, and container boundary as the single `1px solid var(--line)` hairline — the system has no second border weight.
- **Do** reuse the two existing breakpoints (`1000px`, `700px`) for new responsive work instead of introducing a third.
- **Do** use the `.tok-*` utility classes (`.tok-ink`, `.tok-green`, `.tok-accent-bold`, etc.) for org-syntax-colored inline text instead of ad hoc inline styles.
- **Do** keep new interactive disclosure patterns instant (no fold transition), matching the Feature Row's real org-mode `TAB` behavior; if motion is ever added to folding, keep it under 150ms.

### Don't:
- **Don't** lighten Org Amber, Moss Olive, Warm Umber, or Faded Umber back toward the Android app's brighter in-product values — the light-mode versions were deliberately darkened during design review specifically to clear WCAG AA (4.5:1).
- **Don't** reach for `var(--shadow)` to add polish to a new card or panel; it exists solely to lift the hero's rendered-screenshot card off the page ("The One Shadow Rule").
- **Don't** treat Moss Olive, Terracotta Red, Slate Blue, or Dusty Plum as general-purpose accent colors — each one is reserved org-syntax semantics (headline stars/tags, priority/deadline, scheduled timestamps, template placeholders), not a decorative palette to pick from ("The One Amber Rule").
- **Don't** add SVG illustrations or any logo/icon beyond the Bloom app icon (favicon, buffer-bar wordmark mark, and Starlight docs-header logo). The original handoff said "`✳` glyph only"; the app icon replaced it site-wide. Anything further is a separate design task.
- **Don't** introduce a component library or UI framework; every component here is intentionally hand-authored `.astro` + scoped CSS, to preserve 1:1 fidelity with the reference prototype.
- **Don't** treat the hero comparison card's `18px` radius / borderless-then-1px-edge-border treatment as a second valid card language — it's a flagged, temporary divergence pending reconciliation back to the standard `10px`/`5px` screenshot-card treatment.
