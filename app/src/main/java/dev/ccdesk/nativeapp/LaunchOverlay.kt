// Copyright 2026 CC Desk Android contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Mirrors the baseline native launch timeline, including the system reduced-motion switch. */
@Composable
fun LaunchOverlay() {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(!launchFinished && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) }
    val timeline = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (visible) {
            withFrameNanos { }; withFrameNanos { }
            timeline.animateTo(0f, tween(515, easing = LinearEasing))
        }
        visible = false
        launchFinished = true
    }
    if (visible) {
        val t = timeline.value
        val opacity = when { t >= .65f -> 1f; t >= .3f -> .38f + (t - .3f) / .35f * .62f; else -> t / .3f * .38f }
        val scale = if (t >= .7f) .965f + (t - .7f) / .3f * .035f else 1.09f - t / .7f * .125f
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity }.background(if (isSystemInDarkTheme()) Color(0xff191919) else Color(0xfffaf9f5)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.launch_mark), null, Modifier.size((200f * 740f / 1024f).dp).graphicsLayer { scaleX = scale; scaleY = scale })
        }
    }
}
private var launchFinished = false
