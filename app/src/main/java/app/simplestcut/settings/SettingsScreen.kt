package app.simplestcut.settings

import app.simplestcut.storage.MediaAccess
import android.widget.Toast
import app.simplestcut.storage.StorageFolders
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.Box
import java.util.Locale
import android.os.LocaleList
import android.app.LocaleManager
import app.simplestcut.R
import androidx.compose.ui.res.stringResource
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.simplestcut.storage.LocalMediaAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.section_start))
            StartFolder.entries.forEach { option ->
                ListItem(
                    headlineContent = { Text(stringResource(option.label)) },
                    leadingContent = { RadioButton(selected = settings.startFolder == option, onClick = null) },
                    modifier = Modifier.selectable(
                        selected = settings.startFolder == option,
                        role = Role.RadioButton,
                        onClick = { settings.updateStartFolder(option) },
                    ),
                )
            }
            SectionTitle(stringResource(R.string.section_playback))
            SwitchRow(
                title = stringResource(R.string.autoplay),
                subtitle = stringResource(R.string.autoplay_sub),
                checked = settings.autoplay,
                onChange = settings::updateAutoplay,
            )
            SwitchRow(
                title = stringResource(R.string.loop),
                subtitle = stringResource(R.string.loop_sub),
                checked = settings.loop,
                onChange = settings::updateLoop,
            )
            SectionTitle(stringResource(R.string.section_dim))
            ControlsDim.entries.forEach { option ->
                ListItem(
                    headlineContent = { Text(stringResource(option.label)) },
                    leadingContent = { RadioButton(selected = settings.controlsDim == option, onClick = null) },
                    modifier = Modifier.selectable(
                        selected = settings.controlsDim == option,
                        role = Role.RadioButton,
                        onClick = { settings.updateControlsDim(option) },
                    ),
                )
            }
            LanguageChoice()
            SectionTitle(stringResource(R.string.section_design))
            ThemeMode.entries.forEach { option ->
                ListItem(
                    headlineContent = { Text(stringResource(option.label)) },
                    leadingContent = { RadioButton(selected = settings.themeMode == option, onClick = null) },
                    modifier = Modifier.selectable(
                        selected = settings.themeMode == option,
                        role = Role.RadioButton,
                        onClick = { settings.updateThemeMode(option) },
                    ),
                )
            }
            SectionTitle(stringResource(R.string.section_cutting))
            SwitchRow(
                title = stringResource(R.string.drag_playhead),
                subtitle = stringResource(R.string.drag_playhead_sub),
                checked = settings.dragPlayhead,
                onChange = settings::updateDragPlayhead,
            )
            StorageAccess()
            VersionInfo()
        }
    }
}

/**
 * Sprache der App, unabhängig von der Handysprache (Android-Sprache pro App, dieselbe Einstellung wie in den
 * Android-Einstellungen). Neue Sprachen: Kürzel in [APP_LANGUAGES] ergänzen und values-<kürzel>/strings.xml anlegen.
 */
@Composable
private fun LanguageChoice() {
    val context = LocalContext.current
    val localeManager = remember { context.getSystemService(LocaleManager::class.java) }
    val current = remember { localeManager.applicationLocales.get(0)?.language }
    var open by remember { mutableStateOf(false) }
    // Sprachnamen in der jeweiligen Sprache ("Deutsch", "English"), damit man sie auch findet, wenn man die
    // aktuelle Sprache nicht versteht.
    fun autonym(tag: String) = Locale.forLanguageTag(tag).let { it.getDisplayName(it) }.replaceFirstChar { it.uppercase() }
    SectionTitle(stringResource(R.string.section_language))
    Box {
        ListItem(
            headlineContent = { Text(current?.let(::autonym) ?: stringResource(R.string.language_system)) },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.language_system)) },
                onClick = {
                    open = false
                    localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
                },
            )
            APP_LANGUAGES.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(autonym(tag)) },
                    onClick = {
                        open = false
                        // Android startet die App danach in der neuen Sprache neu.
                        localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
                    },
                )
            }
        }
    }
}

/** Sprachen mit Übersetzung (res/values = Englisch, values-de = Deutsch). */
private val APP_LANGUAGES = listOf("en", "de")

/** "Medienverwaltung" und freigegebene Ordner (siehe SPEC "Speicherzugriff"). */
@Composable
private fun StorageAccess() {
    val access = LocalMediaAccess.current
    // Nach der Rückkehr aus der Android-Einstellung neu lesen.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val manageMedia = remember(refresh) { access.canManageMedia() }
    val folders = remember(refresh) { access.grantedFolders() }
    var foldersOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Ordner der Messenger (WhatsApp, Telegram, …): eine Freigabe deckt alle ab.
    val messengerFolder = StorageFolders.messengers
    val mediaShared = remember(refresh) { access.isGranted(messengerFolder) }
    val scope = rememberCoroutineScope()
    SectionTitle(stringResource(R.string.section_storage))
    ListItem(
        headlineContent = { Text(stringResource(R.string.manage_media)) },
        supportingContent = {
            Text(
                stringResource(if (manageMedia) R.string.manage_media_on else R.string.manage_media_off)
            )
        },
        modifier = Modifier.clickable { access.openManageMediaSettings() },
    )
    if (folders.isEmpty()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.shared_folders)) },
            supportingContent = { Text(stringResource(R.string.shared_folders_none)) },
        )
    } else {
        // Eingeklappt, damit die Liste die Einstellungen nicht überflutet.
        ListItem(
            headlineContent = { Text(stringResource(R.string.shared_folders_count, folders.size)) },
            trailingContent = {
                Icon(if (foldersOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            },
            modifier = Modifier.clickable { foldersOpen = !foldersOpen },
        )
        if (foldersOpen) folders.forEach { (path, tree) ->
            ListItem(
                headlineContent = { Text(path) },
                modifier = Modifier.padding(start = 16.dp),
                trailingContent = {
                    IconButton(onClick = {
                        access.revokeFolder(tree)
                        refresh++
                    }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.revoke_folder)) }
                },
            )
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.share_folder)) },
        supportingContent = { Text(stringResource(if (mediaShared) R.string.share_folder_tip_plain else R.string.share_folder_tip)) },
        leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
        modifier = Modifier.clickable {
            scope.launch {
                when (access.pickFolder(if (mediaShared) StorageFolders.root else messengerFolder)) {
                    MediaAccess.PickResult.ADDED -> refresh++
                    MediaAccess.PickResult.ALREADY ->
                        Toast.makeText(context, context.getString(R.string.already_shared), Toast.LENGTH_SHORT).show()
                    MediaAccess.PickResult.CANCELLED -> Unit
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** "simplestCUT 0.7" – hilft, wenn Tester einen Fehler melden. */
@Composable
private fun VersionInfo() {
    val context = LocalContext.current
    val version = remember {
        val name = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        "simplestCUT ${name ?: ""}" + if (debug) context.getString(R.string.version_debug) else ""
    }
    Text(
        version,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
    )
}
