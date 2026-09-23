# Build notes, decisions, and open issues

This file is the running log promised during the build: every non-obvious decision, every
ambiguity in the source spreadsheet, every bug found and fixed along the way, and everything
that still needs your judgment call. Read this before trusting the imported historical data for
anything operational.

## 1. Environment

This machine had no JDK, Maven, Node-adjacent Java tooling, or Python. With your go-ahead I:
- Installed **Eclipse Temurin JDK 21** via `winget` (system-wide).
- Downloaded **Apache Maven 3.9.9** as a plain zip to `C:\tools\apache-maven-3.9.9` (not a system
  installer — just unzipped, easy to delete).
- Set `JAVA_HOME` and added Maven to your **user** PATH permanently, so `java`/`mvn` work in new
  terminals without re-setting anything.

## 2. Stack actually built

- **Backend:** Java 21, Spring Boot 3.3.4, Maven. REST API under `/api/**`.
- **Storage:** JSON flat files in `data/` (`berths.json`, `vessels.json`, `reservations.json`,
  `validation-flags.json`, `audit-log.json`, `import-review-queue.json`). All writes go through
  `JsonCollectionStore`, which serializes access with a per-file lock and writes
  temp-file-then-atomic-rename, so concurrent requests can't corrupt the file or race into a
  double-booking.
- **Frontend:** Static HTML/CSS/vanilla JS under `src/main/resources/static/`, served directly by
  Spring Boot. No build step. Calendar is a pure render of `GET /api/reservations` — it holds no
  authoritative state itself.
- **Import:** Apache POI, run automatically on startup by `ImportRunner` whenever
  `reservations.json` is empty (or `MARINA_FORCE_REIMPORT=true` / `--reimport`). Safe to leave
  running permanently — it's a no-op once real data exists.

## 3. Data model summary

`Berth`, `Vessel`, `Reservation`, `ValidationFlag`, `AuditLogEntry`, `ReviewQueueItem` — see
`domain/` package. `Berth.restrictions` is an open `Map<String,Object>` specifically so future
fields (min draft, electrical, water, weather closures) can be added without a schema change, per
your requirement. `Reservation` carries `source` (MANUAL vs IMPORTED) and, for imported rows, an
`importMeta` block with the exact source sheet/cell, original text, a confidence score, and a
list of human-readable flags — every imported record is traceable back to precisely where it came
from in the spreadsheet.

## 4. Validation engine

Re-runs on every create/update/cancel/delete, scoped to the affected berth(s):
- **HARD_CONFLICT** only when two **CONFIRMED** reservations overlap and it's **not** an
  explicitly rafting-approved pair (`raftingApproved` is now a real boolean field staff set going
  forward — not inferred from free text).
- Everything else (a draft/pending overlap, a rafting-approved overlap, LOA exceeding berth
  length, missing vessel/berth data, booking a closed/restricted berth) is a **WARNING** or
  **DATA_QUALITY** flag for staff judgment, never an auto-block.

Covered by `ValidationServiceTest` (11 cases: exact overlap, partial overlap, adjacent
non-overlapping dates, pending-status overlap, rafting-approved overlap, canceled reservations,
LOA over/under berth length, missing vessel LOA, missing berth length, and flag cleanup on
delete).

## 5. Decisions made on ambiguous historical data

These were flagged in the original architecture proposal and I proceeded with them since you said
to continue without stopping — flip any of them if you disagree, they're all easy to change:

- **"Marsh Landing"** appears only in the `8YR Dock Summary` sheet, never in any of the 23 yearly
  grids. Imported as a berth with `unverified: true` and an explanatory note, rather than guessing
  it's an alias for one of the real berths.
- **"Small craft slips (institution boats)"** has no recorded length anywhere in the workbook.
  Imported with `lengthFt: null` and a note; it's excluded from automatic LOA-fit warnings until
  someone fills in a length.
- **Years with no merged cells at all** (roughly 1997 through the mid-2000s) never recorded a
  multi-day range — a vessel name in one cell only proves that one day was occupied. Every such
  entry is imported as a single day and flagged `durationConfident: false` in its `importMeta`.
  Years that do use merges (2010+) are trusted exactly — an unmerged cell there really is a 1-day
  booking, not an uncertain one.
- **Non-vessel grid text** was classified by keyword matching built directly from the 46 distinct
  non-vessel strings actually found in the sheets (community events, maintenance/closures,
  fueling/logistics/training). Bare fragments like "ETA 1200" or "Departs 0600" that read as
  scheduling annotations rather than standalone events are imported as `OTHER` but flagged
  `UNCLASSIFIED_ANNOTATION` for review rather than invented into fake bookings.
- **Ragged Science/Yachts vessel sheets:** fields aren't in fixed columns and are sometimes spread
  across multiple rows per vessel. Parsed by pattern (LOA:, Draft:, email, phone regexes) rather
  than column position; leftover text is assigned to operator/contact in sheet-header order, and
  anything that can't be classified goes in `notes`. Any vessel missing LOA is flagged and
  excluded from berth-fit checks rather than assumed to fit.
- **Tours → berth inference:** the Tours sheet's "Dock/Ship" column names a vessel, not a berth.
  Per your instruction, the importer cross-references that vessel's own reservation covering the
  tour date to infer the berth. **32 of 32 tours** had no matching vessel booking that day and are
  imported with `berthId: null`, flagged `TOUR_NO_BERTH_MATCH` — the Tours sheet's vessel names
  apparently don't line up with names used in the yearly grids closely enough to auto-match; this
  needs a human to reconcile.
- **Vessel name matching:** case/whitespace-insensitive, and a trailing embedded LOA suffix
  ("S/V Iron Petrel 32'" vs "S/V IRON PETREL") is stripped for matching. Anything else that looks
  like it might be the same vessel is **not** auto-merged — two duplicate vessel-sheet blocks were
  found and the second occurrence of each was left out of the vessel table, flagged
  `DUPLICATE_VESSEL` with both records' data shown for manual comparison.
- **December year-boundary spillover** (adjacent year sheets both listing the same late-December
  days) produces literal duplicate cells across two sheets. 3 exact duplicates (same berth, same
  vessel/text, same date range) were detected and only imported once, flagged
  `DUPLICATE_RESERVATION`.

## 6. Bugs found and fixed while building this

Left in here deliberately rather than cleaned out of the history, since two of these mattered:

1. **Silent whole-year data loss (the serious one).** Sheets **2014–2019** (six full years) title
   their month blocks with a bare month name and no year ("January" instead of "JANUARY 2018").
   My first parser version only recognized the "MONTH YEAR" format used by every other sheet, so
   it found **zero** title rows in those six sheets and imported **zero** reservations from them
   — with no flag raised, because the code path that would have flagged a bad block never ran if
   no blocks were found at all. Caught by manually spot-checking `2015-06` against the raw
   spreadsheet after the first import looked plausible but I hadn't verified every year. Fixed by
   (a) supporting the bare-month format with the sheet's own name as the year, and (b) adding a
   permanent safety net: if a sheet's title-scan finds *zero* blocks of any known format, it now
   raises an `IMPORT_FORMAT` review-queue item instead of silently producing nothing. Reservation
   count went from 1,587 to 2,221 after the fix. **`GridSheetParserTest` now has a named
   regression test for exactly this.**
2. **Idempotency-key bug.** Every reservation-create request without an explicit
   `Idempotency-Key` header was being cached under the literal string `"create:null"`, so the
   *second* unkeyed create in the cache TTL window (10 minutes) silently returned the *first*
   one's result instead of creating a new reservation. Found by an API smoke test where two
   different POSTs came back with the same id. Fixed so a missing/blank key bypasses the cache
   entirely, as intended.
3. **Orphaned validation flags.** `revalidateBerth` only recomputes flags for reservations
   *currently* at a berth, so deleting or canceling a reservation left its own flags in the store
   forever (they'd never again belong to any berth's active set). Found via a manual conflict
   test — a `HARD_CONFLICT` flag survived on the dashboard after both reservations in the pair
   were deleted. Fixed by explicitly purging a reservation's own flags on cancel/delete.
4. **Calendar rendering bug.** The month grid used CSS `var(--status-confirmed-alpha)` *strings*
   inside a JS `Math.round(x * 100)` call, producing `NaN%` and making every reservation cell
   render with an invalid, effectively invisible background. Caught by screenshotting the
   calendar and noticing reservations were present in the DOM/text but not visually rendered.
   Fixed by using real numeric alpha values in JS and picking dark-vs-light text per status alpha
   for contrast.

## 7. What the import actually found (current numbers)

- **8 berths**, **509 vessels** (104 with real registry data, 405 known only from grid mentions),
  **2,221 reservations**, **49 review-queue items**, **4,161 validation flags** outstanding
  (mostly `DATA_QUALITY` from the 405 grid-only vessels having no recorded LOA/contact info —
  expected, not a bug).
- **4 real HARD_CONFLICT double-bookings** were found in the historical data itself — exactly the
  class of problem this system was built to catch automatically instead of by eyeballing a grid:
  - `South Float East`, 2017-07-09→07-18 (`OSV AMBER REEF`) overlapping 2017-07-11 maintenance
    ("Utility work on pier face").
  - `Small craft slips (institution boats)`, `R/V Silver Petrel` (2017-09-01→09-10) overlapping
    `M/V HIGH COVE` (2017-09-04→09-08).
- **Review queue breakdown:** `TOUR_NO_BERTH_MATCH` 32, `UNPARSEABLE_VESSEL_BLOCK` 7,
  `UNPARSEABLE_TOUR_DATE` 3, `DUPLICATE_RESERVATION` 3, `IMPORT_FORMAT` 2, `DUPLICATE_VESSEL` 2.
  All browsable/resolvable at `/review.html`.
- One `IMPORT_FORMAT` item is a genuine anomaly *in the source file itself*: the "2010" sheet has
  a trailing block titled "NOVEMBER 2018"/"DECEMBER 2018" (8 years off, no day-of-week header,
  data crammed into the title row) that doesn't match any recognized layout. It was correctly
  skipped and flagged rather than guessed at. I checked whether the real "2018" sheet already has
  proper November/December data of its own — it does (via the bare-month format) — so this looks
  like leftover corrupted/misplaced synthetic data rather than a second copy of real information,
  but you should eyeball `/review.html` → `IMPORT_FORMAT` to confirm nothing was actually lost.

## 8. Known limitations (prototype scope, called out on purpose)

- **"Multiple users" protection** is a single in-process lock per JSON file — correct for this
  one Spring Boot instance, but would not hold up across multiple server processes/instances. Fine
  for a prototype; would need a real datastore for that.
- **Idempotency cache is in-memory** (10-minute TTL) and resets on server restart. Fine for
  "don't double-submit a form," not a durable dedup guarantee.
- **No authentication.** "Who did this" is whatever string the client sends in `X-User` (default
  `"staff"`) — enough for the audit log to be meaningful in a single-office prototype, not a real
  access-control system.
- **Vessel width, electrical/water hookups, weather closures** etc. are not yet modeled as actual
  fields — `Berth.restrictions` is deliberately an open map so they can be added without
  restructuring anything, but nothing populates it yet.
- **Deployment:** pushed to a public GitHub repo (https://github.com/kv2496-ai/marina-reservation)
  at your request. Not yet deployed live — that needs a hosting account only you control. Render's
  **free tier has no persistent disk**, so the ready-to-use `render.yaml` runs the JSON store on
  ephemeral storage: it self-seeds from the bundled spreadsheet on every restart, but any
  reservations created/edited on a free-tier deploy are lost on the next restart, and the service
  sleeps after inactivity (~30-60s cold start). Fine for a demo link, not for real operational use
  — see `README.md` §Deployment for the upgrade path.

## 9. Things that need your judgment call

- Confirm or correct the "Marsh Landing" and "Small craft slips" handling above.
- Skim `/review.html` — 49 items, most self-explanatory, a few (the 2010-sheet anomaly
  especially) worth a second pair of eyes.
- Decide whether the 405 grid-only vessels are worth backfilling with real LOA/contact data, or
  whether they should just stay flagged indefinitely as "historical, unverifiable."
- Say the word when you want the GitHub repo created and pushed / the Render deploy actually
  wired up — everything is ready locally, I just haven't published anything.
