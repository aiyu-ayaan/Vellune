# 12 — Android design system

Material 3 Expressive on Jetpack Compose. Same visual language as the author's Findex app, so both feel like one family. Code lives in `apps/android/app/src/main/java/com/aiyu/vellune/ui/theme/` and `ui/components/`.

Inputs: the `material-3` skill (tokens, Expressive components) and the `ui-ux-pro-max` design-system search ("notes productivity pen canvas calm professional"), which recommended a flat, tonal style with a teal primary and an orange accent.

## Tokens

| Area | Rule |
|---|---|
| Colour | Dynamic colour on Android 12+ by default (switch in Settings). Fallback scheme from seed `#0D9488` (teal), tertiary from `#EA580C` (orange). Tonal container ladder, never alpha-tinted surfaces. |
| Type | System Roboto Flex, MD3 scale; emphasized roles for hero text and titles. Numbers (zoom, sizes) use tabular figures. |
| Shape | `MaterialShapes` containers for icons (`Cookie9Sided` brand mark), grouped list rows (20dp outer, 4dp inner corners), `extraLargeIncreased` hero card, full-shape pills on the canvas. |
| Motion | `MaterialExpressiveTheme(motionScheme = expressive())`; springs for state, staggered fade+rise for list entry, shape morph on press. |
| Spacing | 8dp grid with 4dp half step (`Dimens`). Content max width 840dp. |

## Screens

| Screen | Components |
|---|---|
| Shell | Push drawer on compact (screen slides aside as a rounded card), navigation rail at ≥600dp, Navigation 3 back stack with predictive back. Roadmap items in the drawer carry a "Soon" tag. |
| Home | `LargeFlexibleTopAppBar` with morphing menu icon, pen-lab hero card (`primaryContainer`, decorative `Cookie12Sided` blob, single CTA), adaptive grid of canvas cards with live thumbnails, long-press bottom sheet (open, rename, delete), extended FAB. |
| Canvas | Full-bleed dot-grid paper. Top: two floating pills (back + title; undo, redo, zoom %, overflow). Bottom: `HorizontalFloatingToolbar` with toggle buttons (pen, marker, highlighter, eraser, hand) and a colour dot that opens the brush panel (swatches + size slider). Optional input HUD. |
| Settings | `MediumFlexibleTopAppBar`, grouped rows, connected `ToggleButton` groups for input mode and theme, sliders for palm size and lockout. |
| Input traces | Record switch, copyable `adb pull` command, list of trace files. |

## Canvas colour rule

Swatch "Ink" (`null` in `BrushStyle`) resolves to the theme's `onSurface` when a stroke starts, so the default pen suits light and dark paper. Other swatches are fixed colours; highlighters always draw at 40% alpha.

## Accessibility

- Touch targets ≥48dp; every icon-only control has a content description.
- Tool buttons expose checked state; swatches expose selected state and a colour name.
- Every gesture has a button alternative: undo/redo buttons, zoom reset button, hand tool for one-finger pan in any mode.
