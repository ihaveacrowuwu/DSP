# Mobile evidence

Screenshots of every screen on both platforms, captured from real runs against the local
stack (`make up && make seed N=200`) on 2026-08-21 — not staged, and not mocked.

The iOS set comes out of `SignInFlowUITests`, which attaches a screenshot at each step, so
re-running `make test-ios` regenerates them. The Android set was captured with
`adb shell screencap` against the same seeded data.

| Screen | Android | iOS |
|---|---|---|
| Sign in | `android-signin.png` | `ios-sign-in.png` |
| My sightings | `android-sightings.png` | `ios-my-sightings.png` |
| Sync queue | `android-sync.png` | `ios-sync.png` |
| Profile | `android-profile.png` | `ios-profile.png` |
| Sighting detail | `android-detail.png` | `ios-sighting-detail.png` |
| Filtered history | `android-filtered.png` | `ios-filtered.png` |
| Filter control | (in `android-filtered.png`) | `ios-filter-menu.png` |

`dark/` holds the same screens in dark mode (NFR14). The iOS set comes from
`make dark-shots-ios`, which sets the simulator's appearance, runs
`testEveryScreenInDarkMode`, and sets it back.

## What to look at

**The two detail screenshots are the same sighting.** Both read 6% bleached, 61%
confidence, a 5×5 grid, 431 ms, model `seed-0.0.0` — and both draw the same two bone-white
cells in the same two positions of the patch lattice. That is the design language working:
the chrome is unmistakably Material 3 on one and Liquid Glass on the other, while the data
is identical.

**NFR13, three ways over.** The provenance chip on both is a *dashed* outline with a
*hollow* marker and the word *model*. Print either screenshot in greyscale and the
distinction survives, which is the requirement.

**No screen says "Synced".** The statuses shown are the server's — "Awaiting expert review",
"Verified by an expert" — with "checked just now" beside them recording how old the app's
knowledge is. That is D21 visible in the interface rather than only in the code.

**Dynamic colour.** The Android screenshots take their chrome from the emulator's wallpaper,
which is why they are lilac rather than teal. The condition swatches and the lattice are the
same teal-to-bone in both apps regardless, because those come from a fixed palette that
dynamic colour cannot reach.

**Search and filtering give the same answer.** Filter both apps to bleached sightings and both
independently report **"6 of 50"** — same seeded account, same criteria, two implementations
written from the same reasoning. The controls are deliberately different: Material filter chips
on Android, a native `UIMenu` on iOS. What matches is the filter and its wording, not the
widget.

**Dark mode is where the data palette earns its separation.** Compare the two sighting-detail
screenshots in `dark/` against the light ones. The chrome inverts, as it should. The bleached
lattice cells change too — from parched sand to bone-white — and that is also correct: it is
what `design-tokens.json` specifies, because bone-white is invisible on a light surface. What
does *not* change is the meaning, or which cells are marked, or the dashed "model" chip.
