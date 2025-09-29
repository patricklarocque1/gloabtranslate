package com.example.gloabtranslate.core.version

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.gloabtranslate.core.logging.StructuredLogger
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import com.example.gloabtranslate.core.BuildConfig

/**
 * Version management system for the Global Translate app
 * Handles version information, build metadata, and update management
 */
class VersionManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "VersionManager"
        private const val VERSION_PREFS = "version_preferences"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_UPDATE_AVAILABLE = "update_available"
        private const val KEY_FORCE_UPDATE = "force_update"
        private const val KEY_APP_VERSION_CODE = "app_version_code"
        private const val KEY_APP_VERSION_NAME = "app_version_name"
        
        @Volatile
        private var INSTANCE: VersionManager? = null
        
        fun getInstance(context: Context): VersionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VersionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    data class VersionInfo(
        val versionName: String,
        val versionCode: Long,
        val buildType: BuildType,
        val buildTime: Long,
        val gitCommit: String?,
        val gitBranch: String?,
        val buildNumber: String,
        val flavor: String?,
        val isDebug: Boolean,
        val minSdkVersion: Int,
        val targetSdkVersion: Int,
        val compileSdkVersion: Int
    )
    
    data class UpdateInfo(
        val isUpdateAvailable: Boolean,
        val latestVersionName: String?,
        val latestVersionCode: Long?,
        val isForceUpdate: Boolean,
        val updateUrl: String?,
        val releaseNotes: String?,
        val updateSize: Long?,
        val lastChecked: Long
    )
    
    enum class BuildType {
        DEBUG,
        RELEASE,
        BETA,
        ALPHA
    }
    
    private val structuredLogger = StructuredLogger.getInstance(context)
    private val prefs = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
    
    /**
     * Get current app version information
     */
    fun getCurrentVersionInfo(): VersionInfo {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName ?: "Unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            
            VersionInfo(
                versionName = versionName,
                versionCode = versionCode,
                buildType = getBuildType(),
                buildTime = getBuildTime(),
                gitCommit = getGitCommit(),
                gitBranch = getGitBranch(),
                buildNumber = getBuildNumber(),
                flavor = getBuildFlavor(),
                isDebug = isDebugBuild(),
                minSdkVersion = getMinSdkVersion(),
                targetSdkVersion = getTargetSdkVersion(),
                compileSdkVersion = getCompileSdkVersion()
            )
        } catch (e: PackageManager.NameNotFoundException) {
            structuredLogger.e(TAG, "Error getting version info", throwable = e)
            getDefaultVersionInfo()
        }
    }
    
    /**
     * Check for app updates
     */
    suspend fun checkForUpdates(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentVersionInfo()
            val lastChecked = System.currentTimeMillis()
            
            // In a real implementation, this would check with a server
            // For now, we'll simulate the check
            val updateInfo = simulateUpdateCheck(currentVersion)
            
            // Save update check time
            prefs.edit()
                .putLong(KEY_LAST_UPDATE_CHECK, lastChecked)
                .putBoolean(KEY_UPDATE_AVAILABLE, updateInfo.isUpdateAvailable)
                .putBoolean(KEY_FORCE_UPDATE, updateInfo.isForceUpdate)
                .apply()
            
            structuredLogger.i(TAG, "Update check completed", mapOf(
                "update_available" to updateInfo.isUpdateAvailable,
                "current_version" to (currentVersion.versionName ?: "unknown"),
                "latest_version" to (updateInfo.latestVersionName ?: "unknown")
            ))
            
            updateInfo
        } catch (e: Exception) {
            structuredLogger.e(TAG, "Error checking for updates", throwable = e)
            UpdateInfo(
                isUpdateAvailable = false,
                latestVersionName = null,
                latestVersionCode = null,
                isForceUpdate = false,
                updateUrl = null,
                releaseNotes = null,
                updateSize = null,
                lastChecked = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Get update information from cache
     */
    fun getCachedUpdateInfo(): UpdateInfo {
        val lastChecked = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0)
        val isUpdateAvailable = prefs.getBoolean(KEY_UPDATE_AVAILABLE, false)
        val isForceUpdate = prefs.getBoolean(KEY_FORCE_UPDATE, false)
        
        return UpdateInfo(
            isUpdateAvailable = isUpdateAvailable,
            latestVersionName = null,
            latestVersionCode = null,
            isForceUpdate = isForceUpdate,
            updateUrl = null,
            releaseNotes = null,
            updateSize = null,
            lastChecked = lastChecked
        )
    }
    
    /**
     * Check if update is required
     */
    fun isUpdateRequired(): Boolean {
        val updateInfo = getCachedUpdateInfo()
        return updateInfo.isForceUpdate
    }
    
    /**
     * Get version comparison result
     */
    fun compareVersions(version1: String, version2: String): Int {
        val v1Parts = version1.split(".").map { it.toIntOrNull() ?: 0 }
        val v2Parts = version2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLength = maxOf(v1Parts.size, v2Parts.size)
        
        for (i in 0 until maxLength) {
            val v1Part = v1Parts.getOrElse(i) { 0 }
            val v2Part = v2Parts.getOrElse(i) { 0 }
            
            when {
                v1Part > v2Part -> return 1
                v1Part < v2Part -> return -1
            }
        }
        
        return 0
    }
    
    /**
     * Get app installation date
     */
    fun getInstallationDate(): Long {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            structuredLogger.e(TAG, "Error getting installation date", throwable = e)
            0L
        }
    }
    
    /**
     * Get last update date
     */
    fun getLastUpdateDate(): Long {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            structuredLogger.e(TAG, "Error getting last update date", throwable = e)
            0L
        }
    }
    
    /**
     * Get version history
     */
    fun getVersionHistory(): List<VersionHistoryEntry> {
        // In a real implementation, this would read from a database or file
        return listOf(
            VersionHistoryEntry(
                versionName = "1.0.0",
                versionCode = 1L,
                releaseDate = getInstallationDate(),
                changes = listOf(
                    "Initial release",
                    "Basic translation functionality",
                    "Multi-language support",
                    "Voice input and output"
                )
            )
        )
    }
    
    /**
     * Get build type
     */
    private fun getBuildType(): BuildType {
        return when {
            isDebugBuild() -> BuildType.DEBUG
            BuildConfig.BUILD_TYPE.contains("beta", ignoreCase = true) -> BuildType.BETA
            BuildConfig.BUILD_TYPE.contains("alpha", ignoreCase = true) -> BuildType.ALPHA
            else -> BuildType.RELEASE
        }
    }
    
    /**
     * Check if this is a debug build
     */
    private fun isDebugBuild(): Boolean {
        return BuildConfig.DEBUG
    }
    
    /**
     * Get build time
     */
    private fun getBuildTime(): Long {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            System.currentTimeMillis()
        }
    }
    
    /**
     * Get git commit hash
     */
    private fun getGitCommit(): String? {
        // In a real implementation, this would read from BuildConfig or a file
        return BuildConfig.BUILD_TYPE // Placeholder
    }
    
    /**
     * Get git branch
     */
    private fun getGitBranch(): String? {
        // In a real implementation, this would read from BuildConfig or a file
        return "main" // Placeholder
    }
    
    /**
     * Get build number
     */
    private fun getBuildNumber(): String {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode.toString()
        } catch (e: PackageManager.NameNotFoundException) {
            "1"
        }
    }
    
    /**
     * Get build flavor
     */
    private fun getBuildFlavor(): String? {
        return try {
            val flavorField = BuildConfig::class.java.getDeclaredField("FLAVOR")
            val flavor = flavorField.get(null) as? String
            flavor?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get minimum SDK version
     */
    private fun getMinSdkVersion(): Int {
        return Build.VERSION.SDK_INT // This would be read from build.gradle in real implementation
    }
    
    /**
     * Get target SDK version
     */
    private fun getTargetSdkVersion(): Int {
        return Build.VERSION.SDK_INT // This would be read from build.gradle in real implementation
    }
    
    /**
     * Get compile SDK version
     */
    private fun getCompileSdkVersion(): Int {
        return Build.VERSION.SDK_INT // This would be read from build.gradle in real implementation
    }
    
    /**
     * Get default version info when error occurs
     */
    private fun getDefaultVersionInfo(): VersionInfo {
        return VersionInfo(
            versionName = "1.0.0",
            versionCode = 1L,
            buildType = BuildType.DEBUG,
            buildTime = System.currentTimeMillis(),
            gitCommit = null,
            gitBranch = null,
            buildNumber = "1",
            flavor = null,
            isDebug = true,
            minSdkVersion = 34,
            targetSdkVersion = 36,
            compileSdkVersion = 36
        )
    }
    
    /**
     * Simulate update check (replace with real implementation)
     */
    private suspend fun simulateUpdateCheck(currentVersion: VersionInfo): UpdateInfo {
        // Simulate network delay
        delay(1000)
        
        // Simulate update availability (10% chance)
        val hasUpdate = Math.random() < 0.1
        val isForceUpdate = hasUpdate && Math.random() < 0.3
        
        return if (hasUpdate) {
            val latestVersion = incrementVersion(currentVersion.versionName)
            UpdateInfo(
                isUpdateAvailable = true,
                latestVersionName = latestVersion,
                latestVersionCode = currentVersion.versionCode + 1,
                isForceUpdate = isForceUpdate,
                updateUrl = "https://play.google.com/store/apps/details?id=${context.packageName}",
                releaseNotes = "Bug fixes and performance improvements",
                updateSize = 15 * 1024 * 1024L, // 15MB
                lastChecked = System.currentTimeMillis()
            )
        } else {
            UpdateInfo(
                isUpdateAvailable = false,
                latestVersionName = null,
                latestVersionCode = null,
                isForceUpdate = false,
                updateUrl = null,
                releaseNotes = null,
                updateSize = null,
                lastChecked = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Increment version number
     */
    private fun incrementVersion(version: String): String {
        val parts = version.split(".")
        if (parts.size >= 3) {
            val patch = parts[2].toIntOrNull() ?: 0
            return "${parts[0]}.${parts[1]}.${patch + 1}"
        }
        return version
    }
    
    /**
     * Get formatted version string
     */
    fun getFormattedVersionString(): String {
        val versionInfo = getCurrentVersionInfo()
        val buildType = if (versionInfo.isDebug) " (Debug)" else ""
        return "${versionInfo.versionName}${buildType}"
    }
    
    /**
     * Get detailed version string
     */
    fun getDetailedVersionString(): String {
        val versionInfo = getCurrentVersionInfo()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val buildTime = dateFormat.format(Date(versionInfo.buildTime))
        
        return buildString {
            appendLine("Version: ${versionInfo.versionName}")
            appendLine("Build: ${versionInfo.buildNumber}")
            appendLine("Type: ${versionInfo.buildType}")
            appendLine("Build Time: $buildTime")
            versionInfo.gitCommit?.let { appendLine("Commit: $it") }
            versionInfo.gitBranch?.let { appendLine("Branch: $it") }
            versionInfo.flavor?.let { appendLine("Flavor: $it") }
        }
    }
    
    /**
     * Data class for version history entry
     */
    data class VersionHistoryEntry(
        val versionName: String,
        val versionCode: Long,
        val releaseDate: Long,
        val changes: List<String>
    )
}
