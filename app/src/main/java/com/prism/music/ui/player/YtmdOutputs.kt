package com.prism.music.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.R
import com.prism.music.connect.CastState
import com.prism.music.connect.RendererKind
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ytmd.YtmdDesktops

/**
 * This fork's YouTube Music Desktop outputs in the output picker: the PCs found on this Wi-Fi (mDNS, YTMD 2.1.0+) and
 * those added by address. Picking one pairs the first time (its code shows here and on the PC, allowed there), then
 * plays there. Discovery runs with the speakers' ([SpeakersAndTvs] starts it).
 */
@Composable
internal fun YtmdOutputs(castState: CastState, castingTo: String?) {
    val cast = LocalContainer.current.cast
    if (!cast.ytmdDesktops.enabled) return
    val renderers by cast.renderers.collectAsState()
    val code by cast.ytmdCode.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val playing = ((castState as? CastState.Casting)?.renderer ?: (castState as? CastState.Connecting)?.renderer)?.takeIf { it.kind == RendererKind.YTMD }
    val listed = renderers.filter { it.kind == RendererKind.YTMD }
    val shown = if (playing != null && listed.none { it.id == playing.id }) listOf(playing) + listed else listed
    OutputGroup(tr(R.string.ytmd_group)) {
        shown.forEach { r ->
            val selected = r.id == castingTo
            val connecting = castState is CastState.Connecting && selected
            DeviceRow(
                PrismIcons.Tv, r.name,
                when {
                    connecting && code != null -> tr(R.string.ytmd_approve_code, code)
                    connecting -> r.detail + tr(R.string.connecting)
                    selected -> r.detail
                    cast.ytmdDesktops.token(r) == null -> tr(R.string.ytmd_play_there) + " · " + tr(R.string.ytmd_pair_first)
                    else -> tr(R.string.ytmd_play_there)
                },
                selected = selected && !connecting, busy = connecting,
                trailing = if (YtmdDesktops.isManual(r) && !selected) ({ PrismTextButton(onClick = { cast.forgetYtmd(r) }) { Text(tr(R.string.ytmd_forget)) } }) else null,
            ) { if (!selected) cast.connect(r) }
        }
        DeviceRow(PrismIcons.Add, tr(R.string.ytmd_add_by_address), tr(R.string.ytmd_add_by_address_detail)) { adding = true }
    }
    // The pairing code, large: YTMD shows the same one and asks whether to allow Prism (for 30 seconds).
    code?.let { c ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(playing?.name ?: tr(R.string.ytmd_group)) },
            text = {
                Column {
                    Text(tr(R.string.ytmd_approve_body), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        c,
                        style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp).testTag("ytmd/code"),
                    )
                }
            },
            confirmButton = { PrismTextButton(onClick = { cast.disconnect() }) { Text(tr(R.string.cancel)) } },
        )
    }
    if (adding) {
        var address by remember { mutableStateOf("") }
        var bad by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(tr(R.string.ytmd_address_title)) },
            text = {
                Column {
                    Text(tr(R.string.ytmd_address_body), style = MaterialTheme.typography.bodyMedium)
                    com.prism.music.ui.components.PrismTextField(
                        value = address,
                        onValueChange = { v -> address = v.filter { it.isDigit() || it == '.' || it == ':' }.take(21); bad = false },
                        singleLine = true,
                        placeholder = { Text(tr(R.string.ytmd_address_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    if (bad) Text(tr(R.string.ytmd_address_bad), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                PrismTextButton(onClick = {
                    val r = cast.addYtmd(address)
                    if (r == null) bad = true else { adding = false; cast.connect(r) }
                }, enabled = address.isNotBlank()) { Text(tr(R.string.ytmd_add)) }
            },
            dismissButton = { PrismTextButton(onClick = { adding = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}
