package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Port of the website SlideToConfirmButton
 * (formerly admin/Pages/src/components/ui/slide-to-confirm-button.tsx):
 * 48dp pill track, 40dp knob, drag to the end to confirm. No tap-to-confirm:
 * only a full drag (or Enter/Space on the focused knob) fires [onConfirm].
 * Pass a changing [resetKey] (like the website key={slideKey}) to snap back
 * to idle after the action completes.
 */
@Composable
fun SlideToConfirmButton(
    label: String = "Slide to confirm",
    confirmedLabel: String = "Confirmed",
    disabled: Boolean = false,
    resetKey: Any? = null,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var confirmed by remember(resetKey) { mutableStateOf(false) }
    val knob = remember(resetKey) { androidx.compose.animation.core.Animatable(0f) }

    fun fire() {
        if (confirmed || disabled) return
        confirmed = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onConfirm()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (disabled) 0.5f else 1f)
    ) {
        val trackPx = with(density) { maxWidth.toPx() }
        val padPx = with(density) { 4.dp.toPx() }
        val knobPx = with(density) { 40.dp.toPx() }
        val endPx = with(density) { 4.dp.toPx() }
        val maxX = (trackPx - knobPx - padPx * 2).coerceAtLeast(0f)
        val progress = if (maxX > 0) (knob.value / maxX).coerceIn(0f, 1f) else 0f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // Progress fill: primary at 6%, full black at 15% when confirmed.
            val fillFraction = ((padPx + knobPx / 2 + knob.value) / trackPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fillFraction)
                    .align(Alignment.CenterStart)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (confirmed) 0.15f else 0.06f
                        )
                    )
            )
            if (!confirmed) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha((1f - progress * 1.6f).coerceIn(0f, 1f))
                )
            } else {
                Text(
                    text = confirmedLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            val dragState = rememberDraggableState { delta ->
                if (!confirmed && !disabled) {
                    scope.launch { knob.snapTo((knob.value + delta).coerceIn(0f, maxX)) }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(knob.value.roundToInt(), 0) }
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        if (confirmed) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.surface
                    )
                    .focusable(enabled = !disabled && !confirmed)
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyDown &&
                            (it.key == Key.Enter || it.key == Key.Spacebar)
                        ) {
                            scope.launch {
                                knob.animateTo(maxX)
                                fire()
                            }
                            true
                        } else false
                    }
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        enabled = !disabled && !confirmed,
                        onDragStopped = {
                            scope.launch {
                                if (knob.value >= maxX - endPx) {
                                    knob.animateTo(maxX)
                                    fire()
                                } else {
                                    knob.animateTo(0f)
                                }
                            }
                        }
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                        stateDescription =
                            if (confirmed) confirmedLabel
                            else "$label, ${(progress * 100).toInt()} percent"
                        onClick(label = "Confirm $label") {
                            // Same contract as the keyboard path: drive the
                            // knob to the end before firing, so an
                            // accessibility activation cannot skip the drag.
                            scope.launch {
                                if (knob.value >= maxX - endPx) {
                                    knob.animateTo(maxX)
                                    fire()
                                }
                            }
                            true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val ink = MaterialTheme.colorScheme.onSurface
                val paper = MaterialTheme.colorScheme.surface
                Canvas(modifier = Modifier.size(18.dp)) {
                    val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    if (confirmed) {
                        drawPath(
                            path = Path().apply {
                                moveTo(size.width * 0.26f, size.height * 0.53f)
                                lineTo(size.width * 0.44f, size.height * 0.7f)
                                lineTo(size.width * 0.76f, size.height * 0.32f)
                            },
                            color = paper,
                            style = stroke
                        )
                    } else {
                        val y = size.height / 2
                        drawLine(
                            color = ink,
                            start = androidx.compose.ui.geometry.Offset(size.width * 0.2f, y),
                            end = androidx.compose.ui.geometry.Offset(size.width * 0.78f, y),
                            strokeWidth = stroke.width,
                            cap = StrokeCap.Round
                        )
                        drawPath(
                            path = Path().apply {
                                moveTo(size.width * 0.52f, size.height * 0.26f)
                                lineTo(size.width * 0.78f, y)
                                lineTo(size.width * 0.52f, size.height * 0.74f)
                            },
                            color = ink,
                            style = stroke
                        )
                    }
                }
            }
        }
    }
}
