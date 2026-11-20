package app.kotowski.sms2email.system

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import app.kotowski.sms2email.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// A push-based "the contacts changed" signal: a ContentObserver on the contacts URI
// fires on insert/update/delete (a rename is an update to the contacts row), plus a
// 60 s fallback tick in case a change is missed. Each event first invalidates the
// ContactResolver scan cache (so the next read is fresh) and then emits. The flow is
// cold (callbackFlow): the observer and the tick exist only while a screen is
// collecting it (its view-model scope), so there is no background observation.
@Singleton
class ContactChangeObserver
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val contactResolver: ContactResolver,
        private val permissionManager: PermissionManager,
    ) {
        // The fallback poll interval; a test seam (like SimChangeObserver's) so the
        // tick can be exercised without waiting the real 60 s.
        internal var pollIntervalMs: Long = AppConfig.CONTACTS_CACHE_TTL_MS

        val contactChanges: Flow<Unit> =
            callbackFlow {
                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(
                            selfChange: Boolean,
                            uri: Uri?,
                        ) {
                            super.onChange(selfChange, uri)
                            contactResolver.invalidate()
                            trySend(Unit)
                        }
                    }
                // Without READ_CONTACTS the registration throws SecurityException, crashing
                // the collector.
                val canObserve = permissionManager.isGranted(permissionManager.contactsPermission)
                if (canObserve) {
                    appContext.contentResolver.registerContentObserver(
                        ContactsContract.Contacts.CONTENT_URI,
                        true,
                        observer,
                    )
                }
                // First collect: force a fresh read so a change that landed while no
                // screen was open is reflected the moment one opens.
                contactResolver.invalidate()
                trySend(Unit)
                // Unlike the SIM tick, the poll does not invalidate the resolver: the scan
                // keeps its own TTL, and a tick invalidation would force a full scan every 60 s.
                launch {
                    while (isActive) {
                        delay(pollIntervalMs)
                        trySend(Unit)
                    }
                }
                awaitClose {
                    if (canObserve) {
                        appContext.contentResolver.unregisterContentObserver(observer)
                    }
                }
            }
    }
