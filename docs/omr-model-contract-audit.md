# OMR model contract audit

The bundled ONNX graphs, not historical comments, are the runtime source of
truth. `OmrModelContractVerifier` fails session creation if an input/output
name, type, layout, spatial dimension, or channel count differs from
`OMR_MODEL_MANIFEST.json`.

## Upstream parity

The audit compared SheetSight against BreezeWhite/oemer 0.1.8's
`inference.py` and `ete.py` behavior:

- Non-GIF input is decoded by OpenCV as BGR and handed to Pillow without a
  channel swap. SheetSight reproduces those BGR bytes from Android RGBA.
- The model receives raw `UINT8` pixels. No mean/std or 0–1 normalization is
  applied.
- Windows are NHWC and square: 256 for staff/symbol and 288 for symbol detail.
- Raw predictions are merged per channel, then `argmax(axis=-1)` selects the
  class. Channel identities match `ete.py::generate_pred`.
- Canonical resizing preserves aspect ratio and targets the midpoint of the
  upstream 3.0–4.35 megapixel range.

## Intentional divergence requiring evaluation

Upstream inference defaults to stride 128. SheetSight production currently
uses one model-window stride because a same-page device experiment increased
tile count and runtime sharply without changing detected noteheads or accepted
bar lines. That count-only experiment does not prove seam equivalence. Stride
128 remains selectable in the diagnostic runner and must be compared using the
same labelled corpus and exported prediction/detection artifacts before this
divergence is either retained or removed.

OpenCV `INTER_CUBIC` and Pillow's bicubic implementation are the same class of
resampler but are not claimed to be byte-identical. Resolution experiments are
therefore evaluated end-to-end rather than treated as proof of pixel parity.

## Local graph identities

| Model | SHA-256 |
| --- | --- |
| staff and symbols | `37512E858731096439746F60B377C049F07055B4A23EC6EB9A178CE92CFBA174` |
| symbol detail | `ED2E1A86EA75712EE6CDC740E96F7A36753543CF9BB980227C071C9256D9D82E` |

The hashes identify the audited assets; they do not by themselves claim that
the files are byte-identical to a particular upstream release download.
