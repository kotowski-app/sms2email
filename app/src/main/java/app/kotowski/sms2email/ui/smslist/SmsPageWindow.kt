package app.kotowski.sms2email.ui.smslist

import app.kotowski.sms2email.AppConfig

// The growing live window shared by the History and Queue view models: the loaded window
// is the newest `loadedLimit` rows (the query is called with `loadedLimit + 1`, the extra
// row is the hasMore probe), and it only grows — a page per loadMore — so a row pushed
// out by a newer insert re-enters as the window grows.
class SmsPageWindow {
    var loadedLimit: Int = AppConfig.SMS_LIST_PAGE_SIZE
        private set

    fun grow() {
        loadedLimit += AppConfig.SMS_LIST_PAGE_SIZE
    }

    // The query returns at most `loadedLimit + 1` rows: a full window plus the probe row.
    fun hasMore(loadedRows: Int): Boolean = loadedRows > loadedLimit
}

fun shouldLoadMore(
    lastVisibleIndex: Int,
    itemCount: Int,
    hasMore: Boolean,
): Boolean {
    if (!hasMore) return false
    return lastVisibleIndex >= itemCount - AppConfig.SMS_LOAD_MORE_TRIGGER_THRESHOLD
}
