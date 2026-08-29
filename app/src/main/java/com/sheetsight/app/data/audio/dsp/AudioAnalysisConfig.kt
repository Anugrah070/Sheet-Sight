package com.sheetsight.app.data.audio.dsp

/** Shared PCM/DSP sizes. Musical tolerances remain cents-based in the recognition layer. */
data class AudioAnalysisConfig(
    val sampleRateHz: Int = 22_050,
    val captureHopSize: Int = 256,
    val onsetFrameSize: Int = 1_024,
    val recognitionHopSize: Int = 512,
    val maximumFrameSize: Int = 8_192,
    val highPassCutoffHz: Double = 15.0
) {
    init {
        require(sampleRateHz > 0)
        require(captureHopSize > 0 && onsetFrameSize >= captureHopSize)
        require(recognitionHopSize >= captureHopSize && recognitionHopSize % captureHopSize == 0)
        require(maximumFrameSize >= onsetFrameSize && maximumFrameSize.countOneBits() == 1)
        require(highPassCutoffHz in 0.0..sampleRateHz / 2.0)
    }
}
