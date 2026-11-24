package app.simplestcut.demo

import android.content.Context
import android.os.Environment

/**
 * Demo-Modus für Bildschirmaufnahmen (Erklär-Videos): die Galerie zeigt nur die Videos eines Ordners, ohne
 * Ordner-Menü und Papierkorb, damit nichts Privates ins Bild kommt. Eingeschaltet wird er nur über die
 * DemoActivity der Dev-App (scripts/demo.sh); die Store-Version hat keinen Weg dorthin, der Wert bleibt leer.
 */
object DemoMode {
    private const val PREFS = "demo"
    private const val KEY = "folder"

    /** Ordner relativ zum Speicher (z. B. "Movies/Demo") oder null = aus. */
    fun folder(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun set(context: Context, folder: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (folder == null) remove(KEY) else putString(KEY, folder.trim('/'))
        }.commit()
    }

    /** Liegt die Datei [path] im Demo-Ordner [folder] (auch in Unterordnern)? */
    fun contains(folder: String, path: String): Boolean =
        path.startsWith(Environment.getExternalStorageDirectory().absolutePath + "/" + folder + "/")
}
