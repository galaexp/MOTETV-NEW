package com.gala.motetv.debloat

import android.content.Context
import com.gala.motetv.adb.TvDeviceInfo
import org.json.JSONArray
import org.json.JSONObject

class DebloatRepository(
    private val context: Context?,
    private val simulateBackupFailureForTest: Boolean = false
) {

    companion object {
        private const val PREFS_NAME = "motetv_debloat_records"
        private const val KEY_LATEST_BACKUP = "latest_backup_json"
        private const val KEY_MODIFIED_PACKAGES = "modified_by_motetv_set"
        private const val KEY_HISTORY = "operation_history_json"
        private const val MAX_HISTORY_ENTRIES = 100
    }

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var memoryBackupJson: String? = null
    private val memoryModified = linkedSetOf<String>()
    private val memoryHistory = mutableListOf<DebloatOperationEntry>()

    @Synchronized
    fun saveBackup(record: DebloatBackupRecord): Result<DebloatBackupRecord> {
        if (simulateBackupFailureForTest) {
            return Result.failure(IllegalStateException("Simulated backup persistence failure"))
        }
        return try {
            val json = serializeBackup(record).toString(2)
            memoryBackupJson = json
            prefs?.edit()?.putString(KEY_LATEST_BACKUP, json)?.apply()
            Result.success(record)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    fun getLatestBackup(): DebloatBackupRecord? {
        val jsonStr = prefs?.getString(KEY_LATEST_BACKUP, null) ?: memoryBackupJson ?: return null
        return runCatching { deserializeBackup(JSONObject(jsonStr)) }.getOrNull()
    }

    @Synchronized
    fun exportLatestBackupJson(): String? {
        val backup = getLatestBackup() ?: return null
        val root = serializeBackup(backup)
        val historyArray = JSONArray()
        getOperationHistory().forEach { entry ->
            historyArray.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("timestampMs", entry.timestampMs)
                    put("action", entry.action.name)
                    put("packageName", entry.packageName ?: "")
                    put("succeeded", entry.succeeded)
                    put("verifiedState", entry.verifiedState)
                    put("detail", entry.detail)
                }
            )
        }
        root.put("operationHistory", historyArray)
        return root.toString(2)
    }

    @Synchronized
    fun markPackageModified(packageName: String) {
        val current = getModifiedPackages().toMutableSet()
        current.add(packageName)
        saveModifiedSet(current)
    }

    @Synchronized
    fun unmarkPackageModified(packageName: String) {
        val current = getModifiedPackages().toMutableSet()
        current.remove(packageName)
        saveModifiedSet(current)
    }

    @Synchronized
    fun getModifiedPackages(): Set<String> {
        val p = prefs
        if (p != null) {
            return p.getStringSet(KEY_MODIFIED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        }
        return memoryModified.toSet()
    }

    private fun saveModifiedSet(set: Set<String>) {
        memoryModified.clear()
        memoryModified.addAll(set)
        prefs?.edit()?.putStringSet(KEY_MODIFIED_PACKAGES, set.toSet())?.apply()
    }

    @Synchronized
    fun appendHistory(entry: DebloatOperationEntry) {
        val list = getOperationHistory().toMutableList()
        list.add(0, entry)
        val trimmed = list.take(MAX_HISTORY_ENTRIES)
        memoryHistory.clear()
        memoryHistory.addAll(trimmed)

        val arr = JSONArray()
        trimmed.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("timestampMs", item.timestampMs)
                    put("action", item.action.name)
                    put("packageName", item.packageName ?: "")
                    put("succeeded", item.succeeded)
                    put("verifiedState", item.verifiedState)
                    put("detail", item.detail)
                }
            )
        }
        prefs?.edit()?.putString(KEY_HISTORY, arr.toString())?.apply()
    }

    @Synchronized
    fun getOperationHistory(): List<DebloatOperationEntry> {
        val raw = prefs?.getString(KEY_HISTORY, null)
        if (raw.isNullOrBlank()) return memoryHistory.toList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DebloatOperationEntry(
                    id = o.getString("id"),
                    timestampMs = o.getLong("timestampMs"),
                    action = DebloatActionType.valueOf(o.getString("action")),
                    packageName = o.optString("packageName").ifBlank { null },
                    succeeded = o.getBoolean("succeeded"),
                    verifiedState = o.optString("verifiedState"),
                    detail = o.optString("detail")
                )
            }
        }.getOrDefault(memoryHistory.toList())
    }

    private fun serializeBackup(record: DebloatBackupRecord): JSONObject {
        val devObj = JSONObject().apply {
            put("manufacturer", record.deviceInfo.manufacturer)
            put("model", record.deviceInfo.model)
            put("deviceName", record.deviceInfo.deviceName)
            put("productBoard", record.deviceInfo.productBoard)
            put("androidVersion", record.deviceInfo.androidVersion)
            put("sdkVersion", record.deviceInfo.sdkVersion)
            put("buildId", record.deviceInfo.buildId)
            put("buildFingerprint", record.deviceInfo.buildFingerprint)
            put("currentHomePackage", record.deviceInfo.currentHomePackage ?: "")
            put("currentHomeActivity", record.deviceInfo.currentHomeActivity ?: "")
        }
        return JSONObject().apply {
            put("backupId", record.backupId)
            put("createdAtMs", record.createdAtMs)
            put("deviceInfo", devObj)
            put("allInstalledPackages", JSONArray(record.allInstalledPackages))
            put("originallyDisabledPackages", JSONArray(record.originallyDisabledPackages))
            put("originalHomePackage", record.originalHomePackage ?: "")
            put("originalHomeActivity", record.originalHomeActivity ?: "")
            put("intendedDisablePackages", JSONArray(record.intendedDisablePackages))
            put("modifiedByMoteTvPackages", JSONArray(record.modifiedByMoteTvPackages))
        }
    }

    private fun deserializeBackup(root: JSONObject): DebloatBackupRecord {
        val devObj = root.getJSONObject("deviceInfo")
        val devInfo = TvDeviceInfo(
            manufacturer = devObj.optString("manufacturer"),
            model = devObj.optString("model"),
            deviceName = devObj.optString("deviceName"),
            productBoard = devObj.optString("productBoard"),
            androidVersion = devObj.optString("androidVersion"),
            sdkVersion = devObj.optInt("sdkVersion", 0),
            buildId = devObj.optString("buildId"),
            buildFingerprint = devObj.optString("buildFingerprint"),
            currentHomePackage = devObj.optString("currentHomePackage").ifBlank { null },
            currentHomeActivity = devObj.optString("currentHomeActivity").ifBlank { null },
            currentHomeLabel = devObj.optString("currentHomePackage").ifBlank { null }
        )
        return DebloatBackupRecord(
            backupId = root.getString("backupId"),
            createdAtMs = root.getLong("createdAtMs"),
            deviceInfo = devInfo,
            allInstalledPackages = jsonArrayToList(root.getJSONArray("allInstalledPackages")),
            originallyDisabledPackages = jsonArrayToList(root.getJSONArray("originallyDisabledPackages")),
            originalHomePackage = root.optString("originalHomePackage").ifBlank { null },
            originalHomeActivity = root.optString("originalHomeActivity").ifBlank { null },
            intendedDisablePackages = jsonArrayToList(root.optJSONArray("intendedDisablePackages")),
            modifiedByMoteTvPackages = jsonArrayToList(root.optJSONArray("modifiedByMoteTvPackages"))
        )
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
