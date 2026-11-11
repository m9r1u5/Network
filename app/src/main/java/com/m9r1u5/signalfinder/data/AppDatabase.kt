package com.m9r1u5.signalfinder.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotDao {
    @Query("SELECT * FROM spots ORDER BY score DESC, avgDbm DESC, timestamp DESC")
    fun observeRanked(): Flow<List<Spot>>

    @Query("SELECT * FROM spots ORDER BY score DESC, avgDbm DESC, timestamp DESC")
    suspend fun getRanked(): List<Spot>

    @Query("SELECT * FROM spots WHERE id = :id")
    suspend fun get(id: Long): Spot?

    @Insert
    suspend fun insert(spot: Spot): Long

    @Update
    suspend fun update(spot: Spot)

    @Delete
    suspend fun delete(spot: Spot)
}

@Dao
interface ReadingDao {
    @Insert
    suspend fun insert(reading: Reading): Long

    @Query("SELECT * FROM readings ORDER BY timestamp ASC")
    suspend fun getAll(): List<Reading>

    @Query("SELECT * FROM readings WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY timestamp ASC")
    fun observeWithLocation(): Flow<List<Reading>>

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun count(): Int

    @Query("DELETE FROM readings")
    suspend fun deleteAll()
}

@Database(entities = [Spot::class, Reading::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spotDao(): SpotDao
    abstract fun readingDao(): ReadingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "signalfinder.db",
            ).build().also { instance = it }
        }
    }
}
