package app.fernreader.core.data

import androidx.room.Room
import androidx.room.RoomDatabase

fun jvmDatabaseBuilder(path: String): RoomDatabase.Builder<AppDatabase> = Room.databaseBuilder<AppDatabase>(name = path)
