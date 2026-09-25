package com.freefile.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freefile.R
import com.freefile.data.db.NetworkLocation
import com.freefile.vfs.remote.RemoteTypes
import java.util.UUID

/**
 * 添加/编辑网络位置（Sprint 5）：WebDAV / FTP / SMB / S3(OSS 兼容)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkDialog(
    onDismiss: () -> Unit,
    onSave: (NetworkLocation) -> Unit
) {
    var type by remember { mutableStateOf(RemoteTypes.WEBDAV) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var basePath by remember { mutableStateOf("") }   // SMB 共享名 / S3 bucket
    var useSsl by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }

    fun defaultsFor(t: String) {
        port = RemoteTypes.defaultPort(t, useSsl).toString()
        basePath = ""
    }

    val valid = name.isNotBlank() && host.isNotBlank() &&
        (type != RemoteTypes.SMB || basePath.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.net_add_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 类型选择
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteTypes.ALL.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t; defaultsFor(t) },
                            label = { Text(t) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.net_name)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text(stringResource(R.string.net_host)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.net_port)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text(stringResource(R.string.net_username)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.net_password)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )

                when (type) {
                    RemoteTypes.SMB -> {
                        OutlinedTextField(
                            value = basePath, onValueChange = { basePath = it },
                            label = { Text(stringResource(R.string.net_smb_share)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = domain, onValueChange = { domain = it },
                            label = { Text(stringResource(R.string.net_smb_domain)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    RemoteTypes.S3 -> {
                        OutlinedTextField(
                            value = basePath, onValueChange = { basePath = it },
                            label = { Text(stringResource(R.string.net_s3_bucket)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = region, onValueChange = { region = it },
                            label = { Text(stringResource(R.string.net_s3_region)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = endpoint, onValueChange = { endpoint = it },
                            label = { Text(stringResource(R.string.net_s3_endpoint)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = prefix, onValueChange = { prefix = it },
                            label = { Text(stringResource(R.string.net_s3_prefix)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    RemoteTypes.WEBDAV -> OutlinedTextField(
                        value = basePath, onValueChange = { basePath = it },
                        label = { Text(stringResource(R.string.net_webdav_prefix)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = useSsl, onCheckedChange = {
                        useSsl = it
                        port = RemoteTypes.defaultPort(type, it).toString()
                    })
                    Text(stringResource(R.string.net_use_ssl), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val extras = buildString {
                        if (type == RemoteTypes.S3) {
                            if (region.isNotBlank()) appendLine("region=$region")
                            if (endpoint.isNotBlank()) appendLine("endpoint=$endpoint")
                            if (prefix.isNotBlank()) appendLine("prefix=$prefix")
                        }
                        if (type == RemoteTypes.SMB && domain.isNotBlank()) appendLine("domain=$domain")
                    }
                    onSave(
                        NetworkLocation(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            type = type,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: RemoteTypes.defaultPort(type, useSsl),
                            username = username.trim(),
                            password = password,
                            basePath = basePath.trim(),
                            useSsl = useSsl,
                            extras = extras
                        )
                    )
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
