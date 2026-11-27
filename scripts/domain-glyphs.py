#!/usr/bin/env python3
"""Write ui/.../DomainGlyphs.kt: the SF Symbols a domain can wear (the list of
`DomainIcons` in DomainsViews.swift and the server's services/domainIcons.ts),
each drawn with the Material Symbol closest to it (Apache 2.0), plus the few
other symbols the domain screens name. Run again when the list changes."""
import re, sys, urllib.request, pathlib

# SF Symbol -> Material Symbol (outlined, 400).
MAP = {
    "cross.case": "medical_services", "brain.head.profile": "psychology", "moon.zzz": "bedtime",
    "figure.run": "directions_run", "figure.pool.swim": "pool", "bicycle": "pedal_bike",
    "figure.mind.and.body": "self_improvement", "mountain.2": "landscape", "fork.knife": "restaurant",
    "wineglass": "wine_bar", "leaf": "eco", "pawprint": "pets",
    "house": "home", "hammer": "handyman", "building.2": "apartment", "building.columns": "account_balance",
    "banknote": "payments", "briefcase": "work",
    "lightbulb": "lightbulb", "megaphone": "campaign", "chevron.left.forwardslash.chevron.right": "code",
    "server.rack": "dns", "cpu": "memory", "graduationcap": "school",
    "character.book.closed": "translate", "books.vertical": "library_books", "pencil.and.outline": "edit_note",
    "text.quote": "format_quote", "clock.arrow.circlepath": "history", "atom": "science",
    "music.note": "music_note", "guitars": "library_music", "pianokeys": "piano", "paintpalette": "palette",
    "camera": "photo_camera", "film": "movie",
    "theatermasks": "theater_comedy", "gamecontroller": "sports_esports", "airplane": "flight",
    "car": "directions_car", "sailboat": "sailing", "globe.europe.africa": "public",
    "person.2": "group", "figure.2.and.child.holdinghands": "family_restroom", "heart": "favorite",
    "sparkles": "star_shine", "envelope": "mail", "cart": "shopping_cart",
    "tshirt": "checkroom", "calendar": "calendar_month", "trophy": "emoji_events", "bolt": "bolt",
    "shield": "shield", "newspaper": "newspaper",
    # Not in the night's list: the fallback, a companion, and the screens' own.
    "book.closed": "book", "book.pages": "menu_book", "moon.stars": "bedtime", "person": "person",
    "link.badge.plus": "add_link", "link.badge.minus": "link_off", "arrow.triangle.merge": "merge",
    "scissors": "content_cut", "bubble.left": "chat_bubble", "bubble.left.and.bubble.right": "forum",
    "cloud": "cloud", "lock.shield": "shield_lock", "doc.text": "description", "dial.medium": "tune",
    "brain": "neurology", "arrow.clockwise": "refresh", "creditcard": "credit_card", "hourglass": "hourglass_empty",
    "wrench.and.screwdriver": "build", "globe": "language", "text.badge.plus": "playlist_add",
    "folder": "folder", "doc": "draft", "note.text": "sticky_note_2", "person.text.rectangle": "badge",
    # The composer's item kinds and controls.
    "rectangle.3.group": "account_tree", "book": "book_2", "square.text.square": "article",
    "photo": "image", "doc.richtext": "picture_as_pdf", "lock.fill": "lock", "exclamationmark.triangle.fill": "warning",
    "paperclip": "attach_file", "suitcase": "luggage", "pause.circle": "pause_circle", "bookmark": "bookmark",
    "play": "play_arrow", "pause": "pause", "arrow.counterclockwise": "replay", "plus.circle": "add_circle",
    "checkmark.circle.fill": "check_circle", "arrow.up.doc": "upload_file", "bolt.fill": "bolt",
    "wrench.adjustable": "construction",
    # Tool families (the server's icons).
    "binoculars": "travel_explore", "checklist": "checklist", "doc.text.magnifyingglass": "find_in_page",
    "paperplane": "send", "person.crop.rectangle.stack": "contacts", "play.rectangle.on.rectangle": "video_library",
    "text.append": "notes", "tray.full": "inbox", "waveform.path.ecg": "monitor_heart",
    "trash": "delete", "pencil": "edit", "eye.slash": "visibility_off", "checkmark": "check", "xmark": "close", "plus": "add", "arrow.up": "arrow_upward", "gearshape": "settings", "arrow.up.arrow.down": "swap_vert", "archivebox": "archive", "hand.raised": "pan_tool", "minus.circle": "do_not_disturb_on", "chevron.left": "chevron_left", "person.badge.plus": "person_add", "arrow.up.forward": "north_east", "externaldrive": "hard_drive", "key": "key", "trophy": "emoji_events",
    # Carnet's readers.
    "textformat.size": "format_size", "highlighter": "ink_highlighter", "sparkle": "star_shine", "arrow.down.circle": "arrow_circle_down",
    "arrow.down.circle.fill": "download_for_offline", "bookmark.fill": "bookmark_added", "chevron.right": "chevron_right", "ellipsis": "more_horiz",
    "link": "link", "square.and.arrow.up": "ios_share",
    # Carnet: an archived entry brought back.
    "tray.and.arrow.up": "unarchive",
    # The member's own tools (MCP servers).
    "puzzlepiece.extension": "extension",
    # My devices.
    "laptopcomputer.and.iphone": "devices", "iphone": "smartphone", "ipad": "tablet", "laptopcomputer": "laptop_mac", "desktopcomputer": "desktop_windows", "questionmark.circle": "help", "rectangle.portrait.and.arrow.right": "logout",
}

URL = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/{0}/materialsymbolsoutlined/{0}_24px.svg"

def path_of(name):
    svg = urllib.request.urlopen(URL.format(name), timeout=20).read().decode()
    # The glyphs are drawn in the symbols' 960 grid; an older icon still
    # drawn on 24 units would come out a speck.
    if 'viewBox="0 -960 960 960"' not in svg: raise SystemExit(f"{name} is not on the 960 grid")
    paths = re.findall(r'<path d="([^"]+)"', svg)
    if not paths: raise SystemExit(f"no path in {name}")
    return " ".join(paths)

out = pathlib.Path(sys.argv[1])
lines = []
for sf, ms in MAP.items():
    lines.append(f'        "{sf}" to "{path_of(ms)}",')
    print(sf, "->", ms, file=sys.stderr)
out.write_text(f'''package eu.chezmaurice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The SF Symbols the iOS app names for domains (`DomainIcons`) and their
 * screens, drawn with the closest Material Symbol (outlined, Apache 2.0).
 * Generated by `scripts/domain-glyphs.py`; do not edit by hand.
 */
object DomainGlyphs {{
    /** A domain without an icon, or with one this table lacks: a closed book. */
    const val FALLBACK = "book.closed"

    /** What the domain editor offers, in the night's order. */
    val CHOICES: List<String> = listOf(
{chr(10).join(f'        "{k}",' for k in list(MAP)[:54])}
    )

    fun has(symbol: String?): Boolean = symbol != null && symbol in PATHS

    /** The glyph for an SF Symbol name; the closed book for an unknown one. */
    fun of(symbol: String?): ImageVector {{
        val key = if (has(symbol)) symbol!! else FALLBACK
        return cache.getOrPut(key) {{ build(key, PATHS.getValue(key)) }}
    }}

    private val cache = HashMap<String, ImageVector>()

    // Material Symbols draw in a 960 box whose top is at y = -960.
    private fun build(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f)
            .addGroup(translationY = 960f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .clearGroup()
            .build()

    private val PATHS: Map<String, String> = mapOf(
{chr(10).join(lines)}
    )
}}
''')
