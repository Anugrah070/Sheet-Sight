# OMR accuracy diagnosis — 2026-08-22

This report separates source/test evidence, completed measurements on the connected CPH2707 device, and accuracy claims that still require human ground-truth annotations. The evaluated page remains in app-private storage; no private score image was exported.

## Diagnosis

| Layer | Status | Evidence |
| --- | --- | --- |
| Preprocessing | GOOD | The production path converts Android RGBA to the checkpoint's BGR byte order and bicubic-resizes to 3.675 MP. Debug previews expose pre-resize pixels, the canonical page, and tile boundaries. |
| Model input | VERIFIED | Loaded ONNX sessions validate input/output names, UINT8/FLOAT32 types, NHWC layout, spatial dimensions, and channel counts from runtime graph metadata before inference. |
| Model predictions | SUSPECT | Both argmax maps and all five masks are previewable. On the representative page, notehead output is stable across CPU and XNNPACK and across no-overlap/128-stride runs, but the generic-symbol/Hough path emits only one raw bar candidate and accepts none. |
| Tile merging | VERIFIED, NOT BENEFICIAL HERE | Accumulation is HWC-consistent and unit-tested. Full stride-128 overlap changed 119 tiles to 476, took 640 s instead of 171 s, kept noteheads at 35 and accepted bars at zero, and increased rest candidates from 6 to 12. Production therefore uses per-model window stride until a labelled corpus demonstrates an overlap benefit. |
| Staff detection | GOOD ON THIS PAGE | The completed runs consistently found three systems and six staves. Local staff spacing is used downstream. |
| Bar-line detection | NEXT-PHASE ACTIVE | The original Hough path found one raw line and rejected it. A conservative fallback now examines narrow unclaimed stem/rest components, requires four staff-line crossings, requires aligned evidence across both tracks for piano systems, and merges the two staff fragments. Single-track fallback remains available for single-staff scores. |
| Rest detection | IMPROVED | Six no-overlap rest detections included five page-bottom artifacts. A staff-relative vertical envelope removed all six on the completed after-run without changing the 35 noteheads. Detached-stem and whole/half-placement rules remain active. |
| Rhythmic reconstruction | SUSPECT | The completed after-run resolved 25 of 31 chord rhythms and left 6 unresolved. No time-signature recognizer supplies a safe expected measure duration, so the exporter correctly avoids inventing 4/4 repairs. |
| MusicXML generation | VERIFIED FOR ROUND TRIP | The completed after-run constructed, exported, parsed, and rendered 10 measures consistently. This proves structural integrity, not musical correctness of the detected boundaries. |

## Connected-device measurements

Input `omr-eval-case1.png` decoded as 494×607 and was canonically resized to 1729×2125. The same private page and model assets were used in every comparison.

| Profile | Stride / batch | Result | Model timings | Peak used memory | Recognition summary |
| --- | --- | --- | ---: | ---: | --- |
| Provider chain | window / 8 | Stopped after >9 CPU-min | did not finish page | ~5.5 GB native allocation; ~3.75 GB PSS | Batch activation arena is unsafe. |
| CPU only | window / 8 | Stopped early | — | ~2.63 GB PSS | Provider choice is not the primary batch-memory cause. |
| CPU only | window / 1 | Completed in 324.8 s | 59.5 s + 214.6 s | 1.64 GB tracked peak | 35 noteheads, 0 bars, 6 rests. |
| XNNPACK only | window / 1 | Completed in 171.0 s | 40.4 s + 77.1 s | 1.04 GB tracked peak | Byte-stable counts versus CPU: 35 noteheads, 0 bars, 6 rests. |
| XNNPACK only | 128 / 1 | Completed in 640.1 s | 159.2 s + 312.4 s | 1.07 GB tracked peak | 35 noteheads, 0 bars, 12 rests; no accuracy benefit observed. |
| XNNPACK only + first next-phase filters | window / 1 | Completed in 188.1 s | 39.8 s + 79.5 s | 1.02 GB tracked peak | 35 noteheads, 0 rests, 7 preliminary structural bars, 10 measures round-tripped. |

The seven preliminary bars included close, vertically separated pairs and single-track candidates. That evidence prompted the stricter multi-track consensus/merge rule. Its focused JVM tests and Android build pass, but a consecutive full-page repeat became thermally throttled (model 1: 343.7 s versus 39.8 s) and then stopped advancing during model 2. It was terminated and is not reported as a successful device run.

## Changes selected for production

- Default execution profile is XNNPACK-only with batch size 1. This is the fastest completed safe configuration on the test device and avoids the batch-8 allocation failure.
- Production inference uses each model's window-sized stride. Stride 128 remains available explicitly for diagnostic comparison.
- Runtime tensor-contract verification and compact intermediate previews remain enabled in the diagnostic path.
- Rest candidates must be within two local staff spaces of their assigned staff, in addition to detached-stem rejection and whole/half placement checks.
- The barline fallback requires narrow vertical geometry, at least four crossed staff lines, and two-track alignment on piano systems. It does not promote a lone long piano note stem.
- Diagnostic reports retain coordinates, stage counts, provider/batch settings, timings, memory checkpoints, and semantic/XML/editor measure counts.

## Verification status and remaining accuracy gate

- 235 OMR JVM tests passed with zero failures before the final multi-track tightening; the focused barline/rest suite passed again afterward.
- Debug app and instrumentation APKs compile, sign, install, and the completed next-phase device run passed its semantic → MusicXML → editor measure-count assertions.
- The final tightened build is installed, but its thermally throttled repeat did not complete and must be rerun after device cooldown.
- Absolute precision, recall, pitch accuracy, rest-type accuracy, and measure-boundary accuracy remain unknown because the private pages have no human ground-truth annotations. Counts alone must not be presented as accuracy percentages.

The next evaluation phase is a small consented, manually annotated corpus. For each page, record staff systems, barline x positions, notehead pitch/duration, rest position/type, and time signature; then use the existing coordinate matcher to calculate precision/recall and per-symbol type accuracy. Run one page per cooled-device session and record cold/warm thermal state with the timing.

## 2026-08-23 next-phase evidence

### Installed build and recovered private references

The connected CPH2707 reported thermal status 0 before evaluation. The installed production package was `com.sheetsight.app`, version code 1 / version `0.1.0`, last updated `2026-08-22 15:45:52`; its installed base APK SHA-256 was `F421561C4C8FB4ECF36ECE88A483D6C260B70BB28517DD825CA86EFDB4D3E0AD`. The supplied attachment bundle contained request text only, but the two source pages were recovered directly from the debuggable app sandbox into ignored `app/build/omr-private` storage. They were not copied to shared device storage or added to Git.

Checked-in structure fixtures identify the private pages by dimensions and SHA-256 without embedding them:

- Canon: 1080×1395, SHA-256 `91D5DEE9CC11406722FD6877A4832B88A01EA83CF92B7CFAD656E950331E2843`.
- Simplified page: 466×466, SHA-256 `1923AF6A670739897F1F64C02C3C97BB57A959FC5BD4F130ED59289FB4F7A745`.

Both fixtures contain complete system/staff ordering, clef/key/time context, and source barline coordinates. Note/rest event transcription is explicitly marked pending and `eligibleForMetrics=false`; notehead, pitch, duration, chord, and rest accuracy percentages remain prohibited until that work is double-checked.

### Canon cold baseline and first divergence

The old installed build completed one cooled Canon page in 164.266 s using XNNPACK-only, batch 1, and model-window stride. Canonical size was 1687×2179; tile counts were 63 and 48; model timings were 38,481 ms and 59,432 ms; tracked peak was 1,023 MB.

The first semantic divergence is staff-grid completeness, not AlphaTab or MusicXML:

| Evidence | Expected | Detected |
| --- | ---: | ---: |
| Systems | 5 | 5 |
| Staves | 10 | 9 |
| Internal measure barlines | 15 | 11 TP, 0 FP, 4 FN |
| Measures | 20 | 16 |
| Noteheads | event annotation pending | 233 |
| Note groups/chords | event annotation pending | 209 |
| Resolved / unresolved rhythm groups | event annotation pending | 203 / 6 |
| Semantic / MusicXML / parsed / rendered measures | 20 | 16 / 16 / 16 / 16 |
| Warned omitted/unresolved events | 0 target | 10 (not silent) |

At the declared 0.50-staff-space, same-system barline tolerance, the 11 detected structural barlines all match annotated boundaries; four internal boundaries are missing. This is structural barline precision 100% and recall 73.3% for Canon only. No note/rest percentage is reported.

The missing row is the fourth system's treble staff: clef classification produced five F clefs but only four G clefs. With nine rows, modulo `row % 2` ownership shifted the fourth-system bass to treble, the fifth-system treble to the preceding bass identity, and the final bass to treble. The resulting global/zone-average pitch geometry produced three MusicXML-incompatible octave −1 notes; six groups had unresolved durations; export reported ten omitted events. Thus the observed late-page inversion and extreme pitches are downstream consequences of one globally missing staff row plus unsafe assignment geometry.

### Selected implementation changes

- Repeated two-staff row identities are now inferred from alternating within-system/inter-system gaps. A single internal missing slot is accepted only when its spacing model is materially better and unambiguous.
- Once proven, only the missing staff geometry is reconstructed from adjacent repeated-system spacing. It is marked `AssignedStaff.isInterpolated`; no note, rest, duration, measure, clef, key, or time signature is invented. Edge and ambiguous omissions remain unresolved.
- Noteheads are assigned to a source system first, then to the x-local staff segment. Staff-line position uses the line regression at the note's x rather than zone-average y.
- Staff-space interpolation now weights the nearer staff correctly. Previously the distance weights were reversed.
- Pitch positions outside −5..15 remain unresolved while the semantic seam lacks explicit ledger-line evidence, preventing impossible exported octaves without suppressing ordinary piano ledger notes.
- Diagnostic overlays now label staff/system IDs, interpolated rows, notehead IDs and coordinates, chord ownership, and per-group stem/beam/flag/dot/duration evidence. Full-resolution masks are still not retained.
- Accuracy matching now enforces annotated system and staff identity and exposes F1 in addition to precision/recall.
- Rest candidates must also remain at least one local staff space inside the x-local left/right staff envelope. This rejects final-bar components without imposing a page-global crop.
- A grouping-independent barline path accepts only whole generic-symbol components corroborated by the independent stem/rest channel, then applies the same narrow, vertical, four-line, two-track consensus rules. It does not synthesize boundaries from repeated measure spacing.

Focused geometry/assignment/pitch/metric/barline/rest/measure tests passed, followed by the complete OMR JVM suite: 251 tests, zero failures, zero errors, zero skipped (up from the recorded 236 after fifteen new regressions/assertions).

### Simplified-page old-build baseline

A separate thermal-status-0 session completed the 466×466 simplified page in 267.541 s at 1917×1917 canonical resolution (64/49 tiles, XNNPACK-only, batch 1, window stride, 1,029 MB tracked peak). Model timings were 45,803 ms and 141,519 ms. The second model was much slower than Canon's 59,432 ms, so this run was nominally unthrottled by Android status but not thermally comparable to a truly cold run.

The earliest divergence differs from Canon:

| Evidence | Expected | Detected |
| --- | ---: | ---: |
| Systems / staves | 4 / 8 | 4 / 8 |
| Measure-splitting internal barlines | 12 | 12 |
| Measures | 16 | 16 |
| Noteheads | 81 by manual visual count | 81 |
| Filled/open notehead counts | 52 / 29 by manual visual count | 52 / 29 |
| Note groups | 81 monophonic source groups | 81 |
| Resolved / unresolved rhythm groups | 81 target | 76 / 5 |
| Source rests | 4 | 5 detections |
| Semantic / MusicXML / parsed / rendered measures | 16 | 16 / 16 / 16 / 16 |
| Warned omitted/unresolved events | 0 target | 5 (not silent) |

Count equality is not a coordinate-level accuracy claim, but it localizes the page's missing rendered notes downstream of the raw notehead mask and connected-component/group stages. All 81 noteheads survive into semantic construction; five note groups first become unusable in rhythm reconstruction and are then omitted with explicit `UNRESOLVED_DURATION` warnings. The filled/open aggregate also matches the manual visual count, so the observed older rendered comparison is not evidence for changing global notehead thresholds without stable-ID coordinate matching.

Rest handling remains incorrect. The source has a treble quarter rest and bass half rest in measures 4 and 8. The baseline detected both treble quarter rests, typed the second bass rest as a quarter, missed the first bass half rest, and emitted two false quarter rests at x≈1794–1795 beside the final system's right edge. This is direct evidence that page/right-edge rejection must use each system's x-local staff extent, not only a page-global or vertical envelope. Rest percentages remain withheld from the corpus report until the rest events are entered into the metric-eligible fixture.

For barline interpretation, all 12 internal measure-splitting boundaries were found. Two final-system right-edge components were also retained as true printed end barlines, while the first two systems' right edges were not emitted as candidates. Measure construction still correctly produced 16 containers from annotated staff extents plus internal bars.

### Rebuilt Canon after-run

The rebuilt APK was signed with the certificate matching the installed debug build and updated in place, preserving the app-private corpus. The installed `base.apk` SHA-256 exactly matched the evaluated local artifact: `240F182419B9F35925A98732BDBB4941289EE9F0C4BBAF34B8B29D1F4A6C3C31`. The final isolated Canon session used XNNPACK-only, batch 1, model-window stride, and Android thermal status 0. It completed in 177.472 s; model timings were 41,803 ms and 68,749 ms, with a 1,051 MB tracked peak.

| Evidence | Expected | Final detected |
| --- | ---: | ---: |
| Systems / staves | 5 / 10 | 5 / 10 |
| Treble / bass clefs | 5 / 5 | 5 / 5 |
| Internal measure barlines | 15 | 15 TP, 0 FP, 0 FN |
| Measures | 20 | 20 |
| Noteheads / semantic notes | event annotation pending | 233 / 233 |
| Note groups/chords | event annotation pending | 209 |
| Resolved / unresolved rhythm groups | event annotation pending | 203 / 6 |
| Semantic / MusicXML / parsed / rendered measures | 20 | 20 / 20 / 20 / 20 |
| Warned unresolved durations | 0 target | 6 (not silent) |

The recovered barline x positions match all annotated internal boundaries within the declared same-system 0.50-staff-space tolerance: system 0 `[583, 910, 1239]`; systems 1–4 `[559, 896, 1231]`. This is 100% precision/recall/F1 for Canon's manually annotated internal barline structure only. It is not a note, pitch, chord, rhythm, or rest accuracy claim. The six unresolved durations remain explicit MusicXML warnings; the exporter neither substituted rests nor claimed rhythmic validity. Full note-level acceptance remains gated on completing and double-checking the event fixtures.

### Final simplified-page after-run

The final rebuilt APK was again updated in place with the matching debug certificate. Its local and installed `base.apk` SHA-256 matched exactly: `53B8479F483A0D25EC8464E6E69D39F37970FB1DB1DDFE0D3F845E635875B41F`. The isolated simplified-page session started at thermal status 0 / approximately 35.6°C, used XNNPACK-only, batch 1, and model-window stride, and completed in 186.304 s. Model timings were 52,301 ms and 75,907 ms; tracked peak was 1,058 MB.

| Evidence | Expected | Final detected |
| --- | ---: | ---: |
| Systems / staves | 4 / 8 | 4 / 8 |
| Internal measure barlines | 12 | 12 TP, 0 FP, 0 FN |
| Measures | 16 | 16 |
| Noteheads / semantic notes | 81 by manual visual count | 81 / 81 |
| Note groups | 81 monophonic source groups | 81 |
| Resolved / unresolved rhythm events | 85 target events | 78 / 6 from 84 retained events |
| Source rests | 4 | 3 retained: 2 resolved, 1 explicit classifier/placement conflict |
| False right-edge rests | 0 | 0 |
| Semantic / MusicXML / parsed / rendered measures | 16 | 16 / 16 / 16 / 16 |
| Warned unresolved durations | 0 target | 6 (five chords, one rest; not silent) |

All twelve annotated internal barline positions match within the declared same-system tolerance. Three additional accepted components are printed system-edge bars; they do not split the expected measure containers. The final double-bar strokes consolidate into one boundary, and a detected bar within the staff-relative endpoint tolerance owns the staff edge instead of creating the former 3-pixel sliver measure.

The x-local rest-envelope diagnosis was also concrete. The real second treble and bass rest crops at x≈1586/1588 had been rejected because one staff-mask row ended early; using the complete system extent retains both. The treble quarter rest resolves. The bass crop's SVM label conflicts with strong half-rest line-placement geometry, so its duration is deliberately unresolved and exported with a warning rather than being emitted as a wrong quarter rest. The first bass rest remains a small x=1628 crop rejected by the minimum-area gate. Several open-notehead residues are rejected by the same gate (for example x=1057), so promoting that crop from position/count context would fabricate a rest. It is retained in rejection diagnostics with reason `area`, but not converted into a semantic event.

Therefore the structural and round-trip targets are met on both pages, and no retained unresolved event is silent. The full musical-accuracy target is not yet met: the fixtures still lack double-checked event annotations, five note groups on the simplified page and six on Canon have unresolved durations, and one visually expected simplified-page rest cannot yet be safely distinguished from small notehead residue. Note/pitch/duration/rest percentages and a ≥95% notehead-F1 claim remain prohibited until the event fixtures are complete.

An additional Canon regression was run on this exact final `53B8…B41F` APK. Telegram was independently consuming approximately one CPU core, so the 203.966 s elapsed time and 50,482/70,325 ms model timings are intentionally excluded from performance comparison. Deterministic output remained unchanged: 5 systems, 10 staves, 15 annotated internal barlines, 20 semantic/MusicXML/parsed/rendered measures, 233 noteheads, 233 semantic notes, and six explicitly warned unresolved chord durations.
