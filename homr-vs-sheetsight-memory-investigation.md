# Why HOMR/Andromr Runs and SheetSight OOMs — A Source-Grounded Investigation

**Method note:** Every claim about HOMR/Andromr below is backed by a file/line reference into the
actual cloned repositories (`github.com/liebharc/homr` @ current `main`, `github.com/aicelen/Andromr`
@ current `main`), which I fetched and read directly rather than working from description or memory.
Every claim about SheetSight is backed by the Kotlin source already in this project. Figures are
computed with the *actual* sizing formulas from both codebases (see the Python reproduction in Part 3),
not estimated by eye.

Both HOMR and Andromr are AGPL-3.0-licensed. This report treats them purely as a reference for
**architecture and algorithm choices** (tiling stride, output reduction order, crop scope, batching,
quantization) — not as code to copy. That respects the licensing constraint already flagged as an
open blocker in the Andromr/HOMR migration track; adapting any of these *ideas* into SheetSight's
existing Kotlin/ONNX pipeline is a clean-room reimplementation question, independent of this report.

---

## PART 1 — HOMR / Andromr Analysis (source-verified)

### 1.1 Image loading & preprocessing
- `homr/main.py::load_and_preprocess_predictions` — decode via `cv2.imread`, `autocrop`, then a
  **single fixed-width resize**, then CLAHE contrast adjustment. One decode, one resize, no
  intermediate full-resolution duplicate kept around beyond what CLAHE needs in-place.
- `homr/resize.py`:
  ```python
  def calc_target_image_size(width, height):
      target_width = 1920
      ratio = target_width / width
      return target_width, round(height * ratio)
  ```
  This is a **fixed target width**, not a megapixel-count target. For a typical 2481×3508 (A4 @
  300 dpi) source this yields **1920×2715 ≈ 5.21 MP** — actually *larger* than SheetSight/oemer's
  3.68 MP target. **Resolution is not the reason HOMR is lighter** — its canonical page is bigger.
  This single fact rules out "smaller input image" as an explanation and forces the real
  explanation onto tiling/inference/reduction design, which is exactly what Parts 3–4 show.

### 1.2 Segmentation model: one model, six classes, fused per-pixel argmax
`homr/segmentation/inference_segnet.py` (desktop, ONNX Runtime):
```python
self.input_name = self.model.get_inputs()[0].name  # [batch, 3, 320, 320]
...
for out in batch_out:
    data.append(np.argmax(out, axis=0))   # <-- reduced to ONE channel, right here, per tile
```
- **One** ONNX session covering **all six classes** (background, stems/rests, notehead,
  clefs/keys, staff, symbols) in one forward pass — where oemer/SheetSight run **two separate
  models** (`staff_and_symbols`, 3 channels; `symbol_detail`, 4 channels), each needing its own
  full sliding-window sweep of the page.
- `np.argmax(out, axis=0)` runs **immediately after each batch**, collapsing that tile's raw
  `(6, 320, 320)` float output down to a `(320, 320)` integer class map **before it is ever added
  to the accumulation list**. The multi-channel float tile data never survives past the batch that
  produced it.
- On Android, this argmax is pushed **into the model graph itself**. Andromr's TFLite model is
  literally named `segnet_308_int8.tflite` (`Andromr/homr/segmentation/config.py:5`), and its
  Java-side wrapper returns the result pre-reduced:
  ```python
  # Andromr/homr/segmentation/inference_segnet.py:38-44
  result = self.segnet.runInt(float_buffer)
  return np.array(result, dtype=np.int64).reshape((1, image.shape[2], image.shape[3]))
  ```
  `runInt` — the model's *fused output is already the discrete class map*, not per-class scores.
  Android never materializes the raw 6-channel float tensor in Python/Kotlin at all.

### 1.3 Zero-overlap tiling
```python
# homr/main.py:86 and Andromr/homr/main.py:81
result = extract(preprocessed, img_path, step_size=320, ...)   # win_size default = 320
```
`step_size == win_size == 320` → **no overlap**. `merge_patches` (same file) is written to handle
generic overlap, but with `step == window` every interior pixel is touched by exactly one tile;
only the final clamped row/column of tiles can overlap their neighbor, the same edge-clamp
convention SheetSight's `SlidingWindowTiler` also uses. Compare to `SlidingWindowTiler.DEFAULT_STEP_SIZE
= 128` against **256- and 288-px windows** — 50% and ~56% overlap respectively. Overlap directly
multiplies tile count, inference calls, and (critically) how much raw tile data must be held before
reduction.

### 1.4 Tiles are generated lazily, one batch at a time — never materialized for the whole page
```python
# homr/segmentation/inference_segnet.py: inference()
batch: list[NDArray] = []
for y_loop in range(0, max(h, win_size), step_size):
    for x_loop in range(0, max(w, win_size), step_size):
        hop = extract_patch(image, y, x, win_size)
        batch.append(hop)
        if len(batch) == batch_size:
            batch_out = model.run(np.stack(batch, axis=0))
            for out in batch_out:
                data.append(np.argmax(out, axis=0))
            batch.clear()          # <-- input batch discarded immediately
```
At any instant, at most `batch_size` (8 desktop, **hard-coded to 1 on Android** — see
`Andromr/homr/segmentation/inference_segnet.py:194`, `batch_size=1,  # Fixed batch size`) raw input
tiles exist in memory. The **input** tiles for the whole page are never simultaneously resident —
only the running list of already-argmaxed, single-channel outputs accumulates, and that list is
~4–7× smaller per entry than a raw multi-channel float tile (see Part 3 for the arithmetic).

### 1.5 Batch size, precision, execution provider
- Desktop default `batch_size=8` (`inference_segnet.py:242`) — same order of magnitude as
  SheetSight's `TileInferenceRunner.DEFAULT_BATCH_SIZE = 8`, so batching itself is *not* a
  differentiator.
- **Android forces `batch_size=1`** — no batching at all, trading throughput for the smallest
  possible peak native buffer per inference call.
- **Android's segmentation model is INT8-quantized** (`segnet_308_int8.tflite`), not FP32 ONNX.
  Quantized weights are ~4× smaller resident, and TFLite's int8 kernels typically also shrink
  intermediate activation buffers relative to FP32.
- ONNX Runtime session objects use **IO binding** created once per session and reused for every
  batch: `self.io_binding = self.model.io_binding()` (`inference_segnet.py:82`,
  `encoder_inference.py`, `decoder_inference.py`). `run_with_iobinding` avoids Runtime allocating a
  brand-new output-tensor object on every call — SheetSight's `TileInferenceRunner.run()` instead
  calls plain `session.run(...)` fresh per mini-batch, letting ONNX Runtime allocate new output
  tensors every batch.
- Execution providers (`homr/onnx_providers.py`): CUDA on desktop GPU, CoreML `MLProgram` on Apple
  Silicon (segnet + optional encoder only — the decoder never runs on CoreML because of a
  documented dynamic-KV-cache crash in ORT 1.26), otherwise CPU EP. **No GPU delegate is used on
  Android** — the mobile path is TFLite CPU (int8) plus a native ONNX-Runtime-Mobile encoder/decoder
  written directly in Java/Kotlin (`com.aicelen.andromr.Staff2Score`, `com.aicelen.andromr.LiteRTModel`,
  invoked from Python via `pyjnius.autoclass`).

### 1.6 The symbol-recognition stage runs on tiny per-staff crops, never the whole page
This is the largest *structural* difference and it sits entirely downstream of segmentation:
```python
# homr/staff_parsing.py: parse_staff_image() -> prepare_staff_image() -> dewarp_staff_image()
# homr/staff_parsing_tromr.py: parse_staff_tromr()
```
After segmentation produces the page-level masks, HOMR crops **one staff system at a time**
(`_calculate_region`, `prepare_staff_image`) into a small region padded to
`Config.max_height=256 × Config.max_width=1280`, single-channel grayscale
(`homr/transformer/configs.py:97-99`). *Every* subsequent step — dewarping
(`dewarp_staff_image`), the ConvNeXt-based transformer encoder, and the autoregressive decoder —
operates on that small per-staff crop, not on the full page. Compare to SheetSight's
`DewarpPipeline`, which remaps the **entire canonical page** (all 5 boolean masks *and* every
original image channel) through cubic interpolation in one call — a page-sized operation, not a
staff-sized one.

### 1.7 The transformer stage: tiny fixed-size input, KV-cache decoding, one session for the whole page
- Encoder input: `(1, 1, 256, 1280)` grayscale, patch-embedded ConvNeXt (`configs.py`), one
  `ort.InferenceSession` created **once** and reused via a module-level global
  (`homr/staff_parsing_tromr.py: inference: Staff2Score | None = None`) across every staff on the
  page — not recreated per staff.
- Decoder: `max_seq_len = 608` tokens, autoregressive with an explicit KV cache
  (`transformer/decoder_inference.py: init_cache()`), so each of the 608 steps binds only the
  *single new token* plus the cached keys/values — **O(1) memory per step**, not O(n²) or
  O(sequence-so-far). `io_binding` is reused across all 608 steps of all staffs.
- On Android, the entire encoder+decoder loop is implemented in Java/Kotlin
  (`Staff2Score.java`, via `pyjnius`), so none of the per-token Python↔native marshaling overhead
  that the desktop path pays (numpy array construction, GIL, object churn) exists on-device at all.

### 1.8 Session lifecycle and cleanup
- `Andromr/homr/transformer/tromr.py::get_tromr()` keeps one global `model` and only tears it down
  (`model.unload_model()`) when the user changes the thread-count setting — i.e., explicit,
  deliberate model lifecycle management, not implicit GC-and-hope.
- Segnet similarly is a module-level singleton reloaded only when `appdata.threads` changes
  (`Andromr/homr/segmentation/inference_segnet.py:153`).
- This is **the same pattern** SheetSight already uses (`OrtSessionProvider`'s
  `getOrPut`-cached, process-lifetime sessions) — session reuse is *not* a point of difference.

---

## PART 2 — SheetSight Analysis (from the actual Kotlin source in this project)

### 2.1 Canonical resize
`CanonicalImageResizer` targets **3,000,000–4,350,000 px** (≈3.675 MP average), aspect-preserving,
bicubic. For a 2481×3508 source this resolves to **1612×2280 (3.676 MP)** — smaller than HOMR's
1920-wide target (5.21 MP). Confirms resolution is not the culprit; if anything SheetSight's
canonical page is already *smaller*.

### 2.2 Two full sliding-window passes, 50%+ overlap, over the whole page
`OmrModelSpec` defines two independent ONNX models (`STAFF_AND_SYMBOLS`, 256×256×3 in / 3 ch out;
`SYMBOL_DETAIL`, 288×288×3 in / 4 ch out). `OmrPreprocessor.preprocess()` builds **both** models'
full tile sets in one call:
```kotlin
val tilesByModel = OmrModelSpec.entries.associateWith { spec ->
    SlidingWindowTiler.tile(resized, spec.windowSize)
}
```
`SlidingWindowTiler.DEFAULT_STEP_SIZE = 128` against 256/288 windows means every interior pixel is
covered by **~4 overlapping tiles**. For a 1612×2280 canonical page this yields **234 tiles per
model, 468 tiles total** (computed via the actual `computeOrigins` algorithm — see Part 3).

### 2.3 Raw multi-channel float tile data is fully materialized before any reduction
`TileInferenceRunner.run()` returns a `List<TilePrediction>` where each `TilePrediction.values` is
the model's **raw, un-argmaxed** per-pixel channel-score `FloatArray` (documented explicitly in its
own KDoc as "no argmax/threshold applied"). `PredictionMapMerger.merge()` — the reduction step —
only runs **after every tile for that model has already been produced and appended to the list**.
There is no equivalent of HOMR's "argmax immediately, discard the raw batch" step anywhere in this
path; per-pixel argmax happens only later, in `ClassMaskExtractor`, on the *already-merged*
full-page prediction map.

`OmrSmokeTestRunner`'s own KDoc documents that this was **already partially fixed once**: model 1's
raw tile list used to stay alive alongside model 2's inference and was corrected to merge-then-discard
before model 2 starts. That fix helps, but it does not change the *shape* of the problem — the
single largest tile list (`SYMBOL_DETAIL`, 4 channels × 288² × ~234 tiles) is still fully
materialized in memory before it is ever reduced.

### 2.4 Tiling materializes every tile for the whole page upfront, not lazily per batch
`SlidingWindowTiler.tile()` computes **every** origin for the whole canonical page in one call and
clones a native `Mat` for each (`Mat(padded, Rect(...)).clone()`), returning the complete
`List<ImageTile>` before any inference begins. `OmrPageInferenceRunner` only releases these Mats in
a `finally` block after **both** models have finished inference. There is no batch-sized "produce,
infer, discard" loop analogous to HOMR's; input tiles for the *entire page, both models* are
resident simultaneously as native (off-heap) memory for the whole inference phase.

### 2.5 No IO-binding reuse
`TileInferenceRunner.run()` calls plain `session.run(mapOf(...))` per mini-batch inside a `.use {}`
block — a fresh output-tensor allocation every batch call, not a bound/reused output buffer.

### 2.6 Dewarping operates on the whole page, all five masks, at once
`DewarpPipeline.run()` takes the **entire canonical page's** image channels and all five
`OmrClassMasks` layers and remaps every one of them via cubic interpolation in a single call,
producing a full second copy of each (the original `imageChannels`/`masks` plus the
`dewarpedChannels`/`dewarpedMasks`, both page-sized, alive simultaneously inside `DewarpedPage`
until GC). There is no equivalent to HOMR's "dewarp only this one small staff crop."

### 2.7 Estimated peak memory, by stage (1612×2280 canonical page, both models)

| Stage | Structure | Approx. size |
|---|---|---|
| Input tiles, both models (native Mats) | `List<ImageTile>` × 2 | ~46 MB + ~58 MB = **~104 MB** |
| `SYMBOL_DETAIL` raw `TilePrediction` list | 234 tiles × 288²×4ch×4B | **~310.5 MB** |
| `STAFF_AND_SYMBOLS` raw `TilePrediction` list | 234 tiles × 256²×3ch×4B | **~184.0 MB** |
| Merged prediction maps (both models) | sum + count arrays | ~44 MB + ~59 MB + ~29 MB ≈ **~132 MB** |
| 5 boolean class masks | `BooleanArray` × 5 | ~18.4 MB |
| Canonical image channels (×2, orig + dewarped) | `FloatArray` × 3ch × 2 | ~88.2 MB |
| Dewarp intermediates (group/grid maps) | several `IntArray`s, page-sized | ~15–30 MB each, several alive |

The single largest number in this table — **~310.5 MB for the `SYMBOL_DETAIL` tile list alone** —
is already within reach of the documented hard 512 MB heap cap on its own; add anything else in
this table that's concurrently alive (native input Mats, the previous model's merged output, JVM/
Compose/Bitmap baseline) and the crash the memory log already places at
`TileInferenceRunner.extractPredictions` during `MODEL2_INFERENCE` is exactly where the arithmetic
says it should happen.

---

## PART 3 — Direct Comparison (same 2481×3508 source page, real formulas from both codebases)

*(Reproduction script output; both canonical-size and tile-count formulas are each project's own
actual code, not approximations.)*

| Stage | HOMR / Andromr | SheetSight | Difference | Perf. impact | Memory impact |
|---|---|---|---|---|---|
| Canonical resize target | Fixed 1920 px width → **1920×2715 (5.21 MP)** | Fixed **3.0–4.35 MP** range → 1612×2280 (3.68 MP) | HOMR's page is *larger* | Neutral–slightly slower for HOMR | Neutral (rules out "resolution" as the cause) |
| # segmentation models | **1** model, 6 fused classes | **2** independent models (3 ch + 4 ch) | 2× sessions, 2× full sweeps | 2× tiling + inference work | 2× resident ORT sessions/weights |
| Tile window/stride | 320/320, **0% overlap** | 256/128 & 288/128, **50–56% overlap** | ~4.3× more tiles in SheetSight (468 vs 54) | ~4.3× more inference calls | ~4.3× more raw tile bytes before reduction |
| Per-tile output reduction timing | **argmax immediately per tile**, even fused into the TFLite graph on Android | argmax deferred until **after full-page merge** | Reduction happens ~6–8× later in the pipeline | Same total ops, different order | Raw multi-channel float tile list resident (**~184–310 MB**) vs argmaxed int map list (**~44 MB for all 54 tiles**) |
| Tile materialization | **Lazy**, batch-of-`batch_size` generated/discarded on the fly | **Eager**, all tiles for the whole page (both models) built upfront, released only after both models finish | N/A | N/A | Input tiles for entire page × both models resident simultaneously (~104 MB native) vs ≤8 (or 1 on Android) tiles at a time (~10 MB or less) |
| Batch size | 8 (desktop), **1 (Android, hard-coded)** | 8 (fixed) | Android trades speed for a minimal per-call footprint | Android slower per call | Android: smallest possible transient spike |
| Precision / quantization | **INT8 TFLite** on Android | FP32 ONNX everywhere | ~4× smaller weights + smaller activations on Android | Faster on mobile CPU int8 kernels | ~4× less resident model memory on Android |
| IO/output buffer handling | `io_binding()` created once, reused every call | Fresh `session.run()` output tensor every mini-batch | N/A | More GC/alloc churn in SheetSight | Repeated small allocation/dealloc churn, not the dominant factor but adds up over ~30 batches |
| Symbol/structure stage scope | **Per-staff crop** (256×1280 gray) — dewarp, encode, decode all operate on one staff at a time | **Whole page**, all 5 masks + image channels, dewarped together in one call | Structural: SheetSight hasn't reached notehead/symbol stages yet, but its *existing* dewarp stage already works at full-page scope | Dewarp cost scales with page size in SheetSight, with staff-crop size in HOMR | Full-page float/boolean arrays × 2 (orig+dewarped) alive at once in SheetSight vs a few hundred KB per staff crop in HOMR |
| Sequence generation | ConvNeXt encoder + KV-cache autoregressive decoder, per staff, O(1) memory/step | *Not implemented yet* — no equivalent stage exists in SheetSight | N/A | N/A | N/A (future risk: if implemented at whole-page scope instead of per-staff, would reproduce the same class of problem) |
| Session lifecycle | Global singleton, explicit `unload_model()` on settings change | `OrtSessionProvider` — cached singleton per model, same pattern | **No material difference** | — | — |

---

## PART 4 — Root Cause Analysis (ranked, all backed by the code above)

1. **Highest impact — raw, un-reduced, multi-channel tile data is fully materialized for the whole
   page before any reduction.** SheetSight's `TileInferenceRunner` returns every tile's full
   float32 channel-score vector and appends it to a page-spanning `List<TilePrediction>`;
   `PredictionMapMerger` only reduces it afterward. HOMR argmaxes every tile the instant it comes
   back from the model (`np.argmax(out, axis=0)` right inside the batch loop in
   `inference_segnet.py`), and on Android that argmax is fused directly into the TFLite graph so
   the raw tensor never even reaches Python. This single ordering difference is worth **~4–7× less
   memory per tile held** (a 288×288×4-channel float tile is 1.33 MB; the equivalent argmaxed int
   map is ~0.2–0.8 MB depending on dtype) **multiplied by 4.3× more tiles** (see #2) — this is the
   root cause the ~310.5 MB `SYMBOL_DETAIL` figure in Part 2 traces back to, and it lines up
   exactly with the documented crash site (`TileInferenceRunner.extractPredictions`,
   `MODEL2_INFERENCE`).

2. **Second highest — 50%+ tile overlap vs. HOMR's exact tiling.** `DEFAULT_STEP_SIZE = 128`
   against 256/288-px windows quadruples the tile count relative to HOMR's `step_size == win_size`
   (468 vs. 54 tiles for the same-sized source page). Every downstream cost — inference calls,
   raw-tile bytes before reduction, merge-array traffic — scales with this multiplier. This
   compounds directly with #1 rather than being independent of it.

3. **Third — two full models/sweeps instead of one.** oemer's architecture (which SheetSight
   ported faithfully) splits segmentation into two checkpoints with different windows, each
   requiring its own complete tiling + inference + merge pass. HOMR fused this into one 6-class
   model and one sweep. This roughly doubles the total tiling/inference/merge work and means two
   full-size ONNX sessions are resident instead of one.

4. **Fourth — eager, whole-page tile materialization instead of lazy batch generation.**
   Independent of overlap or channel count: SheetSight builds the *entire* tile list for *both*
   models before any inference call happens and keeps the input tiles alive (native memory) until
   both models finish. HOMR generates one batch (8, or 1 on Android), infers, and discards the
   raw batch before generating the next. This is a smaller contributor than #1–#3 in raw byte
   count (input tiles are uint8, not float32), but it is a straightforward, structural fix with no
   accuracy or architecture cost.

5. **Fifth — whole-page dewarping vs. per-staff-crop dewarping.** This doesn't explain the
   confirmed OOM (which occurs earlier, in tile inference/merging), but it is the same
   *class* of problem recurring downstream: `DewarpPipeline` remaps 5 full-page masks and every
   image channel at once, keeping both pre- and post-dewarp copies alive. HOMR never dewarps more
   than one staff system (a few hundred KB) at a time. If SheetSight's pipeline is pushed further
   (staffline/notehead/symbol stages) at whole-page scope the way dewarping currently is, this
   becomes the *next* OOM, not a first one.

6. **Lower impact — precision/quantization (FP32 vs. Android's INT8 TFLite) and IO-binding
   reuse.** Real, measurable, and code-confirmed (`segnet_308_int8.tflite`;
   `io_binding()` reused across calls) — but both are roughly a constant-factor (~4×, and a
   modest allocation-churn reduction respectively) applied evenly across an already-multiplied
   tile count. They matter for a mobile port's steady-state footprint and speed, but they are not
   what turns a working pipeline into a crashing one; #1–#4 are.

7. **Explicitly not the primary cause, per your instruction and confirmed by evidence: the
   runtime itself (ONNX Runtime vs. TensorFlow Lite).** Desktop HOMR uses ONNX Runtime throughout,
   with the identical batch-size-8 default SheetSight uses, and still avoids the OOM class of
   failure — because of #1–#4, all of which are pipeline/algorithm decisions orthogonal to which
   inference runtime is underneath. Swapping SheetSight's ONNX Runtime for TensorFlow Lite without
   changing tiling stride, reduction order, or model count would not, by itself, fix the confirmed
   crash.

---

## PART 5 — Recommendations, ranked by benefit

### A. Can be adapted directly (algorithm/ordering changes, no new architecture)
1. **Argmax per tile immediately after inference, before adding to any accumulator.** Change
   `TileInferenceRunner`/`PredictionMapMerger` so each tile's raw `channels`-length float output is
   reduced to a single class-index (or a small set of boolean flags) the moment it comes back from
   `session.run()`, and only that reduced form is retained. This directly removes the largest
   single item in the memory table.
   *RAM reduction:* removes the ~184–310 MB raw tile lists almost entirely (drops to the low tens
   of MB, similar to HOMR's ~44 MB for all argmaxed tiles).
   *Speed:* neutral to slightly faster (less data to move/allocate).
   *Effort:* moderate — changes the shape of data flowing between `TileInferenceRunner` and
   `PredictionMapMerger`, but does not touch model files or tiling geometry. Note this does change
   *what* gets overlap-averaged (discrete class votes instead of continuous scores, as HOMR itself
   does) — that's a deliberate, documented tradeoff HOMR itself made, not a hidden regression.

2. **Generate tiles lazily, per-batch, instead of eagerly for the whole page.** Replace
   `SlidingWindowTiler.tile()`'s eager `List<ImageTile>` with a sequence/iterator that crops,
   yields, and lets the caller release each batch before the next is produced — mirroring HOMR's
   `batch.append(hop); if len(batch)==batch_size: infer(); batch.clear()`.
   *RAM reduction:* removes the ~104 MB of simultaneously-resident native input tiles (both
   models), replaced by ≤8 tiles' worth (a few MB) at a time.
   *Speed:* neutral.
   *Effort:* low–moderate — mostly a control-flow change in `OmrPreprocessor`/`OmrPageInferenceRunner`.

3. **Reuse an ONNX Runtime `OrtSession`'s output buffer via IO binding instead of a fresh
   `session.run()` per batch**, matching HOMR's `io_binding()` pattern.
   *RAM reduction:* small (reduces allocation churn, not peak).
   *Speed:* modest improvement, fewer GC pauses during the ~30 batches/page.
   *Effort:* low — `OnnxTensor`/`OrtSession` in ONNX Runtime for Java expose the same IO-binding API.

4. **Reduce tile overlap toward HOMR's near-zero-overlap tiling**, if the underlying
   `oemer` checkpoints tolerate a larger stride without accuracy loss (this needs empirical
   validation against oemer's own trained stride assumptions — oemer's Python source uses
   `step_size=128` deliberately, so this is not a free change, listed here for completeness).
   *RAM/speed:* up to ~4× fewer tiles if validated safe.
   *Effort:* moderate, requires accuracy testing since it changes what the checkpoints were tuned
   against.

### B. Requires architectural redesign
1. **Fuse the two segmentation models into one multi-class model**, as HOMR did. SheetSight's
   `oemer_staff_and_symbols.onnx`/`oemer_symbol_detail.onnx` are oemer's own separate checkpoints;
   merging them the way HOMR did requires either retraining/converting a new joint model or
   accepting HOMR's own model outright (which is the Andromr/HOMR migration already tracked
   separately, gated on the AGPL licensing decision).
   *RAM/speed:* roughly halves total segmentation-stage tiling/inference/merge cost.
   *Effort:* high — not a refactor of existing code, a model-level decision already flagged as a
   pending blocker.

2. **Scope dewarping (and any future staffline/notehead/symbol stage) to per-staff-crop instead
   of whole-page**, as HOMR's `staff_parsing.py` does. This requires staff detection to run
   *before* dewarping (today `DewarpPipeline` operates on the whole page precisely because no
   staff-region concept exists yet upstream of it) — a real sequencing change to the pipeline, not
   a local optimization.
   *RAM/speed:* would keep every downstream stage's peak memory proportional to one staff system
   instead of the whole page — the single largest long-term win, but only relevant once
   staffline/notehead/symbol stages are actually built.
   *Effort:* high — changes pipeline stage order, not just implementation details.

3. **INT8-quantize SheetSight's own ONNX models for the Android target**, mirroring Andromr's
   `segnet_308_int8.tflite`. Requires re-quantizing oemer's existing FP32 checkpoints (post-training
   quantization) and validating accuracy doesn't regress.
   *RAM/speed:* ~4× smaller resident weights, likely faster on-device.
   *Effort:* moderate-high — a model-conversion and validation task, not a code change.

### C. Should NOT be copied
1. **Hard-coding `batch_size=1`.** Andromr does this because it targets the least capable Android
   devices with a TFLite CPU delegate and no batching benefit on-device; SheetSight already runs a
   sensible `batch_size=8` with a documented rationale (bounding native/RSS memory while retaining
   throughput). Dropping to 1 would only be worth revisiting *after* items A.1–A.2 land and only if
   real-device profiling still shows headroom needed.
2. **Running the transformer's hot loop in native Java/Kotlin via a custom JNI bridge equivalent
   to Andromr's `pyjnius.autoclass`/`Staff2Score.java`.** This is a Kivy/Python-interop workaround
   specific to Andromr being a Python app wrapped for Android; SheetSight is already pure
   Kotlin/ONNX Runtime Mobile, so this problem (cross-language marshaling overhead per decode step)
   does not exist for SheetSight and there is nothing to port here.
3. **Adopting HOMR's fixed-1920-width canonical resize verbatim.** It happens to produce a
   *larger* image than SheetSight/oemer's target for common page aspect ratios (5.21 MP vs.
   3.68 MP) — copying it would work against memory goals, not for them. If anything, SheetSight's
   existing 3–4.35 MP target is already better-tuned for this constraint than HOMR's own choice.

---

## PART 6 — Migration Feasibility (without replacing the whole oemer pipeline)

**Yes — items A.1, A.2, and A.3 can be adopted without touching the oemer model files, the Room
schema, the UI, or any other unrelated system, and without waiting on the pending AGPL/Andromr
licensing decision**, because none of them require copying HOMR/Andromr code or reusing their model
weights — they are *algorithmic ordering changes* applied to SheetSight's existing, already-Kotlin,
already-oemer-compatible pipeline:

- **`TileInferenceRunner.kt`** — change `extractPredictions()` to reduce each tile's raw
  `channels`-length float vector to a class index (or a small fixed set of boolean flags) before
  constructing what gets accumulated, instead of returning the full raw `FloatArray` per tile.
- **`PredictionMapMerger.kt` / `OmrPredictionMap`** — adapt the merge step to operate on
  discrete per-tile class votes (majority/count-based, matching HOMR's own averaged-index
  approach) instead of continuous per-channel float sums, OR keep continuous merging but make it
  operate on a *reduced* per-tile representation (e.g., store one prediction score for a small set
  of target classes instead of all raw channels) if you want to preserve the current
  continuous-averaging behavior more closely than HOMR's approach does.
- **`SlidingWindowTiler.kt` / `OmrPreprocessor.kt` / `OmrPageInferenceRunner.kt`** — replace the
  eager `List<ImageTile>` construction with a generator/sequence that produces one batch at a
  time and lets the caller release it before producing the next, mirroring HOMR's
  `batch.append/if len==batch_size: infer/batch.clear()` loop.
- **`OrtSessionProvider.kt` / `TileInferenceRunner.kt`** — add an `OnnxTensor`-based IO-binding
  reuse path per session, mirroring `io_binding()`.

**Item B (fusing the two models, or scoping dewarp to per-staff-crop) is not a "migrate a
technique" question** — it either requires a new/converted model (a licensing- and
accuracy-gated decision already tracked separately as the Andromr/HOMR migration) or a pipeline
resequencing (staff detection before dewarping) that is a genuine architectural change, not
something that can be dropped into the existing file set incrementally. These should stay on the
already-tracked "three unresolved migration blockers" list rather than being folded into an
incremental OOM fix.

**What additional information would sharpen this further:** real on-device memory logging from
`OmrSmokeTestRunner` after item A.1 (argmax-before-accumulate) is implemented, to confirm the
~184–310 MB reduction actually materializes as measured RSS/heap on a real low-end target device,
per the project's own "on-device measurement is the source of truth" principle. No additional
HOMR/Andromr source was needed beyond what's cited above — every figure in Parts 2–4 traces to a
specific line in the cloned repositories or SheetSight's own files.
