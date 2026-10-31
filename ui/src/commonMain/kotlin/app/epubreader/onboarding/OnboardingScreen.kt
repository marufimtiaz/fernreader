package app.epubreader.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.epubreader.ui.Copy

/** Storage access as the app sees it (implemented by app's StorageAccess). */
interface StoragePermissionPort {
    fun granted(): Boolean
    /** Sends the user to the system permission screen (or shows the runtime request on API 26–29). */
    fun request()
}

/** F-001: shown before any library UI when the app cannot read shared storage. Opaque: blocks the WebView below. */
@Composable
fun OnboardingScreen(onGrant: () -> Unit, onOpenPicker: () -> Unit, onLeave: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
        ) {
            Text(Copy.onboardingTitle, style = MaterialTheme.typography.headlineMedium)
            Text(Copy.onboardingBody, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text(Copy.onboardingGrant) }
            OutlinedButton(onClick = onOpenPicker, modifier = Modifier.fillMaxWidth()) { Text(Copy.onboardingPicker) }
            TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text(Copy.onboardingLeave) }
        }
    }
}
