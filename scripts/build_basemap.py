#!/usr/bin/env python3
"""Build the dashboard's offline Maldives basemap from Natural Earth.

Why this exists
---------------
The reef map used to render sighting markers over an empty background: without a
tile server there was no geography, so the map was a scatter of dots with nothing
to locate them against. The project forbids depending on any third-party service
(no keys, and nothing that has to be reachable during a demo demo), so a hosted
basemap - even a keyless one - is not something we want to rely on.

Instead the geography ships with the bundle. The Maldives is small enough that
its whole national outline fits in well under 100 KB of GeoJSON, which MapLibre
draws as vector layers with no tiles, no glyph server and no network at all.

Source
------
Natural Earth 10m, via the natural-earth-vector GeoJSON mirror. Natural Earth is
public domain (https://www.naturalearthdata.com/about/terms-of-use/), so the
derived file can simply be committed.

Four source layers are used, each answering a different question:

  ne_10m_bathymetry_K_200  the 200 m isobath. Its *holes* are the shallow
                           platforms - which in the Maldives means the atolls
                           themselves. This is the layer that makes the map
                           recognisable.
  ne_10m_bathymetry_J_1000 the 1000 m isobath, inverted the same way, giving the
                           slope that skirts the atoll chain. Three tones of
                           water — abyss, slope, platform — is what makes the
                           ocean read as depth rather than one flat colour.

                           Both isobaths are inverted rather than filled as
                           given. Filling them would paint the whole clip box,
                           and the box has a straight edge that shows on screen
                           as a rectangle drawn around the country.
  ne_10m_reefs             coral reef rims, as lines.
  ne_10m_land              the islands. ~175 specks: invisible at national zoom
                           and essential at dive-site zoom.

Everything is clipped to a box around the Maldivian EEZ, rounded to 4 decimal
places (~11 m, far finer than a 10m-scale dataset actually resolves) and merged
into ONE FeatureCollection tagged with a `kind` property. One file means one
fetch that either succeeds or fails as a whole: the map can never come up with
reefs but no atolls.

Usage
-----
    python scripts/build_basemap.py                 # download, clip, write
    python scripts/build_basemap.py --cache ~/ne    # reuse downloaded sources

Output: web/public/basemap/maldives.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import urllib.request
from pathlib import Path

MIRROR = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson"

# The Maldivian EEZ, roughly. Generous enough that panning to the edge of the
# archipelago never runs off the drawn geography.
WEST, SOUTH, EAST, NORTH = 71.0, -2.0, 75.0, 8.0

PRECISION = 4

# (source layer, kind, how its geometry is handled)
LAYERS = [
    ("ne_10m_bathymetry_J_1000", "slope", "holes"),
    ("ne_10m_bathymetry_K_200", "shelf", "holes"),
    ("ne_10m_reefs", "reef", "line"),
    ("ne_10m_land", "island", "fill"),
]

# Administrative atolls, north to south, as label anchors only.
#
# Natural Earth carries no Maldivian atoll names, so these come from the one
# table in the repository that already had them: `atolls` in
# backend/cmd/seed/main.go, copied verbatim including the coordinates. Keeping
# the two identical matters — the seeder scatters synthetic sightings around
# these centroids, so any drift here would put a label next to the cluster it is
# supposed to name rather than over it. If that table changes, change this one.
#
# They are approximate centroids, not survey data, and nothing in the pipeline
# computes anything from them.
ATOLLS = [
    ("HA", "Haa Alifu", 72.9000, 6.9500),
    ("HDh", "Haa Dhaalu", 73.1000, 6.7500),
    ("Sh", "Shaviyani", 73.1500, 6.3500),
    ("N", "Noonu", 73.3000, 5.8500),
    ("R", "Raa", 72.9500, 5.6000),
    ("B", "Baa", 73.0500, 5.2000),
    ("Lh", "Lhaviyani", 73.5000, 5.4000),
    ("K", "Kaafu", 73.5000, 4.2000),
    ("AA", "Alifu Alifu", 72.8500, 4.0500),
    ("ADh", "Alifu Dhaalu", 72.8000, 3.7500),
    ("V", "Vaavu", 73.5000, 3.5500),
    ("F", "Faafu", 72.9500, 3.2000),
    ("M", "Meemu", 73.5500, 2.9500),
    ("Dh", "Dhaalu", 72.9500, 2.8500),
    ("Th", "Thaa", 73.1500, 2.3500),
    ("L", "Laamu", 73.4500, 1.9500),
    ("GA", "Gaafu Alifu", 73.3000, 0.6000),
    ("GDh", "Gaafu Dhaalu", 73.1000, 0.2000),
    ("Gn", "Gnaviyani", 73.4200, -0.3000),
    ("S", "Seenu", 73.1000, -0.6000),
]

# Male', the one populated place Natural Earth records inside the box. Kept as a
# separate kind so the capital can be drawn differently from an atoll label.
CAPITAL = ("Malé", 73.5089, 4.1755)


def fetch(layer: str, cache: Path) -> dict:
    path = cache / f"{layer}.geojson"
    if not path.exists() or path.stat().st_size < 1024:
        url = f"{MIRROR}/{layer}.geojson"
        print(f"  downloading {layer} ...", file=sys.stderr, flush=True)
        with urllib.request.urlopen(url, timeout=180) as response:
            path.write_bytes(response.read())
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def clip_ring(ring: list) -> list:
    """Sutherland-Hodgman clip of one ring against the bounding box.

    Rings are clipped independently. That is safe here only because every
    exterior ring we care about either contains the whole box (the bathymetry
    fields) or sits entirely inside it (islands, atoll platforms) - a hole
    straddling the edge would lose its relationship to its exterior, and none do.
    """

    def half_plane(poly: list, inside, intersect) -> list:
        out: list = []
        for index in range(len(poly)):
            a, b = poly[index - 1], poly[index]
            in_a, in_b = inside(a), inside(b)
            if in_a and in_b:
                out.append(b)
            elif in_a:
                out.append(intersect(a, b))
            elif in_b:
                out.extend([intersect(a, b), b])
        return out

    def at_x(a, b, x):
        t = (x - a[0]) / (b[0] - a[0])
        return [x, a[1] + t * (b[1] - a[1])]

    def at_y(a, b, y):
        t = (y - a[1]) / (b[1] - a[1])
        return [a[0] + t * (b[0] - a[0]), y]

    poly = [point[:2] for point in ring]
    for inside, intersect in (
        (lambda p: p[0] >= WEST, lambda a, b: at_x(a, b, WEST)),
        (lambda p: p[0] <= EAST, lambda a, b: at_x(a, b, EAST)),
        (lambda p: p[1] >= SOUTH, lambda a, b: at_y(a, b, SOUTH)),
        (lambda p: p[1] <= NORTH, lambda a, b: at_y(a, b, NORTH)),
    ):
        poly = half_plane(poly, inside, intersect)
        if not poly:
            return []
    return poly


def clip_line(coords: list) -> list:
    """Split a line into the runs of vertices that fall inside the box."""
    runs: list = []
    current: list = []
    for point in coords:
        x, y = point[0], point[1]
        if WEST <= x <= EAST and SOUTH <= y <= NORTH:
            current.append([x, y])
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    return [run for run in runs if len(run) > 1]


def close(ring: list) -> list:
    return ring if ring[0] == ring[-1] else ring + [ring[0]]


def round_coords(value):
    if isinstance(value[0], (int, float)):
        return [round(value[0], PRECISION), round(value[1], PRECISION)]
    return [round_coords(item) for item in value]


def polygons(geometry: dict) -> list:
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    return []


def lines(geometry: dict) -> list:
    if geometry["type"] == "LineString":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiLineString":
        return geometry["coordinates"]
    return []


def build(cache: Path) -> dict:
    features: list = []

    for layer, kind, mode in LAYERS:
        source = fetch(layer, cache)
        parts: list = []

        for feature in source["features"]:
            geometry = feature.get("geometry")
            if not geometry:
                continue

            if mode == "line":
                for line in lines(geometry):
                    parts.extend(clip_line(line))
                continue

            for polygon in polygons(geometry):
                rings = [clip_ring(ring) for ring in polygon]
                if not rings or len(rings[0]) < 3:
                    continue
                if mode == "holes":
                    # The shallow platforms are the holes punched in the isobath,
                    # so each hole becomes a polygon in its own right.
                    parts.extend([[close(hole)] for hole in rings[1:] if len(hole) > 2])
                else:
                    parts.append([close(ring) for ring in rings if len(ring) > 2])

        if not parts:
            raise SystemExit(f"{layer}: nothing survived clipping - check the bounding box")

        geometry_type = "MultiLineString" if mode == "line" else "MultiPolygon"
        features.append(
            {
                "type": "Feature",
                "properties": {"kind": kind},
                "geometry": {"type": geometry_type, "coordinates": round_coords(parts)},
            }
        )
        print(f"  {kind:7s} <- {layer} ({len(parts)} parts)", file=sys.stderr)

    for code, name, lon, lat in ATOLLS:
        features.append(
            {
                "type": "Feature",
                "properties": {"kind": "atoll", "code": code, "name": name},
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
            }
        )

    name, lon, lat = CAPITAL
    features.append(
        {
            "type": "Feature",
            "properties": {"kind": "capital", "name": name},
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
        }
    )

    return {
        "type": "FeatureCollection",
        "attribution": "Natural Earth (public domain), 10m physical",
        "bbox": [WEST, SOUTH, EAST, NORTH],
        "features": features,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the offline Maldives basemap.")
    parser.add_argument(
        "--cache",
        default=os.path.join(tempfile.gettempdir(), "natural-earth"),
        help="directory to keep downloaded Natural Earth layers in",
    )
    parser.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parent.parent / "web/public/basemap/maldives.json"),
        help="output GeoJSON path",
    )
    args = parser.parse_args()

    cache = Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)
    print(f"Natural Earth cache: {cache}", file=sys.stderr)

    collection = build(cache)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    # Compact: this is a build artefact read by a browser, not by a person.
    out.write_text(json.dumps(collection, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size / 1024:.0f} KB)", file=sys.stderr)


if __name__ == "__main__":
    main()
