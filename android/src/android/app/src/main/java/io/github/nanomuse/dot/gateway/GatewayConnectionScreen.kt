package io.github.nanomuse.dot.gateway

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.launch

/** Optional own-key form: no account migration, default model selection or anonymous key. */
@Composable
fun GatewayConnectionScreen(repository: ProviderRepository, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val loaded by repository.configLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(GatewayConnection.LABEL, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.nm_gateway_key_required))
        OutlinedTextField(value = key, onValueChange = { key = it; failed = false },
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            label = { Text(stringResource(R.string.provider_list_api_key)) },
            singleLine = true, enabled = loaded && !busy)
        if (failed) Text(stringResource(R.string.nm_gateway_connection_failed), color = MaterialTheme.colorScheme.error)
        Button(enabled = loaded && !busy && key.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try {
                    GatewayConnection(repository, context.getSharedPreferences("nanodot_gateway", Context.MODE_PRIVATE)).connect(key)
                    key = ""
                    onSaved()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.provider_list_add_provider)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
    }
}
