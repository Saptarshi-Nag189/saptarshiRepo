package com.lyricstage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricstage.R
import com.lyricstage.core.stage.TemplateId
import com.lyricstage.settings.BackgroundMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: StageViewModel, systemReduceMotion: Boolean, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Section(stringResource(R.string.settings_template))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.template == null, onClick = { vm.setTemplate(null) }, label = { Text(stringResource(R.string.template_auto)) })
            TemplateId.entries.forEach { id ->
                FilterChip(s.template == id, onClick = { vm.setTemplate(id) }, label = { Text(id.label) })
            }
        }

        Section(stringResource(R.string.settings_background))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                BackgroundMode.LIQUID to R.string.background_liquid,
                BackgroundMode.ARTWORK to R.string.background_artwork,
                BackgroundMode.SOLID to R.string.background_solid,
            ).forEach { (mode, label) ->
                FilterChip(s.background == mode, onClick = { vm.updateSettings { it.copy(background = mode) } }, label = { Text(stringResource(label)) })
            }
        }

        Section(stringResource(R.string.settings_font_scale) + "  ${"%.0f".format(s.fontScale * 100)} %")
        Slider(s.fontScale, onValueChange = { v -> vm.updateSettings { it.copy(fontScale = v) } }, valueRange = 0.6f..1.4f, steps = 7)

        Section(stringResource(R.string.settings_highlight) + "  ${"%.0f".format(s.highlightIntensity * 100)} %")
        Slider(s.highlightIntensity, onValueChange = { v -> vm.updateSettings { it.copy(highlightIntensity = v) } })

        ToggleRow(stringResource(R.string.settings_reduce_motion), s.reduceMotion || systemReduceMotion, enabled = !systemReduceMotion) {
            vm.updateSettings { st -> st.copy(reduceMotion = it) }
        }
        Text(
            stringResource(if (systemReduceMotion) R.string.settings_reduce_motion_system else R.string.settings_reduce_motion_hint),
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
        )

        ToggleRow(stringResource(R.string.settings_fps), s.showFps) { vm.updateSettings { st -> st.copy(showFps = it) } }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
        Section(stringResource(R.string.settings_debug))
        LinkRow(stringResource(R.string.debug_now_playing)) { onOpen(Screen.DEBUG_NOW_PLAYING) }
        LinkRow(stringResource(R.string.debug_lyrics)) { onOpen(Screen.DEBUG_LYRICS) }
        LinkRow(stringResource(R.string.debug_fitcheck)) { onOpen(Screen.FIT_CHECK) }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}
