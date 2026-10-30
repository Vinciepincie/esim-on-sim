package com.esimonsim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.esimonsim.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Minimal drawings of the Settings screens setup sends people to, with the one thing to tap circled.
// Stock Android names and order: phones differ in detail, so these show what to look for, not a
// screenshot. Grey bars stand in for the rows that don't matter.

/** "About phone", with "Build number" circled: tap it 7 times. */
@Composable
fun AboutPhoneSketch(modifier: Modifier = Modifier) =
    SketchScreen("About phone", "Drawing of About phone, with Build number circled", modifier) {
        Bars(0.45f, 0.3f)
        Bars(0.35f, 0.55f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Item("Build number", Modifier.weight(1f, fill = false).circled())
            Spacer(Modifier.width(24.dp))
            Text("×7", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }

/** "Developer options", with the "Wireless debugging" switch circled. */
@Composable
fun DeveloperOptionsSketch(modifier: Modifier = Modifier) =
    SketchScreen("Developer options", "Drawing of Developer options, with the Wireless debugging switch circled", modifier) {
        SwitchRow(on = false) { Bars(0.6f, 0.4f) }
        SwitchRow(on = true, circled = true) { Item("Wireless debugging") }
        SwitchRow(on = false) { Bars(0.5f, 0.65f) }
    }

/** The "Wireless debugging" page, with "Pair device with pairing code" circled. */
@Composable
fun PairCodeSketch(modifier: Modifier = Modifier) =
    SketchScreen("Wireless debugging", "Drawing of Wireless debugging, with Pair device with pairing code circled",
        modifier) {
        SwitchRow(on = true) { Bars(0.55f) }
        Bars(0.3f, 0.5f)
        Bars(0.6f)
        Item("Pair device with pairing code", Modifier.circled())
    }

/** This app's pairing notification, with its "Enter code" button circled. */
@Composable
fun NotificationSketch(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    SketchFrame("Drawing of this app's notification, with its Enter code button circled", modifier) {
        Bars(0.2f) // the status bar's clock
        Column(Modifier.fillMaxWidth().background(c.surfaceContainerLowest, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sim), contentDescription = null, Modifier.size(14.dp),
                    tint = c.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(APP_NAME, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1)
            }
            Text("Enter the pairing code", style = MaterialTheme.typography.labelLarge, color = c.onSurface)
            Bars(0.85f, 0.6f)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 2.dp)) {
                Text("Enter code", style = MaterialTheme.typography.labelLarge, color = c.onSurface, softWrap = false,
                    modifier = Modifier.circled())
                Text("Cancel", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, softWrap = false,
                    overflow = TextOverflow.Clip)
            }
        }
        Bars(0.7f, 0.45f) // another notification
    }
}

// ---------------- building blocks ----------------

/** A phone screen in miniature: a title, then rows. */
@Composable
private fun SketchScreen(title: String, description: String, modifier: Modifier,
                         content: @Composable ColumnScope.() -> Unit) =
    SketchFrame(description, modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp))
        content()
    }

/** Read out as one description; the drawing itself says nothing new to a screen reader. Not clipped,
 *  so a circle near the edge can reach past it. */
@Composable
private fun SketchFrame(description: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Column(modifier.widthIn(max = 320.dp).fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .background(c.surfaceContainer, shape).border(1.dp, c.outlineVariant, shape)
            .padding(start = 24.dp, top = 12.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A settings entry's title, over a bar standing in for its summary. */
@Composable
private fun Item(title: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Bar(Modifier.width(72.dp))
    }
}

/** A settings entry that doesn't matter here: a title bar and an optional summary bar, as fractions of the width. */
@Composable
private fun Bars(title: Float, summary: Float? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bar(Modifier.fillMaxWidth(title), strong = true)
        summary?.let { Bar(Modifier.fillMaxWidth(it)) }
    }
}

@Composable
private fun Bar(modifier: Modifier, strong: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(modifier.height(if (strong) 8.dp else 6.dp).background(if (strong) c.outline.copy(alpha = 0.45f)
        else c.outlineVariant, CircleShape))
}

@Composable
private fun SwitchRow(on: Boolean, circled: Boolean = false, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        Spacer(Modifier.width(16.dp))
        MiniSwitch(on, if (circled) Modifier.circled(padX = 8.dp, padY = 8.dp) else Modifier)
    }
}

@Composable
private fun MiniSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.size(30.dp, 18.dp).background(if (on) c.primary else c.surfaceContainerHighest, CircleShape)
            .then(if (on) Modifier else Modifier.border(1.5.dp, c.outline, CircleShape)).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(12.dp).background(if (on) c.onPrimary else c.outline, CircleShape))
    }
}

/** A pen circle around the element: it runs a little past its starting point, like one drawn by hand.
 *  Drawn outside the element's bounds, without taking space. */
@Composable
private fun Modifier.circled(padX: Dp = 9.dp, padY: Dp = 7.dp): Modifier {
    val color = MaterialTheme.colorScheme.primary
    return drawWithCache {
        val path = penCircle(size.width / 2 + padX.toPx(), size.height / 2 + padY.toPx(), Offset(size.width / 2, size.height / 2))
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawWithContent { drawContent(); drawPath(path, color, style = stroke) }
    }
}

private fun penCircle(rx: Float, ry: Float, center: Offset) = Path().apply {
    val steps = 72
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val angle = -2.2 + t * (2 * PI + 0.35) // starts upper left and overshoots a little
        val grow = 1f + 0.05f * t // widening as it goes, so the ends pass each other
        val x = center.x + rx * grow * cos(angle).toFloat()
        val y = center.y + ry * grow * sin(angle).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
}
