package eu.chezmaurice.carnet.platform

/**
 * What Carnet keeps to show offline (`ShelfCache` and its siblings): plain
 * files, one per purpose — a member's own under their prefix ([offlineKey]).
 */
expect object Offline {
    fun read(key: String): String?
    fun write(key: String, text: String)
    fun delete(key: String)
    fun exists(key: String): Boolean
    /** Every file whose key starts with [prefix]: a downloaded book, all at once. */
    fun deleteAll(prefix: String)
    /** Their size, in bytes. */
    fun sizeOf(prefix: String): Long
    /** A file kept as it is (a book waiting to be sent), beside the others. */
    fun readBytes(key: String): ByteArray?
    fun writeBytes(key: String, bytes: ByteArray)
}

/** FNV-1a, 64 bits, in hex: a name for a file from any string (a slug, an address). */
fun stableHash(text: String): String {
    var h = 0xcbf29ce484222325uL
    for (b in text.encodeToByteArray()) { h = h xor b.toUByte().toULong(); h *= 0x100000001b3uL }
    return h.toString(16)
}

/** What a member's files start with: one prefix per member of each household, so they go in one call. */
private fun memberPrefix(scope: String) = "m-" + stableHash(scope) + "-"

/**
 * A key for [Offline], safe as a file name: a purpose, for one member of a
 * household ([scope], `household#member`). The shelf, the reading positions,
 * the outbox and the articles' text are the member's own; the books and the
 * reader's settings are the household's and are not keyed here.
 */
fun offlineKey(scope: String, purpose: String): String = memberPrefix(scope) + purpose

/**
 * Forget everything kept for a member: account deleted, session refused (401),
 * household unpaired — but their outbox, which waits for them to sign in again
 * and is sent only as them (iOS's queue outlives a session the same way).
 */
fun forgetMember(scope: String) {
    // The books waiting for a library too: their files are kept apart, under no member's prefix.
    val kept = listOf("outbox", "book-outbox").associateWith { Offline.read(offlineKey(scope, it)) }
    Offline.deleteAll(memberPrefix(scope))
    for ((purpose, text) in kept) if (text != null) Offline.write(offlineKey(scope, purpose), text)
}

/**
 * Up to Carnet 0.1.3 the keys were `purpose-hash(scope)`, and the articles'
 * text was keyed by server alone, readable by whoever was signed in next.
 * The member's shelf, positions and unsent captures move under their prefix;
 * the articles' text is fetched again.
 */
fun migrateLegacyOffline(scope: String) {
    for (purpose in listOf("shelf", "positions", "outbox")) {
        val legacy = purpose + "-" + stableHash(scope)
        val text = Offline.read(legacy) ?: continue
        if (!Offline.exists(offlineKey(scope, purpose))) Offline.write(offlineKey(scope, purpose), text)
        Offline.delete(legacy)
    }
    Offline.deleteAll("article-")
}
