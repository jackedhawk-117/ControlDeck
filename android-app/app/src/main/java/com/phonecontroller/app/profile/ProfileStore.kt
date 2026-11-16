package com.phonecontroller.app.profile

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Manages loading, saving, creating, duplicating, and importing/exporting [GameProfile]s.
 * Profiles are persisted in JSON format in the application's internal files directory.
 */
class ProfileStore(context: Context) {

    private val appContext = context.applicationContext
    private val profilesFile = File(appContext.filesDir, "profiles_v1.json")
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadProfiles(): List<GameProfile> {
        if (!profilesFile.exists()) {
            val defaults = DefaultProfiles.createAll()
            saveProfiles(defaults)
            return defaults
        }
        return try {
            val json = profilesFile.readText()
            val parsed = ProfileJson.parseList(json)
            if (parsed.isEmpty()) {
                Log.w(TAG, "Profile file contained no profiles; restoring defaults")
                val defaults = DefaultProfiles.createAll()
                saveProfiles(defaults)
                defaults
            } else {
                parsed
            }
        } catch (e: Exception) {
            // Keep the unreadable file for diagnosis instead of silently
            // overwriting it, then restore a working default set.
            Log.e(TAG, "Failed to read profiles; backing up and restoring defaults", e)
            backupCorruptFile()
            val defaults = DefaultProfiles.createAll()
            saveProfiles(defaults)
            defaults
        }
    }

    @Synchronized
    fun saveProfiles(profiles: List<GameProfile>) {
        try {
            val json = ProfileJson.serializeList(profiles)
            writeAtomically(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles", e)
        }
    }

    /** Write via temp file + atomic rename so a crash cannot truncate the DB. */
    private fun writeAtomically(json: String) {
        val dir = profilesFile.parentFile ?: throw IllegalStateException("No profile directory")
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "${profilesFile.name}.tmp")
        tmp.writeText(json)
        try {
            Files.move(
                tmp.toPath(),
                profilesFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            // Some Android filesystems do not support atomic moves.
            if (!tmp.renameTo(profilesFile)) {
                profilesFile.writeText(json)
                tmp.delete()
            }
        }
    }

    private fun backupCorruptFile() {
        try {
            if (!profilesFile.exists()) return
            val backup = File(
                profilesFile.parentFile,
                "profiles_v1.corrupt-${System.currentTimeMillis()}.json",
            )
            if (!profilesFile.renameTo(backup)) {
                Log.w(TAG, "Could not back up unreadable profile file")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not back up unreadable profile file", e)
        }
    }

    fun getActiveProfileId(): String {
        return prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
            ?: DefaultProfiles.FPS_PROFILE_ID
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    @Synchronized
    fun getActiveProfile(): GameProfile {
        val all = loadProfiles()
        val activeId = getActiveProfileId()
        val active = all.firstOrNull { it.id == activeId }
        if (active != null) return active
        val fallback = all.firstOrNull() ?: DefaultProfiles.fpsProfile()
        // The stored id can point at a deleted/renamed profile; heal it so the
        // next launch does not repeat the lookup miss.
        if (all.isNotEmpty()) setActiveProfileId(fallback.id)
        return fallback
    }

    @Synchronized
    fun saveProfile(updated: GameProfile) {
        val all = loadProfiles()
        val exists = all.any { it.id == updated.id }
        val next = if (exists) {
            all.map { if (it.id == updated.id) updated else it }
        } else {
            all + updated
        }
        saveProfiles(next)
    }

    @Synchronized
    fun createProfile(name: String, base: GameProfile? = null): GameProfile {
        val template = base ?: DefaultProfiles.generalProfile()
        val newProfile = template.copy(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Custom Profile" },
            description = "Custom layout created on ${java.text.SimpleDateFormat.getDateInstance().format(java.util.Date())}",
        )
        saveProfile(newProfile)
        setActiveProfileId(newProfile.id)
        return newProfile
    }

    @Synchronized
    fun duplicateProfile(id: String): GameProfile {
        val all = loadProfiles()
        val target = all.firstOrNull { it.id == id } ?: getActiveProfile()
        val duplicated = target.copy(
            id = UUID.randomUUID().toString(),
            name = "${target.name} (Copy)",
        )
        saveProfile(duplicated)
        return duplicated
    }

    @Synchronized
    fun deleteProfile(id: String): Boolean {
        val all = loadProfiles()
        if (all.size <= 1) return false // Prevent deleting only profile
        val next = all.filterNot { it.id == id }
        saveProfiles(next)
        if (getActiveProfileId() == id) {
            setActiveProfileId(next.first().id)
        }
        return true
    }

    @Synchronized
    fun importProfile(jsonStr: String): GameProfile {
        val parsed = ProfileJson.parse(jsonStr)
        // Ensure unique ID so it doesn't overwrite an existing profile unintentionally
        val imported = parsed.copy(
            id = UUID.randomUUID().toString(),
            name = if (parsed.name.isNotBlank()) parsed.name else "Imported Profile",
        )
        saveProfile(imported)
        setActiveProfileId(imported.id)
        return imported
    }

    @Synchronized
    fun exportProfile(id: String): String {
        val all = loadProfiles()
        val target = all.firstOrNull { it.id == id } ?: getActiveProfile()
        return ProfileJson.serialize(target)
    }

    @Synchronized
    fun resetToDefaults(): List<GameProfile> {
        val defaults = DefaultProfiles.createAll()
        saveProfiles(defaults)
        setActiveProfileId(DefaultProfiles.FPS_PROFILE_ID)
        return defaults
    }

    private companion object {
        const val TAG = "ProfileStore"
        const val PREFS_NAME = "phonecontroller_profiles"
        const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }
}
