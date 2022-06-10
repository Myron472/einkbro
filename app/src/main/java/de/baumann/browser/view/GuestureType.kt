package de.baumann.browser.view

import de.baumann.browser.Ninja.R

enum class GestureType(val value: String, val resId: Int) {
    NothingHappen("01", R.string.nothing),
    Forward("02", R.string.forward_in_history),
    Backward("03", R.string.back_in_history),
    ScrollToTop("04", R.string.scroll_to_top),
    ScrollToBottom("05", R.string.scroll_to_bottom),
    ToLeftTab("06", R.string.switch_to_left_tab),
    ToRightTab("07", R.string.switch_to_right_tab),
    Overview("08", R.string.show_overview),
    OpenNewTab("09", R.string.open_new_tab),
    CloseTab("10", R.string.close_tab),
    PageUp("11", R.string.page_up),
    PageDown("12", R.string.page_down),
    Bookmark("13", R.string.bookmarks);

    companion object {
        fun from(value: String): GestureType = values().firstOrNull { it.value == value } ?: NothingHappen
    }

}