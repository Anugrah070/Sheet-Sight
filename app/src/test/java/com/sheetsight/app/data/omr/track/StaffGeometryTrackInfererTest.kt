package com.sheetsight.app.data.omr.track

import com.sheetsight.app.data.omr.staffline.Staffline
import com.sheetsight.app.data.omr.staffline.StafflinePoint
import com.sheetsight.app.data.omr.staffline.StafflinePosition
import com.sheetsight.app.data.omr.staffline.ZoneStaff
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffGeometryTrackInfererTest {
    @Test
    fun `four clearly separated piano systems infer two tracks`() {
        val centers = listOf(77, 172, 344, 439, 612, 707, 879, 975)

        assertEquals(2, StaffGeometryTrackInferer.infer(grid(centers)))
    }

    @Test
    fun `a missing final bass staff still preserves the piano grouping`() {
        val centers = listOf(77, 172, 344, 439, 612, 707, 879)

        assertEquals(2, StaffGeometryTrackInferer.infer(grid(centers)))
    }

    @Test
    fun `tight systems and a page break still preserve the repeated grand staff`() {
        // Scaled from the supplied Guts Theme screenshot. Its tightest gap
        // between systems is close to its widest within-system gap, while the
        // median alternating pattern remains unambiguous. The 103px outlier is
        // the visible page break.
        val centers = listOf(83, 112, 161, 194, 243, 267, 313, 340, 392, 423, 526, 554, 593, 621)

        assertEquals(2, StaffGeometryTrackInferer.infer(grid(centers)))
    }

    @Test
    fun `evenly spaced single staffs remain one track`() {
        assertEquals(1, StaffGeometryTrackInferer.infer(grid(listOf(50, 150, 250, 350))))
    }

    @Test
    fun `two four-staff systems infer four tracks`() {
        assertEquals(
            4,
            StaffGeometryTrackInferer.infer(grid(listOf(50, 90, 130, 170, 400, 440, 480, 520)))
        )
    }

    @Test
    fun `one possible multi-staff system is left to barline evidence`() {
        assertEquals(1, StaffGeometryTrackInferer.infer(grid(listOf(50, 140))))
    }

    private fun grid(centers: List<Int>): List<List<ZoneStaff>> = List(3) { zone ->
        centers.map { center -> staff(center, zone * 20) }
    }

    private fun staff(center: Int, x: Int): ZoneStaff = ZoneStaff(
        StafflinePosition.entries.mapIndexed { index, position ->
            Staffline(position, listOf(StafflinePoint(x, center + (index - 2) * 9)))
        }
    )
}
