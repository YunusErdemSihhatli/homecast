package io.github.yunuserdemsihhatli.homecast.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yunuserdemsihhatli.homecast.R
import io.github.yunuserdemsihhatli.homecast.cast.CastDevice
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.sameAs

/**
 * Bottom sheet listing discovered and saved Cast devices, with a "Connect by IP" field. A saved device can be
 * forgotten with a long-press. Picking a device (or a valid address) connects and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DevicePickerSheet(
    notice: String?,
    devices: List<CastDevice>,
    saved: List<CastDevice>,
    connection: ConnectionState,
    onConnect: (CastDevice) -> Unit,
    onConnectByIp: (String) -> Boolean,
    onForget: (CastDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    var forgetting by remember { mutableStateOf<CastDevice?>(null) }
    val forgetLabel = stringResource(R.string.picker_forget_action)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (notice != null) {
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            val connectedTo = (connection as? ConnectionState.Connected)?.device
            if (devices.none { saved.none(it::sameAs) }) {
                // Nothing discovered yet; discovery runs for as long as this sheet is open.
                Text(
                    text = stringResource(R.string.picker_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(devices, key = { "${it.id}|${it.host}:${it.port}" }) { device ->
                    val isSaved = saved.any(device::sameAs)
                    val isConnected = connectedTo != null && connectedTo.sameAs(device)
                    ListItem(
                        leadingContent = {
                            Icon(
                                painterResource(R.drawable.ic_notification_cast),
                                contentDescription = null,
                            )
                        },
                        headlineContent = { Text(device.name) },
                        supportingContent = {
                            val details =
                                listOfNotNull(
                                    device.model,
                                    "${device.host}:${device.port}",
                                    stringResource(R.string.picker_saved).takeIf { isSaved },
                                )
                            Text(details.joinToString(" · "))
                        },
                        trailingContent = {
                            if (isConnected) {
                                Text(
                                    text = stringResource(R.string.picker_connected),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        modifier =
                            Modifier.combinedClickable(
                                onClick = { onConnect(device) },
                                onLongClickLabel = if (isSaved) forgetLabel else null,
                                onLongClick = if (isSaved) ({ forgetting = device }) else null,
                            ),
                    )
                }
            }
            HorizontalDivider()
            ConnectByIp(onConnectByIp = onConnectByIp)
        }
    }

    forgetting?.let { device ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.picker_forget_title, device.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onForget(device)
                    forgetting = null
                }) { Text(stringResource(R.string.picker_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun ConnectByIp(onConnectByIp: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val submit = { invalid = !onConnectByIp(text) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                invalid = false
            },
            label = { Text(stringResource(R.string.picker_ip_label)) },
            placeholder = { Text(stringResource(R.string.picker_ip_placeholder)) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) ({ Text(stringResource(R.string.picker_ip_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = submit, enabled = text.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.picker_connect))
        }
    }
}
