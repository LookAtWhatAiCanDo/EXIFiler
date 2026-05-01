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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.exifiler.MonitoringProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val prefsManager = AppPreferencesManager(application)

    val serviceEnabled: StateFlow<Boolean> = prefsManager.serviceEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val activityLog: StateFlow<List<String>> = prefsManager.activityLogFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profiles: StateFlow<List<MonitoringProfile>> = prefsManager.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _multiSelectActive = MutableStateFlow(false)
    val multiSelectActive: StateFlow<Boolean> = _multiSelectActive.asStateFlow()

    private val _selectedEntries = MutableStateFlow<Set<String>>(emptySet())
    val selectedEntries: StateFlow<Set<String>> = _selectedEntries.asStateFlow()

    private val _needsManageMedia = MutableStateFlow(false)
    val needsManageMedia: StateFlow<Boolean> = _needsManageMedia.asStateFlow()

    init {
        // Atomically seed the default profile on first launch so users immediately
        // see a profile to work with. The DataStore edit transaction ensures this
        // is safe even if the ViewModel is initialised concurrently.
        viewModelScope.launch {
            prefsManager.ensureDefaultProfile()
        }
    }

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

    /** Runs a one-shot scan that does NOT start or stop the foreground service. */
    fun scanNow(context: Context) {
        ServiceManager.requestScan(context, viewModelScope)
    }

    fun enterMultiSelectMode(entry: String) {
        _selectedEntries.value = setOf(entry)
        _multiSelectActive.value = true
    }

    fun toggleSelection(entry: String) {
        _selectedEntries.update { current ->
            if (entry in current) current - entry else current + entry
        }
    }

    fun selectAll() {
        _selectedEntries.value = activityLog.value.toSet()
    }

    fun unselectAll() {
        _selectedEntries.value = emptySet()
    }

    fun cancelMultiSelect() {
        _selectedEntries.value = emptySet()
        _multiSelectActive.value = false
    }

    fun deleteSelected() {
        // Snapshot the current selection before doing anything else.  The coroutine only
        // references this local val, so any state changes that happen after this point
        // (e.g. user re-entering multi-select) never affect which entries are deleted.
        val toDelete = _selectedEntries.value
        // Clear UI state immediately for a responsive feel; safe because the coroutine
        // no longer reads _selectedEntries or _multiSelectActive after this point.
        _selectedEntries.value = emptySet()
        _multiSelectActive.value = false
        viewModelScope.launch {
            prefsManager.removeActivityLogEntries(toDelete)
        }
    }

    /** Persists a new or edited profile. */
    fun saveProfile(profile: MonitoringProfile) {
        viewModelScope.launch { prefsManager.saveProfile(profile) }
    }

    /** Removes a profile by its id. */
    fun deleteProfile(id: String) {
        viewModelScope.launch { prefsManager.deleteProfile(id) }
    }

    /** Toggles the [MonitoringProfile.isEnabled] flag for the given profile. */
    fun toggleProfile(profile: MonitoringProfile) {
        saveProfile(profile.copy(isEnabled = !profile.isEnabled))
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
        // Finish the activity when the service emits a quit signal (e.g. notification Quit button).
        // repeatOnLifecycle(STARTED) ensures collection only while the activity is visible;
        // extraBufferCapacity=1 on the SharedFlow means a signal emitted while in background
        // is picked up immediately when the activity returns to the foreground.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppEvents.quit.collect { finish() }
            }
        }
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
        val hadPermissionMissing = viewModel.needsManageMedia.value
        // Refresh MANAGE_MEDIA state in case the user just came back from Settings
        viewModel.refreshManageMediaState(this)
        // If MANAGE_MEDIA was just granted, trigger an immediate rescan so the service retries
        // pending source deletions without waiting for the next ContentObserver notification.
        if (hadPermissionMissing && !viewModel.needsManageMedia.value) {
            viewModel.scanNow(this)
        }
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
    val activityLog by viewModel.activityLog.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    // Refreshed on every onResume via MainViewModel.refreshManageMediaState()
    val needsManageMedia by viewModel.needsManageMedia.collectAsState()
    val multiSelectActive by viewModel.multiSelectActive.collectAsState()
    val selectedEntries by viewModel.selectedEntries.collectAsState()

    // Track the actual rendered height of the action bar so the list never scrolls behind it.
    // rememberSaveable preserves the last-known height across config changes (e.g. rotation)
    // so the list keeps the correct padding until onSizeChanged fires with the new measurement.
    var multiSelectBarHeightPx by rememberSaveable { mutableIntStateOf(0) }
    val multiSelectBarHeightDp = with(LocalDensity.current) { multiSelectBarHeightPx.toDp() }

    // Auto-exit multi-select if the log becomes empty (e.g. after deleting all entries)
    LaunchedEffect(activityLog) {
        if (activityLog.isEmpty() && multiSelectActive) {
            viewModel.cancelMultiSelect()
        }
    }

    // Dialog state
    var showProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<MonitoringProfile?>(null) }
    var deleteConfirmProfile by remember { mutableStateOf<MonitoringProfile?>(null) }

    if (showProfileDialog) {
        ProfileEditorDialog(
            initial = editingProfile,
            onDismiss = { showProfileDialog = false; editingProfile = null },
            onSave = { profile ->
                viewModel.saveProfile(profile)
                showProfileDialog = false
                editingProfile = null
            }
        )
    }

    deleteConfirmProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteConfirmProfile = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_message, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(profile.id)
                    deleteConfirmProfile = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmProfile = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp)
                // Reserve space at the bottom so the list is never hidden behind the action bar.
                // Uses the actual measured bar height so navigation insets are accounted for.
                .then(if (multiSelectActive) Modifier.padding(bottom = multiSelectBarHeightDp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Media Management permission banner (API 31+ only, shown until granted) ──────────────
            if (needsManageMedia) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.permission_media_required),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.permission_media_explanation),
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
                                Text(stringResource(R.string.grant_in_settings))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // ── Service toggle ────────────────────────────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                    text = stringResource(R.string.service_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Switch(
                                checked = serviceEnabled,
                                onCheckedChange = { viewModel.setServiceEnabled(context, it) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.scanNow(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.scan_now))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Monitoring Profiles header ─────────────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.profiles_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(onClick = {
                        editingProfile = null
                        showProfileDialog = true
                    }) {
                        Text(stringResource(R.string.profile_add))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Profile list ───────────────────────────────────────────────────────────────────────
            if (profiles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.profiles_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                items(profiles) { profile ->
                    ProfileCard(
                        profile = profile,
                        onToggle = { viewModel.toggleProfile(profile) },
                        onEdit = { editingProfile = profile; showProfileDialog = true },
                        onDelete = { deleteConfirmProfile = profile }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // ── Activity log ───────────────────────────────────────────────────────────────────────
            item {
                Text(
                    text = stringResource(R.string.recent_activity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (activityLog.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_activity),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                items(activityLog) { entry ->
                    ActivityLogEntry(
                        entry = entry,
                        isMultiSelectActive = multiSelectActive,
                        isSelected = selectedEntries.contains(entry),
                        onLongClick = { viewModel.enterMultiSelectMode(entry) },
                        onCheckedChange = { viewModel.toggleSelection(entry) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        } // end LazyColumn

        // Multi-select action bar — anchored to the bottom of the screen
        if (multiSelectActive) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    // Measure the true height (including navigation insets) so the LazyColumn above
                    // can reserve exactly the right amount of bottom padding.
                    .onSizeChanged { multiSelectBarHeightPx = it.height },
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel — fully left-justified
                    TextButton(onClick = { viewModel.cancelMultiSelect() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    // Spacer pushes Select All + Delete Selected to the right
                    Spacer(modifier = Modifier.weight(1f))
                    // Toggle between Select All / Unselect All based on current selection state.
                    // Use containsAll to correctly handle duplicate log entries (Set vs List size
                    // comparison would fail when the log contains repeated strings).
                    val allSelected = activityLog.isNotEmpty() && selectedEntries.containsAll(activityLog)
                    TextButton(
                        onClick = {
                            if (allSelected) viewModel.unselectAll() else viewModel.selectAll()
                        }
                    ) {
                        Text(stringResource(if (allSelected) R.string.unselect_all else R.string.select_all))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.deleteSelected() },
                        enabled = selectedEntries.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.delete_selected))
                    }
                }
            }
        }
    } // end Box
}

// ── Profile Card ───────────────────────────────────────────────────────────────────────────────

@Composable
fun ProfileCard(
    profile: MonitoringProfile,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isEnabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = profile.isEnabled, onCheckedChange = { onToggle() })
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.profile_input_label, profile.inputFolder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = stringResource(R.string.profile_output_label, profile.outputFolder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (profile.filePatterns.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.profile_patterns_label,
                        profile.filePatterns.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            if (profile.exifFilters.isNotEmpty()) {
                val filtersText = profile.exifFilters.entries.joinToString(", ") { "${it.key}=${it.value}" }
                Text(
                    text = stringResource(R.string.profile_filters_label, filtersText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = onDelete) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ── Profile Add / Edit Dialog ──────────────────────────────────────────────────────────────────

@Composable
fun ProfileEditorDialog(
    initial: MonitoringProfile?,
    onDismiss: () -> Unit,
    onSave: (MonitoringProfile) -> Unit,
) {
    val defaultInputFolder = stringResource(R.string.profile_default_input_folder)
    val defaultFilePatterns = stringResource(R.string.profile_default_file_patterns)
    val defaultExifFilters = stringResource(R.string.profile_default_exif_filters)
    val defaultOutputFolder = stringResource(R.string.profile_default_output_folder)

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var inputFolder by remember { mutableStateOf(initial?.inputFolder ?: defaultInputFolder) }
    var filePatterns by remember {
        mutableStateOf(initial?.filePatterns?.joinToString(", ") ?: defaultFilePatterns)
    }
    var exifFilters by remember {
        mutableStateOf(
            initial?.exifFilters?.entries?.joinToString(", ") { "${it.key}=${it.value}" }
                ?: defaultExifFilters
        )
    }
    var outputFolder by remember { mutableStateOf(initial?.outputFolder ?: defaultOutputFolder) }

    // Validation
    val nameError = name.isBlank()
    val inputError = inputFolder.isBlank()
    val outputError = outputFolder.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) stringResource(R.string.profile_add)
                else stringResource(R.string.profile_edit)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profile_field_name)) },
                    isError = nameError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = inputFolder,
                    onValueChange = { inputFolder = it },
                    label = { Text(stringResource(R.string.profile_field_input)) },
                    isError = inputError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = filePatterns,
                    onValueChange = { filePatterns = it },
                    label = { Text(stringResource(R.string.profile_field_patterns)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exifFilters,
                    onValueChange = { exifFilters = it },
                    label = { Text(stringResource(R.string.profile_field_filters)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = outputFolder,
                    onValueChange = { outputFolder = it },
                    label = { Text(stringResource(R.string.profile_field_output)) },
                    isError = outputError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!nameError && !inputError && !outputError) {
                        val parsedPatterns = filePatterns
                            .split(",")
                            .map { it.trim().lowercase().trimStart('*', '.') }
                            .filter { it.isNotEmpty() }
                        val parsedFilters = exifFilters
                            .split(",")
                            .mapNotNull { pair ->
                                val parts = pair.trim().split("=", limit = 2)
                                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                            }
                            .toMap()
                        onSave(
                            MonitoringProfile(
                                id = initial?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                inputFolder = inputFolder.trim().trimStart('/').trimEnd('/').replace(Regex("/+"), "/"),
                                filePatterns = parsedPatterns,
                                exifFilters = parsedFilters,
                                outputFolder = outputFolder.trim().trimStart('/').trimEnd('/').replace(Regex("/+"), "/"),
                                isEnabled = initial?.isEnabled ?: true,
                            )
                        )
                    }
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ActivityLogEntry(
    entry: String,
    isMultiSelectActive: Boolean,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    // In multi-select mode the whole row is clickable (toggles selection).
    // In normal mode only a long-press is meaningful (enters multi-select); the row is NOT
    // announced as "clickable" to accessibility services so there is no confusing tap ripple.
    val interactionModifier = if (isMultiSelectActive) {
        Modifier.clickable { onCheckedChange(!isSelected) }
    } else {
        // Handles physical long-press for sighted users.  Accessibility services (TalkBack,
        // Switch Access) use the semantics onLongClick action declared below, not pointer events.
        Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { onLongClick() })
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            // Standard selection semantics so TalkBack announces "selected" / "not selected"
            // alongside the visible text.  In normal (non-multi-select) mode, advertise a
            // long-click action so TalkBack / Switch Access users can also enter multi-select.
            .semantics(mergeDescendants = true) {
                selected = isSelected
                if (!isMultiSelectActive) {
                    onLongClick(label = "Select") {
                        onLongClick()
                        true
                    }
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isMultiSelectActive) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onCheckedChange
                )
            }
            Text(
                text = entry,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    }
}
