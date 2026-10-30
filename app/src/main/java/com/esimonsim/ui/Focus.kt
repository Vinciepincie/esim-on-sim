package com.esimonsim.ui

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Drops focus when the keyboard closes (back gesture, the keyboard's own hide key), so no field is
 *  left with a blinking cursor. Watches the window it is composed in, so dialogs need their own. */
@Composable
fun ClearFocusWhenKeyboardHides() {
    val view = LocalView.current
    val focus = LocalFocusManager.current
    DisposableEffect(view) {
        var shown = keyboardShown(view)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val now = keyboardShown(view)
            if (shown && !now) focus.clearFocus()
            shown = now
        }
        val observer = view.viewTreeObserver
        observer.addOnGlobalLayoutListener(listener)
        onDispose {
            (if (observer.isAlive) observer else view.viewTreeObserver).removeOnGlobalLayoutListener(listener)
        }
    }
}

private fun keyboardShown(view: View) =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/** A tap on empty space ends text entry: focus (and with it the keyboard) goes away. A Modifier.Node, so a
 *  recomposing screen doesn't rebuild it the way Modifier.composed did. */
fun Modifier.clearFocusOnTap(): Modifier = this then ClearFocusOnTap

private object ClearFocusOnTap : ModifierNodeElement<ClearFocusOnTapNode>() {
    override fun create() = ClearFocusOnTapNode()
    override fun update(node: ClearFocusOnTapNode) {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = "clearFocusOnTap".hashCode()
}

private class ClearFocusOnTapNode : DelegatingNode(), CompositionLocalConsumerModifierNode {
    init { delegate(SuspendingPointerInputModifierNode { detectTapGestures { currentValueOf(LocalFocusManager).clearFocus() } }) }
}
