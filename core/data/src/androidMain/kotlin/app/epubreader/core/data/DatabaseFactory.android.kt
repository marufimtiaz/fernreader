package app.epubreader.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun androidDatabaseBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
    val app = context.applicationContext
    return Room.databaseBuilder<AppDatabase>(context = app, name = app.getDatabasePath("library.db").absolutePath)
}
