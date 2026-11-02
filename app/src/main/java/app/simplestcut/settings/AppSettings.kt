package app.simplestcut.settings

import app.simplestcut.R
import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class StartFolder(@StringRes val label: Int) { ALL(R.string.start_all), CAMERA(R.string.start_camera), LAST(R.string.start_last) }

enum class ThemeMode(@StringRes val label: Int) { SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark) }

/** Bild abdunkeln, solange die Player-Bedienung eingeblendet ist. */
enum class ControlsDim(@StringRes val label: Int) { FULL(R.string.dim_full), BUTTONS(R.string.dim_buttons), NONE(R.string.dim_none) }

enum class SortKey(@StringRes val label: Int) { DATE(R.string.sort_date), DURATION(R.string.sort_duration), SIZE(R.string.sort_size) }

/** Dauerhaft gespeicherte Einstellungen. Änderungen wirken sofort in der Oberfläche. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoplay by mutableStateOf(prefs.getBoolean(KEY_AUTOPLAY, true))
        private set
    var loop by mutableStateOf(prefs.getBoolean(KEY_LOOP, false))
        private set

    /** Spalten im Galerie-Raster (2..5), per Zwei-Finger-Geste änderbar. */
    var gridColumns by mutableStateOf(prefs.getInt(KEY_GRID_COLUMNS, 3).coerceIn(2, 5))
        private set

    fun updateGridColumns(value: Int) {
        gridColumns = value.coerceIn(2, 5)
        prefs.edit().putInt(KEY_GRID_COLUMNS, gridColumns).apply()
    }

    var themeMode by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM
    )
        private set

    fun updateThemeMode(value: ThemeMode) {
        themeMode = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    /** Schnittmodus: weißen Strich (Abspielposition) mit dem Finger ziehen. */
    var dragPlayhead by mutableStateOf(prefs.getBoolean(KEY_DRAG_PLAYHEAD, true))
        private set

    var controlsDim by mutableStateOf(
        ControlsDim.entries.firstOrNull { it.name == prefs.getString(KEY_CONTROLS_DIM, null) } ?: ControlsDim.BUTTONS
    )
        private set

    fun updateControlsDim(value: ControlsDim) {
        controlsDim = value
        prefs.edit().putString(KEY_CONTROLS_DIM, value.name).apply()
    }

    var sortKey by mutableStateOf(
        SortKey.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SortKey.DATE
    )
        private set
    /** true = neueste / längste / größte zuerst */
    var sortDescending by mutableStateOf(prefs.getBoolean(KEY_SORT_DESC, true))
        private set

    /** Neue Sortierung wählen; dieselbe nochmal wählen dreht die Richtung um. */
    fun chooseSort(key: SortKey) {
        if (key == sortKey) {
            sortDescending = !sortDescending
        } else {
            sortKey = key
            sortDescending = true
        }
        prefs.edit().putString(KEY_SORT, sortKey.name).putBoolean(KEY_SORT_DESC, sortDescending).apply()
    }

    var startFolder by mutableStateOf(
        StartFolder.entries.firstOrNull { it.name == prefs.getString(KEY_START, null) } ?: StartFolder.ALL
    )
        private set

    /** Zuletzt gewählter Ordner (null = alle Videos). */
    val lastFolderId: Long?
        get() = prefs.getLong(KEY_LAST_FOLDER, -1L).takeIf { it != -1L }

    fun updateStartFolder(value: StartFolder) {
        startFolder = value
        prefs.edit().putString(KEY_START, value.name).apply()
    }

    fun rememberFolder(id: Long?) {
        prefs.edit().putLong(KEY_LAST_FOLDER, id ?: -1L).apply()
    }

    fun updateAutoplay(value: Boolean) {
        autoplay = value
        prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    fun updateLoop(value: Boolean) {
        loop = value
        prefs.edit().putBoolean(KEY_LOOP, value).apply()
    }

    /** Hinweis auf die "Medienverwaltung" wurde schon einmal gezeigt (siehe MediaAccess.requestWrite). */
    val manageMediaOffered: Boolean
        get() = prefs.getBoolean(KEY_MANAGE_MEDIA_OFFERED, false)

    fun markManageMediaOffered() {
        prefs.edit().putBoolean(KEY_MANAGE_MEDIA_OFFERED, true).apply()
    }

    /** Freigabe des Messenger-Ordners wurde beim Start schon einmal angeboten. */
    val messengerFolderOffered: Boolean
        get() = prefs.getBoolean(KEY_MESSENGER_OFFERED, false)

    fun markMessengerFolderOffered() {
        prefs.edit().putBoolean(KEY_MESSENGER_OFFERED, true).apply()
    }

    fun updateDragPlayhead(value: Boolean) {
        dragPlayhead = value
        prefs.edit().putBoolean(KEY_DRAG_PLAYHEAD, value).apply()
    }

    private companion object {
        const val KEY_MANAGE_MEDIA_OFFERED = "manage_media_offered"
        const val KEY_MESSENGER_OFFERED = "messenger_folder_offered"
        const val KEY_CONTROLS_DIM = "controls_dim"
        const val KEY_DRAG_PLAYHEAD = "drag_playhead"
        const val KEY_THEME = "theme"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_LOOP = "loop"
        const val KEY_SORT = "sort"
        const val KEY_SORT_DESC = "sort_desc"
        const val KEY_START = "start_folder"
        const val KEY_LAST_FOLDER = "last_folder"
    }
}

@Composable
fun rememberAppSettings(): AppSettings {
    val context = LocalContext.current.applicationContext
    return remember { AppSettings(context) }
}
