package com.lyricstage.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricstage.stage.StageFonts

/** Centred message for stage states without lyrics (no session, loading, none found). */
@Composable
fun StageMessage(
    title: String,
    hint: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    pulsing: Boolean = false,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (pulsing) {
                Dots()
                Spacer(Modifier.height(20.dp))
            }
            Text(
                title,
                color = Color.White,
                fontFamily = StageFonts.inter,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
            )
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(hint, color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onAction) { Text(action, color = Color.White) }
            }
        }
    }
}

/** Three softly pulsing dots for loading and instrumental passages. */
@Composable
fun Dots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700, delayMillis = i * 180), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(12.dp).alpha(a).background(Color.White, CircleShape))
        }
    }
}
