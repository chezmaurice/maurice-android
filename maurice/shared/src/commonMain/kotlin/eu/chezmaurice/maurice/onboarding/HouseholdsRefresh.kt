package eu.chezmaurice.maurice.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.chezmaurice.core.session.Session
import kotlinx.coroutines.launch

/**
 * The paired households' names and looks, their identity keys pinned, and a
 * household that moved followed to its new address (`Session.refreshHouseholds`):
 * at launch and each time the app comes back to the front. Both apps.
 */
@Composable
fun HouseholdsRefresh(session: Session) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) scope.launch { session.refreshHouseholds() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
