# SheetSight

An offline, Android-native OMR (Optical Music Recognition) app: import a
PDF or image of printed sheet music, recognize it into structured
MusicXML on-device, then (eventually) correct it, analyze it, and use it
for real-time piano practice against a microphone/MIDI input.

This README describes **only what exists in the current codebase**. Where
something is scaffolded but not implemented, or planned but not started,
it's labeled as such explicitly.

---

## 1. Project overview

SheetSight's intended end-to-end flow:

1. **Import** a PDF/JPG/PNG of sheet music.
2. **Recognize** it via an on-device OMR pipeline into MusicXML (no cloud
   calls, no external OMR service).
3. **Edit** the recognized notation to correct OMR mistakes.
4. **Analyze** the score (key, chords, cadences, intervals, motifs, ...).
5. **Practice** by playing the piece on a real piano; the app listens via
   microphone or MIDI and advances a cursor through the score note-by-note.

The app is offline-first end to end: local Room storage, on-device OMR via
ONNX Runtime Mobile, no network dependency for any core feature.

---

## 2. Current implementation status

### Completed and implemented
- **App shell**: single-Activity Compose app (`MainActivity`), Material 3
  theming, bottom-tab navigation (`SheetSightNavHost`) across five tabs —
  Library, Editor, Practice, Analysis, Settings — plus a Preview screen.
- **Library tab**: fully functional — grid/list view, search, sort, file
  import via the system file picker, favorite/delete, backed by Room.
- **Score persistence**: Room database (`AppDatabase`, `ScoreDao`,
  `ScoreEntity`) and a `ScoreRepository`/`ScoreRepositoryImpl` pair
  exposing `Score` domain models via `Flow`.
- **Import pipeline**: `ImportScoreUseCase` validates a picked PDF/JPG/PNG,
  copies it into app-private storage (`ScoreFileStorage`, under
  `filesDir/scores/`), determines page count for PDFs, and persists a new
  `Score` row. **It does not run OMR** — `Score.musicXmlPath` is left
  `null` by this use case.
- **Preview screen**: renders PDF pages via `android.graphics.pdf.PdfRenderer`
  with pinch-zoom/pan.
- **Dependency injection**: Hilt modules for the database, repositories,
  qualified coroutine dispatchers, and the OMR module.
- **OMR pipeline integration**: The pipeline is wired from image decode
  through dewarping via `OnnxOmrEngine` → `OmrPageDewarpRunner`.
- **OMR pipeline components (verified with 53 passing unit tests)**:
  - oemer-compatible image preprocessing and tiling.
  - ONNX Runtime tensor preparation and real model inference.
  - prediction-map merging and class-mask extraction.
  - **Dewarping**: staffline geometry estimation, gap-bridging,
    coordinate mapping, and remap application — fully integrated and
    mathematically verified against oemer's behavior.

### In progress
- **OMR pipeline completion**: The pipeline correctly produces a
  `DewarpedPage` (original image + 5 masks, pixel-aligned), but later
  stages (staff/note extraction) are not yet implemented.
- **Instrumented testing**: While unit tests pass, no Android instrumented
  tests exist yet for the OpenCV/ONNX native code paths.

### Planned / not yet implemented
- Staffline extraction, notehead extraction, note grouping, symbol
  classification (clefs/accidentals/rests via SVM classifiers), rhythm
  extraction, and MusicXML generation — none of this exists in the
  codebase yet.
- Editor tab (notation editing) — placeholder screen only.
- Practice tab (pitch detection, cursor tracking, metronome, looping) —
  placeholder screen only. `TarsosDSP` is not present as a dependency.
- Analysis tab (key/chord/cadence/interval/motif detection, overlays) —
  placeholder screen only.
- Settings tab — placeholder screen only.
- Fingering suggestion, practice statistics, MIDI support, multi-page
  side-by-side view, measure bookmarks — not started.

---

## 3. Current OMR pipeline status

The OMR pipeline is implemented and integrated from image decoding through
dewarping. It consists of independently testable Kotlin components under
`app/src/main/java/com/sheetsight/app/data/omr/`.

### 3.1 OMR foundation / architecture
- `OmrEngine` (interface), `OmrResult`, `OmrState`, `OmrRepository`:
  define the contract for running OMR.
- `OnnxOmrEngine`: coordinates the pipeline up to dewarping.
  `recognize()` correctly runs the dewarping pipeline and then throws
  `NotImplementedError` specifically for the missing later phases.
- `OmrPageDewarpRunner`: **NEW** — orchestrates the end-to-end dewarping
  flow (inference → mask extraction → image alignment → dewarp).
- `di/OmrModule.kt` provides `OrtEnvironment` and binds `OmrEngine`.
- `SheetSightApplication.onCreate()` loads OpenCV natively.

### 3.2 Oemer-compatible preprocessing (`data/omr/preprocessing/`)
- `OmrModelSpec`: verified (via direct ONNX graph introspection, not
  assumption) input/output tensor specs for both models — see section 4.
- `CanonicalImageResizer`: reproduces oemer's `resize_image()` — resizes
  toward ~3.675 megapixels, bicubic, aspect-ratio preserved.
- `ImagePreprocessing`: Bitmap → OpenCV `Mat`, deliberately converting to
  BGR (not RGB) to replicate oemer's own BGR-mislabeled-as-RGB
  training-data quirk.

### 3.3 Image tiling
- `SlidingWindowTiler`: 128px stride, edge-clamped (not padded) windows,
  duplicate-origin-at-edges behavior preserved to match oemer exactly.
  Pure coordinate math (`computeOrigins`) is split out from `Mat` cropping
  for JVM-only unit testing.
- `ImageTile`: one tile — crop, origin coordinates, and `Mat` lifecycle
  (`release()`).
- `OmrPreprocessor`: orchestrates decode → resize → tile per model.

### 3.4 ONNX Runtime inference (`data/omr/inference/`)
- `OrtSessionProvider`: lazily creates and caches one `OrtSession` per
  model, loading model bytes from `assets/`.
- `OmrTensorFactory`: packs tile batches into `OnnxTensor` (UINT8 NHWC, no
  float normalization, matching oemer's raw uint8 input).
- `TileInferenceRunner`: runs `session.run()` per model over a tile batch
  and unpacks results into `TilePrediction`s — **real ONNX Runtime
  inference**, not a stub.
- `OmrPageInferenceRunner`: orchestrates preprocessing → inference →
  merge for a single page, returning one `OmrPredictionMap` per model.

### 3.5 Prediction-map merging and class-map extraction
- `PredictionMapMerger` / `OmrPredictionMap`: overlap-averages tile
  predictions into one full-page raw prediction map per model
  (`out[y:y+win,x:x+win] += pred; mask += 1; out /= mask`, matching oemer).
- `ClassMaskExtractor` / `OmrClassMasks`: argmaxes each model's raw
  prediction map into the five boolean masks oemer's downstream stages
  need: `staff`, `symbols`, `stemsRests`, `noteheads`, `clefsKeys`.

### 3.6 Dewarping status (`data/omr/dewarp/`) — integrated and verified
- `StaffMaskMorphology`: vertical dilate + horizontal open on staff mask.
- `StafflineGridDetector` / `StafflineGridGrouper` (+ `ConnectedComponents`):
  detect and group staffline segments.
- `StafflineGridBridger`: bridges gaps in stafflines via linear regression.
- `DewarpMappingBuilder`: extracts control points from the bridged map.
- `DewarpCoordinateInterpolator`: dense coordinate map construction (row-then-column linear interpolation approximation).
- `ImageMaskAligner`: **NEW** — ensures pixel alignment between the
  canonical image and masks.
- `DewarpRemapper`: cubic remap application for image and all 5 masks.
- `DewarpPipeline`: orchestrates the full geometric transformation.

---

## 4. Models

Two ONNX models from the [oemer](https://github.com/BreezeWhite/oemer)
project, referenced by `OmrModelSpec`:

| Model | Purpose | Input | Output |
|---|---|---|---|
| `oemer_staff_and_symbols.onnx` | Staff lines + generic symbols | tensor `input`, UINT8, NHWC `[batch, 256, 256, 3]` | tensor `prediction`, FLOAT32, NHWC `[batch, 256, 256, 3]` (3 classes: background / staff / symbols) |
| `oemer_symbol_detail.onnx` | Stems/rests, noteheads, clefs/accidentals | tensor `input`, UINT8, NHWC `[batch, 288, 288, 3]` | tensor `conv2d_25`, FLOAT32, NHWC `[batch, 288, 288, 4]` (4 classes: background / stems+rests / noteheads / clefs+keys) |

These specs were confirmed by directly inspecting the two `.onnx` graph
files with `onnxruntime`'s Python API, not assumed from oemer's
documentation.

**Where they live:** `OmrModelSpec` expects them at
`app/src/main/assets/models/oemer_staff_and_symbols.onnx` and
`.../oemer_symbol_detail.onnx`. **As of this codebase inspection, that
`assets/models/` directory exists but is empty** — the two `.onnx` files
(≈70MB and ≈37MB) are present at the project root instead, evidently kept
out of `assets/` because of their size. They must be moved/copied into
`app/src/main/assets/models/` before `OrtSessionProvider` can actually
load a session — without that, any code path that reaches
`OrtSessionProvider.sessionFor()` will fail with an asset-not-found error.

Only these two segmentation models exist in the project. oemer's further
symbol/clef/accidental sub-classification depends on separate
**scikit-learn SVM models**, which are **not present in this project** —
see section 10.

---

## 5. Current OMR architecture

| Package | Role |
|---|---|
| `data/omr` (root) | `OmrEngine` contract, `OmrResult`/`OmrState`, `OmrRepository`, `OnnxOmrEngine` — the (currently unimplemented) integration seam. |
| `data/omr/preprocessing` | Decode → canonical resize → BGR conversion → sliding-window tiling → ONNX tensor packing. Pure math split from `Mat`-dependent code wherever possible for JVM testability. |
| `data/omr/inference` | ONNX Runtime session management, real per-tile inference, tile-prediction merging into full-page maps, and argmax class-mask extraction. |
| `data/omr/dewarp` | Staffline geometry detection, gap-bridging, coordinate-map construction, and cubic remap — oemer's `dewarp.py` ported to Kotlin. |
| `di` | Hilt modules: `DatabaseModule`, `DispatcherModule` (qualified `IoDispatcher`/`DefaultDispatcher`/`MainDispatcher`), `OmrModule` (`OrtEnvironment` + `OmrEngine` binding), `RepositoryModule`. |
| `data/local`, `data/repository`, `domain` | Room persistence, file storage, and the `Score`/`ScoreRepository` domain layer — unrelated to OMR, already functional. |
| `ui/*` | Compose screens per tab; only Library and Preview have real functionality today. |

Design pattern used throughout the OMR packages: most classes are plain
`object`s (stateless, pure functions) or `@Inject constructor` singletons
with no `@Provides` binding needed, following the pattern set by
`OmrTensorFactory`/`OmrPreprocessor` in Phase 4.2.

---

## 6. OMR pipeline diagram

```
PDF/JPG/PNG file
      │
      ▼
[Import: ImportScoreUseCase]                       ✅ implemented
      │  (copies file, persists Score row; musicXmlPath left null)
      ▼
──────────────────────────────────────────────────────────────────
  NOT WIRED UP — no code path currently connects Import to OMR
──────────────────────────────────────────────────────────────────
      │
      ▼
[OnnxOmrEngine.recognize()]                        ⚠️  runs dewarp pipeline, then throws
      │
      ▼  (integrated end-to-end from decode to dewarp)
      │
[OmrPreprocessor]                                  ✅ implemented
  resize → BGR convert → sliding-window tile
      │
      ▼
[OmrTensorFactory + TileInferenceRunner]           ✅ implemented
  pack tiles → real ONNX Runtime inference
      │
      ▼
[PredictionMapMerger]                              ✅ implemented
  overlap-average predictions into 2 full-page maps
      │
      ▼
[ClassMaskExtractor]                               ✅ implemented
  argmax → 5 masks: staff, symbols, stemsRests, noteheads, clefsKeys
      │
      ▼
[OmrPageDewarpRunner + ImageMaskAligner]           ✅ implemented
  aligns image to masks, then runs dewarp
      │
      ▼
[DewarpPipeline]                                   ✅ implemented
  morphology → grid detect/group → gap-bridge → mapping → cubic remap
      │
      ▼
[Staffline extraction]                             📋 planned — not started
      │
      ▼
[Notehead extraction]                              📋 planned — not started
      │
      ▼
[Note grouping]                                     📋 planned — not started
      │
      ▼
[Symbol classification (barlines/clefs/sfns/rests,  📋 planned — not started
 needs SVM models not present in this project]
      │
      ▼
[Rhythm extraction]                                 📋 planned — not started
      │
      ▼
[MusicXML generation]                                📋 planned — not started
      │
      ▼
Score.musicXmlPath persisted → Editor / Practice / Analysis tabs
                                (all three: placeholder UI only)
```

---

## 7. Remaining OMR work

In dependency order, all **planned / not yet implemented**:

1. **Wire the existing pipeline together** — `OnnxOmrEngine.recognize()`
   needs to actually call `OmrPreprocessor` → `TileInferenceRunner` →
   `PredictionMapMerger` → `ClassMaskExtractor` → `DewarpPipeline` in
   sequence, and connect it to a real decoded page `Bitmap`.
2. **Finish validating dewarping** — instrumented tests against real
   OpenCV `Mat` data and real page images; a test for the bridging
   merge-success path.
3. **Staffline extraction** — turn the dewarped `staff` mask into
   structured `Staff`/unit-size data (oemer's `staffline_extraction.py`).
4. **Notehead extraction** — turn the dewarped `noteheads` mask into
   individual notehead objects (`notehead_extraction.py`).
5. **Note grouping** — group noteheads into chords via stem direction
   (`note_group_extraction.py`).
6. **Symbol classification** — barlines, clefs, sharps/flats/naturals,
   rests (`symbol_extraction.py`). Requires scikit-learn SVM models that
   are **not currently part of this project** (see section 10) — this is
   a real, unresolved dependency gap, not just unwritten code.
7. **Rhythm extraction** — dots, beams, flags (`rhythm_extraction.py`).
8. **MusicXML generation** — assemble all of the above into a MusicXML
   document (`build_system.py`'s `MusicXMLBuilder`).
9. Only after MusicXML exists: Editor, Practice, Analysis, Fingering,
   Statistics tabs can move past their current placeholder state.

---

## 8. Testing

The OMR pipeline is verified with **53 passing JVM unit tests**
(`app/src/test/...`). These verify the mathematical correctness of
preprocessing, inference merging, mask extraction, and the full dewarping
logic using synthetic and real-structured data.

| Test file | Covers |
|---|---|
| `preprocessing/CanonicalImageResizerTest` | Target-size computation. |
| `preprocessing/SlidingWindowTilerTest` | Tile-origin computation. |
| `inference/ClassMaskExtractorTest` | Argmax correctness and validation. |
| `dewarp/ImageMaskAlignerTest` | **NEW** — source-to-mask size reconciliation. |
| `dewarp/StaffMaskMorphologyTest` | Dilate/erode primitives and border handling. |
| `dewarp/StafflineGridDetectorTest` | Grid detection and filtering. |
| `dewarp/StafflineGridGrouperTest` | Region-based grid grouping. |
| `dewarp/StafflineGeometryEstimatorTest` | End-to-end geometry estimation. |
| `dewarp/SimpleLinearRegressionTest` | OLS fit/predict correctness. |
| `dewarp/DewarpMappingBuilderTest` | Control-point extraction. |
| `dewarp/DewarpCoordinateInterpolatorTest` | Coordinate map interpolation. |
| `dewarp/DewarpRemapperTest` | Cubic remap and mask thresholding. |
| `dewarp/StafflineGridBridgerTest` | Gap bridging logic. |
| `dewarp/DewarpPipelineTest` | End-to-end pipeline orchestration. |

No tests exist yet for: `OmrPreprocessor`, `OmrTensorFactory`,
`OrtSessionProvider`, `TileInferenceRunner`, `PredictionMapMerger`,
`OmrPageInferenceRunner`, or any UI/ViewModel/repository code.

---

## 9. Build and run instructions

Confirmed from the project's own Gradle configuration:

- **Language/toolchain**: Kotlin 2.0.21, AGP 8.5.2, JVM target 17.
- **`compileSdk`/`targetSdk`**: 35. **`minSdk`**: 25 (Android 7.1+).
- **Build**: standard Gradle Android project —
  `./gradlew assembleDebug` to build.
- **Tests**: `./gradlew testDebugUnitTest` — **53 tests passing**.
- **Before running OMR-related code**: copy the two `.onnx` model files
  into `app/src/main/assets/models/` (see section 4) — they are not
  currently there.

---

## 10. Important technical decisions

- **ONNX Runtime Mobile for inference**, not TensorFlow Lite or a
  server-side model — matches the offline-first requirement and oemer's
  own model format.
- **oemer-compatible preprocessing/inference behavior is a hard
  requirement, not a loose inspiration** — resizing, BGR channel order,
  tiling stride/edge behavior, and the tile-merge averaging formula are
  all deliberately bit-for-bit-intent matches to oemer's actual Python
  source (verified by downloading and reading oemer's real source from
  PyPI, not from memory or documentation).
- **Kotlin/Android-native implementation**, no Python/Chaquopy bridge —
  OpenCV Android SDK + ONNX Runtime Mobile only.
- **Documented deviation: `DewarpCoordinateInterpolator` is not a true
  Delaunay-based `griddata` port.** oemer's actual scattered-point
  interpolation uses `scipy.interpolate.griddata(method='linear')`
  (Delaunay triangulation + barycentric interpolation) over an arbitrary
  point cloud. This project instead exploits the specific structure of
  oemer's own control points (one dense row per detected staffline plus
  two fully-dense boundary rows) to do row-then-column linear
  interpolation. This is exact for that structure but is **not** a
  general-purpose replacement for `griddata`. The rationale, recorded in
  `DewarpCoordinateInterpolator`'s own KDoc, is that a from-scratch
  Delaunay implementation couldn't be validated against real data in the
  environment it was written in, and a subtly-wrong triangulation would
  fail silently.
- **Known unresolved dependency gap: SVM classifier models.** oemer's own
  symbol/clef/accidental/rest sub-classification depends on scikit-learn
  SVM models (`sklearn_models/*.model` in the reference project). This
  project has not sourced, converted, or integrated any equivalent for
  Android — symbol classification (section 7, item 6) cannot proceed
  without resolving this first.
- **Known gap: ONNX model assets are not checked into `assets/`** as
  currently laid out in this codebase snapshot — see section 4.
- **No androidTest coverage for OMR code.** Every OMR test is a JVM unit
  test against synthetic data; nothing has exercised the real OpenCV/ONNX
  Runtime native libraries as they'd actually run on-device.
- **`OnnxOmrEngine`/`OmrRepository` are intentionally still stubs.** Their
  `NotImplementedError` throws are not bugs — they're the explicit,
  documented boundary between "components exist" and "pipeline is wired
  up," per each file's own KDoc.
