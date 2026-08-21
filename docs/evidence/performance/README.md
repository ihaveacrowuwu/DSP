# Performance evidence

Four of the project's non-functional requirements are numbers. Until 2026-08-21 all four
were carried in prose — "measured ~1.5s", "22ms", "320ms" — which is a memory, not
evidence. `scripts/perf_test.py` prints them, and the JSON files here are the runs that
produced the figures the project quotes.

```bash
make up && make seed N=10000     # NFR3 is a claim about 10,000 sightings
make perf                        # writes perf-YYYY-MM-DD.json beside this file
```

## Run of 2026-08-21 — [`perf-2026-08-21.json`](perf-2026-08-21.json)

Against the compose stack on the development machine (Apple M1 Pro), 10,304 sightings
seeded, ML service in fake mode.

| Requirement | Threshold | Measured | |
|---|---|---|---|
| NFR1 — label readable after sync | ≤ 30 s | **0.89 s** | ✅ |
| NFR2 — CPU inference per image | ≤ 500 ms | **22 ms** | ✅ ⚠️ |
| NFR3 — map viewport at 10,000 sightings | ≤ 2 s | **56 ms** worst of 5 | ✅ |
| NFR11 — 50 concurrent submissions | no error, no loss | **0 errors, 0 lost**, 919/s | ✅ |

⚠️ **NFR2's figure is the fake model.** The ML service ships a deterministic stub until
the training track produces a real one, so 22 ms measures the plumbing and says nothing
about the model that will replace it. Quoting it as evidence for NFR2 without that
sentence beside it would be misleading.

### What the harness asserts that a stopwatch would not

- **NFR11 checks for data loss, not just errors.** Fifty successful responses do not
  prove fifty rows. Every sighting id is client-generated, so all fifty are known before
  anything is sent and every one is read back afterwards; `missing_after_write` is that
  count. A run where the API returns 201 fifty times and stores forty-nine is a pass on
  status codes and a failure here.
- **NFR3 reports the corpus it actually measured against.** The first run of this check
  returned 8 ms and **failed**, because the database held 253 sightings — a fast query
  against 253 rows is not evidence for a requirement about 10,000. The threshold and the
  precondition are both enforced.
- **NFR2 reads the time the service reported** rather than timing the request from
  outside, because the requirement is about inference, not about the network and the
  worker's poll interval.
- **NFR1 starts its clock at the photo upload**, which is the moment the contributor's
  phone has finished its side of the sync — not at the metadata POST.

### The cost of D45, measured

D45 added a primary-key lookup to every authenticated request so that bans and
demotions take effect immediately rather than when the access token expires. That trade
was made on the promise of measuring it:

| | p50 | p95 |
|---|---|---|
| `/healthz` (no auth) | 1.6 ms | 3.4 ms |
| `/v1/me` (auth + its own aggregate query) | 3.5 ms | 4.4 ms |

The gap is an **upper bound** — `/v1/me` runs an aggregate of its own — so the auth
lookup costs under ~1.9 ms at p50 and the throughput figure above was measured with it
in place. NFR11 passes at 919 submissions per second with the lookup on every one.

## Figures this supersedes

`CLAUDE.md` previously recorded "map with 10k sightings 22ms" and "sync→label ~1.5s".
The map figure is now **43–56 ms** at 10,304 sightings, measured five times; the 22 ms
was either a smaller corpus or a warm single sample. The sync figure is **0.89 s**. Both
are comfortably inside their thresholds, and the point of writing them down here is that
the next person to quote them can see what produced them.
