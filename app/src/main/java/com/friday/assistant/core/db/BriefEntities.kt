package com.friday.assistant.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "interests",
    indices = [Index(value = ["title"], unique = true)]
)
data class InterestEntity(
    @PrimaryKey val id: String, // slug, e.g. "cricket_intl"
    val title: String,
    val category: String, // "news", "event", "sports"
    val keywordsJson: String, // JSON array of query terms/keywords
    val sourcesJson: String, // JSON array of RSS or HTML URLs
    val isEnabled: Boolean = true,
    val isCustom: Boolean = false,
    val lastCrawlTime: Long = 0L
)

@Entity(
    tableName = "brief_items",
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["interestId"]),
        Index(value = ["pubDate"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = InterestEntity::class,
            parentColumns = ["id"],
            childColumns = ["interestId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BriefItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val interestId: String,
    val title: String,
    val summary: String,
    val url: String,
    val sourceName: String,
    val pubDate: Long = System.currentTimeMillis(),
    val relevanceScore: Float = 1.0f,
    val status: String = "new" // "new", "archived", "dismissed"
)
