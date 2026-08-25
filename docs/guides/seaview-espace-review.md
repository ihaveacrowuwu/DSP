# Seaview UQ eSpace page — human review guide

> **Status: done 2026-08-24.** The user read the page and pasted its metadata; the
> outcome is recorded in the Q6 row of `docs/08`. Checks 1 and 3 (licence at the
> source, no extra terms) are confirmed. Check 2 is closed: the page has no
> "Cite this" widget, so the citation constructed from its metadata (in the Q6
> prose block) is the citation of record. Checks 5 and 6 (partition size, survey
> IDs) plus the evidence screenshot fold into the download step on the Mac —
> see §3 and §4 for what to capture there.

**Why this exists.** D60 adopted the Seaview Survey photo-quadrat dataset (Central
Indian Ocean subset only) as the cross-domain evaluation corpus, and its CC-BY 3.0
licence was confirmed on 2026-08-20 — but only **via the Research Data Australia
metadata record**, because the UQ eSpace landing page blocks automated fetching.
Q6 in `docs/08` therefore still carries one open sub-item: a human needs to read
the actual landing page once. This is that read. Budget: **10–15 minutes.**

This does **not** block training — Seaview is evaluation-only (it has no
healthy/bleached labels). It blocks the *download* step on the Mac, and the
project's data-provenance section.

## 1. Open the record

Either of these should land on the same page:

- DOI resolver: <https://doi.org/10.14264/uql.2019.930>
- UQ eSpace record id: **UQ:734799** (search at <https://espace.library.uq.edu.au>)

If the DOI resolves somewhere unexpected, stop and note where it went — that
alone is a finding.

## 2. What to verify, in order

| # | Check | What we currently believe (from the RDA record) |
|---|---|---|
| 1 | The licence stated **on the eSpace page itself** | CC-BY 3.0 |
| 2 | The exact citation / attribution text the page requests | Unknown — capture it verbatim |
| 3 | Any terms of use **beyond** the licence (registration wall, click-through agreement, usage restrictions) | None — downloads are keyless |
| 4 | The dataset is partitioned by region and a **Central Indian Ocean** partition exists and is individually downloadable | Yes — 22 regional partitions, ~1.5 TB total |
| 5 | The approximate size of the Central Indian Ocean partition alone | Unknown — needed to plan the Mac download |
| 6 | Whether survey IDs / a file manifest are published on the page or inside the download | Unknown — `baseline.yaml` has `survey_ids: []` and `manifest_sha256: null` waiting for them |

Check 3 is the one that could actually change anything: **a click-through
agreement or registration requirement would collide with the no-API-key
constraint** and needs a decision, not a workaround.

## 3. What to capture as evidence

1. **A screenshot of the page section stating the licence**, saved to
   `docs/evidence/datasets/seaview-espace-licence.png`. This is report evidence
   for the data chapter — the licence claim should trace to the publisher's own
   page, not to a third-party metadata aggregator.
2. **The citation text**, copied verbatim (it goes in the project's references and
   the data section).
3. Anything that contradicts the table above.

## 4. Where to record the outcome

- **All clear (expected):** in `docs/08`, edit the Q6 row — change "Seaview read
  pending" to confirmed with today's date. One line, no new decision needed;
  D60 already covers the adoption.
- **Anything differs** (licence isn't CC-BY 3.0, there's a click-through, no
  regional partitioning): record what you found in the Q6 row and flag it —
  that becomes a decision-log entry, because it changes D60's terms. Do not
  download anything in the meantime.
- Either way, the citation text and partition size are worth pasting into the
  Q6 row so the Mac session doesn't need the page again.

## 5. What NOT to do here

- **Do not download the dataset from this page** — the download happens on the
  Mac, Central Indian Ocean partition only, never the full 1.5 TB, and the
  survey IDs + manifest hash get recorded in `baseline.yaml` at that moment.
- Do not add Seaview to a training split, ever — its label set is hard coral /
  algae / soft coral / other invertebrate / other. Evaluation and hand-labelling
  only (D60).
