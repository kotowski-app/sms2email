package app.kotowski.sms2email.data.database

// The DAO projection the UI layer consumes (the paged list window, the detail row):
// the display fields only, so the entity's send-machinery internals (dedupKey, the
// search facets) never leak into composables and a schema change to those cannot
// ripple into the UI. Room maps properties to columns by name (the Sms entity uses
// no @ColumnInfo renames, so the names match the columns) and applies the SmsStatus
// TypeConverter to projection properties too. A query-result type only: the Sms
// entity's schema stays untouched.
data class SmsUiModel(
    val id: Long,
    val phoneNumber: String,
    val messageBody: String,
    val simId: Int,
    val status: SmsStatus,
    val statusDetails: String?,
    val timeReceived: Long,
    val timeLastSendAttempt: Long?,
    val timeNextSendAttempt: Long?,
    val retryCount: Int,
)
