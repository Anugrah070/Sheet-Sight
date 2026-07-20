package com.sheetsight.app.data.omr.inference

import com.sheetsight.app.data.omr.preprocessing.OmrModelSpec

/**
 * Converts the two models' raw, merged [OmrPredictionMap]s into the five
 * boolean masks oemer's downstream stages (staffline extraction, notehead
 * extraction, symbol extraction, rhythm extraction) each consume.
 *
 * Reproduces `oemer/ete.py`'s `generate_pred()`: per-pixel **argmax**
 * across the channel dimension, then one boolean mask per class of
 * interest — not the unused `symbol_thresholds` values, which the real
 * oemer pipeline defines but never actually applies (`manual_th=None` is
 * what's passed at the real call site).
 *
 * Both [OmrPredictionMap] inputs are read-only here — the raw prediction
 * data isn't discarded or mutated, only argmaxed into these masks, since
 * later phases may still need the raw values.
 */
object ClassMaskExtractor {

    /** Class indices for [OmrModelSpec.STAFF_AND_SYMBOLS]'s 3-channel output. */
    private object StaffAndSymbolsClass {
        const val BACKGROUND = 0
        const val STAFF = 1
        const val SYMBOLS = 2
    }

    /** Class indices for [OmrModelSpec.SYMBOL_DETAIL]'s 4-channel output. */
    private object SymbolDetailClass {
        const val BACKGROUND = 0
        const val STEMS_RESTS = 1
        const val NOTEHEADS = 2
        const val CLEFS_KEYS = 3
    }

    /** Convenience overload for [OmrPageInferenceRunner.run]'s per-model result map. */
    fun extract(predictionsByModel: Map<OmrModelSpec, OmrPredictionMap>): OmrClassMasks {
        val staffAndSymbols = predictionsByModel[OmrModelSpec.STAFF_AND_SYMBOLS]
            ?: error("Missing prediction map for ${OmrModelSpec.STAFF_AND_SYMBOLS}")
        val symbolDetail = predictionsByModel[OmrModelSpec.SYMBOL_DETAIL]
            ?: error("Missing prediction map for ${OmrModelSpec.SYMBOL_DETAIL}")
        return extract(staffAndSymbols, symbolDetail)
    }

    /**
     * Argmaxes [staffAndSymbols] (must be [OmrModelSpec.STAFF_AND_SYMBOLS]'s
     * 3-channel output) and [symbolDetail] (must be
     * [OmrModelSpec.SYMBOL_DETAIL]'s 4-channel output) into [OmrClassMasks].
     */
    fun extract(staffAndSymbols: OmrPredictionMap, symbolDetail: OmrPredictionMap): OmrClassMasks {
        require(staffAndSymbols.channels == OmrModelSpec.STAFF_AND_SYMBOLS.outputChannels) {
            "staffAndSymbols must have ${OmrModelSpec.STAFF_AND_SYMBOLS.outputChannels} channels, " +
                    "got ${staffAndSymbols.channels}"
        }
        require(symbolDetail.channels == OmrModelSpec.SYMBOL_DETAIL.outputChannels) {
            "symbolDetail must have ${OmrModelSpec.SYMBOL_DETAIL.outputChannels} channels, " +
                    "got ${symbolDetail.channels}"
        }
        require(staffAndSymbols.width == symbolDetail.width && staffAndSymbols.height == symbolDetail.height) {
            "staffAndSymbols (${staffAndSymbols.width}x${staffAndSymbols.height}) and symbolDetail " +
                    "(${symbolDetail.width}x${symbolDetail.height}) must share one page size"
        }

        val staffSymbolsClasses = argmax(staffAndSymbols)
        val symbolDetailClasses = argmax(symbolDetail)

        return OmrClassMasks(
            width = staffAndSymbols.width,
            height = staffAndSymbols.height,
            staff = maskFor(staffSymbolsClasses, StaffAndSymbolsClass.STAFF),
            symbols = maskFor(staffSymbolsClasses, StaffAndSymbolsClass.SYMBOLS),
            stemsRests = maskFor(symbolDetailClasses, SymbolDetailClass.STEMS_RESTS),
            noteheads = maskFor(symbolDetailClasses, SymbolDetailClass.NOTEHEADS),
            clefsKeys = maskFor(symbolDetailClasses, SymbolDetailClass.CLEFS_KEYS)
        )
    }

    /**
     * Per-pixel argmax across [map]'s channel dimension. Ties resolve to
     * the lowest class index (first-seen-wins), the standard argmax
     * convention (e.g. numpy's `argmax`), which is what oemer itself runs
     * on.
     */
    private fun argmax(map: OmrPredictionMap): IntArray {
        val classes = IntArray(map.width * map.height)
        for (pixelIndex in classes.indices) {
            val base = pixelIndex * map.channels
            var bestClass = 0
            var bestValue = map.data[base]
            for (c in 1 until map.channels) {
                val value = map.data[base + c]
                if (value > bestValue) {
                    bestValue = value
                    bestClass = c
                }
            }
            classes[pixelIndex] = bestClass
        }
        return classes
    }

    private fun maskFor(classes: IntArray, targetClass: Int): BooleanArray =
        BooleanArray(classes.size) { classes[it] == targetClass }
}

/**
 * The five boolean masks oemer's downstream stages (staffline extraction,
 * notehead extraction, symbol/barline/rest extraction, rhythm extraction)
 * are each built on top of, derived via [ClassMaskExtractor.extract].
 *
 * All masks share one row-major `width * height` layout: pixel `(x, y)`
 * lives at index `y * width + x`. [symbols] is [OmrModelSpec.STAFF_AND_SYMBOLS]'s
 * *generic* symbol class only — oemer's own further-merged `symbols_pred`
 * layer (generic symbols + clefsKeys + stemsRests) belongs to whichever
 * later phase reproduces `ete.py`'s post-dewarp merge step, not here.
 */
data class OmrClassMasks(
    val width: Int,
    val height: Int,
    val staff: BooleanArray,
    val symbols: BooleanArray,
    val stemsRests: BooleanArray,
    val noteheads: BooleanArray,
    val clefsKeys: BooleanArray
) {
    /** Returns whether pixel ([x], [y]) belongs to [mask]. */
    fun isSet(mask: BooleanArray, x: Int, y: Int): Boolean = mask[y * width + x]
}