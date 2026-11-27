# Coverage of the parity suite

As of 11 November 2026 (maurice `a45ae44`, carnet `a9418ad`): 62 domains,
about 1 600 cases. Each line names the vectors (`parity/vectors/<app>/`).

## Maurice

| Area | Domains | What is compared |
|---|---|---|
| Chat | `chat-turn`, `chat-thread`, `chat-snippets`, `chat-cards`, `chat-article`, `conversation-list` | A turn end to end (every stream event, drops and re-attach, HTTP refusals, 409 busy, photos, regenerate, stop); opening, rename, delete, search, paging, rooms (add, leave, block), domain binding, report; snippets and the cards a message is read back into; tallies and sources; shared articles. |
| Session | `session-invitation`, `session-invitation-link`, `session-address`, `session-move-record`, `session-move-bytes`, `session-ed25519`, `session-move-proof`, `session-refresh`, `session-receive`, `session-leave`, `session-rejected`, `session-prefs`, `session-prefs-save`, `session-ai-consent`, `session-budget`, `session-account-events`, `pin-rules` | Invitations and addresses, household move (TXT record, Ed25519 — RFC 8032 vectors), `/api/health` refresh, sign-out and forget, 401, preferences and device report, AI consent, budget thresholds, account events, PIN rules. |
| Domains & composer | `domains-load`, `domains-edit`, `domains-brief`, `domains-proposals`, `domains-binding`, `domains-pastilles`, `domains-format`, `domains-colours`, `composer-spec`, `composer-tray`, `composer-suggestions` | Domains and models, create/edit/delete, brief, night proposals, pastilles, palettes and hex colours, the composer's tray and what a message carries, Carnet suggestions. |
| Settings | `settings-reports`, `settings-mail`, `settings-data`, `settings-dictation`, `settings-store` | Reports (queue, act, dismiss, report), mail and address-book providers and rows, sizes, library, account events, imports, dictation upload, subscription state and the tower. |
| Words | `strings-table`, `strings-format` | Every key of the seven languages; how placeholders are filled. |

## Carnet

| Area | Domains | What is compared |
|---|---|---|
| Shelf & entries | `shelf-decode`, `shelf-reads`, `shelf-writes`, `shelf-session-refused`, `shelf-share-card`, `shelf-outbox`, `shelf-invitation`, `shelf-address`, `shelf-move-record`, `shelf-move-proof`, `shelf-household-refresh`, `shelf-handoff`, `shelf-web-link` | Entries and their pages, every write, the share card's text, the offline queue, invitations, household move and refresh, handing to Maurice, the signed-in web link. |
| Reading | `reading-positions`, `reading-calibre`, `reading-highlights`, `reading-articles`, `reading-resonances`, `reading-models`, `reading-caches`, `reading-dictation` | Reading positions and their reconciliation, Calibre routes, highlights, articles, résonances, sizes and washes, offline caches, dictation. |

## UI scenarios — Maurice (`parity/ui/scenarios/maurice-*.json`)

| Scenario | The flow |
|---|---|
| `pair-by-address`, `pair-errors` | Pairing by address; an invalid address, nothing answering, a web server that is no Maurice, a Maurice not ready. |
| `join-with-code` | A code alone, then the address; who is joining (name, colour); a PIN too easy, a mismatch, then set. |
| `admin-sign-in`, `pin-login`, `sign-out`, `forget-household` | The admin's password (401, then in); « Who's here? » with members known: a wrong PIN, 429, the right one; signing out; forgetting the household. |
| `settings-members`, `settings-member-sheet` | Members and open invitations, « Invite someone »; a member's sheet: rename, delete (iOS only: `not-ported`). |
| `settings-admin-password` | Too short (refused before sending), too easy (400), wrong current (401). |
| `settings-mail-add` | The form; the mailbox refuses the login (422); connected. |
| `my-data`, `my-data-erase` | Export; erase: a wrong password (403), then erased. |
| `domain-editor`, `domain-delete` | New domain: a taken name (409), created (`ios-bug`: iOS never opens the editor); delete after a confirmation. |
| `chat-send`, `carnet-drawer` | A message sent, the reply streamed; the Carnet drawer keeping a suggestion. |

## UI scenarios — Carnet (`parity/ui/scenarios/carnet-*.json`)

Carnet, driven as a member goes (`parity/ui/scenarios/carnet-*.json`, Alice's
household in `scenarios/common/carnet-household.json`, the way in shared as
`common/carnet-sign-in.json`): the way in (a code then the address, a PIN to
set, a refused code, no household), the shelf (list, search, sort, empty,
refused, the menu, archived, unarchive), an entry (both faces and the ⋯ menu,
refresh, archive and delete by sliding a row, with the warning, refused),
a note (kept, refused), the card (written, published and unpublished after
their confirmation, a refusal, shared up to the system sheet), Notes, Maurice,
the book reader (position, bookmark, next chapter), a highlight, a résonance
with its target picker, the article reader, talking with Maurice from an
entry, switching member with a wrong then a right PIN.

## Beside the cases

`parity/endpoints.py` lists, from the source of both apps (views included),
every server route iOS calls and Android never does. `scripts/parity.sh`
prints it after the run.

## Not compared, and why

**Inside a SwiftUI view or `private`** — out of reach of the Swift half; needs
UI-level tests (driving both apps on a simulator and an emulator against the
fake household), not built yet:

- Maurice: the requests of SettingsView not driven by a UI scenario below
  (device codes, household name, avatar, MCP token, web themes, library, mail
  actions beyond adding one, contacts); MyDataView's git, import and account
  deletion; conversation import; the domain editor's consent question; the
  dictation state machine; reply rendering (Markdown, math, images, tables).
  A turn's « stop » is not driven either: the fake household answers a
  stream at once, so there is never a turn running to stop.
- Carnet: "Continue", month grouping (seen in the UI layer's texts, not
  compared), `[[link]]` resolution in a page, the reader's paragraph mapping,
  cover URLs (left out of the UI comparison: an image one app takes from its
  cache). Search, sort, position saving, the highlight and résonance sheets
  are in the UI layer.

**No Android counterpart yet** (the iOS delta, `scripts/ios-delta.sh`): guest
invitations, giving an entry to another household, adding an entry, note
editing and linking a book, shared gardens (ScopePickerView), person review,
sign-up from the app.

**Platform-specific, by design** (parity.md): on-device dictation, StoreKit
purchases (Android: Play Billing), hats, the iOS push decryption (Android:
UnifiedPush), the macOS browser session.

**Live sockets**: the fake household speaks no WebSocket; what the room and
member sockets bring is not compared (`/ws` requests are not logged).

**Carnet's full scope** (CarnetScope.swift): library, drawer — not shipped on
iOS either.
