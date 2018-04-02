package net.mbonnin.arcanetracker.room

import android.arch.persistence.room.*
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import net.mbonnin.arcanetracker.ArcaneTrackerApplication


@Entity
data class RDeck(
        @PrimaryKey
        val id: String,
        val name: String,
        val deck_string: String,
        val wins: Int = 0,
        val losses: Int = 0,
        val accessMillis: Long = System.currentTimeMillis()
)

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


@Database(entities = arrayOf(RDeck::class, RGame::class), version = 3)
abstract class RDatabase : RoomDatabase() {
    abstract fun deckDao(): RDeckDao
    abstract fun gameDao(): RGameDao
}

@Dao
interface RDeckDao {
    @Query("SELECT * FROM rdeck")
    fun getAll(): Single<List<RDeck>>

    @Update
    @Query("UPDATE rdeck SET name = :name, deck_string = :deck_string, accessMillis = :accessMillis WHERE id = :id")
    fun updateNameAndContents(id: String, name: String, deck_string: String, accessMillis: Long)

    @Update
    @Query("UPDATE rdeck SET wins = :wins, losses = :losses WHERE id = :id")
    fun setWinsLosses(id: String, wins:Int, losses: Int)

    @Update
    @Query("UPDATE rdeck SET wins = wins + :wins, losses = losses + :losses WHERE id = :id")
    fun incrementWinsLosses(id: String, wins:Int, losses: Int)

    @Insert(onConflict = OnConflictStrategy.FAIL)
    fun insert(rDeck: RDeck)

    @Query("SELECT * FROM rdeck WHERE id = :id LIMIT 1")
    fun findById(id: String): Flowable<RDeck>


    @Delete
    fun delete(rDeck: RDeck)

    @Query("DELETE FROM rdeck WHERE id NOT IN (SELECT id FROM rdeck ORDER BY accessMillis DESC LIMIT 18)")
    fun cleanup()
}

@Dao
interface RGameDao {
    @Query("UPDATE rgame SET hs_replay_url = :hs_replay_url WHERE id = :id")
    fun update(id: Long, hs_replay_url: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rGame: RGame): Long

    @Query("SELECT COUNT(*) FROM rgame WHERE opponent_class = :opponent_class")
    fun totalPlayedAgainst(opponent_class: String): Maybe<Int>

    @Query("SELECT COUNT(*) FROM rgame WHERE opponent_class = :opponent_class AND victory = 1")
    fun totalVictoriesAgainst(opponent_class: String): Maybe<Int>

    @Query("SELECT SUM(victory) as won, SUM(case victory when 1 then 0 else 1 end) as lost FROM rgame where deck_id = :deck_id")
    fun counter(deck_id: String): Flowable<Counter>
}

data class Counter(val won: Int, val lost: Int)

object RDatabaseSingleton {
    val instance = Room.databaseBuilder(ArcaneTrackerApplication.get(), RDatabase::class.java, "db")
            .fallbackToDestructiveMigration()
            .build()
}