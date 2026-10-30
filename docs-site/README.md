# Bloom launch site

The marketing landing page and documentation site for **Bloom**, a native Android app for notes and tasks on top of plain `.org` files.

## Purpose

Bloom needs a public-facing site before its first public release: a landing page that makes the pitch ("your vault is a folder of `.org` files") and a full documentation section covering every implemented feature, so early adopters and contributors have somewhere to land.

## The problem it solves

Bloom's actual product surface — the Android app — has no web presence yet. This site gives it one: a single scrolling landing page styled as an org-mode buffer ("The Buffer" design direction), and a feature-complete docs section built from the team's existing `.mdx` source content, so documentation doesn't have to be re-authored for the web.

## Features

- **Landing page** — hero, "what you wrote / what Bloom shows" source-vs-rendered comparison, a filetags band (`:plaintext:` `:offline:` `:yours:`), five foldable feature sections (Capture, Agenda, Search, Sync & conflicts, Rendered-not-dumped), and a closing CTA band. Built to match `design/reference/Grove Landing 1a.dc.html` pixel-for-pixel.
- **Docs section** (`/features/`) — every feature doc lives in `src/content/docs/features/`, built on [Starlight](https://starlight.astro.build) with a full visual reskin (fonts, color tokens, an org `:PROPERTIES:` drawer rendering each page's frontmatter) so it reads as the same product as the landing page, not a generic docs template.
- **Blog** (`/blog/`) — one release-notes post per release in `src/content/docs/blog/`, via the `starlight-blog` plugin (pinned to 0.29.x for Starlight 0.41). Give each post a noon-UTC `date` (e.g. `2026-10-19T12:00:00Z`) so it doesn't render as the previous day. RSS at `/blog/rss.xml`. An AI-authorship disclaimer is added to the top of every post automatically by `src/components/starlight-overrides/MarkdownContent.astro`; don't add one to the post itself.
- **Shared light/dark theme** — one toggle, one `localStorage` key, used by both the landing page's `M-x bloom-<label>-theme` pill and its equivalent in the docs header. No flash of the wrong theme on load.
- **Keyboard-accessible folding** — the landing page's five feature sections are real `<button>`s with `aria-expanded`/`aria-controls`; `TAB` toggles a focused headline, `Shift+TAB` cycles global fold state, mirroring org-mode's own `TAB` behavior.
- **Progressive enhancement** — all landing-page sections render open in the HTML; JavaScript only makes them collapsible. The page is fully readable with JavaScript disabled.

## Setup

Requires Node.js ≥ 22.12 and npm.

```sh
npm install
npm run dev       # http://localhost:4321
npm run build     # outputs to ./dist/
npm run preview   # preview the production build locally
npm run astro check   # type-check .astro files
```

### Project structure

```text
/
├── design/                        # design handoff: reference HTML, tokens, copy (source of truth for the landing page)
├── src/
│   ├── components/landing/        # landing page bands (BufferBar, Hero, FiletagsBand, FeatureRow, CtaBand, ...)
│   ├── components/starlight-overrides/  # PageTitle / ThemeProvider / ThemeSelect overrides for the docs reskin
│   ├── content/docs/features/     # docs content collection — the single source for every feature doc
│   ├── content/docs/blog/         # release-notes blog posts (starlight-blog), one per release
│   ├── layouts/LandingLayout.astro
│   ├── pages/index.astro          # the landing page itself, outside the Starlight collection
│   ├── scripts/                   # theme boot/toggle scripts shared by both sections
│   └── styles/                    # tokens.css (design tokens), starlight-overrides.css, global.css
├── astro.config.mjs               # Starlight config, hand-written sidebar, component overrides
└── PROGRESS.md                    # milestone tracking (gitignored, not part of the shipped project)
```

See `docs/ARCHITECTURE.md` for the reasoning behind the routing split, theming approach, and content-sourcing decisions.

## Deployment

This site lives inside the main Bloom repo (`docs-site/`) but deploys separately, as a Cloudflare Worker with static assets (see `wrangler.jsonc`):

```sh
npm run deploy    # astro build && wrangler deploy
```

`astro build` (via the `@astrojs/cloudflare` adapter) produces `dist/client/` (static assets: HTML, CSS, JS, images) and `dist/server/` (worker code, empty for this fully static site). Wrangler auto-detects the adapter-generated `dist/client/wrangler.json` and deploys from there — the `wrangler.jsonc` at the project root is a starting-point config (`wrangler dev`/`wrangler deploy` will report "Using redirected Wrangler configuration" when this kicks in).

**Image handling:** the `cloudflare()` adapter defaults to transforming `<Image>`/`<Picture>` components at *runtime* via Cloudflare's Images product (each image becomes a `/_image?href=...` request against an `IMAGES` binding). That product has to be separately provisioned on the Cloudflare account, and isn't emulated by `wrangler dev` locally, so unconfigured this silently 404s on every optimized image — the browser then falls back to rendering the `alt` text in place of the image. Since this site has a fixed, known set of screenshots, `astro.config.mjs` sets `adapter: cloudflare({ imageService: 'compile' })`, which switches image optimization back to build time (the normal static behavior) and removes the runtime dependency entirely.

`.github/workflows/deploy-docs-site.yml` deploys automatically on push to `main`, scoped to `paths: docs-site/**` — it builds and runs `wrangler deploy` in CI, requiring the `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` repo secrets. It can also be run manually via `workflow_dispatch`. The Android app's `.github/workflows/build.yml` ignores `docs-site/**` the other way, so a push never triggers both workflows. Run `npm run deploy` locally for one-off/manual deploys.

## Known gaps before shipping

- No real GitHub repository or production domain yet — placeholders are used in a few places (see `PROGRESS.md`).
- Not visually verified in a browser in this environment (no Chrome connection available); verified via `astro build`, `astro check`, and structural checks on the rendered HTML instead. See `PROGRESS.md` for details.
