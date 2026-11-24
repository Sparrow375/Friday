package com.friday.assistant.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

data class CachedAppEntry(
    val packageName: String,
    val label: String,
    val normalizedLabel: String,
    val compactLabel: String,
    val isLaunchable: Boolean
)

/**
 * In-memory cache of installed application labels and launchability.
 * Eliminates repeated 150-400ms PackageManager.getInstalledPackages/Applications IPC scans
 * during NLU post-validation and app launching.
 */
object InstalledAppsCache {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var cachedApps: List<CachedAppEntry> = emptyList()

    @Volatile
    private var lastLoadedMs: Long = 0L

    @Synchronized
    fun getApps(context: Context, forceRefresh: Boolean = false): List<CachedAppEntry> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedApps.isNotEmpty() && (now - lastLoadedMs) < CACHE_TTL_MS) {
            return cachedApps
        }

        val pm = context.packageManager
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList<ApplicationInfo>()
        }

        val entries = ArrayList<CachedAppEntry>(apps.size)
        for (app in apps) {
            val label = try {
                app.loadLabel(pm)?.toString()?.trim() ?: ""
            } catch (_: Exception) {
                ""
            }
            if (label.isEmpty()) continue
            val normalized = label.lowercase(Locale.ROOT)
            val isLaunchable = try {
                pm.getLaunchIntentForPackage(app.packageName) != null
            } catch (_: Exception) {
                false
            }
            entries.add(
                CachedAppEntry(
                    packageName = app.packageName,
                    label = label,
                    normalizedLabel = normalized,
                    compactLabel = normalized.replace(" ", ""),
                    isLaunchable = isLaunchable
                )
            )
        }

        cachedApps = entries
        lastLoadedMs = now
        return entries
    }

    fun isAppInstalled(context: Context, appName: String): Boolean {
        val cleanName = appName.lowercase(Locale.ROOT).trim()
        if (cleanName.isEmpty()) return false
        val compact = cleanName.replace(" ", "")
        return getApps(context).any { entry ->
            entry.normalizedLabel == cleanName || entry.compactLabel == compact
        }
    }

    fun findBestLaunchableMatch(context: Context, queryName: String): CachedAppEntry? {
        val queryLower = queryName.trim().lowercase(Locale.ROOT)
        if (queryLower.isEmpty()) return null

        var bestMatch: CachedAppEntry? = null
        var bestScore = 0
        for (entry in getApps(context)) {
            if (!entry.isLaunchable) continue
            val score = when {
                entry.normalizedLabel == queryLower -> 100
                entry.normalizedLabel.startsWith(queryLower) -> 80
                entry.normalizedLabel.contains(queryLower) -> 50
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = entry
            }
        }
        return bestMatch
    }
}
