package com.coolcupman.dnasq

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Full-screen cover shown over a locked app until the DNA session is unlocked. */
class LockActivity : FragmentActivity() {
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var lockedLabel by mutableStateOf("")

    private val pickSample = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = true
        status = "Checking DNA sample…"
        lifecycleScope.launch {
            when (val outcome = DnaAuthenticator(this@LockActivity).verify(uri)) {
                is DnaAuthenticator.Outcome.Success -> finish()
                is DnaAuthenticator.Outcome.Failure -> status = outcome.message
            }
            busy = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        readIntent(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Locked by DNAsq", style = MaterialTheme.typography.headlineMedium)
                        Text(lockedLabel, textAlign = TextAlign.Center)
                        if (busy) {
                            CircularProgressIndicator()
                        } else {
                            Button(onClick = { pickSample.launch(arrayOf("*/*")) }) {
                                Text("Verify with DNA sample")
                            }
                            OutlinedButton(onClick = ::goHome) { Text("Cancel") }
                        }
                        if (status.isNotEmpty()) Text(status, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Session.isUnlocked) finish()
    }

    private fun readIntent(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        lockedLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "locked_package"
    }
}
