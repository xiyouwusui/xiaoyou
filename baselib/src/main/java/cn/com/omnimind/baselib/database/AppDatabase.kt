package cn.com.omnimind.baselib.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [

        AppIcons::class,
        StudyRecord::class,
        FavoriteRecord::class,
        ExecutionRecord::class,
        Message::class,
        CacheSuggestion::class,
        Conversation::class,
        AgentConversationEntry::class,
        TokenUsageRecord::class
    ],
    version = 12,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appIconsDao(): AppIconsDao
    abstract fun studyRecordDao(): StudyRecordDao
    abstract fun favoriteRecordDao(): FavoriteRecordDao
    abstract fun executionRecordDao(): ExecutionRecordDao
    abstract fun messageDao(): MessageDao
    abstract fun cacheSuggestionDao(): CacheSuggestionDao
    abstract fun conversationDao(): ConversationDao
    abstract fun agentConversationEntryDao(): AgentConversationEntryDao
    abstract fun tokenUsageRecordDao(): TokenUsageRecordDao

    companion object {
        const val DATABASE_NAME = "omnibot_cache_database"
    }
}
