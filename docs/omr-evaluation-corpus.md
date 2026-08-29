# SheetSight OMR evaluation corpus

The repeatable six-profile corpus is declared in
`app/src/test/resources/omr_ground_truth/evaluation-corpus.v1.json`. Source
pages stay in ignored app-private/build storage; the manifest identifies each
page by dimensions and SHA-256 so a different screenshot cannot silently
replace a baseline input.

The current inventory contains six unique source pages covering clean/simple,
rests, accidentals, chords, dense grand staff, and low-quality photographic
input. The simplified and Happy Birthday pages now have independently reviewed
`COMPLETE` event fixtures. Canon has complete structure but pending events; the
other three pages remain in the fixed corpus but are ineligible for event
accuracy percentages until their ground truth is reviewed.

## Frozen split (2026-08-25)

| Role | Pages |
| --- | --- |
| Development | simplified rests, Happy Birthday chords, dense Canon |
| Holdout | Ash Grove, April accidentals/clef changes, June low-quality photo/video frame |

The production candidate and the 3,675,000-pixel/model-window profile were
frozen before the holdouts were opened. The three holdout before/after
barline and notehead TSVs were byte-for-byte identical.

## Metric gate

Only fixtures with `annotationStatus=COMPLETE` produce event percentages.
Canon may produce structural staff/bar/measure metrics because its structure is
complete, but its event metrics remain `N/A`. Ash Grove, the accidentals page,
and the low-quality page produce diagnostic counts and performance only.

The same source hash, execution provider, batch size, target pixel count, and
tile stride must be recorded for every before/after pair. Resolution and stride
experiments change one profile field at a time. A run that changes page content
or uses a thermally incomparable device session is not a valid comparison.

## Current measured baseline and candidate

| Page | Stage | Staves | Internal bar lines | Measures | Event metrics |
| --- | --- | ---: | ---: | ---: | --- |
| Simplified | unchanged/final | 8/8 | 12/12 | 16/16 | COMPLETE; notehead F1 1.000, duration 0.871, rest recall 0.750 |
| Happy Birthday | unchanged | 6/6 | 3/6 strict matches | 9/9 | COMPLETE; notehead F1 0.747 |
| Happy Birthday | final | 6/6 | 6/6 strict matches | 9/9 | COMPLETE; notehead F1 0.747, chord exact 0.222 |
| Canon | final | 10/10 | 15/15 | 20/20 | N/A — events pending |
| Three holdouts | unchanged/final | unchanged | N/A | unchanged | N/A — events pending |

The detailed cold-device timings, memory peaks, unresolved-event counts, and
bar-line coordinates remain in `docs/omr-accuracy-diagnosis-2026-08-22.md`.
Counts from incomplete fixtures remain diagnostic evidence, not precision or
recall. Controlled profile results on the simplified fixture reject 3.0M
(rest recall regression), 4.35M (bar/note/rest regressions), and stride 128
(wrong measure count and 638-second wall time); the default profile remains
3.675M with model-window stride.
