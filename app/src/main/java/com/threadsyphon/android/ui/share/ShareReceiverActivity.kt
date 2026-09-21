package com.threadsyphon.android.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.threadsyphon.android.MainActivity
import com.threadsyphon.android.ThreadSyphonApp
import com.threadsyphon.android.data.model.parseThreadUrl
import com.threadsyphon.android.service.WatchService
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "No 4chan thread URL found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val app = application as ThreadSyphonApp
        lifecycleScope.launch {
            try {
                parseThreadUrl(url)
                app.repository.addThreadFromUrl(url, autoStart = true)
                WatchService.start(this@ShareReceiverActivity)
                Toast.makeText(this@ShareReceiverActivity, "Watching thread", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                )
            } catch (e: Exception) {
                Toast.makeText(this@ShareReceiverActivity, e.message ?: "Could not add thread", Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                return findUrlInText(text)
            }
            Intent.ACTION_VIEW -> return intent.data?.toString()
        }
        return intent.data?.toString()
    }

    private fun findUrlInText(text: String): String? {
        val regex = Regex(
            """https?://(?:boards\.)?(?:www\.)?4chan(?:nel)?\.org/[a-z0-9]+/thread/\d+[^\s]*""",
            RegexOption.IGNORE_CASE,
        )
        return regex.find(text)?.value ?: text.trim().takeIf {
            try { parseThreadUrl(it); true } catch (_: Exception) { false }
        }
    }
}
