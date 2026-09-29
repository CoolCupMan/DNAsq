package com.coolcupman.dnasq

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val app get() = application as DnasqApp

    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var refresh by mutableStateOf(0)

    private enum class Pending { ENROLL, VERIFY }
    private var pending = Pending.VERIFY

    private val pickSample = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy = true
        status = if (pending == Pending.ENROLL) "Building protected template…" else "Checking DNA sample…"
        lifecycleScope.launch {
            val auth = DnaAuthenticator(this@MainActivity)
            val outcome = if (pending == Pending.ENROLL) auth.enroll(uri) else auth.verify(uri)
            status = when (outcome) {
                is DnaAuthenticator.Outcome.Success -> outcome.message
                is DnaAuthenticator.Outcome.Failure -> outcome.message
            }
            busy = false
            refresh++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    private fun pick(what: Pending) {
        pending = what
        pickSample.launch(arrayOf("*/*"))
    }

    @Composable
    private fun Screen() {
        val tick = refresh // reading the state makes the screen recompose after every refresh++
        val enrolled = app.templateStore.isEnrolled
        val unlocked = Session.isUnlocked
        val serviceOn = AppLockService.isEnabled(this)

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(getString(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                "Edition: ${app.lab.displayName} · v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "App ID: ${BuildConfig.APPLICATION_ID}\n" +
                    "Accepts: ${app.lab.formats.joinToString { it.displayName }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (enrolled) {
                val level = runCatching { KeystoreVault.securityLevel(app.templateStore.read().keyAlias) }.getOrNull()
                Text("Template key: ${level ?: "missing"}", style = MaterialTheme.typography.bodySmall)
            }

            if (!serviceOn) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("App locking is off. Enable the DNAsq accessibility service so locked apps can be covered.")
                        Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text("Open accessibility settings")
                        }
                    }
                }
            }

            when {
                busy -> CircularProgressIndicator()
                !enrolled -> Button(onClick = { pick(Pending.ENROLL) }) { Text("Enroll DNA raw-data file") }
                !unlocked -> Button(onClick = { pick(Pending.VERIFY) }) { Text("Verify DNA to manage") }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Session.lock(); refresh++ }) { Text("Lock now") }
                    OutlinedButton(onClick = { pick(Pending.ENROLL) }) { Text("Re-enroll") }
                    OutlinedButton(onClick = {
                        app.templateStore.delete()
                        app.settings.lockedPackages = emptySet()
                        Session.lock()
                        status = "DNA template and hardware key destroyed."
                        refresh++
                    }) { Text("Delete") }
                }
            }
            if (status.isNotEmpty()) Text(status)

            if (enrolled && unlocked && !busy) {
                HorizontalDivider()
                Text("Apps to lock", style = MaterialTheme.typography.titleMedium)
                AppList()
            }
        }
    }

    @Composable
    private fun AppList() {
        val pm = packageManager
        @Suppress("DEPRECATION") // ResolveInfoFlags overload needs API 33
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        var locked by remember { mutableStateOf(app.settings.lockedPackages) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                locked = apps.map { it.first }.toSet(); app.settings.lockedPackages = locked
            }) { Text("Lock all") }
            OutlinedButton(onClick = { locked = emptySet(); app.settings.lockedPackages = locked }) { Text("Unlock all") }
        }
        LazyColumn {
            items(apps, key = { it.first }) { (pkg, label) ->
                val checked = pkg in locked
                Row(
                    Modifier.fillMaxWidth().clickable {
                        locked = if (checked) locked - pkg else locked + pkg
                        app.settings.lockedPackages = locked
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column {
                        Text(label)
                        Text(pkg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
