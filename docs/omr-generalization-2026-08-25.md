# SheetSight OMR generalization evaluation — 2026-08-25

## Generalization Findings

The existing local staff geometry, system-first assignment, explicit unresolved
events, and source-relative evaluator generalize beyond Canon on the tested
clean digital pages. A narrow barline post-processing change also generalizes:
it selects the tightest cross-track structural evidence, requires every
detected track in a multi-staff system, merges double/final-bar strokes within
one local staff space, and uses each system's local scale.

On Happy Birthday, strict internal-bar precision/recall/F1 improve from
0.50/0.50/0.50 to 1.00/1.00/1.00. All simplified-page metrics are preserved,
Canon remains 10/10 staffs and 15/15 internal bars, and the three frozen
holdouts have byte-identical barline and notehead TSVs before/after.

This does not make SheetSight accurate for arbitrary images. Happy Birthday
still misses 17/48 annotated noteheads, and the low-quality frame produces 390
noteheads from keyboard/background texture.

## Root Causes by Page

| Page | Earliest divergence | Classification | Evidence |
| --- | --- | --- | --- |
| Ash Grove | duration interpretation | MUSICAL_INTERPRETATION | 85 heads but 28 unresolved events; event ground truth pending |
| Simplified | rest candidate filtering | POST_PROCESSING | expected bass half-rest reaches the rejection TSV at the correct coordinate with reason `area`; duration accuracy is 0.871 |
| Accidentals | model detail mask, then rest typing | MODEL + POST_PROCESSING | key/clef/note residue occupies stems/rests channel; 31 accepted rests and 140 warnings; ground truth pending |
| Happy Birthday | raw model-two notehead mask | MODEL | many hollow/lettered bass chord heads are absent before Kotlin extraction; chord exact is 2/9 and duration 0.412 |
| Canon | duration interpretation | MUSICAL_INTERPRETATION | structure is exact; 6 events remain unresolved; event ground truth pending |
| Low quality | uncropped full-frame input → raw masks | PREPROCESSING / MODEL | keyboard/background is already labeled as notation in the raw model mask; 390 heads, 210 unresolved, 565 warnings |

## Cross-Page Before/After Metrics

Default profile: XNNPACK-only, batch 1, target 3,675,000 pixels, model-window
stride. `N/A` means the fixture is not complete enough for that percentage.

| Page | Role | Staffs | Accepted bars | Measures | Noteheads | Unresolved | Behavior delta |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| Ash Grove | holdout | 7 → 7 | 26 → 26 | 28 → 28 | 85 → 85 | 28 → 28 | byte-identical bar/head TSVs |
| Simplified | development | 8 → 8 | 15 → 15 | 16 → 16 | 81 → 81 | 6 → 6 | all eligible metrics preserved |
| Accidentals | holdout | 7 → 7 | 6 → 6 | 13 → 13 | 44 → 44 | 11 → 11 | byte-identical bar/head TSVs |
| Happy Birthday | development | 6 → 6 | 6 → 6 | 9 → 9 | 35 → 35 | 6 → 6 | bar coordinates corrected; F1 0.50 → 1.00 |
| Canon | development | 10 → 10 | 15 → 15 | 20 → 20 | 233 → 233 | 6 → 6 | structure preserved |
| Low quality | holdout | 2 → 2 | 6 → 6 | 8 → 8 | 390 → 390 | 210 → 210 | byte-identical bar/head TSVs; still unsupported |

### Complete-fixture metrics

| Metric | Simplified before → after | Happy before → after | Final macro |
| --- | ---: | ---: | ---: |
| Bar P/R/F1 | 1/1/1 → 1/1/1 | .50/.50/.50 → 1/1/1 | 1/1/1 |
| Notehead P/R/F1 | 1/1/1 → 1/1/1 | .886/.646/.747 → same | .943/.823/.873 |
| Filled/open | 1 → 1 | .742 → .742 | .871 |
| Pitch step / octave / full | 1/1/1 → same | 1/1/.742 → same | 1/1/.871 |
| Duration | .871 → .871 | .412 → .412 | .641 |
| Chord exact | N/A | N/A → .222 | .222 applicable-only |
| Rest P/R/F1 | 1/.75/.857 → same | N/A | 1/.75/.857 applicable-only |
| Rest type | 1 → 1 | N/A | 1 applicable-only |
| Accidentals | N/A | N/A | N/A |
| Rhythmically exact measures | N/A → 7/16 | N/A → 0/9 | 21.9% macro rate |
| Unresolved / hallucinated | 6/0 → 6/0 | 6/4 → 6/4 | 6/2 average |

## Holdout Results

The candidate was frozen before Ash Grove, the accidentals screenshot, and the
low-quality frame were opened. All three preserve barline and notehead TSVs
byte-for-byte. Their event percentages remain `N/A`; unchanged bad output on
the low-quality page is a non-regression result, not accuracy.

## Performance

Final-profile device results. Java/native columns are independent peaks; debug
overhead is instrumentation wall time minus the sum of recorded stages.

| Page | Wall s | Model 1 / 2 s | Java / native MB | Debug overhead s |
| --- | ---: | ---: | ---: | ---: |
| Ash Grove | 220.6 | 49.3 / 88.3 | 220 / 843 | 2.5 |
| Simplified | 418.2 | 101.1 / 148.2 | 220 / 845 | 1.7 |
| Accidentals | 350.3 | 57.6 / 94.5 | 220 / 870 | 3.8 |
| Happy Birthday | 447.7 | 83.0 / 213.2 | 220 / 832 | 1.9 |
| Canon | 245.4 | 61.4 / 84.4 | 248 / 831 | 4.7 |
| Low quality | 221.4 | 61.4 / 83.3 | 220 / 828 | 3.1 |

Controlled simplified-page runs reject 3.0M (rest recall 0.50), 4.35M
(notehead F1 0.988, rest P/R 0.25, wrong measures), and stride 128 (225+225
tiles, 637.9 s, wrong measures). Current preprocessing is already the minimal
model-required BGR conversion plus resize; there is no enhancement stage to
remove in a distinct A/B without violating the verified model contract.

## Remaining Failures

- Lettered/hollow chord heads and simultaneous bass chords.
- Chord ownership and duration interpretation (Happy: 2/9 exact chords, 0/9 rhythmically exact measures).
- Small half-rest filtering (one known correct candidate rejected by area).
- Accidentals/key-signature/clef-change separation and rest residue.
- Full-frame photos/video with a small score region and distracting keyboards/backgrounds.
- Event accuracy on Ash Grove, Canon, accidentals, and low-quality pages remains unmeasured until fixtures are complete.

## Recommendation

**B. Add a secondary difficult-symbol classifier.** Keep the proven local
geometry and conservative barline post-processing. A secondary classifier can
target hollow/lettered noteheads, rests, and accidental-versus-key-signature
residue without replacing the reliable simple-page path. Add score-region ROI
preprocessing before attempting photographic pages. Retraining or replacing
the backend is premature with only two complete event fixtures.
