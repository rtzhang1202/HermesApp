package com.ai.assistance.operit.ui.features.usermemory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.UserMemoryItem
import com.ai.assistance.operit.data.model.UserMemoryKind
import com.ai.assistance.operit.data.model.UserMemorySource
import com.ai.assistance.operit.data.model.UserMemoryStatus
import com.ai.assistance.operit.data.model.UserMemoryType
import com.ai.assistance.operit.data.repository.UserMemoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 类型 / 来源 / 状态 的中文（或英文）标签，主页和确认卡共用 */
@Composable
fun userMemoryTypeLabel(type: String): String =
        stringResource(
                when (type) {
                    UserMemoryType.ID_CARD -> R.string.user_memory_type_id_card
                    UserMemoryType.BANK_CARD -> R.string.user_memory_type_id_card
                    UserMemoryType.ADDRESS -> R.string.user_memory_type_address
                    UserMemoryType.PREFERENCE -> R.string.user_memory_type_preference
                    UserMemoryType.SCHEDULE -> R.string.user_memory_type_schedule
                    UserMemoryType.ACCOUNT -> R.string.user_memory_type_account
                    UserMemoryType.OBJECT_LOCATION -> R.string.user_memory_type_object_location
                    UserMemoryType.RELATION -> R.string.user_memory_type_relation
                    UserMemoryType.EVENT -> R.string.user_memory_type_event
                    else -> R.string.user_memory_type_other
                }
        )

@Composable
fun userMemorySourceLabel(source: String): String =
        stringResource(
                when (source) {
                    UserMemorySource.SCREEN -> R.string.user_memory_source_screen
                    UserMemorySource.CHAT -> R.string.user_memory_source_chat
                    UserMemorySource.IMPORT -> R.string.user_memory_source_import
                    UserMemorySource.SYSTEM -> R.string.user_memory_source_system
                    else -> R.string.user_memory_source_manual
                }
        )

private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun StatusBadge(item: UserMemoryItem) {
    val (textRes, color) =
            when {
                item.hasConflict ->
                        R.string.user_memory_status_conflict to MaterialTheme.colorScheme.error
                item.isPending ->
                        R.string.user_memory_status_pending to Color(0xFFF59E0B)
                else ->
                        R.string.user_memory_status_confirmed to Color(0xFF34D399)
            }
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
        Text(
                text = stringResource(textRes),
                color = color,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SourceIcon(source: String) {
    val icon =
            when (source) {
                UserMemorySource.SCREEN -> Icons.Default.Smartphone
                UserMemorySource.CHAT -> Icons.Default.Chat
                UserMemorySource.IMPORT -> Icons.Default.Computer
                UserMemorySource.SYSTEM -> Icons.Default.Computer
                else -> Icons.Default.Person
            }
    Icon(
            imageVector = icon,
            contentDescription = source,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MemoryCard(item: UserMemoryItem, onClick: () -> Unit) {
    Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                )
                StatusBadge(item)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                    text = item.maskedValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                        text = userMemoryTypeLabel(item.type),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                )
                Text(" · ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SourceIcon(item.source)
                Text(
                        text = " " + userMemorySourceLabel(item.source),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                        text = " · " + formatTime(item.updatedAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 记忆主页：搜索 + 分类 tab + 条目卡 + 详情/编辑/删除 */
@Composable
fun UserMemoryScreen() {
    val context = LocalContext.current
    val store = remember { UserMemoryStore.getInstance(context) }
    val revision by store.revision.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("all") }
    var memories by remember { mutableStateOf<List<UserMemoryItem>>(emptyList()) }
    var pendingCount by remember { mutableStateOf(0) }
    var detailItem by remember { mutableStateOf<UserMemoryItem?>(null) }
    var editItem by remember { mutableStateOf<UserMemoryItem?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<UserMemoryItem?>(null) }

    LaunchedEffect(query, tab, revision) {
        val loaded =
                withContext(Dispatchers.IO) {
                    val list =
                            when (tab) {
                                "pending" -> store.list(query = query, status = UserMemoryStatus.PENDING)
                                "all" -> store.list(query = query, status = null)
                                else -> store.list(query = query, type = tab, status = null)
                            }
                    list to store.countByStatus(UserMemoryStatus.PENDING)
                }
        memories = loaded.first
        pendingCount = loaded.second
        // 详情弹窗开着时同步刷新数据
        detailItem?.let { old -> detailItem = withContext(Dispatchers.IO) { store.get(old.id) } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 搜索栏
            OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.user_memory_search_hint), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = RoundedCornerShape(12.dp)
            )

            // 待确认提示
            if (pendingCount > 0 && tab != "pending") {
                Surface(
                        color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .clickable { tab = "pending" }
                ) {
                    Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                                Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                                text = stringResource(R.string.user_memory_pending_banner, pendingCount),
                                fontSize = 12.sp,
                                color = Color(0xFFF59E0B)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // 分类 tab
            Row(
                    modifier =
                            Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs =
                        listOf(
                                "all" to stringResource(R.string.user_memory_tab_all),
                                "pending" to stringResource(R.string.user_memory_tab_pending)
                        ) +
                                UserMemoryType.ALL.map { it to userMemoryTypeLabel(it) }
                for ((key, label) in tabs) {
                    FilterChip(
                            selected = tab == key,
                            onClick = { tab = key },
                            label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            if (memories.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                            text = stringResource(R.string.user_memory_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(memories, key = { it.id }) { item -> MemoryCard(item) { detailItem = item } }
                }
            }
        }

        FloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.user_memory_add))
        }
    }

    detailItem?.let { item ->
        UserMemoryDetailDialog(
                item = item,
                store = store,
                onClose = { detailItem = null },
                onEdit = {
                    detailItem = null
                    editItem = item
                },
                onDelete = {
                    detailItem = null
                    deleteTarget = item
                }
        )
    }

    if (showAdd || editItem != null) {
        UserMemoryEditDialog(
                initial = editItem,
                store = store,
                onClose = {
                    showAdd = false
                    editItem = null
                }
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(stringResource(R.string.user_memory_delete_confirm_title)) },
                text = { Text(stringResource(R.string.user_memory_delete_confirm_text)) },
                confirmButton = {
                    TextButton(
                            onClick = {
                                deleteTarget = null
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.delete(item.id) }
                                }
                            }
                    ) {
                        Text(stringResource(R.string.user_memory_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text(stringResource(R.string.user_memory_cancel))
                    }
                }
        )
    }
}

@Composable
private fun UserMemoryDetailDialog(
        item: UserMemoryItem,
        store: UserMemoryStore,
        onClose: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var revealValue by remember { mutableStateOf(item.sensitivity == 0) }
    AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.user_memory_detail_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                            text = if (revealValue) item.plainValue else item.maskedValue,
                            style = MaterialTheme.typography.bodyMedium
                    )
                    if (item.sensitivity > 0) {
                        TextButton(onClick = { revealValue = !revealValue }) {
                            Text(
                                    stringResource(
                                            if (revealValue) R.string.user_memory_hide
                                            else R.string.user_memory_reveal
                                    ),
                                    fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val meta =
                            buildString {
                                append(userMemoryTypeLabel(item.type))
                                append(" · ")
                                append(userMemorySourceLabel(item.source))
                                append(" · ")
                                append(formatTime(item.updatedAt))
                                if (item.sensitivity > 0) {
                                    append(" · ")
                                    append(if (item.sensitivity == 2) "secret" else "sensitive")
                                }
                            }
                    Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.sourceUri != null) {
                        Text("URI: ${item.sourceUri}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.hasConflict) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                                text = stringResource(R.string.user_memory_conflict_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                        )
                        TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { store.resolveConflict(item.id) }
                                    }
                                    onClose()
                                }
                        ) {
                            Text(stringResource(R.string.user_memory_keep_this), fontSize = 12.sp)
                        }
                    }
                    if (item.isPending) {
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { store.confirm(item.id) }
                                        }
                                        onClose()
                                    }
                            ) { Text(stringResource(R.string.user_memory_save), fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { store.reject(item.id) }
                                        }
                                        onClose()
                                    }
                            ) { Text(stringResource(R.string.user_memory_discard), fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.user_memory_edit), fontSize = 12.sp)
                        }
                        TextButton(onClick = onDelete) {
                            Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                    stringResource(R.string.user_memory_delete),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.user_memory_close)) }
            }
    )
}

@Composable
private fun UserMemoryEditDialog(
        initial: UserMemoryItem?,
        store: UserMemoryStore,
        onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var value by remember { mutableStateOf(initial?.plainValue ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: UserMemoryType.OTHER) }
    var sensitivity by remember { mutableStateOf(initial?.sensitivity ?: 0) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
            onDismissRequest = onClose,
            title = {
                Text(stringResource(if (initial == null) R.string.user_memory_add else R.string.user_memory_edit))
            },
            text = {
                Column {
                    OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(R.string.user_memory_field_title)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = { Text(stringResource(R.string.user_memory_field_value)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.user_memory_field_type), fontSize = 12.sp)
                    Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (t in UserMemoryType.ALL) {
                            FilterChip(
                                    selected = type == t,
                                    onClick = { type = t },
                                    label = { Text(userMemoryTypeLabel(t), fontSize = 11.sp) }
                            )
                        }
                    }
                    Text(stringResource(R.string.user_memory_field_sensitivity), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                                        R.string.user_memory_sensitivity_normal,
                                        R.string.user_memory_sensitivity_sensitive,
                                        R.string.user_memory_sensitivity_secret
                                )
                                .forEachIndexed { idx, res ->
                                    FilterChip(
                                            selected = sensitivity == idx,
                                            onClick = { sensitivity = idx },
                                            label = { Text(stringResource(res), fontSize = 11.sp) }
                                    )
                                }
                    }
                    if (error.isNotEmpty()) {
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            if (title.isBlank() || value.isBlank()) {
                                error = "标题和内容不能为空"
                                return@TextButton
                            }
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (initial == null) {
                                        store.insert(
                                                title = title,
                                                value = value,
                                                type = type,
                                                kind = UserMemoryKind.FACT,
                                                subject = null,
                                                source = UserMemorySource.MANUAL,
                                                sourceApp = null,
                                                sourceUri = null,
                                                confidence = 1.0,
                                                sensitivity = sensitivity,
                                                userVerified = true,
                                                status = UserMemoryStatus.ACTIVE,
                                                actor = "user"
                                        )
                                    } else {
                                        store.update(
                                                id = initial.id,
                                                title = title,
                                                value = value,
                                                type = type,
                                                sensitivity = sensitivity,
                                                actor = "user"
                                        )
                                    }
                                }
                                onClose()
                            }
                        }
                ) { Text(stringResource(R.string.user_memory_save)) }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.user_memory_cancel)) }
            }
    )
}
