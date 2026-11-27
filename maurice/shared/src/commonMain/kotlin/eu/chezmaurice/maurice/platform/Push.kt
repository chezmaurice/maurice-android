package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import eu.chezmaurice.core.session.Session

/**
 * Once signed in, ask for this device's push address and declare it to the
 * households (Android: UnifiedPush, and the notification permission). The
 * desktop has no push: its window keeps the live socket while it runs.
 *
 * Both apps use it, each with its own UnifiedPush registration (the
 * distributor tells them apart by package) and its own [platform] on the
 * server — `android` for Maurice, `carnet-android` for Carnet, which the
 * server reads as Carnet's device (an entry shared goes only there).
 * [appName] is what the distributor shows for the registration.
 */
@Composable
expect fun PushRegistrationEffect(session: Session, platform: String = "android", appName: String = "Maurice")
