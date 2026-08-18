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

## Map

MapLibre GL with a keyless tile style — no API-key services are permitted. If
tiles fail to load, the sighting layers still render over the chart-dark ground,
which also makes the demo safe without internet.

Clustering happens server-side: below zoom 11 the API returns grid-aggregated
cells, so a national view of 10,000 sightings is roughly 30 features and 2.7 KB.
