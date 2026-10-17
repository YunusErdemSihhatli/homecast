package io.github.yunuserdemsihhatli.homecast.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yunuserdemsihhatli.homecast.R
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackError
import io.github.yunuserdemsihhatli.homecast.ui.message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

private const val TAG = "RemoteScreen"
private const val MAX_SUBTITLE_BYTES = 5 * 1024 * 1024
private val SUBTITLE_MIME_TYPES = arrayOf("application/x-subrip", "text/*", "application/octet-stream")

/**
 * The phone as a remote for what plays on the TV, with the device picker behind the top-bar chip.
 *
 * [pendingPlay] is a "Play on TV" the browser handed over: it is started here, once [viewModel] exists and so already
 * listens for playback errors, and reported through [onStartPlay]. "No TV selected" opens the device picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel,
    pendingPlay: VideoCandidate?,
    onStartPlay: (VideoCandidate) -> Unit,
    onBackToBrowser: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }

    /** Shown at the top of the picker when it was opened because playback needs a TV. */
    var pickerNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingPlay) {
        pendingPlay?.let(onStartPlay)
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            when {
                message is RemoteMessage.Playback && message.error == PlaybackError.NoDevice -> {
                    pickerNotice = message.error.message(resources)
                    showPicker = true
                }

                message is RemoteMessage.Playback -> {
                    snackbar.showSnackbar(message.error.message(resources))
                }

                else -> {
                    snackbar.showSnackbar(resources.getString(R.string.remote_command_failed))
                }
            }
        }
    }

    val pickSubtitle =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                when (val file = readSubtitleFile(context, uri)) {
                    is SubtitleFile.Read -> {
                        viewModel.addSubtitle(file.bytes, file.name)
                    }

                    SubtitleFile.TooLarge -> {
                        snackbar.showSnackbar(resources.getString(R.string.remote_subtitle_too_large))
                    }

                    SubtitleFile.Unreadable -> {
                        snackbar.showSnackbar(resources.getString(R.string.remote_subtitle_unreadable))
                    }
                }
            }
        }

    Scaffold(
        topBar = {
            RemoteTopBar(
                connection = state.connection,
                onBack = onBackToBrowser,
                onPickDevice = { showPicker = true },
                onOpenBatterySettings = onOpenBatterySettings,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            ConnectionBanner(
                connection = state.connection,
                onReconnect = { if (!viewModel.reconnect()) showPicker = true },
            )
            Controls(
                state = state,
                viewModel = viewModel,
                onAddSubtitle = { pickSubtitle.launch(SUBTITLE_MIME_TYPES) },
                onBackToBrowser = onBackToBrowser,
            )
        }
    }

    if (showPicker) {
        // Collected only while the sheet is open: that is what keeps mDNS discovery (and its multicast lock) running.
        val devices by viewModel.devices.collectAsStateWithLifecycle()
        val saved by viewModel.saved.collectAsStateWithLifecycle()
        DevicePickerSheet(
            notice = pickerNotice,
            devices = devices,
            saved = saved,
            connection = state.connection,
            onConnect = {
                viewModel.connect(it)
                showPicker = false
                pickerNotice = null
            },
            onConnectByIp = { input ->
                viewModel.connectByIp(input).also { ok ->
                    if (ok) {
                        showPicker = false
                        pickerNotice = null
                    }
                }
            },
            onForget = viewModel::forget,
            onDismiss = {
                showPicker = false
                pickerNotice = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteTopBar(
    connection: ConnectionState,
    onBack: () -> Unit,
    onPickDevice: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.remote_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.remote_back_to_browser),
                )
            }
        },
        actions = {
            val deviceName =
                when (connection) {
                    is ConnectionState.Connected -> connection.device.name
                    is ConnectionState.Connecting -> connection.device.name
                    else -> stringResource(R.string.remote_choose_device)
                }
            AssistChip(
                onClick = onPickDevice,
                label = { Text(deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_notification_cast), contentDescription = null) },
                modifier = Modifier.widthIn(max = 200.dp),
            )
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.remote_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_battery_setting)) },
                    onClick = {
                        menuOpen = false
                        onOpenBatterySettings()
                    },
                )
            }
        },
    )
}

@Composable
private fun ConnectionBanner(
    connection: ConnectionState,
    onReconnect: () -> Unit,
) {
    when (connection) {
        is ConnectionState.Connected -> {
            // No banner: the device chip in the top bar names the TV.
        }

        is ConnectionState.Connecting -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.remote_connecting, connection.device.name),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        ConnectionState.Disconnected, is ConnectionState.Failed -> {
            val text =
                if (connection is ConnectionState.Failed) {
                    // Failed.message is technical English for the log; the user gets a plain sentence.
                    stringResource(R.string.remote_connect_failed, connection.device.name)
                } else {
                    stringResource(R.string.remote_disconnected)
                }
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReconnect) { Text(stringResource(R.string.remote_reconnect)) }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    state: RemoteUiState,
    viewModel: RemoteViewModel,
    onAddSubtitle: () -> Unit,
    onBackToBrowser: () -> Unit,
) {
    val hasMedia = state.playerState != null && state.connection is ConnectionState.Connected
    val connected = state.connection is ConnectionState.Connected
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = state.title ?: stringResource(R.string.remote_nothing_playing),
            style = MaterialTheme.typography.run { if (state.title != null) titleLarge else bodyLarge },
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        if (state.canPlayAgain) {
            Button(onClick = viewModel::playAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_play_again))
            }
        }

        SeekBar(state = state, enabled = hasMedia, onSeek = viewModel::seekTo)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val canSkip = hasMedia && !state.isLive
            FilledTonalIconButton(onClick = { viewModel.skip(-SKIP_SECONDS) }, enabled = canSkip) {
                Icon(
                    painterResource(R.drawable.ic_rewind_10),
                    contentDescription = stringResource(R.string.remote_rewind),
                )
            }
            val playing = state.playerState == PlayerState.PLAYING || state.playerState == PlayerState.BUFFERING
            LargeFloatingActionButton(
                onClick = { if (hasMedia) viewModel.togglePlay() },
                // A FAB has no enabled state of its own; tell accessibility services it is disabled.
                modifier = Modifier.semantics { if (!hasMedia) disabled() },
                containerColor =
                    MaterialTheme.colorScheme.run { if (hasMedia) primaryContainer else surfaceVariant },
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (playing) R.string.remote_pause else R.string.remote_play),
                    modifier = Modifier.padding(4.dp),
                )
            }
            FilledTonalIconButton(onClick = { viewModel.skip(SKIP_SECONDS) }, enabled = canSkip) {
                Icon(
                    painterResource(R.drawable.ic_forward_10),
                    contentDescription = stringResource(R.string.remote_forward),
                )
            }
        }

        VolumeRow(
            state = state,
            enabled = connected,
            onVolume = viewModel::setVolume,
            onToggleMute = viewModel::toggleMute,
        )

        SubtitlePicker(
            state = state,
            enabled = state.title != null,
            onSelect = viewModel::selectSubtitle,
            onAdd = onAddSubtitle,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = viewModel::stop, enabled = state.title != null || hasMedia) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_stop))
            }
            OutlinedButton(onClick = onBackToBrowser) { Text(stringResource(R.string.remote_back_to_browser)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SeekBar(
    state: RemoteUiState,
    enabled: Boolean,
    onSeek: (Double) -> Unit,
) {
    if (state.isLive) {
        AssistChip(
            onClick = {},
            label = { Text(stringResource(R.string.remote_live)) },
        )
        return
    }
    val duration = state.durationSeconds?.takeIf { it > 0 }
    // While the thumb is dragged the slider follows the finger; the seek is sent once, on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = dragging ?: state.positionSeconds.toFloat()
    val seekLabel = stringResource(R.string.remote_seek)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration != null) position.coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toDouble()) }
                dragging = null
            },
            valueRange = 0f..(duration?.toFloat() ?: 1f),
            enabled = enabled && duration != null,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatTime(position.toDouble()), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(duration?.let(::formatTime) ?: "--:--", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VolumeRow(
    state: RemoteUiState,
    enabled: Boolean,
    onVolume: (Double) -> Unit,
    onToggleMute: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val volumeLabel = stringResource(R.string.remote_volume)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onToggleMute, enabled = enabled && state.volume != null) {
            Icon(
                painterResource(if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                contentDescription = stringResource(if (state.muted) R.string.remote_unmute else R.string.remote_mute),
            )
        }
        Slider(
            value = dragging ?: (state.volume ?: 0.0).toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onVolume(it.toDouble()) }
                dragging = null
            },
            enabled = enabled && state.volume != null,
            modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitlePicker(
    state: RemoteUiState,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
    onAdd: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val off = stringResource(R.string.remote_subtitles_off)
    val selected = state.subtitleOptions.firstOrNull { it.trackId == state.activeSubtitleId }?.label ?: off
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.remote_subtitles)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(off) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            state.subtitleOptions.forEach { track ->
                DropdownMenuItem(
                    text = { Text(track.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(track.trackId)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_subtitles_add)) },
                onClick = {
                    expanded = false
                    onAdd()
                },
            )
        }
    }
}

private const val SKIP_SECONDS = 10

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

private sealed interface SubtitleFile {
    data class Read(
        val bytes: ByteArray,
        val name: String,
    ) : SubtitleFile

    data object TooLarge : SubtitleFile

    data object Unreadable : SubtitleFile
}

/** Reads a picked file off the main thread, refusing anything over [MAX_SUBTITLE_BYTES]. */
private suspend fun readSubtitleFile(
    context: Context,
    uri: Uri,
): SubtitleFile =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val name =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: uri.toString()
            val bytes =
                resolver.openInputStream(uri)?.use { input ->
                    // One byte more than allowed tells "exactly at the limit" from "too large".
                    val buffer = input.readAtMost(MAX_SUBTITLE_BYTES + 1)
                    if (buffer.size > MAX_SUBTITLE_BYTES) return@withContext SubtitleFile.TooLarge
                    buffer
                } ?: return@withContext SubtitleFile.Unreadable
            SubtitleFile.Read(bytes, name)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't read subtitle file", e)
            SubtitleFile.Unreadable
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to subtitle file", e)
            SubtitleFile.Unreadable
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad subtitle file URI", e)
            SubtitleFile.Unreadable
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Subtitle file provider can't open it", e)
            SubtitleFile.Unreadable
        }
    }

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}
