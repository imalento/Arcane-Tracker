package net.mbonnin.arcanetracker.room

import android.database.Cursor
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.reactivex.Flowable
import io.reactivex.Maybe
import net.mbonnin.arcanetracker.ArcaneTrackerApplication
import timber.log.Timber


@Entity
data class RDeck(
        @PrimaryKey
        val id: String,
        val name: String,
        val deck_string: String,
        val wins: Int = 0,
        val losses: Int = 0,
        val arena: Boolean = false,
        val accessMillis: Long = System.currentTimeMillis()
)

@Entity
data class RPack(
        val timeMillis: Long = System.currentTimeMillis(),
        val cardList: String, // a comma separated list of cards
        val dust: Int
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

}


@Entity
data class RGame(
        val deck_id: String?,
        val victory: Boolean,
        val player_class: String,
        val opponent_class: String,
        val coin: Boolean,
        val rank: Int? = null,
        val game_type: String,
        val format_type: String,
        val hs_replay_url: String? = null,
        val date: Long? = null,
        val deck_name: String
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
}


@Database(entities = arrayOf(RDeck::class, RGame::class, RPack::class), version = 6)
abstract class RDatabase : RoomDatabase() {
    abstract fun deckDao(): RDeckDao
    abstract fun gameDao(): RGameDao
    abstract fun packDao(): RPackDao
}

@Dao
interface RDeckDao {
    @Query("SELECT * FROM rdeck WHERE arena = 0")
    fun getCollection(): Flowable<List<RDeck>>

    @Query("SELECT * FROM rdeck WHERE arena = 1 ORDER BY accessMillis DESC LIMIT 1")
    fun getLatestArenaDeck(): Flowable<List<RDeck>>

    @Query("SELECT * FROM rdeck ORDER BY accessMillis DESC LIMIT 1")
    fun getLatestDeck(): Flowable<List<RDeck>>

    @Query("UPDATE rdeck SET name = :name, deck_string = :deck_string, accessMillis = :accessMillis WHERE id = :id")
    fun updateNameAndContents(id: String, name: String, deck_string: String, accessMillis: Long)

    @Query("UPDATE rdeck SET wins = :wins, losses = :losses WHERE id = :id")
    fun setWinsLosses(id: String, wins:Int, losses: Int)

    @Query("UPDATE rdeck SET wins = wins + :wins, losses = losses + :losses WHERE id = :id")
    fun incrementWinsLosses(id: String, wins:Int, losses: Int)

    @Insert(onConflict = OnConflictStrategy.FAIL)
    fun insert(rDeck: RDeck)

    @Query("SELECT * FROM rdeck WHERE id = :id LIMIT 1")
    fun findById(id: String): Flowable<List<RDeck>>

    @Query("DELETE FROM rdeck WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface RGameDao {
    @Query("SELECT * from rgame")
    fun selectAll(): Flowable<List<RGame>>

    @Query("DELETE from rgame")
    fun deleteAll()
}

@Dao
interface RPackDao {
    @Insert
    fun insert(rPack: RPack): Long

    @Query("select * from rpack ORDER BY timeMillis DESC")
    fun all(): Cursor

    @Query("select COUNT(*) as count, SUM(DUST) as dust from rpack")
    fun stats(): PackStats
}

data class PackStats(val count: Int, val dust: Int)

data class Counter(val won: Int, val lost: Int)

object RDatabaseSingleton {
    val instance = Room.databaseBuilder(ArcaneTrackerApplication.get(), RDatabase::class.java, "db")
            .addMigrations(
                    Migration3_4(),
                    Migration4_5(),
                    Migration5_6()
            )
            .fallbackToDestructiveMigration()
            .build()
}

class Migration3_4: Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        try {
            database.execSQL("ALTER TABLE rdeck ADD arena integer NOT NULL DEFAULT 0")
        } catch (e: Exception) {
            Timber.d(e)
        }
    }
}

class Migration4_5: Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        try {
            database.execSQL("CREATE TABLE rpack(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timeMillis INTEGER NOT NULL, cardList TEXT NOT NULL)")
        } catch (e: Exception) {
            Timber.d(e)
        }
    }
}

class Migration5_6: Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        try {
            database.execSQL("ALTER TABLE rpack ADD dust integer NOT NULL DEFAULT 0")
        } catch (e: Exception) {
            Timber.d(e)
        }
    }
}