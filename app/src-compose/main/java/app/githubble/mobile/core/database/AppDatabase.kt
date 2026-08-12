package app.githubble.mobile.core.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="api_cache") data class ApiCacheEntity(@PrimaryKey val key:String,val payload:String,val updatedAt:Long,val expiresAt:Long)
@Entity(tableName="translation_cache") data class TranslationCacheEntity(@PrimaryKey val key:String,val sourceHash:String,val targetLanguage:String,val translatedText:String,val provider:String,val updatedAt:Long)
@Dao interface CacheDao{
 @Query("SELECT * FROM api_cache WHERE `key`=:key LIMIT 1") suspend fun api(key:String):ApiCacheEntity?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putApi(value:ApiCacheEntity)
 @Query("DELETE FROM api_cache WHERE expiresAt < :now") suspend fun purgeApi(now:Long)
 @Query("SELECT * FROM translation_cache WHERE `key`=:key LIMIT 1") suspend fun translation(key:String):TranslationCacheEntity?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putTranslation(value:TranslationCacheEntity)
 @Query("DELETE FROM translation_cache") suspend fun clearTranslations()
 @Query("SELECT COUNT(*) FROM translation_cache") fun translationCount():Flow<Int>
}
@Database(entities=[ApiCacheEntity::class,TranslationCacheEntity::class],version=1,exportSchema=false)
abstract class AppDatabase:RoomDatabase(){ abstract fun cacheDao():CacheDao
 companion object{ @Volatile private var instance:AppDatabase?=null
  fun get(context:Context)=instance?:synchronized(this){ instance?:Room.databaseBuilder(context.applicationContext,AppDatabase::class.java,"githubble.db").fallbackToDestructiveMigration().build().also{instance=it} }
 }
}
