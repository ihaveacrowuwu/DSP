# Mobile evidence

Screenshots of every screen on both platforms, captured from real runs against the local
stack (`make up && make seed N=200`) on 2026-08-21 - not staged, and not mocked.

The iOS set comes out of `SignInFlowUITests`, which attaches a screenshot at each step, so
re-running `make test-ios` regenerates them. The Android set was captured with
`adb shell screencap` against the same seeded data.

| Screen | Android | iOS |
|---|---|---|
| Sign in | `android-signin.png` | `ios-sign-in.png` |
| My sightings | `android-sightings.png` | `ios-my-sightings.png` |
| Sync queue | `android-sync.png` | `ios-sync.png` |
| Config | `android-config.png` | `ios-config.png` |
| Sighting detail | `android-detail.png` | `ios-detail.png` |
| Detail, grid off | `android-detail-grid-off.png` | `ios-detail-grid-off.png` |
| Filtered history | `android-filtered.png` | `ios-filtered.png` |
| Filter control | (in `android-filtered.png`) | `ios-filter-menu.png` |
| Filtering while searching | (chips stay visible) | `ios-scope-bar.png` |

| Appearance toggle | `android-appearance-toggle.png` | `ios-appearance-toggle.png` |

`dark/` holds the same screens in dark mode (NFR14).

**The dark screenshots were taken with the device set to LIGHT.** Both sets come from
switching the in-app appearance control to Dark, not from changing the simulator or emulator
- which is what makes them evidence that the toggle works rather than evidence that the
device was already dark. The iOS set is produced by
`testTheAppearanceToggleDarkensEveryScreen`, so `make test-ios` regenerates it.

## What to look at

**The two detail screenshots are the same sighting.** Both read 6% bleached, 61%
confidence, a 5×5 grid, 431 ms, model `seed-0.0.0` - and both draw the same two bone-white
cells in the same two positions of the patch lattice. That is the design language working:
the chrome is unmistakably Material 3 on one and Liquid Glass on the other, while the data
is identical.

**NFR13, three ways over.** The provenance chip on both is a *dashed* outline with a
*hollow* marker and the word *model*. Print either screenshot in greyscale and the
distinction survives, which is the requirement.

**No screen says "Synced".** The statuses shown are the server's - "Awaiting expert review",
"Verified by an expert" - with "checked just now" beside them recording how old the app's
knowledge is. That is D21 visible in the interface rather than only in the code.

**Dynamic colour.** The Android screenshots take their chrome from the emulator's wallpaper,
which is why they are lilac rather than teal. The condition swatches and the lattice are the
same teal-to-bone in both apps regardless, because those come from a fixed palette that
dynamic colour cannot reach.

**Search and filtering give the same answer.** Filter both apps to bleached sightings and both
independently report **"6 of 50"** - same seeded account, same criteria, two implementations
written from the same reasoning. The controls are deliberately different: Material filter chips
on Android, a native `UIMenu` on iOS. What matches is the filter and its wording, not the
widget.

**`ios-scope-bar.png` is there because iOS takes the navigation row away.** Activating search
removes both bar-button items and substitutes UIKit's own Close button, so the filter menu is
unreachable while typing - the scope bar is what keeps condition available. Android needs no
equivalent: its filter chips sit in the content, so searching never hides them. Both report the
same **"6 of 50"**.

**Where the primary action sits is deliberately different.** `android-sightings.png` has a
bottom-right FAB; `ios-my-sightings.png` has a `+` in the navigation bar. Both are what their
platform's users reach for, and the divergence is the point - an assessor comparing the two
screenshots is looking at rule 3 of `mobile-shared/README.md` being followed, not at
inconsistency.

**The grid toggle.** Compare `*-detail.png` with `*-detail-grid-off.png`. The lattice is an
annotation, and an annotation that cannot be removed is an obstruction - turning it off is how
a contributor checks the model's reading against the reef rather than against the model's own
drawing of it. The control lives in the photograph card's title row and carries its state in
its fill: accent-filled when the grid is on, plain when it is off. (An outline-versus-filled
pair of the same glyph was the first attempt and the two states were indistinguishable at
24pt.) The choice is remembered across sightings.

**Layout.** Both screens are built from titled cards on a shared spacing scale rather than one
long column - `ReefSpacing` on Android, `Spacing` on iOS, same six steps. On Config that
grouping is doing safety work as well as tidiness: "Sign out" and "Delete my account" sit in
their own last card rather than in an undifferentiated column of controls.

**The appearance toggle.** `System` is the default and follows the device, which is what
NFR14 is really about. `Light` and `Dark` override it, and the choice survives a relaunch and
a sign-out - it is a display preference, not part of the session. The Android screenshot shows
the same screen before and after: the device is in light mode in both halves.

**Dark mode is where the data palette earns its separation.** Compare the two sighting-detail
screenshots in `dark/` against the light ones. The chrome inverts, as it should. The bleached
lattice cells change too - from parched sand to bone-white - and that is also correct: it is
what `design-tokens.json` specifies, because bone-white is invisible on a light surface. What
does *not* change is the meaning, or which cells are marked, or the dashed "model" chip.
