# Muraka dashboard (Vue 3)

The researcher and administrator interface: reef map, review queue, sighting
records, operations.

## Running

```bash
npm install
npm run dev          # http://localhost:5180, proxies /v1 to the API
```

The dev server proxies to `http://localhost:8090`, so start the backend first.
In a container build, `VITE_API_BASE_URL` is baked in at build time instead.

```bash
npm run typecheck
npm run build
```

## Layout

```
src/
  assets/theme.css      design tokens and primitives
  components/           PatchLattice (the signature element), ConditionChip
  lib/api.ts            typed client; handles token refresh and retry
  stores/auth.ts        session state
  router/               routes and role guards
  views/                one file per screen
```

## Design direction

An instrument, not a brochure. Colour comes from the phenomenon: living coral
tissue reads teal, bleached coral reads bone-white, because that is literally
what bleaching looks like. Rust is reserved for destructive actions so it never
competes with the data. Every measured quantity — coordinate, depth, severity,
count — is set in monospace like an instrument readout.

There are no webfonts anywhere: the project forbids external service
dependencies, so the type personality comes from a deliberate mono/sans pairing
of system faces.

**The signature element is the patch lattice** (`components/PatchLattice.vue`).
The model does not judge a photo as a whole; it tiles it into a grid and judges
each cell. Showing that lattice is showing the model's actual reasoning. It
appears twice: as an overlay on the photograph, and as a thumbnail-sized glyph in
list rows, where it works like a sparkline — you read a sighting's bleaching
pattern without opening it.

Model output and expert verdicts must never look alike (NFR13). Verified chips
carry a filled marker and a solid border; model chips are dashed and labelled
"model". The distinction survives greyscale.

**The photo frame is one fixed size**, on the detail page and in the queue alike —
capped by the column, by the viewport height, and by 44rem. It deliberately does
*not* scale to the source resolution. An earlier version did, on the reasonable
principle that upscaling a 224 px dataset crop adds no detail; the effect was that
most seeded photographs rendered too small to judge, and two shots of the same reef
looked like different sizes of thing.

**The model's reading sits beside the photograph, not under it.** On the detail page
the plate is a two-track grid — frame, then an assessment panel carrying extent,
confidence, the patch tally, model version, grid, inference time and resolution. The
tracks are explicit rather than `flex-wrap`, because wrapping dropped the panel
below a 700 px frame at 1366 px and put every number off screen; with explicit
tracks the panel keeps its 17rem floor and the frame gives up width instead.

What the fixed frame costs is honesty about resolution, so it is paid back in words:
the panel prints the source dimensions, and under 600 px on the shorter side adds a
sentence naming the resolution and saying to reject the photograph if it cannot be
judged (`lib/photos.ts`). The queue, whose caption is a single line, uses the same
rule as a hover label instead.

## Map

MapLibre GL over geography that ships with the bundle: `public/basemap/maldives.json`,
67 KB of Natural Earth 10m clipped to the Maldives by `scripts/build_basemap.py`.
Natural Earth is public domain, so the derived file is simply committed.

It carries four kinds of geometry, drawn as vector layers — the 1000 m slope, the
shallow atoll platforms, the reef rims and the islands — plus the twenty
administrative atolls as label anchors. Both isobaths are *inverted*: the source
polygons cover everything deeper than their contour, which when filled paints the
whole clip box and puts a visible rectangle around the country. Their holes are
the shallow ground, and that is what gets drawn.

No tile server, no glyph server, no network. Place names are DOM markers rather
than a symbol layer, which is what avoids the glyph server; there are twenty-one
of them, so the cost is nil. A raster basemap is still supported through
`VITE_MAP_TILE_URL` for anyone who wants imagery — when one is configured the
local fills stand down and the reef rims stay on as annotation over it.

Every layer and paint expression lives in `lib/mapStyle.ts` rather than in the
view, because a malformed paint expression is not a type error — it is a layer
that silently never appears. `lib/mapStyle.test.ts` runs the style spec's own
validator over the assembled style and evaluates the radius expressions at the
zooms their design decision was made about.

Clustering happens server-side: below zoom 11 the API returns grid-aggregated
cells on a grid one forty-eighth of a tile wide, so a national view of 10,000
sightings is on the order of 150 features. An earlier eighth-of-a-tile grid was
smaller still and useless — the Maldives is a 9-degree ribbon barely 1 degree
wide, so the whole country collapsed into about seven enormous dots. Marker
radius is interpolated on zoom as well as cluster size for the same reason.
