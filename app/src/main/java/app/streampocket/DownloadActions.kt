package app.streampocket

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val DeleteRed = Color(0xFFFF626B)

@Composable
internal fun SwipeToRevealDelete(id: String, enabled: Boolean = true, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val actionWidth = with(LocalDensity.current) { 80.dp.toPx() }
    var revealed by remember(id) { mutableStateOf(false) }
    var dragging by remember(id) { mutableStateOf(false) }
    var dragOffset by remember(id) { mutableFloatStateOf(0f) }
    val requestDelete by rememberUpdatedState(onDelete)
    val offset by animateFloatAsState(if (dragging) dragOffset else if (revealed) -actionWidth else 0f,
        animationSpec = tween(if (dragging) 0 else 180), label = "delete reveal")
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).semantics {
        if (enabled) customActions = listOf(CustomAccessibilityAction("Delete download") { requestDelete(); true })
    }) {
        Box(Modifier.matchParentSize().background(DeleteRed.copy(alpha = 0.12f)), contentAlignment = Alignment.CenterEnd) {
            if (offset < -1f) IconButton(onClick = { revealed = false; requestDelete() }, enabled = enabled,
                modifier = Modifier.width(80.dp).fillMaxHeight()) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete download", tint = DeleteRed, modifier = Modifier.size(28.dp))
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.pointerInput(id, actionWidth, enabled) {
            if (enabled) detectHorizontalDragGestures(
                onDragStart = { dragging = true; dragOffset = if (revealed) -actionWidth else 0f },
                onDragEnd = { revealed = dragOffset < -actionWidth * 0.35f; dragging = false },
                onDragCancel = { dragging = false },
                onHorizontalDrag = { change, amount -> change.consume(); dragOffset = (dragOffset + amount).coerceIn(-actionWidth, 0f) })
        }) { content() }
    }
}

@Composable
internal fun DeleteDownloadDialog(title: String, format: String, hasFile: Boolean, active: Boolean, onDismiss: () -> Unit, onDelete: (Boolean) -> Unit) {
    var deleteFile by remember(title, format) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null, tint = DeleteRed) },
        title = { Text("Delete download?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Remove “$title” from Downloads?")
                if (active) Text("This also cancels the download. Any file that has already finished saving is kept.")
                if (hasFile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteFile, { deleteFile = it }, colors = CheckboxDefaults.colors(checkedColor = DeleteRed))
                        Text("Also delete the $format file from this phone", Modifier.weight(1f))
                    }
                    Text("Leave unchecked to keep the file.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton({ onDelete(deleteFile) }, colors = ButtonDefaults.textButtonColors(contentColor = DeleteRed)) { Text("Delete") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
