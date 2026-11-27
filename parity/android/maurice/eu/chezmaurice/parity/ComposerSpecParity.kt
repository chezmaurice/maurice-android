package eu.chezmaurice.parity

import eu.chezmaurice.core.composer.TrayItem
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

class ComposerSpecParity {
    @Test fun composerSpec() = Parity.check("maurice", "composer-spec") { c ->
        val it = (c.input as? JsonObject)?.let(TrayItem::fromSpec)
        mapOf("item" to it?.let(ComposerSupport::item))
    }
}
