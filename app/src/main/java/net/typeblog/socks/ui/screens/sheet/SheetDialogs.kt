package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Shared modal matching the website .modal-overlay/.modal-box:
// dimmed scrim (click-outside + back dismisses), centered box (surface,
// 1dp border, 8dp radius, 20dp padding, dialog shadow), 14sp/600 title.
@Composable
fun SheetModal(
    onDismiss: () -> Unit,
    widthDp: Int = 320,
    title: String,
    miniTitle: Boolean = false,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (androidx.compose.foundation.isSystemInDarkTheme()) 0.5f else 0.25f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(widthDp.dp)
                .shadow(24.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    fontSize = if (miniTitle) 10.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (miniTitle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.size(12.dp))
                content()
            }
        }
    }
    }
}

@Composable
fun SheetModalFooter(
    onCancel: () -> Unit,
    cancelText: String = "Cancel",
    onConfirm: () -> Unit,
    confirmText: String,
    danger: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        SheetBtnGhost(label = cancelText, onClick = onCancel)
        Spacer(modifier = Modifier.width(8.dp))
        if (danger) SheetBtnDanger(label = confirmText, onClick = onConfirm)
        else SheetBtnPrimary(label = confirmText, onClick = onConfirm)
    }
}

@Composable
fun SheetBtnGhost(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SheetBtnPrimary(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.surface)
    }
}

@Composable
fun SheetBtnDanger(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DeadRed)
            .border(1.dp, DeadRed, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

// Website confirm(): shadcn-style dialog — "Are you sure?" 18sp title,
// 14sp muted message, ghost Cancel + filled-red action.
@Composable
fun SheetConfirm(
    onDismiss: () -> Unit,
    message: String,
    actionText: String,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Are you sure?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SheetBtnGhost(label = "Cancel", onClick = onDismiss)
                    Spacer(modifier = Modifier.width(8.dp))
                    SheetBtnDanger(label = actionText, onClick = onConfirm)
                }
            }
        }
    }
    }
}

@Composable
fun SheetNameInput(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var tfv by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    OutlinedTextField(
        value = tfv,
        onValueChange = {
            tfv = it
            onValueChange(it.text)
        },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        shape = RoundedCornerShape(6.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
