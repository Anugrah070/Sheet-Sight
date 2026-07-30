package com.sheetsight.app.data.omr.track

/**
 * Infers the number of tracks from barline height, matching oemer's
 * `staffline_extraction.py::further_infer_track_nums()`.
 *
 * The previous implementation counted staff centers inside each component
 * and took the mode. That algorithm does not exist in oemer and made the
 * result extremely sensitive to thousands of tiny residual-symbol blobs.
 */
object TrackVotingLoop {
    private const val HEIGHT_FACTOR = 10.0
    private const val MAX_TRACK_CHECK = 9

    data class TrackVoteResult(
        val trackNums: Int,
        /** One height/unit-size ratio for each component taller than one staff unit. */
        val heightRatios: List<Double>,
        val barlineBoxes: List<BoundingBox>
    )

    fun infer(
        barlineMask: BooleanArray,
        width: Int,
        height: Int,
        staffGrid: List<List<StaffCenterInfo>>
    ): TrackVoteResult {
        require(barlineMask.size == width * height) {
            "barlineMask size ${barlineMask.size} doesn't match ${width}x$height"
        }

        val boxes = ConnectedComponentBoxExtractor.extract(barlineMask, width, height)
        if (staffGrid.isEmpty() || staffGrid.all { it.isEmpty() }) {
            return TrackVoteResult(trackNums = 1, heightRatios = emptyList(), barlineBoxes = boxes)
        }

        val ratios = boxes.mapNotNull { box ->
            val centerX = (box.left + box.right) / 2.0
            val centerY = (box.top + box.bottom) / 2.0
            val unitSize = NearestStaffUnitSizeResolver.resolve(staffGrid, centerX, centerY)
            if (unitSize > 0.0 && box.height > unitSize) box.height / unitSize else null
        }

        // `staffs.shape[1]` in the source: the aligned number of physical
        // staff rows, not the number of zone cells in the whole grid.
        val staffRows = staffGrid.maxOf { it.size }
        var trackNums = 1
        for (i in 1..MAX_TRACK_CHECK) {
            val sufficientlyTall = ratios.count { it > HEIGHT_FACTOR * i }
            if (sufficientlyTall * (i + 1) > staffRows) {
                trackNums++
            } else {
                break
            }
        }

        return TrackVoteResult(
            trackNums = trackNums,
            heightRatios = ratios,
            barlineBoxes = boxes
        )
    }
}
