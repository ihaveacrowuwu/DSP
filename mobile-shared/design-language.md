# Design language - how the apps relate to the dashboard

Read [`design-tokens.json`](design-tokens.json) first; this document explains how to
apply it on each platform.

## The rule

**Platform guidelines govern chrome. The family resemblance lives in the data.**

Android follows Material 3. iOS follows Apple's Human Interface Guidelines with the
iOS 26 Liquid Glass design language. Where a platform guideline and the dashboard's
appearance disagree, **the platform wins** - that is a project decision, recorded
here so nobody has to re-litigate it mid-build.

This is not a compromise on identity. Three clients read as one product when a
bleached reef is bone-white in all three, a model label is dashed in all three, and
every measurement is monospaced in all three. They do not need matching corner
radii, and forcing web chrome onto a phone gets you an app that looks like a website
and satisfies neither reviewer.

### What crosses platforms

| Carried everywhere | Why |
|---|---|
| The condition scale - living teal to bone-white | It is the phenomenon. A red/green scale would be the wrong metaphor and inaccessible |
| The severity ramp, 0→1 | Same numbers, same colours as the dashboard legend and map markers |
| Provenance by shape - dashed/hollow for model, solid/filled for expert, plus the word | NFR13. Must survive greyscale and colour blindness |
| The patch lattice, and its two opacity formulas | It is the model's reasoning made visible; the signature element |
| Monospaced tabular figures for every measurement | Cheap on both platforms, and the strongest resemblance of all |
| One accent - reef teal | M3 seed / iOS tint |

### What does not

Surfaces, elevation, corner radii, blur, motion curves, navigation patterns,
component shapes, iconography. All of these come from the platform. The dashboard's
glass chrome, 24px rail radius and `linear()` springs have **no authority** on
either app - they are recorded under `webOnly` in the token file for comparison
only.

**The clearest worked example is where the primary action sits.** Android puts "new
sighting" in a bottom-right FAB; iOS puts it as a `+` in the navigation bar's
trailing slot. Same action, same screen, deliberately different chrome, because that
is what each platform's users already reach for. If a change makes the two apps look
*more* alike in a place like this, it is probably wrong.

## Android - Material 3

Follow [m3.material.io](https://m3.material.io): its components, shape scale,
elevation model, motion tokens and accessibility guidance.

**Colour.** Prefer dynamic colour from the wallpaper on Android 12+, with a seeded
scheme from the accent below that. Dynamic colour applies to **chrome only** - app
bars, navigation, buttons, surfaces, cards.

> Dynamic colour must never touch `condition`, `severityRamp` or `signal`. Those are
> data. A user's wallpaper deciding what "bleached" looks like would corrupt the
> reading, and the same screen would be a different colour on a different phone. Keep them as fixed data colours with light/dark variants and
> pull them from a separate palette object, not from `MaterialTheme.colorScheme`.

**Surfaces.** Use the M3 surface roles (`surface`, `surfaceContainerLow` through
`surfaceContainerHighest`, `onSurface`, `onSurfaceVariant`, `outline`,
`outlineVariant`). Tonal elevation, not the web's translucent panels.

**Shape.** The M3 shape scale - 4 / 8 / 12 / 16 / 28dp. Ignore the web radii.

**Components.** Reach for the M3 component first, always: `TopAppBar`,
`NavigationBar`, `FloatingActionButton` for the capture action, `Card`, `ListItem`,
`FilterChip` for the provenance chip, `Snackbar` for sync feedback,
`LinearProgressIndicator` for upload progress.

**Motion.** M3 motion tokens and Compose's `MotionScheme` springs.

**Type.** The M3 type ramp (Display / Headline / Title / Body / Label), scaling with
the user's font-size setting. Override only the family for readouts, never the size.

## iOS - HIG and Liquid Glass

Follow Apple's [Human Interface
Guidelines](https://developer.apple.com/design/human-interface-guidelines/). UIKit,
not SwiftUI - a project requirement.

**Colour.** Set the accent as the app's tint and let system controls inherit it.
Use semantic colours (`label`, `secondaryLabel`, `systemBackground`,
`secondarySystemBackground`, `separator`) so dark mode, increased contrast and
increased legibility all come free. The same carve-out applies: the condition and
severity colours are fixed data colours, defined with light/dark variants in an
asset catalogue, never derived from the system palette.

**Liquid Glass** is a *surface treatment for chrome* - bars, toolbars, floating
controls, sheets. Two rules that matter more than the API details:

1. **Glass goes on chrome, never on content.** Photographs, the patch lattice and
   the sighting list are content. A reef photograph behind a glass panel is a reef
   photograph you cannot assess.
2. **It must degrade.** Liquid Glass is a visual layer, not a structural
   dependency; the app must fall back to standard UIKit materials where the effect
   is unavailable, and the layout must not depend on it.

The expected UIKit surface for this is `UIGlassEffect` inside a
`UIVisualEffectView`, `UIGlassContainerEffect` to group nearby glass elements so
they merge, glass button configurations, and concentric corner configuration rather
than hardcoded radii - plus SF Symbols for iconography. **Verify these names against
the installed SDK in Xcode before building.** This document was written on a
Windows machine with no SDK available; for a design language this new, treat
Xcode's documentation and the current HIG as authoritative over anything written
here, and correct this file if it is wrong.

**Type.** Dynamic Type text styles, scaling with the user's setting. Use
`UIFont.monospacedDigitSystemFont` for figures inside otherwise proportional text,
and SF Mono where a whole readout is monospaced.

## Where the two apps must agree with each other

Different chrome is fine and expected. These must match, because they are the same
information:

- The condition and severity colours, and the ramp stops
- Which shape means "model" and which means "expert"
- The patch lattice geometry (centre square) and both opacity formulas
- The status vocabulary shown to the contributor - the same sighting must not read
  "Analysing" on one platform and "Processing" on the other
- Sync semantics: what "pending", "uploading", "synced" and "failed" mean, and what
  the counts count

## Accessibility, on both platforms

Not optional, and several of these are requirements rather than polish:

- **Dynamic type / font scaling** must work. A fixed 14pt body is a bug
- **Never colour alone.** Provenance uses shape and a word; condition carries a
  label and a percentage (NFR13)
- **Contrast** to the platform minimum on chrome; the condition colours are fills
  and graphics, so use on-surface colours for text over them
- **Respect reduce-motion.** The lattice sweep and any queue-item animation are
  decoration; every state they express must be readable from a still frame
- **Screen reader labels** on the lattice ("14 of 25 patches classified bleached"),
  on the provenance chip, and on every icon-only control
- **Both appearances** correct, via platform theming rather than manual branching

## Deciding a case this document does not cover

In order:

1. Does the platform guideline say? Follow it.
2. Is it *data* - a condition, a severity, a provenance distinction? Then match
   `design-tokens.json` exactly and ignore platform aesthetics.
3. Otherwise pick the platform-idiomatic option and note it in the app's README.

If a choice contradicts the rule at the top of this file, it needs a decision-log
entry in the project's decision log,
not a quiet exception.
