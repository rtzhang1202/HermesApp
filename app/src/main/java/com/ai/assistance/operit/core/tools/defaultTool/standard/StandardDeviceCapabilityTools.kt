package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.Manifest
import android.app.NotificationManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 系统能力工具：通讯录 / 相册 / 文件管理 / 设备设置。
 *
 * 与 StandardSystemOperationTools 分开成独立文件，避免与上游同步冲突（新增文件档）。
 * 所有工具都遵循同一个约定：参数缺失或权限不足时返回可行动的错误文案
 * （tell the model what to do next），绝不静默假成功。
 */
open class StandardDeviceCapabilityTools(private val context: Context) {

    companion object {
        private const val TAG = "DeviceCapabilityTools"
        private val PKG = com.ai.assistance.operit.BuildConfig.APPLICATION_ID

        // Android 13+ 分媒体权限；12 以下仍走 READ_EXTERNAL_STORAGE
        private val READ_IMAGES_PERMISSION: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        private val READ_VIDEO_PERMISSION: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        private const val SKIP_DIR_NAMES = "Android/data,Android/obb,.thumbnails,.trashed"
    }

    // ==================== 通用小工具 ====================

    private fun param(tool: AITool, name: String): String? =
        tool.parameters.find { it.name == name }?.value?.trim()?.takeIf { it.isNotEmpty() }

    private fun paramInt(tool: AITool, name: String, default: Int? = null): Int? =
        param(tool, name)?.toIntOrNull() ?: default

    private fun hasPermission(perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** 有 root 就顺手补授权，免去手工点确认（失败静默，由调用方判断最终状态） */
    private fun tryGrantPermission(perm: String) {
        if (hasPermission(perm)) return
        try {
            val p = ProcessBuilder("su", "-c", "pm grant $PKG $perm")
                .redirectErrorStream(true)
                .start()
            p.waitFor(4, TimeUnit.SECONDS)
            p.destroy()
        } catch (e: Exception) {
            AppLogger.w(TAG, "pm grant failed for $perm: ${e.message}")
        }
    }

    private fun ensureRuntimePermission(perm: String): Boolean {
        if (hasPermission(perm)) return true
        tryGrantPermission(perm)
        return hasPermission(perm)
    }

    /** 执行一条 root 命令；返回 stdout+stderr，su 不可用时返回 null */
    private fun runSuCommand(command: String): String? {
        return try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(6, TimeUnit.SECONDS)
            if (!finished) p.destroy()
            out.trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "su command failed: $command (${e.message})")
            null
        }
    }

    private fun rootAvailable(): Boolean {
        val out = runSuCommand("id") ?: return false
        return out.contains("uid=0")
    }

    private fun fail(tool: AITool, message: String): ToolResult =
        ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = message)

    private fun ok(tool: AITool, message: String): ToolResult =
        ToolResult(toolName = tool.name, success = true, result = StringResultData(message), error = "")

    private fun fmtTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    // ==================== 通讯录（query / add / delete） ====================

    open suspend fun contacts(tool: AITool): ToolResult {
        // action 缺失兜底：带电话就是新增，否则按查询
        val actionRaw = param(tool, "action")?.lowercase(Locale.ROOT)
        val action = actionRaw?.takeIf { it.isNotBlank() }
            ?: if (param(tool, "phone") != null) "add" else "query"
        val name = param(tool, "name") ?: param(tool, "query")
        return try {
            when (action) {
                "query", "search", "list" -> queryContacts(tool, name)
                "add", "create", "insert" -> addContact(tool, name)
                "delete", "remove" -> deleteContact(tool, name)
                else -> fail(tool, "Unknown action '$action'. Use query / add / delete.")
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "contacts permission denied", e)
            fail(tool, "Contacts permission denied: ${e.message}. Grant READ_CONTACTS/WRITE_CONTACTS (adb: pm grant $PKG android.permission.READ_CONTACTS).")
        } catch (e: Exception) {
            AppLogger.e(TAG, "contacts operation failed", e)
            fail(tool, "Contacts operation failed: ${e.message}")
        }
    }

    private fun queryContacts(tool: AITool, name: String?): ToolResult {
        if (!ensureRuntimePermission(Manifest.permission.READ_CONTACTS)) {
            return fail(tool, "READ_CONTACTS not granted. Run: adb shell pm grant $PKG android.permission.READ_CONTACTS")
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection: String?
        val args: Array<String>?
        if (name.isNullOrEmpty()) {
            selection = null
            args = null
        } else {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            args = arrayOf("%$name%", "%$name%")
        }
        val limit = (paramInt(tool, "limit", 20) ?: 20).coerceIn(1, 200)
        val lines = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (c.moveToNext() && lines.size < limit) {
                val display = c.getString(nameIdx) ?: "(no name)"
                val number = c.getString(numIdx) ?: ""
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources, c.getInt(typeIdx), ""
                ).toString()
                lines.add("[id=${c.getLong(idIdx)}] $display | $number | $type")
            }
        }
        return if (lines.isEmpty()) {
            ok(tool, if (name.isNullOrEmpty()) "No contacts on device." else "No contact matched '$name'.")
        } else {
            ok(tool, "Contacts (${lines.size}):\n" + lines.joinToString("\n"))
        }
    }

    private fun addContact(tool: AITool, name: String?): ToolResult {
        if (name.isNullOrEmpty()) return fail(tool, "action=add requires name")
        val phone = param(tool, "phone")
        if (phone.isNullOrEmpty()) return fail(tool, "action=add requires phone")
        if (!ensureRuntimePermission(Manifest.permission.WRITE_CONTACTS)) {
            return fail(tool, "WRITE_CONTACTS not granted. Run: adb shell pm grant $PKG android.permission.WRITE_CONTACTS")
        }
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                )
                .build()
        )
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = results.firstOrNull()?.uri?.lastPathSegment
            ?: return fail(tool, "Contact insert returned no raw contact id.")
        // 回读确认真的落库（不做"调用成功即成功"的假验收）
        var contactId: String? = null
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawId),
            null
        )?.use { c -> if (c.moveToFirst()) contactId = c.getString(0) }
        if (contactId == null) {
            return fail(tool, "Contact inserted but cannot resolve contact id (raw=$rawId).")
        }
        return ok(tool, "Contact created: $name | $phone (contact_id=$contactId)")
    }

    private fun deleteContact(tool: AITool, name: String?): ToolResult {
        if (!ensureRuntimePermission(Manifest.permission.WRITE_CONTACTS)) {
            return fail(tool, "WRITE_CONTACTS not granted. Run: adb shell pm grant $PKG android.permission.WRITE_CONTACTS")
        }
        val contactIdParam = param(tool, "contact_id")?.toLongOrNull()
        val targets = mutableListOf<Pair<Long, String>>()
        if (contactIdParam != null) {
            targets.add(contactIdParam to "(id:$contactIdParam)")
        } else {
            if (name.isNullOrEmpty()) return fail(tool, "action=delete requires name or contact_id")
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                selection,
                arrayOf("%$name%"),
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    if (targets.none { it.first == id }) {
                        targets.add(id to (c.getString(nameIdx) ?: "(no name)"))
                    }
                }
            }
        }
        if (targets.isEmpty()) return ok(tool, "No contact matched '$name' — nothing deleted.")
        val deleted = mutableListOf<String>()
        for ((id, label) in targets) {
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) deleted.add("$label (id=$id)")
        }
        return if (deleted.isEmpty()) {
            fail(tool, "Delete matched ${targets.size} contact(s) but provider deleted 0 rows.")
        } else {
            ok(tool, "Deleted ${deleted.size} contact(s): " + deleted.joinToString(", "))
        }
    }

    // ==================== 相册（list_recent） ====================

    open suspend fun gallery(tool: AITool): ToolResult {
        val action = param(tool, "action")?.lowercase(Locale.ROOT) ?: "list_recent"
        if (action !in listOf("list_recent", "list", "recent", "query")) {
            return fail(tool, "Unknown action '$action'. Only list_recent is supported.")
        }
        val mediaType = param(tool, "media_type")?.lowercase(Locale.ROOT) ?: "images"
        val needVideo = mediaType == "video" || mediaType == "videos" || mediaType == "all"
        val needImage = mediaType != "video" && mediaType != "videos"
        val limit = (paramInt(tool, "limit", 20) ?: 20).coerceIn(1, 200)
        val days = (paramInt(tool, "days", 7) ?: 7).coerceIn(0, 3650)
        val lines = mutableListOf<String>()

        try {
            if (needImage) {
                if (!ensureRuntimePermission(READ_IMAGES_PERMISSION)) {
                    return fail(tool, "READ_MEDIA_IMAGES not granted. Run: adb shell pm grant $PKG $READ_IMAGES_PERMISSION")
                }
                collectRecent(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", days, limit, lines)
            }
            if (needVideo && lines.size < limit) {
                if (!ensureRuntimePermission(READ_VIDEO_PERMISSION)) {
                    if (needImage) {
                        lines.add("(videos skipped: READ_MEDIA_VIDEO not granted)")
                    } else {
                        return fail(tool, "READ_MEDIA_VIDEO not granted. Run: adb shell pm grant $PKG $READ_VIDEO_PERMISSION")
                    }
                } else {
                    collectRecent(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", days, limit, lines)
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "gallery permission denied", e)
            return fail(tool, "Media permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "gallery query failed", e)
            return fail(tool, "Gallery query failed: ${e.message}")
        }

        return if (lines.isEmpty()) {
            ok(tool, "No ${mediaType} in the last $days day(s).")
        } else {
            val head = "Recent $mediaType (${lines.size}${if (days > 0) ", last ${days}d" else ", all time"}):"
            ok(tool, head + "\n" + lines.joinToString("\n"))
        }
    }

    private fun collectRecent(
        uri: Uri,
        kind: String,
        days: Int,
        limit: Int,
        out: MutableList<String>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE
        )
        val selection: String?
        val args: Array<String>?
        if (days > 0) {
            val from = System.currentTimeMillis() / 1000 - days.toLong() * 86_400L
            selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
            args = arrayOf(from.toString())
        } else {
            selection = null
            args = null
        }
        context.contentResolver.query(
            uri,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext() && out.size < limit) {
                val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                val displayName = if (nameIdx >= 0) c.getString(nameIdx) else null
                val addedSec = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                val path = if (dataIdx >= 0) c.getString(dataIdx) else null
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                val contentUri = ContentUris.withAppendedId(uri, id)
                val sb = StringBuilder()
                sb.append(out.size + 1).append(". ")
                sb.append(fmtTime(addedSec * 1000L))
                sb.append(" | ").append(kind)
                sb.append(" | ").append(path ?: displayName ?: "(no path)")
                sb.append(" | ").append(formatSize(size))
                sb.append(" | ").append(contentUri)
                out.add(sb.toString())
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ==================== 文件管理（copy / move / search） ====================

    open suspend fun fileManager(tool: AITool): ToolResult {
        val actionRaw = param(tool, "action")?.lowercase(Locale.ROOT)
        val action = actionRaw?.takeIf { it.isNotBlank() }
            ?: if (param(tool, "source") != null && param(tool, "destination") != null) "copy" else "search"
        return try {
            when (action) {
                "copy", "cp" -> copyOrMove(tool, move = false)
                "move", "mv", "rename" -> copyOrMove(tool, move = true)
                "search", "search_files", "find" -> searchFiles(tool)
                else -> fail(tool, "Unknown action '$action'. Use copy / move / search.")
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "file_manager permission denied", e)
            fail(tool, "File operation permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "file_manager failed", e)
            fail(tool, "File operation failed: ${e.message}")
        }
    }

    private fun copyOrMove(tool: AITool, move: Boolean): ToolResult {
        val verb = if (move) "move" else "copy"
        val sourcePath = param(tool, "source")
            ?: return fail(tool, "action=$verb requires source")
        val destPath = param(tool, "destination")
            ?: return fail(tool, "action=$verb requires destination")
        val source = File(sourcePath)
        if (!source.exists()) return fail(tool, "Source not found: $sourcePath")
        var dest = File(destPath)
        // 目标是已存在目录 → 落到该目录下同名文件
        if (dest.isDirectory) dest = File(dest, source.name)
        if (dest.exists() && param(tool, "overwrite") != "true") {
            return fail(tool, "Destination already exists: ${dest.absolutePath}. Pass overwrite=true to replace it.")
        }
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }

        if (move) {
            if (source.renameTo(dest)) {
                return ok(tool, "Moved: ${source.absolutePath} -> ${dest.absolutePath}")
            }
        }
        val copied = if (source.isDirectory) {
            val target = dest
            var count = 0
            source.copyRecursively(target, overwrite = true)
            source.walkTopDown().forEach { if (it.isFile) count++ }
            count
        } else {
            source.copyTo(dest, overwrite = true)
            1
        }
        if (move) {
            val removed = source.deleteRecursively()
            if (!removed) {
                return ok(tool, "Copied to ${dest.absolutePath} but failed to remove source ${source.absolutePath} ($copied file(s) copied).")
            }
        }
        val unit = if (source.isDirectory) "$copied file(s)" else formatSize(dest.length())
        return ok(tool, "${verb.replaceFirstChar { it.uppercase() }}d: ${source.absolutePath} -> ${dest.absolutePath} ($unit)")
    }

    private fun searchFiles(tool: AITool): ToolResult {
        val rootPath = param(tool, "path") ?: "/sdcard"
        val root = File(rootPath)
        if (!root.exists()) return fail(tool, "Search path not found: $rootPath")
        if (!root.canRead()) return fail(tool, "Search path not readable: $rootPath (grant MANAGE_EXTERNAL_STORAGE)")
        val pattern = param(tool, "pattern") ?: "*"
        val limit = (paramInt(tool, "limit", 50) ?: 50).coerceIn(1, 500)
        val maxDepth = (paramInt(tool, "max_depth", 6) ?: 6).coerceIn(0, 20)
        val regex = globToRegex(pattern)
        val skip = SKIP_DIR_NAMES.split(",")
        val results = mutableListOf<String>()
        var scanned = 0
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && results.size < limit && scanned < 200_000) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                scanned++
                val rel = relativeToRoot(child)
                if (child.isDirectory) {
                    if (skip.any { rel == it || rel.startsWith("$it/") }) continue
                    if (depth < maxDepth) stack.addLast(child to depth + 1)
                } else if (regex.matches(child.name)) {
                    results.add(
                        "${child.absolutePath} | ${formatSize(child.length())} | ${fmtTime(child.lastModified())}"
                    )
                    if (results.size >= limit) break
                }
            }
        }
        return if (results.isEmpty()) {
            ok(tool, "No file matched '$pattern' under $rootPath (scanned $scanned entries).")
        } else {
            ok(tool, "Matched ${results.size} file(s) for '$pattern' under $rootPath:\n" + results.joinToString("\n"))
        }
    }

    private fun relativeToRoot(file: File): String {
        val marker = "/sdcard/"
        val abs = file.absolutePath
        return if (abs.startsWith(marker)) abs.removePrefix(marker) else abs.removePrefix("/")
    }

    private fun globToRegex(pattern: String): Regex {
        // 没有通配符就当子串匹配，模型写 "IMG" 也能找到 IMG_0001.jpg
        if (!pattern.contains('*') && !pattern.contains('?')) {
            return Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
        }
        val sb = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex("^$sb$", RegexOption.IGNORE_CASE)
    }

    // ==================== 设备设置（get / set） ====================

    open suspend fun settings(tool: AITool): ToolResult {
        val action = param(tool, "action")?.lowercase(Locale.ROOT) ?: "get"
        val key = param(tool, "key")?.lowercase(Locale.ROOT)
            ?: return fail(tool, "settings requires key: brightness / media_volume / ring_volume / dnd / bluetooth / wifi")
        return try {
            when (action) {
                "get" -> settingsGet(tool, key)
                "set" -> settingsSet(tool, key)
                else -> fail(tool, "Unknown action '$action'. Use get / set.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "settings operation failed", e)
            fail(tool, "Settings operation failed: ${e.message}")
        }
    }

    private fun settingsGet(tool: AITool, key: String): ToolResult {
        val text = when (key) {
            "brightness" -> {
                val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                val mode = Settings.System.getInt(
                    context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val auto = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                "brightness=${if (value < 0) "unknown" else "$value/255 (${value * 100 / 255}%)"}, mode=${if (auto) "auto" else "manual"}"
            }

            "media_volume" -> {
                val am = audioManager()
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                "media_volume=$cur/$max (${if (max > 0) cur * 100 / max else 0}%)"
            }

            "ring_volume" -> {
                val am = audioManager()
                val cur = am.getStreamVolume(AudioManager.STREAM_RING)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                "ring_volume=$cur/$max (${if (max > 0) cur * 100 / max else 0}%)"
            }

            "dnd" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val filter = nm.currentInterruptionFilter
                "dnd=${filterName(filter)}, policy_access=${nm.isNotificationPolicyAccessGranted}"
            }

            "bluetooth" -> {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    "bluetooth=unsupported (no adapter)"
                } else {
                    val enabled = try {
                        adapter.isEnabled
                    } catch (e: SecurityException) {
                        return fail(tool, "BLUETOOTH_CONNECT not granted. Run: adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT")
                    }
                    "bluetooth=${if (enabled) "on" else "off"}"
                }
            }

            "wifi" -> {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val enabled = try {
                    wm.isWifiEnabled
                } catch (e: Exception) {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 1
                }
                "wifi=${if (enabled) "on" else "off"}"
            }

            else -> return fail(
                tool,
                "Unsupported key '$key'. Supported: brightness / media_volume / ring_volume / dnd / bluetooth / wifi"
            )
        }
        return ok(tool, text)
    }

    private fun settingsSet(tool: AITool, key: String): ToolResult {
        val rawValue = param(tool, "value")
            ?: return fail(tool, "action=set requires value")
        return when (key) {
            "brightness" -> setBrightness(tool, rawValue)
            "media_volume" -> setVolume(tool, AudioManager.STREAM_MUSIC, "media_volume", rawValue)
            "ring_volume" -> setVolume(tool, AudioManager.STREAM_RING, "ring_volume", rawValue)
            "dnd" -> setDnd(tool, rawValue)
            "bluetooth" -> setToggle(tool, "bluetooth", rawValue)
            "wifi" -> setToggle(tool, "wifi", rawValue)
            else -> fail(
                tool,
                "Unsupported key '$key'. Supported: brightness / media_volume / ring_volume / dnd / bluetooth / wifi"
            )
        }
    }

    private fun setBrightness(tool: AITool, rawValue: String): ToolResult {
        if (!Settings.System.canWrite(context)) {
            // 有 root 时先尝试 appops 放行，仍不行则如实报错，不装懂
            runSuCommand("appops set $PKG WRITE_SETTINGS allow")
            if (!Settings.System.canWrite(context)) {
                return fail(
                    tool,
                    "WRITE_SETTINGS not granted. Run: adb shell appops set $PKG WRITE_SETTINGS allow then retry."
                )
            }
        }
        val isPercent = rawValue.endsWith("%") || param(tool, "unit")?.lowercase(Locale.ROOT) == "percent"
        val num = rawValue.removeSuffix("%").toIntOrNull()
            ?: return fail(tool, "brightness value must be an integer (0-255 or 0-100%)")
        val brightness = if (isPercent) (num.coerceIn(0, 100) * 255 / 100) else num.coerceIn(0, 255)
        // 先关自动亮度，否则手动值不生效
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        val written = Settings.System.putInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness
        )
        if (!written) return fail(tool, "Settings.System.putInt(SCREEN_BRIGHTNESS=$brightness) returned false")
        context.contentResolver.notifyChange(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null
        )
        val readBack = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return ok(
            tool,
            "brightness set to $brightness/255 (read-back=$readBack, mode=manual). Note: the system may clamp the value to its own minimum (often 1)."
        )
    }

    private fun setVolume(tool: AITool, stream: Int, label: String, rawValue: String): ToolResult {
        val am = audioManager()
        val max = am.getStreamMaxVolume(stream)
        val isPercent = rawValue.endsWith("%") || param(tool, "unit")?.lowercase(Locale.ROOT) == "percent"
        val num = rawValue.removeSuffix("%").toIntOrNull()
            ?: return fail(tool, "$label value must be an integer (0-100% or raw index)")
        val index = if (isPercent) {
            (num.coerceIn(0, 100) * max + 50) / 100
        } else {
            num.coerceIn(0, max)
        }
        return try {
            am.setStreamVolume(stream, index, 0)
            val readBack = am.getStreamVolume(stream)
            ok(tool, "$label set to $index/$max (read-back=$readBack, request=${rawValue})")
        } catch (e: SecurityException) {
            fail(tool, "$label failed: ${e.message}. MODIFY_AUDIO_SETTINGS may be missing.")
        }
    }

    private fun setDnd(tool: AITool, rawValue: String): ToolResult {
        val filter = when (rawValue.lowercase(Locale.ROOT)) {
            "off", "all", "0" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority", "1" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "none", "on", "total", "2" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "alarms", "3" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> return fail(tool, "dnd value must be off / priority / alarms / none")
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(filter)
            val readBack = nm.currentInterruptionFilter
            return ok(tool, "dnd set to ${filterName(filter)} (read-back=${filterName(readBack)})")
        }
        // 无免打扰访问权：走 root。Android 上 zen_mode 不能直接 settings put 生效（会被系统忽略），
        // 正确的 shell 通道是 cmd notification set_dnd on|off（本机 Android 17 实测 0↔2 生效）。
        val wantSilence = filter != NotificationManager.INTERRUPTION_FILTER_ALL
        val cmd = "cmd notification set_dnd ${if (wantSilence) "on" else "off"}"
        val out = runSuCommand(cmd)
        if (wantSilence && filter != NotificationManager.INTERRUPTION_FILTER_NONE) {
            // priority/alarms 只能靠系统策略服务精确设置；root 下先尝试 zen_mode，再如实报告实际状态
            runSuCommand(
                "settings put global zen_mode " + if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) 1 else 3
            )
        }
        Thread.sleep(600)
        val readBack = Settings.Global.getInt(context.contentResolver, "zen_mode", -1)
        val achieved = filterName(readBack)
        return if (wantSilence == (readBack != 0) && readBack != -1) {
            if (filter != NotificationManager.INTERRUPTION_FILTER_NONE &&
                readBack != when (filter) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> 1
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> 3
                    else -> readBack
                }
            ) {
                ok(
                    tool,
                    "dnd set to $achieved via root ($cmd, zen_mode=$readBack) — exact filter ${filterName(filter)} " +
                        "needs notification-policy access; granting it is the supported path."
                )
            } else {
                ok(tool, "dnd set to $achieved via root ($cmd, zen_mode=$readBack)")
            }
        } else {
            fail(
                tool,
                "dnd set failed: notification policy access not granted and root fallback did not take effect " +
                    "(cmd output=${out ?: "su unavailable"}, zen_mode read-back=$readBack). " +
                    "Grant Do Not Disturb access in system settings."
            )
        }
    }

    private fun setToggle(tool: AITool, label: String, rawValue: String): ToolResult {
        val wantOn = when (rawValue.lowercase(Locale.ROOT)) {
            "on", "true", "1", "enable", "enabled" -> true
            "off", "false", "0", "disable", "disabled" -> false
            else -> return fail(tool, "$label value must be on or off")
        }
        val root = rootAvailable()
        if (label == "wifi") {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            var applied = false
            try {
                applied = wm.setWifiEnabled(wantOn)
            } catch (e: Exception) {
                AppLogger.w(TAG, "WifiManager.setWifiEnabled threw: ${e.message}")
            }
            if (!applied) {
                if (!root) {
                    return fail(
                        tool,
                        "wifi toggle not allowed for normal apps on Android 10+ (WifiManager.setWifiEnabled returns false) and su is unavailable. " +
                            "Ask the user to toggle Wi-Fi in the system panel."
                    )
                }
                runSuCommand("svc wifi ${if (wantOn) "enable" else "disable"}")
            }
            Thread.sleep(1200)
            val readBack = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 1
            val via = if (applied) "WifiManager" else "root svc wifi"
            return if (readBack == wantOn) {
                ok(tool, "wifi=${if (readBack) "on" else "off"} (via $via, read-back=WIFI_ON=$readBack)")
            } else {
                fail(tool, "wifi toggle did not take effect (via $via, WIFI_ON=$readBack)")
            }
        }
        // bluetooth
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            ?: return fail(tool, "bluetooth unsupported: no adapter on this device")
        ensureRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
        var applied = false
        try {
            applied = if (wantOn) adapter.enable() else adapter.disable()
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "BluetoothAdapter.enable SecurityException: ${e.message}")
        }
        if (!applied) {
            if (!root) {
                return fail(
                    tool,
                    "bluetooth toggle not allowed for normal apps on Android 13+ (BluetoothAdapter.enable returns false) and su is unavailable. " +
                        "Ask the user to toggle Bluetooth in Quick Settings."
                )
            }
            runSuCommand("svc bluetooth ${if (wantOn) "enable" else "disable"}")
        }
        Thread.sleep(1500)
        val readBack = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            false
        }
        val via = if (applied) "BluetoothAdapter" else "root svc bluetooth"
        return if (readBack == wantOn) {
            ok(tool, "bluetooth=${if (readBack) "on" else "off"} (via $via, read-back=$readBack)")
        } else {
            fail(tool, "bluetooth toggle did not take effect (via $via, read-back=$readBack)")
        }
    }

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off(all allowed)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none(silent)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        else -> "unknown($filter)"
    }

    private fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
