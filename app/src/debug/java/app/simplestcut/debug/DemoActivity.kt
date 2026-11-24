package app.simplestcut.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.simplestcut.MainActivity
import app.simplestcut.demo.DemoMode

/**
 * Nur Dev-App: Demo-Modus ein (--es folder Movies/Demo) oder aus (--ez off true), danach die App frisch starten.
 * Aufruf über scripts/demo.sh.
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val off = intent.getBooleanExtra("off", false)
        DemoMode.set(this, if (off) null else intent.getStringExtra("folder") ?: "Movies/Demo")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
