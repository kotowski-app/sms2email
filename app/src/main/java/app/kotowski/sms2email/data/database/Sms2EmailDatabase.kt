package app.kotowski.sms2email.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Sms::class],
    version = 1,
    exportSchema = true,
)
abstract class Sms2EmailDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
}
