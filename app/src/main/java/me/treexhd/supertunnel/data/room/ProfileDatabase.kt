package me.treexhd.supertunnel.data.room

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles") data class ProfileEntity(val id: String, val name: String, val json: String, val updatedAt: Long)
@Dao interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ProfileEntity>>
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun get(id: String): ProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(profile: ProfileEntity)
    @Query("DELETE FROM profiles WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [ProfileEntity::class], version = 1, exportSchema = true) abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    companion object { fun open(context: Context) = Room.databaseBuilder(context, ProfileDatabase::class.java, "ssh-tunnel.db").build() }
}
