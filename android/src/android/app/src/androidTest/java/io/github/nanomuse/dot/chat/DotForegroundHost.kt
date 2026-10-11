package io.github.nanomuse.dot.chat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import java.util.UUID

/** External test-APK Activity; renders public text only and closes only its exact owned ID. */
class DotForegroundHost : Activity() {
    private var ownerId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = requireNotNull(intent.getStringExtra("ownerId"))
        require(UUID.fromString(id).toString() == id)
        ownerId = id
        // A cleanup-only launch after process loss creates no lingering host UI.
        if (intent.getStringExtra("finishOwnedId") == id) { finish(); return }
        setContentView(TextView(this).apply {
            text = "PUBLIC_HOST_$id"; textSize = 24f; setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        })
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("finishOwnedId") == ownerId) finish()
    }
}
