package com.sheetsight.app.data.omr.preprocessing

/**
 * Verified input-tensor specification for one of oemer's two ONNX
 * segmentation checkpoints, bundled under `app/src/main/assets/models/`.
 *
 * Per the Phase 4.2 requirement to treat the models themselves as the
 * source of truth, these values come from inspecting the actual `.onnx`
 * graphs (`onnx.checker` + graph input introspection) rather than from
 * oemer's own Python-side `metadata.pkl` — Android never reads that file:
 *  - staff_and_symbols: input "input", UINT8, NHWC [batch, 256, 256, 3];
 *                       output "prediction", FLOAT32, NHWC [batch, 256, 256, 3]
 *  - symbol_detail:     input "input", UINT8, NHWC [batch, 288, 288, 3];
 *                       output "conv2d_25", FLOAT32, NHWC [batch, 288, 288, 4]
 *
 * Output specs (Phase 4.4) come from the same graph-introspection approach
 * as the input specs above — symbol_detail's output was explicitly
 * unverified as of Phase 4.2 and is now confirmed: 4 channels, not 3, and
 * named "conv2d_25" rather than "prediction". Post-processing beyond raw
 * per-model prediction maps (argmax/threshold class maps, staff/symbol
 * interpretation) remains out of scope here — see
 * [com.sheetsight.app.data.omr.inference.PredictionMapMerger].
 *
 * @property assetPath Path under `app/src/main/assets/` to the ONNX file.
 * @property inputTensorName Name of the model's single input tensor.
 * @property outputTensorName Name of the model's single output tensor.
 * @property windowSize Side length (pixels) of the square NHWC input the
 *   model expects — corresponds to oemer's `win_size` (`input_shape[1]`
 *   in `oemer/inference.py`, confirmed against `train.py`'s `win_size=256`
 *   / `win_size=288` for these two checkpoints respectively).
 * @property outputChannels Number of channels in the model's NHWC output
 *   — the per-pixel raw prediction/class-score vector length.
 */
enum class OmrModelSpec(
    val assetPath: String,
    val inputTensorName: String,
    val outputTensorName: String,
    val windowSize: Int,
    val outputChannels: Int
) {
    /** oemer's "unet_big" checkpoint: staff lines + symbols. */
    STAFF_AND_SYMBOLS(
        assetPath = "models/oemer_staff_and_symbols.onnx",
        inputTensorName = "input",
        outputTensorName = "prediction",
        windowSize = 256,
        outputChannels = 3
    ),

    /** oemer's "seg_net" checkpoint: stems/rests, noteheads, clefs/keys. */
    SYMBOL_DETAIL(
        assetPath = "models/oemer_symbol_detail.onnx",
        inputTensorName = "input",
        outputTensorName = "conv2d_25",
        windowSize = 288,
        outputChannels = 4
    );

    companion object {
        /**
         * Channel count of every oemer *input* tile (always 3, RGB/BGR —
         * see [ImagePreprocessing] for byte order). Not to be confused
         * with [outputChannels], which varies per model.
         */
        const val CHANNELS: Int = 3
    }
}