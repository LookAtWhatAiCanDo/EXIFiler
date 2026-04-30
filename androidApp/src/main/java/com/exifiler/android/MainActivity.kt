package com.exifiler.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val prefsManager = AppPreferencesManager(application)

    val serviceEnabled: StateFlow<Boolean> = prefsManager.serviceEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val targetFolder: StateFlow<String> = prefsManager.targetFolderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferencesManager.DEFAULT_TARGET_FOLDER)

    val activityLog: StateFlow<List<String>> = prefsManager.activityLogFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _needsManageMedia = MutableStateFlow(false)
    val needsManageMedia: StateFlow<Boolean> = _needsManageMedia.asStateFlow()

    /** Call from Activity.onResume() to refresh the MANAGE_MEDIA permission state. */
    fun refreshManageMediaState(context: Context) {
        _needsManageMedia.value = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !MediaStore.canManageMedia(context)
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            prefsManager.setServiceEnabled(enabled)
            if (enabled) ServiceManager.startService(context)
            else ServiceManager.stopService(context)
        }
    }

    fun setTargetFolder(uri: Uri) {
        viewModelScope.launch {
            val lastSegment = uri.lastPathSegment ?: ""
            // SAF tree URIs have the form "primary:DCIM/Folder" or "XXXXX-XXXX:path"
            val displayPath = when {
                lastSegment.contains(':') -> lastSegment.substringAfter(':')
                else -> lastSegment
            }.ifBlank { AppPreferencesManager.DEFAULT_TARGET_FOLDER }
            prefsManager.setTargetFolder(displayPath)
        }
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        requestRequiredPermissions()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EXIFilerScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh MANAGE_MEDIA state in case the user just came back from Settings
        viewModel.refreshManageMediaState(this)
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }
}

@Composable
fun EXIFilerScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val serviceEnabled by viewModel.serviceEnabled.collectAsState()
    val targetFolder by viewModel.targetFolder.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()
    // Refreshed on every onResume via MainViewModel.refreshManageMediaState()
    val needsManageMedia by viewModel.needsManageMedia.collectAsState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.setTargetFolder(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))

        // ── Media Management permission banner (API 31+ only, shown until granted) ─────────────
        // MANAGE_MEDIA lets the app silently delete files it didn't create (e.g. images
        // transferred via USB that Android indexes under MediaStore.Images rather than Downloads).
        // Without it, the app copies files to DCIM/EXIFiler but cannot remove the originals.
        if (needsManageMedia) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Media Management permission required",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Grant once in Settings so EXIFiler can silently move files without prompting you for every transfer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }
                        }
                    ) {
                        Text("Grant in Settings")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Service toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (serviceEnabled) stringResource(R.string.service_running)
                        else stringResource(R.string.service_stopped),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Monitor Downloads for Meta AI Glasses files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = serviceEnabled,
                    onCheckedChange = { viewModel.setServiceEnabled(context, it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Target folder
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.target_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = targetFolder,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Button(onClick = { folderPickerLauncher.launch(null) }) {
                    Text(stringResource(R.string.change_folder))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Activity log
        Text(
            text = stringResource(R.string.recent_activity),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (activityLog.isEmpty()) {
            Text(
                text = stringResource(R.string.no_activity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(activityLog) { entry ->
                    ActivityLogEntry(entry = entry)
                }
            }
        }
    }
}

@Composable
fun ActivityLogEntry(entry: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    }
}
