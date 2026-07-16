package com.sheetsight.app.ui.library

import androidx.annotation.StringRes
import com.sheetsight.app.R

/**
 * Sort options exposed on the Library screen.
 *
 * [RECENTLY_PRACTICED] currently sorts by [com.sheetsight.app.domain.model.Score.lastOpenedDate],
 * the closest field that exists today. Once Phase 9 (Statistics) adds a
 * dedicated "last practiced" timestamp distinct from "last opened", switch
 * this option to use that field instead.
 */
enum class LibrarySortOption(@StringRes val labelRes: Int) {
    NAME(R.string.library_sort_name),
    DATE_IMPORTED(R.string.library_sort_date),
    RECENTLY_PRACTICED(R.string.library_sort_recent)
}
