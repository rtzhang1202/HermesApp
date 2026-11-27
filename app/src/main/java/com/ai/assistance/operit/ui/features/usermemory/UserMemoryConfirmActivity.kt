package com.ai.assistance.operit.ui.features.usermemory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.UserMemoryItem
import com.ai.assistance.operit.data.repository.UserMemoryStore
import com.ai.assistance.operit.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆确认卡：memory_save 建了 pending 条目后弹出，用户点「保存到记忆」才真正入库。
 *
 * 独立 Activity（半透明主题），不改 voice/ 与聊天界面代码即可覆盖在所有界面之上；
 * 从工具执行器用 [show] 拉起，启动失败也不影响 pending 落库（可在「用户记忆 → 待确认」处理）。
 */
class UserMemoryConfirmActivity : ComponentActivity() {

    companion object {
        private const val TAG = "UserMemoryConfirm"
        const val EXTRA_ITEM_ID = "item_id"

        /** @return 是否成功拉起确认卡 */
        fun show(context: Context, itemId: String): Boolean {
            return try {
                val intent =
                        Intent(context, UserMemoryConfirmActivity::class.java)
                                .putExtra(EXTRA_ITEM_ID, itemId)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(intent)
                true
            } catch (t: Throwable) {
                AppLogger.w(TAG, "show failed: ${t.message}")
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent?.getStringExtra(EXTRA_ITEM_ID)
        AppLogger.d(TAG, "onCreate itemId=$itemId")
        if (itemId.isNullOrBlank()) {
            finish()
            return
        }
        val store = UserMemoryStore.getInstance(this)
        setContent {
            UserMemoryConfirmTheme {
                ConfirmCard(
                        itemId = itemId,
                        store = store,
                        onSaved = {
                            Toast.makeText(
                                            this,
                                            R.string.user_memory_saved_toast,
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                            finish()
                        },
                        onDiscarded = {
                            Toast.makeText(
                                            this,
                                            R.string.user_memory_discarded_toast,
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                            finish()
                        }
                )
            }
        }
    }
}

@Composable
private fun UserMemoryConfirmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
            colorScheme =
                    darkColorScheme(
                            primary = Color(0xFF8B5CF6),
                            onPrimary = Color.White,
                            background = Color(0xFF0F0E13),
                            surface = Color(0xFF1B1922),
                            onSurface = Color(0xFFF4F2F8),
                            onSurfaceVariant = Color(0xFF9CA3AF),
                            error = Color(0xFFF87171)
                    ),
            content = content
    )
}

@Composable
private fun ConfirmCard(
        itemId: String,
        store: UserMemoryStore,
        onSaved: () -> Unit,
        onDiscarded: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<UserMemoryItem?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(itemId) {
        item = withContext(Dispatchers.IO) { store.get(itemId) }
        loaded = true
    }

    Box(
            modifier = Modifier.fillMaxSize().background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
    ) {
        if (!loaded) return@Box
        val current = item
        if (current == null) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                Text("记忆不存在或已删除", modifier = Modifier.padding(20.dp))
            }
            return@Box
        }
        Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                            Icons.Default.BookmarkAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                            text = stringResource(R.string.user_memory_confirm_card_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                            text = userMemoryTypeLabel(current.type),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                        text = if (current.sensitivity > 0) current.maskedValue else current.plainValue,
                        style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(10.dp))
                Text(
                        text =
                                stringResource(R.string.user_memory_source_label) +
                                        "：" +
                                        userMemorySourceLabel(current.source) +
                                        " · " +
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                                .format(Date(current.updatedAt)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                        text = stringResource(R.string.user_memory_confirm_card_sub),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.reject(current.id) }
                                    onDiscarded()
                                }
                            }
                    ) { Text(stringResource(R.string.user_memory_discard)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.confirm(current.id) }
                                    onSaved()
                                }
                            }
                    ) { Text(stringResource(R.string.user_memory_save)) }
                }
            }
        }
    }
}
