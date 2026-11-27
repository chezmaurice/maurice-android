package eu.chezmaurice.maurice.store

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.store.StoreProducts
import eu.chezmaurice.maurice.budget.LocalBudget
import eu.chezmaurice.maurice.budget.longDate
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.settings.IndexRow
import eu.chezmaurice.maurice.settings.SetCaption
import eu.chezmaurice.maurice.settings.SetCard
import eu.chezmaurice.maurice.settings.SetDivider
import eu.chezmaurice.maurice.settings.SetGroup
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.launch
import kotlin.time.Instant

private const val PACKAGE = "eu.chezmaurice.maurice"

/**
 * Settings → Abonnement (`SubscriptionPane`): the admin of a hosted household,
 * and no one else. Where the subscription stands, the two plans and the
 * top-ups at Google Play's own prices, managing and restoring, the terms.
 *
 * A subscription billed elsewhere (the App Store, the web) is shown as
 * running, and nothing more: no plan to buy, and not a word of where or for
 * how much it was bought.
 */
@Composable
fun SubscriptionPane(store: StoreController, billing: BillingChannel, languageTag: String) {
    val theme = LocalMauriceTheme.current
    val accent = legible(LocalAccent.current, theme.isDark)
    val state by store.state.collectAsState()
    val budget = LocalBudget.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    LaunchedEffect(store) { store.refresh() }
    val channel = billing.name
    val s = state.state

    SetCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s == null && state.error == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute)
            else if (s != null) {
                Text(statusText(state, languageTag), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                if (s.via == channel && state.isSubscribed) Text(L("store.via_play"), fontSize = 12.5.sp, color = theme.inkSoft)
            }
        }
    }

    if (state.offersSubscriptions(channel)) SetGroup(L("store.plans.group")) {
        SetCard {
            state.subscriptions.forEachIndexed { i, product ->
                if (i > 0) SetDivider()
                val yearly = product.id == StoreProducts.YEARLY
                val current = s?.via == channel && state.currentProduct == product.id
                BuyRow(
                    label = L(if (yearly) "store.plan.yearly" else "store.plan.monthly"),
                    price = L(if (yearly) "store.price.year" else "store.price.month", product.price),
                    current = current, busy = state.busy == product.id, enabled = state.busy == null && !current,
                ) { scope.launch { store.purchase(product.id) } }
            }
        }
        SetCaption(L("store.plans.caption.play"))
    }

    // Google keeps the subscription: changing plan, the card, cancelling all happen in Play.
    if (s?.via == channel) SetCard {
        IndexRow("creditcard", L("store.manage")) {
            val sku = state.currentProduct?.let { "sku=$it&" } ?: ""
            uri.openUri("https://play.google.com/store/account/subscriptions?${sku}package=$PACKAGE")
        }
    }

    if (state.topUps.isNotEmpty()) SetGroup(L("store.topup.group")) {
        SetCard {
            state.topUps.forEachIndexed { i, product ->
                if (i > 0) SetDivider()
                BuyRow(L("store.topup.label"), product.price, current = false, busy = state.busy == product.id, enabled = state.busy == null) {
                    scope.launch { store.purchase(product.id) { budget?.refresh() } }
                }
            }
        }
        SetCaption(L("store.topup.caption"))
    }

    if (state.pending) Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(DomainGlyphs.of("hourglass"), null, tint = theme.inkSoft, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(L("store.pending"), fontSize = 13.sp, color = theme.inkSoft)
    }
    state.error?.let { e ->
        Text(when (e) { is StoreError.Key -> L(e.key); is StoreError.Text -> e.text }, fontSize = 13.sp, color = Color(0xFFD64545))
    }

    SetCard {
        IndexRow(
            "arrow.clockwise", L("store.restore"),
            trailing = if (state.busy == StoreController.RESTORE) ({ CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute) }) else null,
        ) { scope.launch { store.restore() } }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(L("store.terms"), fontSize = 12.sp, color = accent, modifier = Modifier.clickable { uri.openUri("https://www.chezmaurice.eu/terms.html") })
        Text(L("store.privacy"), fontSize = 12.sp, color = accent, modifier = Modifier.clickable { uri.openUri("https://www.chezmaurice.eu/privacy.html") })
    }
}

/** What Settings shows beside « Abonnement ». */
@Composable
fun subscriptionValue(state: StoreUiState): String? = when {
    state.state == null -> null
    state.isSubscribed -> L(if (state.state.plan == "yearly") "store.value.yearly" else "store.value.monthly")
    state.isInTrial -> L("store.value.trial")
    else -> L("store.value.none")
}

@Composable
private fun statusText(state: StoreUiState, language: String): String {
    val s = state.state ?: return ""
    fun day(millis: Double) = longDate(Instant.fromEpochMilliseconds(millis.toLong()).toString(), language) ?: ""
    val paid = s.paidThrough
    val trial = s.trialEndsAt
    return when {
        state.isSubscribed && paid != null -> L(if (s.plan == "yearly") "store.status.yearly" else "store.status.monthly", day(paid))
        state.isInTrial && trial != null -> L("store.status.trial", day(trial))
        else -> L("store.status.none")
    }
}

/** A plan or a top-up: its name, then Play's price — or a check when it is the one running, or a spinner while buying. */
@Composable
private fun BuyRow(label: String, price: String, current: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val raw = LocalAccent.current
    val accent = legible(raw, theme.isDark)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.5.sp, color = theme.ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute)
            current -> Text("✓ " + L("store.plan.current"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent)
            else -> Text(
                price, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = accent,
                modifier = Modifier.border(0.8.dp, raw.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
