# ThunderNotes — Stitch Frontend UI (Final)

Static front-end mockups for **ThunderNotes**, a tablet-first note-taking app.
Every page is a single self-contained HTML file (plain HTML + Tailwind CSS CDN
+ Lucide / inline SVG). No build step, no frameworks — Stitch-compatible.

## How to use

Unzip and double-click any HTML file — it opens directly in a browser
(internet required for the Tailwind CDN and Google Fonts). Each file is fully
self-contained; there are no local file dependencies between pages.

## Structure

```
ThunderNotesUI-Final/
├── thunderHomePage.html … canvasUtilityPage.html   ← the 10 screens (open these)
├── Screenshots/        ← desktop preview PNG (1204 × 1080), one per page, same name
├── design/             ← original Stitch design-token spec, one per page, same name
└── Icons/              ← original SVG icon sources used by the canvas pages
```

Screenshots and design docs carry the **same base name** as their HTML file —
`thunderHomePage.html` ↔ `Screenshots/thunderHomePage.png` ↔
`design/thunderHomePage.md`.

## Pages

| HTML file | Screen | Notes |
|---|---|---|
| `thunderHomePage.html` | Library home | Dashboard: recent folders, recent files, floating create dock |
| `notesLibraryPage.html` | All Notes | Filter chips + `Import Note` (blue) / `Create Note` (red) actions |
| `foldersLibraryPage.html` | All Folders | Filter chips + `Create Folder` (emerald) action |
| `createNotePage.html` | Create-note modal | Quick-create sheet over a dimmed library |
| `coverSelectionPage.html` | Cover picker | Template browser with cover/paper previews |
| `createFolderPage.html` | Create-folder modal | Folder creation sheet with color swatches |
| `canvasLayoutPage.html` | Canvas (base) | Lecture sheet, pen tray, zoom + AI-snip controls |
| `canvasCustomizationPage.html` | Canvas + popups | Pen / palettes / shape / line-type popups (closable) |
| `canvasSettingsPage.html` | Canvas + settings | Floating settings popup + Equation-Snip OCR modal |
| `canvasUtilityPage.html` | Canvas + utilities | Pages sidebar, radial AI-snip fan menu, context menu |

`Icons/` holds the original SVG icon assets (flattened — each icon is one
`.html` + one `.png` preview pair). In the canvas pages the icons are already
inlined as SVG, so the folder is reference/source material only.

> The GitHub repo (github.com/langergitanu/StitchFrontendUI) keeps the
> original Stitch layout (`<PageName>/code.html`) so it can be re-imported
> into Stitch. This zip uses friendly file names for easy browsing.

## Shared conventions

- **Design tokens** live in each page's `tailwind.config` (`surface.*`,
  `accent.*` on library pages; `obsidian.*`, `thunder.*` on canvas pages).
  Prefer these over raw hex values.
- **Icons**: library pages use Lucide (`<i data-lucide="…">` rendered by
  `lucide.createIcons()` at the end of `<body>`); canvas pages use hand-drawn
  inline SVG.
- **Anatomy comments**: each file is annotated `[1] status bar … [4] gesture
  dock` so sections can be located quickly. Keep comments in sync with the
  markup.
- **Interactive bits** (pure JS, no frameworks):
  - Canvas pages: tapping a pen in the tray toggles its active (lifted) state;
    customization popups close via their `✕` buttons.
  - Theme / finger-stylus / bookmark toggles are CSS-only (`peer` +
  `has-[:checked]`).

## Responsive behavior

The layout is a fluid flex column capped at a 1600 px chassis (library pages)
or full-bleed (canvas pages). Verified free of horizontal overflow from 480 px
up to 1600 px. The library card grids step 1 → 2 → 3 → 4 columns at Tailwind's
`sm` (640 px) / `lg` (1024 px) / `2xl` (1536 px) breakpoints — the 3-column
layout is held all the way through 1300 px so nothing shrinks at 1280 px, and
the 4-column layout engages from 1536 px. Desktop reference composition:
1204 × 1080.

Below 768 px the canvas pages' dense header rows (notebook tab strip and the
workflow toolbar) become internally scrollable toolbars instead of stretching
the page; the customization popup row wraps onto extra lines below 1280 px
instead of clipping. Both behaviours are driven by media-query-scoped CSS and
never evaluate at desktop widths.

## Final polish (round 3)

Applied on top of the user's own fixes (search-bar repair, MathML equation
chips, lifted lasso icon, colour/typography pass, Tailwind class sorting):

- Misleading comments corrected; section comments added for every header
  sub-row, tray sub-section, MathML chip and the lasso tool.
- Google Fonts consolidated into one request per canvas page (same families
  and weights).
- Responsive hardening as described above — desktop rendering verified
  pixel-identical at 1204 × 1080.

## Grid breakpoint fix (round 4)

On `thunderHomePage.html`, `createNotePage.html`, `createFolderPage.html` and
`coverSelectionPage.html` the 4-column card grids engaged at Tailwind's `xl`
breakpoint (1280 px), visibly squeezing the columns at exactly 1280 px. Per
the fix validated by the user, `xl:grid-cols-4 xl:gap-4` was changed to
`2xl:grid-cols-4 2xl:gap-4` (2 occurrences per page), keeping 3 columns
through 1300 px and beyond, with 4 columns from 1536 px. Rendering at
1204 × 1080 verified pixel-identical after the change; `notesLibraryPage.html`
and `foldersLibraryPage.html` were already capped at `lg:grid-cols-3` and are
untouched.
