package com.ai.assistance.operit.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ai.assistance.operit.data.model.UserMemoryFormat
import com.ai.assistance.operit.data.model.UserMemoryItem
import com.ai.assistance.operit.data.model.UserMemoryKind
import com.ai.assistance.operit.data.model.UserMemoryStatus
import com.ai.assistance.operit.data.model.UserMemoryType
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 写入结果：id + 是否命中去重 + 命中的冲突条目 */
data class UserMemoryWriteResult(
        val id: String,
        val deduped: Boolean,
        val conflictWithId: String?
)

/** 审计记录（memory_audit 一行） */
data class UserMemoryAuditEntry(
        val id: String,
        val itemId: String,
        val action: String,
        val actor: String,
        val beforeJson: String?,
        val afterJson: String?,
        val at: Long
)

/**
 * 用户记忆数据库（独立 SQLite 文件 user_memory.db）。
 *
 * 与 Room 的 app_database 完全隔离：同包不同库文件，互不影响 schema/migration，
 * 也不参与 Room 的备份/恢复流程（P0 有意如此，避免和 app_database 版本升级互相牵扯）。
 */
class UserMemoryStore private constructor(context: Context) :
        SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "UserMemoryStore"
        private const val DB_NAME = "user_memory.db"
        private const val DB_VERSION = 1

        const val TABLE_ITEM = "memory_item"
        const val TABLE_AUDIT = "memory_audit"

        @Volatile private var INSTANCE: UserMemoryStore? = null

        fun getInstance(context: Context): UserMemoryStore =
                INSTANCE
                        ?: synchronized(this) {
                            INSTANCE ?: UserMemoryStore(context).also { INSTANCE = it }
                        }
    }

    /** 任何写操作后自增，UI 用它触发刷新 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_item (
                  id            TEXT PRIMARY KEY NOT NULL,
                  kind          TEXT NOT NULL,
                  type          TEXT NOT NULL,
                  title         TEXT NOT NULL,
                  value_json    TEXT NOT NULL,
                  normalized    TEXT NOT NULL,
                  subject       TEXT NOT NULL DEFAULT '',
                  source        TEXT NOT NULL,
                  source_app    TEXT,
                  source_uri    TEXT,
                  confidence    REAL NOT NULL DEFAULT 1.0,
                  sensitivity   INTEGER NOT NULL DEFAULT 0,
                  user_verified INTEGER NOT NULL DEFAULT 0,
                  status        TEXT NOT NULL DEFAULT 'active',
                  conflict_with TEXT,
                  created_at    INTEGER NOT NULL,
                  updated_at    INTEGER NOT NULL,
                  last_used_at  INTEGER,
                  expires_at    INTEGER
                )
                """
                        .trimIndent()
        )
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_item_type_updated ON memory_item(type, updated_at)"
        )
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_item_source_app ON memory_item(source_app)"
        )
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_item_sensitivity ON memory_item(sensitivity)"
        )
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_item_status ON memory_item(status)"
        )
        db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_audit (
                  id          TEXT PRIMARY KEY NOT NULL,
                  item_id     TEXT NOT NULL,
                  action      TEXT NOT NULL,
                  actor       TEXT NOT NULL,
                  before_json TEXT,
                  after_json  TEXT,
                  at          INTEGER NOT NULL
                )
                """
                        .trimIndent()
        )
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_audit_item ON memory_audit(item_id, at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // P0 只有 v1；后续加列走 ALTER TABLE，不要把用户记忆清库
        AppLogger.w(TAG, "onUpgrade $oldVersion -> $newVersion (no-op)")
    }

    // ==================== 写入 ====================

    /**
     * 插入一条记忆。
     * - 去重：同 type + 同 normalized 的 active/pending 条目直接复用（更新 updated_at）
     * - 冲突：同 type + 同 subject 但值不同 → 两条都标 conflict（不静默覆盖）
     */
    fun insert(
            title: String,
            value: String,
            type: String,
            kind: String,
            subject: String?,
            source: String,
            sourceApp: String?,
            sourceUri: String?,
            confidence: Double,
            sensitivity: Int,
            userVerified: Boolean,
            status: String,
            actor: String
    ): UserMemoryWriteResult {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val id = UserMemoryFormat.newId()
        val valueJson = UserMemoryFormat.toValueJson(value)
        val normalized = UserMemoryFormat.normalize(UserMemoryFormat.plainValueOf(valueJson))
        val subjectKey = UserMemoryFormat.subjectKey(type, subject, title)

        // 1) 去重
        val dup = findByTypeAndNormalized(db, type, normalized, statuses = null)
        if (dup != null) {
            val cv = ContentValues()
            cv.put("updated_at", now)
            cv.put("title", title)
            cv.put("confidence", maxOf(dup.confidence, confidence))
            if (userVerified) cv.put("user_verified", 1)
            db.update(TABLE_ITEM, cv, "id = ?", arrayOf(dup.id))
            audit(db, dup.id, "update", actor, dup.toJson().toString(), null, now)
            bump()
            AppLogger.d(TAG, "insert deduped into existing id=${dup.id}")
            return UserMemoryWriteResult(dup.id, deduped = true, conflictWithId = null)
        }

        // 2) 冲突检测（只和 active 的旧值比；pending 的旧值不构成冲突）
        // 同 type 且（同主体键 或 同标题）都算"同一条事实的不同版本"——模型给的 subject 不一致时
        // 仍能靠标题兜住，避免静默覆盖。
        val conflicting = findActiveConflicts(db, type, subjectKey, title)
        var firstConflictId: String? = null
        for (old in conflicting) {
            if (old.normalized == normalized) continue
            if (firstConflictId == null) firstConflictId = old.id
            val cv = ContentValues()
            cv.put("status", UserMemoryStatus.CONFLICT)
            cv.put("conflict_with", id)
            cv.put("updated_at", now)
            db.update(TABLE_ITEM, cv, "id = ?", arrayOf(old.id))
            audit(
                    db,
                    old.id,
                    "conflict",
                    "system",
                    old.toJson().toString(),
                    JSONObject().put("conflict_with", id).toString(),
                    now
            )
        }

        val cv = ContentValues()
        cv.put("id", id)
        cv.put("kind", kind)
        cv.put("type", type)
        cv.put("title", title)
        cv.put("value_json", valueJson)
        cv.put("normalized", normalized)
        cv.put("subject", subjectKey)
        cv.put("source", source)
        cv.put("source_app", sourceApp)
        cv.put("source_uri", sourceUri)
        cv.put("confidence", confidence)
        cv.put("sensitivity", sensitivity)
        cv.put("user_verified", if (userVerified) 1 else 0)
        cv.put(
                "status",
                if (firstConflictId != null) UserMemoryStatus.CONFLICT else status
        )
        cv.put("conflict_with", firstConflictId)
        cv.put("created_at", now)
        cv.put("updated_at", now)
        cv.put("expires_at", UserMemoryFormat.defaultExpiry(type, kind, now))
        db.insertWithOnConflict(TABLE_ITEM, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        val inserted = get(db, id)
        audit(db, id, "create", actor, null, inserted?.toJson()?.toString(), now)
        bump()
        AppLogger.d(TAG, "insert id=$id type=$type status=${inserted?.status} conflict=$firstConflictId")
        return UserMemoryWriteResult(id, deduped = false, conflictWithId = firstConflictId)
    }

    /** 确认卡「保存」：pending/conflict → active，confidence=1，user_verified=1 */
    fun confirm(id: String, actor: String = "user"): Boolean {
        val db = writableDatabase
        val before = get(db, id) ?: return false
        if (before.status == UserMemoryStatus.ACTIVE) return false
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("status", UserMemoryStatus.ACTIVE)
        cv.put("user_verified", 1)
        cv.put("confidence", 1.0)
        cv.put("updated_at", now)
        val ok = db.update(TABLE_ITEM, cv, "id = ?", arrayOf(id)) > 0
        val after = get(db, id)
        audit(db, id, "confirm", actor, before.toJson().toString(), after?.toJson()?.toString(), now)
        bump()
        AppLogger.d(TAG, "confirm id=$id ok=$ok")
        return ok
    }

    /** 确认卡「放弃」：直接删掉 pending 条目，并留审计 */
    fun reject(id: String, actor: String = "user"): Boolean {
        val db = writableDatabase
        val before = get(db, id) ?: return false
        val deleted = db.delete(TABLE_ITEM, "id = ? AND status = ?", arrayOf(id, UserMemoryStatus.PENDING))
        audit(db, id, "reject", actor, before.toJson().toString(), null, System.currentTimeMillis())
        bump()
        return deleted > 0
    }

    /** 详情页编辑：标题 / 内容 / 类型 / 敏感级别 */
    fun update(
            id: String,
            title: String? = null,
            value: String? = null,
            type: String? = null,
            sensitivity: Int? = null,
            actor: String = "user"
    ): Boolean {
        val db = writableDatabase
        val before = get(db, id) ?: return false
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        title?.takeIf { it.isNotBlank() }?.let { cv.put("title", it) }
        value?.let {
            val valueJson = UserMemoryFormat.toValueJson(it)
            cv.put("value_json", valueJson)
            cv.put(
                    "normalized",
                    UserMemoryFormat.normalize(UserMemoryFormat.plainValueOf(valueJson))
            )
        }
        type?.let { cv.put("type", it) }
        sensitivity?.let { cv.put("sensitivity", it.coerceIn(0, 2)) }
        val finalType = cv.getAsString("type") ?: before.type
        val finalTitle = cv.getAsString("title") ?: before.title
        cv.put("subject", UserMemoryFormat.subjectKey(finalType, null, finalTitle))
        cv.put("updated_at", now)
        val ok = db.update(TABLE_ITEM, cv, "id = ?", arrayOf(id)) > 0
        val after = get(db, id)
        audit(db, id, "update", actor, before.toJson().toString(), after?.toJson()?.toString(), now)
        bump()
        return ok
    }

    /** 删除：物理删除 item 行，审计里保留 before_json 以便追溯 */
    fun delete(id: String, actor: String = "user"): Boolean {
        val db = writableDatabase
        val before = get(db, id) ?: return false
        val deleted = db.delete(TABLE_ITEM, "id = ?", arrayOf(id))
        // 冲突对方解除冲突标记
        before.conflictWith?.let { other ->
            val cv = ContentValues()
            cv.put("status", UserMemoryStatus.ACTIVE)
            cv.putNull("conflict_with")
            db.update(TABLE_ITEM, cv, "id = ? AND status = ?", arrayOf(other, UserMemoryStatus.CONFLICT))
        }
        audit(db, id, "delete", actor, before.toJson().toString(), null, System.currentTimeMillis())
        bump()
        AppLogger.d(TAG, "delete id=$id rows=$deleted")
        return deleted > 0
    }

    /** 冲突页「选这条对」：把选中项恢复 active，另一条降为 rejected 状态并留审计 */
    fun resolveConflict(id: String, actor: String = "user"): Boolean {
        val db = writableDatabase
        val item = get(db, id) ?: return false
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        cv.put("status", UserMemoryStatus.ACTIVE)
        cv.putNull("conflict_with")
        cv.put("updated_at", now)
        cv.put("user_verified", 1)
        cv.put("confidence", 1.0)
        db.update(TABLE_ITEM, cv, "id = ?", arrayOf(id))
        item.conflictWith?.let { other ->
            delete(other, actor)
        }
        audit(db, id, "resolve_conflict", actor, item.toJson().toString(), null, now)
        bump()
        return true
    }

    // ==================== 读取 ====================

    fun get(id: String): UserMemoryItem? = get(readableDatabase, id)

    private fun get(db: SQLiteDatabase, id: String): UserMemoryItem? {
        db.query(TABLE_ITEM, null, "id = ?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) cursorToItem(c) else null
        }
    }

    fun list(
            query: String? = null,
            type: String? = null,
            status: String? = null,
            limit: Int = 200
    ): List<UserMemoryItem> {
        val db = readableDatabase
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!type.isNullOrBlank() && type != "all") {
            where.add("type = ?")
            args.add(type)
        }
        when (status) {
            UserMemoryStatus.PENDING -> where.add("status = 'pending'")
            UserMemoryStatus.CONFLICT -> where.add("status = 'conflict'")
            else -> { /* 全部：active + pending + conflict 都要可见，冲突项不能藏起来 */ }
        }
        val selection = if (where.isEmpty()) null else where.joinToString(" AND ")
        val rows = mutableListOf<UserMemoryItem>()
        db.query(TABLE_ITEM, null, selection, args.takeIf { it.isNotEmpty() }?.toTypedArray(), null, null, "updated_at DESC", limit.toString())
                .use { c ->
                    while (c.moveToNext()) rows.add(cursorToItem(c))
                }
        val tokens = query?.let { UserMemoryFormat.queryTokens(it) }.orEmpty()
        if (tokens.isEmpty()) return rows
        return rows.map { it to UserMemoryFormat.matchScore(tokens, it.title, it.normalized) }
                .filter { it.second > 0.0 }
                .sortedWith(compareByDescending<Pair<UserMemoryItem, Double>> { it.second }.thenByDescending { it.first.updatedAt })
                .map { it.first }
    }

    fun countByStatus(status: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_ITEM WHERE status = ?", arrayOf(status)).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 对话前召回：只用 active、未过期、sensitivity <= maxSensitivity 的条目。
     * 命中后写 last_used_at 并记一条 read 审计（报告要求可追溯"谁在什么时候用了哪条记忆"）。
     */
    fun recall(query: String, limit: Int = 8, maxSensitivity: Int = 1): List<UserMemoryItem> {
        val tokens = UserMemoryFormat.queryTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val candidates =
                list().filter {
                    it.status == UserMemoryStatus.ACTIVE &&
                            it.sensitivity <= maxSensitivity &&
                            (it.expiresAt == null || it.expiresAt > now)
                }
        val scored =
                candidates
                        .map { item ->
                            var score = UserMemoryFormat.matchScore(tokens, item.title, item.normalized)
                            if (score <= 0.0) return@map item to 0.0
                            // 使用频次 + 时间衰减 + 置信度（报告里的重排要素）
                            score += item.confidence * 0.5
                            if (item.userVerified) score += 0.4
                            val ageDays = ((now - item.updatedAt) / 86_400_000.0).coerceAtLeast(0.0)
                            score += (1.0 / (1.0 + ageDays / 30.0)) * 0.3
                            item to score
                        }
                        .filter { it.second > 0.0 }
                        .sortedByDescending { it.second }
                        .take(limit)
                        .map { it.first }
        if (scored.isNotEmpty()) {
            val db = writableDatabase
            for (item in scored) {
                val cv = ContentValues()
                cv.put("last_used_at", now)
                db.update(TABLE_ITEM, cv, "id = ?", arrayOf(item.id))
                audit(db, item.id, "read", "agent", null, null, now)
            }
        }
        return scored
    }

    fun auditTrail(itemId: String, limit: Int = 50): List<UserMemoryAuditEntry> {
        return readableDatabase
                .query(
                        TABLE_AUDIT,
                        null,
                        "item_id = ?",
                        arrayOf(itemId),
                        null,
                        null,
                        "at DESC",
                        limit.toString()
                )
                .use { c ->
                    val out = mutableListOf<UserMemoryAuditEntry>()
                    while (c.moveToNext()) {
                        out.add(
                                UserMemoryAuditEntry(
                                        id = c.getString(c.getColumnIndexOrThrow("id")),
                                        itemId = c.getString(c.getColumnIndexOrThrow("item_id")),
                                        action = c.getString(c.getColumnIndexOrThrow("action")),
                                        actor = c.getString(c.getColumnIndexOrThrow("actor")),
                                        beforeJson = c.getString(c.getColumnIndexOrThrow("before_json")),
                                        afterJson = c.getString(c.getColumnIndexOrThrow("after_json")),
                                        at = c.getLong(c.getColumnIndexOrThrow("at"))
                                )
                        )
                    }
                    out
                }
    }

    fun auditCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_AUDIT", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ==================== 内部 ====================

    private fun findByTypeAndNormalized(
            db: SQLiteDatabase,
            type: String,
            normalized: String,
            statuses: List<String>?
    ): UserMemoryItem? {
        val selection =
                if (statuses.isNullOrEmpty()) "type = ? AND normalized = ? AND status IN ('active','pending','conflict')"
                else "type = ? AND normalized = ? AND status IN (${statuses.joinToString(",") { "'$it'" }})"
        db.query(TABLE_ITEM, null, selection, arrayOf(type, normalized), null, null, "updated_at DESC", "1")
                .use { c ->
                    return if (c.moveToFirst()) cursorToItem(c) else null
                }
    }

    private fun findActiveConflicts(
            db: SQLiteDatabase,
            type: String,
            subjectKey: String,
            title: String
    ): List<UserMemoryItem> {
        val out = mutableListOf<UserMemoryItem>()
        db.query(
                        TABLE_ITEM,
                        null,
                        "type = ? AND status = ? AND (subject = ? OR title = ?)",
                        arrayOf(type, UserMemoryStatus.ACTIVE, subjectKey, title),
                        null,
                        null,
                        "updated_at DESC",
                        "5"
                )
                .use { c ->
                    while (c.moveToNext()) out.add(cursorToItem(c))
                }
        return out
    }

    private fun cursorToItem(c: Cursor): UserMemoryItem =
            UserMemoryItem(
                    id = c.getString(c.getColumnIndexOrThrow("id")),
                    kind = c.getString(c.getColumnIndexOrThrow("kind")),
                    type = c.getString(c.getColumnIndexOrThrow("type")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    valueJson = c.getString(c.getColumnIndexOrThrow("value_json")),
                    normalized = c.getString(c.getColumnIndexOrThrow("normalized")),
                    subject = c.getString(c.getColumnIndexOrThrow("subject")) ?: "",
                    source = c.getString(c.getColumnIndexOrThrow("source")),
                    sourceApp = c.getString(c.getColumnIndexOrThrow("source_app")),
                    sourceUri = c.getString(c.getColumnIndexOrThrow("source_uri")),
                    confidence = c.getDouble(c.getColumnIndexOrThrow("confidence")),
                    sensitivity = c.getInt(c.getColumnIndexOrThrow("sensitivity")),
                    userVerified = c.getInt(c.getColumnIndexOrThrow("user_verified")) == 1,
                    status = c.getString(c.getColumnIndexOrThrow("status")),
                    conflictWith = c.getString(c.getColumnIndexOrThrow("conflict_with")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                    lastUsedAt = c.getLongOrNull(c, "last_used_at"),
                    expiresAt = c.getLongOrNull(c, "expires_at")
            )

    private fun Cursor.getLongOrNull(c: Cursor, column: String): Long? {
        val idx = c.getColumnIndexOrThrow(column)
        return if (c.isNull(idx)) null else c.getLong(idx)
    }

    private fun UserMemoryItem.toJson(): JSONObject =
            JSONObject().apply {
                put("id", id)
                put("kind", kind)
                put("type", type)
                put("title", title)
                put("value_json", valueJson)
                put("subject", subject)
                put("source", source)
                put("source_app", sourceApp ?: "")
                put("source_uri", sourceUri ?: "")
                put("confidence", confidence)
                put("sensitivity", sensitivity)
                put("user_verified", userVerified)
                put("status", status)
                put("conflict_with", conflictWith ?: "")
                put("created_at", createdAt)
                put("updated_at", updatedAt)
                put("last_used_at", lastUsedAt ?: 0L)
                put("expires_at", expiresAt ?: 0L)
            }

    private fun audit(
            db: SQLiteDatabase,
            itemId: String,
            action: String,
            actor: String,
            beforeJson: String?,
            afterJson: String?,
            at: Long
    ) {
        val cv = ContentValues()
        cv.put("id", UUID.randomUUID().toString())
        cv.put("item_id", itemId)
        cv.put("action", action)
        cv.put("actor", actor)
        cv.put("before_json", beforeJson)
        cv.put("after_json", afterJson)
        cv.put("at", at)
        try {
            db.insert(TABLE_AUDIT, null, cv)
        } catch (e: Exception) {
            AppLogger.e(TAG, "audit insert failed action=$action item=$itemId", e)
        }
    }
}
