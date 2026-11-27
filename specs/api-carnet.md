# Carnet: client contract against the Maurice server (for the Android/KMP port)

Written on 21 October 2026 from the Swift sources in `carnet/` (`CarnetApp/`, `Shared/`, `CarnetShareExtension/`) and from the server (`maurice/server`, entry `index.ts`).

**How sure each part is.**
- Request shapes come straight from the Swift client and are exact.
- Response shapes in §2 are what the Swift decoders read: the minimum the server sends. The server sends more.
- **§9 records what the server actually does**, checked route by route in `maurice/server` at commit `cc39263`: status codes, error strings, extra fields, and where client and server disagree. **Where §2 and §9 differ, §9 wins.** Some cells in §2 still carry *(server unverified)*; §9 resolves them.

Conventions in this document:
- `?` after a field means the client treats it as optional (nullable, or absent).
- "snake" or "camel" says the wire casing. The server mixes them: calibre book and chapter lists are camelCase, almost everything else is snake_case.
- Every path is relative to the paired server's base URL, e.g. `https://camille.chezmaurice.eu`.

---

## 0. What the shipping build actually is

`CarnetApp/CarnetScope.swift` sets `CarnetScope.current = .minimal`. **The TestFlight build shows only the following:**
- **Shelf** (`ShelfView`): one list of entries of every kind. It is the root screen.
- **Entry** pages (`EntryView`), with the book reader and the article reader.
- **Notes**: every note the member wrote.
- **Maurice**: domains, reading companions, and conversations Maurice opened.
- The member/household sheet, plus the share extension.

These exist in the code but are **unreachable in `.minimal`**:
- The drawer shell (`AppShell.drawerShell`).
- The Calibre `LibraryView` and `ArticlesShelfView`.
- `GardenView`/`GardenEntryView`.
- `UploadSection`.

Some views are never instantiated anywhere: they are dead code, left out of this contract.

**Port priority:** the minimal scope first (sections 1–5).

---

## 1. Connection and authentication

### 1.1 Storage (iOS)

Everything lives in the **App Group UserDefaults** suite `group.eu.chezmaurice.carnet.app`. The share extension reads the same suite. **Nothing is in the Keychain**: tokens are stored in plain UserDefaults. On Android, use EncryptedSharedPreferences/DataStore, and share it with any share-target activity, which on Android is the same process anyway.

| Key | Type | Meaning |
|---|---|---|
| `carnet.households` | JSON `[Household]` | Every household paired on the device. |
| `carnet.currentHouseholdId` | String | The local id of the household on screen. |
| `carnet.token.<householdId>.<memberId>` | String | The session token per household and member. **This is the source of truth.** |
| `apiBaseURL` | String | Mirror: the current household's server URL. Read by the API layer and the extension. |
| `carnet.activeMemberId` | String | Mirror: the current household's active member. |
| `carnet.token.<memberId>` | String | Mirror: the active member's token, so the extension can authenticate. |
| `carnet.serverURL`, `carnet.deviceMembers` | legacy | Migrated once into a single household, then removed. |

`Household` (local only, never sent to the server):
```
{ id: UUID string (local), serverURL: String, name: String (default = URL host),
  color?: String, icon?: String, deviceMembers: [Member], activeMemberId?: String }
```
`color` and `icon` exist in the model, but nothing in Carnet sets them.

`Member` (as the server returns it, snake_case):
```
{ id: String, display_name: String, avatar_color: "#rrggbb", has_pin: Bool,
  role?: "admin"|"standard"|"guest", username?: String, avatar_url?: String }
```
`avatar_url` is either a server path such as `/api/avatars/<file>` (served **without auth**) or an absolute URL. When it is missing, the UI draws the initial on `avatar_color`.

Other local preferences live in standard UserDefaults (`@AppStorage`), not the group:

| Key | Values |
|---|---|
| `carnet.reader.fontSize` | 0/1/2 → 15/17/19.5 pt |
| `carnet.shelf.kinds` | Comma list of ticked kinds |
| `carnet.shelf.sort` | `activity` \| `title` |
| `carnet.library.sort` | Library sort order |
| `carnet.reading.shelf` | Selected reading shelf |
| `carnet.garden.sort` | Garden sort order |
| `carnet.dictation.allowServer` | Bool. Consent to Apple's speech servers. |
| `carnet.dictation.preferHousehold` | Bool, default `true` |
| Palette key in `CarnetTheme.swift` | Theme palette |

In the group suite, but only written by the dead `SettingsView`:
- `carnet.garden.webURL`
- `carnet.garden.workingCopyRepo`
- `carnet.garden.obsidianVault`

### 1.2 Headers on every request

- `Authorization: Bearer <token of the active member of the current household>`. Every client helper attaches it whenever a token exists, including on `/api/health`.
- `Content-Type: application/json` on JSON bodies.
- Multipart uploads use `multipart/form-data; boundary=<UUID>`.
- The cover upload is a raw `image/jpeg` body. Dictation is a raw `audio/mp4` body.
- **There is no device-id or app-version header, and no User-Agent customisation.**

Timeouts per client:

| Client | Timeout |
|---|---|
| `CalibreAPI`, `GardenAPI`, `DomainsAPI` | 15 s (some calls longer, noted in the table) |
| `MauriceAPI` | 20 s |
| `CarnetAPI` | System default (60 s) |

### 1.3 Pairing flow (`PairingView` → `MemberPickerView`)

1. The user types a host. With no `://`, the app prefixes `https://`; a typed `http://` is kept.
2. The app sets the base URL provisionally and calls `GET /api/health`. On non-2xx, or `status != "ok"`, it shows an error.
3. On success it creates the `Household` (or re-selects an existing one with the same URL), names it after the URL host, and makes it current.
4. **Member picker** (`MemberPickerView`, shown while the household has no active member with a token):
   - If any member of this household on the device has a token, the app calls `GET /api/users` with that token, shows the roster, and replaces `deviceMembers` with it.
   - Otherwise the roster is empty. The app calls `GET /api/health` again: `setup_complete == false` offers **Create admin account**; otherwise it offers **Join with an invite code**.
   - **Invite code**: `POST /api/auth/enroll {code}` returns `{user_id, token, role, needs_pin}`. Then `GET /api/users/me` with that token gives the Member.
     - If `needs_pin` is true, the PIN-setup overlay requires 4–6 digits typed twice, then `POST /api/auth/set-pin {pin}` with the new token. The member is then stored with `has_pin=true`.
     - Then `loginMember(id, token)`, and `GET /api/users` to refresh the roster.
   - **Admin sign-in**: `POST /api/auth/login {username, password}` returns `{user_id, token}`, then `GET /api/users`.
   - **First-run setup**: `POST /api/auth/setup {username, password, display_name}` returns `{user: Member, token}`.
   - **Tapping a member**:
     - If `has_pin`, the app **always** asks for the PIN, even when it holds a cached token, then calls `POST /api/auth/login {user_id, pin}`.
     - Otherwise, a cached token means an instant switch with no network call. With no cached token, it calls `POST /api/auth/login {user_id}` (no PIN). **The server always rejects that call with `401 "Invalid credentials"`** (§9.1): a member with no PIN can only get in through enroll. Do not port this path.
     - Wrong PIN shows "Wrong PIN. Try again." The app does not tell a wrong PIN apart from any other error.
   - **Disconnect** removes the household and its tokens.
5. Once signed in, the root becomes the shelf. `SyncService.startMonitoring()` starts, and the app asks for notification permission and registers the APNs token (§5.2).

### 1.4 Member and household switching (`MemberSwitchSheet`)

- **Households** (listed only when there is more than one): switching rewrites the mirror keys, so all later requests go to the other server with that household's active member's token.
- **Members**: same rules as the picker (PIN members re-verify; cached tokens switch instantly).
- **Add household** opens the pairing screen as a sheet. **Disconnect <household>** unpairs it.
- **Delete my account**:
  - Fetches `username` from the cached member, or from `GET /api/users/me`.
  - The user retypes the username and enters a password or PIN, then the app sends `POST /api/me/erase {scope:"account", confirm, password: secret, pin: secret}`, with a 600 s timeout. The same secret goes in both fields.
  - Error codes mapped to text: `confirm_mismatch`, `bad_password`, `bad_pin`, `last_admin`, `too_many_attempts`.
  - On success: log out the member and remove them from `deviceMembers`.
- Links: `https://www.chezmaurice.eu/privacy.html` and `https://www.chezmaurice.eu/terms.html`.
- `ShelfView` calls `session.refreshMembers()` (`GET /api/users`) on appear, to refresh names, colours and avatars.

### 1.5 401 handling

**There is none.** No code path checks for 401, and nothing logs out or re-prompts on an expired session. Each request fails with an error string (the JSON `error` or the raw body), and the screen shows it. Recovery is manual: tap the member again (with no PIN this re-uses the dead cached token, since `switchToMember` succeeds whenever a token exists), or disconnect and re-pair. **Recommendation for Android:** on 401 from a member route, drop that member's token and send them to the picker. This is a deliberate improvement, not parity.

Server side (verified in `server/index.ts`):
- `proxyAuth` resolves the Bearer token permissively.
- Every `/api/v1/*` route without an authenticated member answers `401 {"error":"Authentication required"}`.
- Routes using `requireAuth` answer one of:
  - `401 {"error":"Missing or invalid Authorization header"}`
  - `401 {"error":"Invalid or expired session"}`
  - `401 {"error":"Invalid API token"}`
  - `401 {"error":"User not found"}`
- Errors that escape a route answer `500 {"error":"Internal error"}`.

### 1.6 Same as the Maurice app, or different

**Same:**
- The household → member picker → PIN model.
- The routes: `/api/health`, `/api/auth/{setup,login,enroll,set-pin}`, `/api/users`, `/api/users/me`, `/api/me/erase`.
- PIN members re-verify on every switch.
- Several households per device.

**Different (from the Carnet code; the Maurice app was not re-read for this file):**
- Carnet keeps tokens in App Group UserDefaults, not the Keychain.
- Carnet ignores the `household`, `household_color` and `household_icon` fields that `/api/health` returns; it names the household after the host.
- Carnet registers push with `platform: "carnet-ios"` rather than the Maurice app's `ios`, because an APNs token is per app.
- Carnet has no chat socket. Every server event reaches it by push.

---

## 2. Endpoint reference

Base prefixes:
- `/api` holds the core routes, which authenticate themselves.
- `/api/v1` holds the data API, which always requires a member.

Caller files:
- `CarnetAPI` is `Shared/CarnetAPI.swift`.
- `GardenAPI`, `CalibreAPI`, `DomainsAPI` and `MauriceAPI` are `CarnetApp/Services/*.swift`.

Unless noted, success means any 2xx. On failure the client throws with the server's `{error}` string, or the raw body.

### 2.1 Core / auth

| Method | Path | Request | Response (decoded) | Caller | Screen |
|---|---|---|---|---|---|
| GET | `/api/health` | — | `{status:"ok", version, setup_complete:Bool, household?, household_color?, household_icon?}` (verified, `index.ts:778`). The client reads only `status` and `setup_complete?`. No auth needed. | `CarnetAPI.health` | Pairing, MemberPicker, (Settings) |
| POST | `/api/auth/setup` | `{username, password, display_name}` | `{user: Member, token}` | `CarnetAPI.setup` | MemberPicker (admin overlay, first run) |
| POST | `/api/auth/login` | `{username, password}` **or** `{user_id, pin?}` | `{user_id, token}` | `CarnetAPI.login` | MemberPicker, MemberSwitchSheet |
| POST | `/api/auth/enroll` | `{code}` (trimmed; the field auto-capitalises) | `{user_id, token, role, needs_pin:Bool}` | `CarnetAPI.enroll` | MemberPicker |
| POST | `/api/auth/set-pin` | `{pin}` (4–6 digits, checked by the client), with the new token | 2xx, body ignored | `CarnetAPI.setPin` | MemberPicker |
| GET | `/api/users/me` | — (explicit token) | `Member` | `CarnetAPI.me` | MemberPicker, DeleteAccount |
| GET | `/api/users` | — | `[Member]` (bare array) | `CarnetAPI.listMembers`, `CarnetSession.refreshMembers` | MemberPicker, Shelf, EntryView share sheet |
| POST | `/api/me/erase` | `{scope:"account", confirm, password, pin}`, 600 s timeout | 2xx. Errors via `{error: code}`. | `CarnetAPI.deleteAccount` | MemberSwitchSheet → DeleteAccountSheet |
| POST | `/api/users/me/device-token` | `{token: <APNs hex>, platform:"carnet-ios", household_tag: <local household id>}`. Sent to **each** paired household with that household's active member token. | 2xx, body ignored | `DomainsAPI.registerDeviceToken` via `CarnetNotifications.upload` | App launch / sign-in |
| GET | `/api/avatars/<file>` | — (**open**, no auth) | Image bytes (`image/png`/`image/jpeg`) | `AsyncImage` | Member badges |

The `/api/users/me/device-token` contract has two consequences for Android:
- Android needs its own platform value (for example `carnet-android`) and FCM support on the server. **The server's support for non-APNs platforms is unverified, probably absent** (see §5.2).
- The `household_tag` round-trips in the push payload.

### 2.2 Calibre library (`/api/v1/calibre`, `CalibreAPI`)

| Method | Path | Request | Response | Screen |
|---|---|---|---|---|
| GET | `/books` | — | `[Book]` or `{books:[Book]}`; the client accepts both. Book (camel): `{id:Int, title, authors:[String], tags?:[String], formats?:[String], series?, description?, chapters?:Int, summarized?:Int, indexed?:Int, added?: ISO-8601}` | LibraryView *(full scope)* |
| GET | `/books/:id/metadata` | — | `Book` (with `description`, counts may be absent) | DomainsShelfView (companion → book) |
| GET | `/books/:id/cover` | — (**Bearer required**) | JPEG/PNG bytes. 404 means no cover; the placeholder letter is shown. | CoverImage everywhere |
| GET | `/books/:id/chapters` | — | `[Chapter]` or `{chapters:[...]}`. Chapter (camel): `{index:Int, slug, title, wordCount?:Int, summaryWordCount?:Int, hasSummary:Bool}` | BookDetail, EntryView, BookResumeView |
| GET | `/books/:id/chapters/by-slug/:slug` | slug percent-encoded | `{title, text, wordCount?}` | ReaderView, BookCache.download |
| GET | `/books/:id/chapters/by-slug/:slug/summary` | — | `{exists:Bool, title?, summary?}` | ReaderView, BookCache |
| GET | `/books/:id/reading-progress` | — | snake `{book_id?, chapter_index?, chapter_slug?, view?:"full"\|"summary", enabled?:0\|1, position?:Double, updated_at?:"YYYY-MM-DD HH:MM:SS" UTC}`. An empty body or `null` means never opened (the client uses `try?`). | BookDetail, EntryView, BookResumeView |
| POST | `/books/:id/reading-progress` | `{chapter_index:Int, chapter_slug, view:"full"\|"summary", position:Double}` | `ReadingProgress?`. The client comment says the server "only advances, never rewinds" *(server unverified)*. | ReaderView (debounced 1 s, fire-and-forget) |
| POST | `/books/:id/reading-progress/tracking` | `{paused:Bool}` | `ReadingProgress?` | BookDetail |
| DELETE | `/books/:id/reading-progress` | — | 2xx | BookDetail ("Reset progress") |
| GET | `/books/:id/bookmarks` | — | `[Bookmark]` or `{bookmarks:[...]}`. Bookmark (snake): `{id, book_id?, chapter_slug, view, note?, created_at?}` | ReaderView |
| POST | `/books/:id/bookmarks` | `{chapter_slug, view}` (a toggle) | `{bookmarked:Bool, bookmark?:Bookmark}` | ReaderView |
| PUT | `/books/:id/bookmarks/:bid` | `{note: String\|null}` | 2xx | ReaderView |
| DELETE | `/books/:id/bookmarks/:bid` | — | 2xx | (no caller in views) |
| GET | `/books/:id/highlights` | — | `[Highlight]` or `{highlights:[...]}`. Highlight (snake): `{id, book_id?, chapter_slug, quote, note?, color, start_offset?, end_offset?, view?:"full"\|"summary" (absent = full), created_at?}` | ReaderView, BookDetail, HighlightsView |
| POST | `/books/:id/highlights` | `{chapter_slug, quote, color, view, note?, start_offset?, end_offset?}` | `Highlight` | ReaderView |
| PUT | `/books/:id/highlights/:hid` | `{note: String\|null, color?}` (`note` is **always** present; null clears it) | `Highlight` | ReaderView |
| DELETE | `/books/:id/highlights/:hid` | — | 2xx | ReaderView |
| GET | `/books/:id/status` | — | `{chapters?, summarized?, indexed?, job?:{action, state:"running"\|"ok"\|"error", error?, at?}}` | BookDetail (polled every 3 s) |
| POST | `/books/:id/summarize` | — | Comment says `202 {status:"started", pid}`; the body is ignored | BookDetail ("All" summarise) |
| POST | `/books/:id/extract` | — | 2xx, body ignored. The client then polls `/chapters` and `/status` every 3 s, 40 tries (~2 min). | BookDetail |
| POST | `/add` | multipart field `file` (`application/epub+zip` if the name ends `.epub`, else `application/pdf`) | `{ok:Bool, bookId?:Int, filename?, output?}` | UploadSection *(full scope)* |

There is no raw EPUB download, and Calibre-Web (`/calibre`) is **not** called by Carnet. Highlight colours are `yellow` (the default, drawn as an ink wash), `green`, `pink` and `blue`.

### 2.3 Garden: articles (`/api/v1/garden/articles`, `GardenAPI` + `CarnetAPI`)

Article (snake), as `GET /` returns it:
```
{ slug, locale, title, url?, saved_at?, subtitle?, author?, publication?, published_at?,
  image? (site path "/images/<member>/resources/..."), excerpt?, summary?, summary_long?,
  tags?:[String], word_count?:Int, has_text?:Bool, text_rev?:String, needs_capture?:Bool,
  capture_error?, source_client? ("ios-share"|"chrome"|"mcp"), highlight_count?:Int,
  opened?:Bool (absent = true), fiche_web_path?, card_web_path? }
```

| Method | Path | Request | Response | Caller / screen |
|---|---|---|---|---|
| POST | `/` | `{url, locale:"fr" (always "fr" from the client), source_client:"ios-share", tags?:[String], comment?, html? (Safari's rendered DOM, ≤6 MB on the client; the server refuses >8 MB per the JS comment), title?, selection?}` | SavedArticle `{slug, title, locale?, url?, saved_at?, duplicate?:Bool, needs_capture?:Bool, completed?:Bool, subtitle?, author?, publication?, published_at?, image?, excerpt?, summary?, comment?, tags?, word_count?, fiche_path?, fiche_web_path?}` | `CarnetAPI.saveArticle`: share extension, SyncService |
| GET | `/lookup?url=` | — | `{saved:Bool, article?:SavedArticle}` | `CarnetAPI.lookupArticle` (**no caller**) |
| GET | `/` | — | `{articles:[Article]}` | ArticlesShelfView *(full scope)* |
| GET | `/:slug?locale=` | — | Article fields + `body` (fiche markdown, default `""`) at top level | ArticleDetailView, ArticleReadView, EntryView |
| GET | `/:slug/text?locale=` | 30 s timeout | `{slug, locale, title?, text, styles?:[{start:Int, end:Int, kind:"heading"\|"strong"\|"em"}]}`. **404 means never captured** (a bookmark). | ArticleReaderView |
| POST | `/:slug/comment` | `{locale, comment}` | Article + `body` | ArticleDetailView |
| POST | `/:slug/summary` | `{locale, force:Bool}`, 180 s timeout (LLM pass) | `{summary, summary_long?}` | EntryView (`summaries`, auto-run when `source.summary.current` is false), ArticleDetailView (`summarize`) |
| GET | `/:slug/highlights?locale=` | — | `[{id, slug, quote, note?, color, start_offset?, end_offset?, view?, created_at?}]` | ArticleReaderView, ArticleDetailView |
| POST | `/:slug/highlights` | `{quote, color, view, locale, note?, start_offset?, end_offset?}` | one highlight (same shape) | ArticleReaderView |
| PUT | `/:slug/highlights/:id` | `{note: String\|null, color?}`. **No locale sent.** | one highlight | ArticleReaderView |
| DELETE | `/:slug/highlights/:id` | — (no locale) | 2xx | ArticleReaderView |

Legacy `POST /api/v1/articles/scrape` still exists according to the client comment, but Carnet does not call it.

### 2.4 Garden: links and résonances (`/api/v1/garden/links`)

| Method | Path | Request | Response | Screen |
|---|---|---|---|---|
| GET | `/targets?q=` | — | `{targets:[{collection, locale, slug, title, date, link_basename, has_fiche:Bool}]}` | LinkTargetPicker (inside HighlightEditorSheet) |
| POST | `/` | `{to:{locale, slug, collection}, comment?, quote?, source?:{label, basename?}}` **or** `{…, source_book_title}` instead of `source`. At least one of `comment`/`quote` must be present. | Ignored | HighlightEditorSheet when a link target is picked. From a book: `source_book_title=<book title>`. From an article: `source={label: title, basename:"<slug>-fiche"}`. |

### 2.5 Garden: entries, the shelf (`/api/v1/garden`, `GardenAPI`)

**ShelfEntry** (snake, decoded leniently: an entry or kind unknown to the build is skipped, not fatal):
```
{ id: "<collection>/<locale>/<slug>" | "calibre/<bookId>",
  kind: "books"|"articles"|"movies"|"series"|"music"|"podcasts"|"games"|"posts",
  collection?, locale?, slug?,            // all null for a library-only book
  title, byline?, date, updated_at, image?, tags:[String], rating?:Int, status?,
  source?: { type:"book"|"article", book_id?:Int, chapters?:Int,
             progress?: {chapter_slug, chapter_title?, fraction:Double 0–1, finished:Bool, tracking:Bool, updated_at},
             url?, captured?:Bool, word_count?:Int, reading_minutes?:Int,
             summary?: {text, long?, current:Bool} },
  mine?: {notes:Int, opened:Bool},
  shared?: {state:"draft"|"published"},
  archived?:Bool,
  translations:[{id, locale, shared?:{state}}],
  owner?: MemberBrief,          // present only when it is another member's entry shared with me
  participants?: [MemberBrief] }
MemberBrief = {id, username, display_name, avatar_color, avatar_url?}
```

**Entry page** (the GET of one entry and every write route): one JSON object decoded **three times**:
- as ShelfEntry, so `mine` at least has `{notes, opened}`;
- as EntryDetail `{mine?:{notes, opened, web_path?, file, prose, items:[MineItem]}, shared?:{state, web_path?, file, public_url?, title?, body, links:[{basename, label, entry_id?, published_url?}]}}`;
- for an optional `deploy:{status:"idle"|"queued"|"running"|"failed", error?}`.

**MineItem**:
```
{ kind:"note"|"quote"|"resonance"|"highlight"|"fragment", id?, date?, text?, quote?, summary?,
  where?:{page?, chapter_slug?, chapter_title?, view?},
  from?:{label, entry_id?, published_url?},
  author?: MemberBrief,
  conversation?:{id, initiator?:MemberBrief} }
```
Without an `id`, the client builds a local id from kind, date, author, quote and text.

`…` below stands for `/entries/:collection/:locale/:slug`. When the entry is another member's (`owner` set), the client appends `?owner=<owner.username>` to **every** entry-scoped URL.

| Method | Path | Request | Response | Screen |
|---|---|---|---|---|
| GET | `/entries?view=shelf` (and `&archived=1`) | 30 s | `{garden:{username, site?}, kinds:[{kind, count}], entries:[ShelfEntry]}`. The server order is used as "Last touched". | ShelfView (root) |
| GET | `/entries?view=notes` | 30 s | `{entries:[ShelfEntry], notes:[{entry_id, …MineItem fields flattened}]}` | NotesView |
| GET | `/entries` | 30 s | `{garden:{username, collections:[String]}, entries:[{collection, locale, slug, title, date, updated_at?, tags, image?, card?:{web_path?, file}, fiche?:{web_path?, file}, opened?}]}` | GardenView *(full scope)*, ResonanceViews |
| GET | `…` | — | entry page | EntryView, ShelfView (deep link), ArticleReaderView (resolve) |
| DELETE | `…` | 30 s | 2xx. Deletes both faces, in every locale. | ShelfView (swipe left; not for library-only or foreign entries) |
| POST | `/entries/archive` | `{id: <ShelfEntry.id>, archived:Bool}` | 2xx | ShelfView (swipe right) |
| POST | `…/notes` | `{text, quote?, where?:{page?}}`, 30 s | entry page | EntryView NoteSheet, ArticleReaderView |
| POST | `/entries/from-book` | `{book_id:Int, text, quote?, where?:{page?, chapter_title?}}`, 30 s | entry page (ignored by the reader) | ReaderView "Note"; EntryView on a library-only book |
| PUT | `…/shared` | `{body, title?}`, 30 s | entry page | EntryView SharedEditor |
| POST / DELETE | `…/shared/publish` | —, 30 s | entry page (+`deploy`) | EntryView publish/unpublish |
| GET | `/site/deploy` | — | `{status, error?}` | EntryView, polled every 2 s while queued/running |
| POST | `…/details` | —, 45 s | entry page | EntryView "Refresh details" |
| PUT | `…/cover` | raw `image/jpeg` body (≤1200 px longest side, quality 0.82), 30 s | entry page (raw) | Share extension, EntryView "Refresh details" |
| POST | `…/participants` | `{member_id}` | entry page | EntryView share sheet |
| DELETE | `…/participants/:memberId` | — | entry page, or empty when you removed yourself (the client uses `try?`) | EntryView share sheet (remove / leave) |
| GET | `…/conversations` | — (skipped for foreign entries) | `{conversations:[{id, title?, created_at, updated_at, message_count:Int, via:"entry"\|"context", maurice?:{id, name}, summary?:{id, text, written_at?, stale:Bool}}]}` | EntryView, MauriceHandoff |
| POST | `…/conversations/:id/summary` | `{force:Bool}`, 180 s | `{summary:{id, text, written_at?, stale}}` | EntryConversationsSection ("Write it now" / "Rewrite") |

Images in the garden:
- `image` is a site path `/images/<member>/resources/<rest>`. The client maps it to **`GET /api/garden-images/<member>/<rest>`**, which is served open, with no auth (verified, `index.ts`). Any other path shows no picture.
- Book covers use `/api/v1/calibre/books/:id/cover` with a Bearer token, when the entry has no `image` but has `source.book_id`.

### 2.6 Maurice: conversations, suggestions, domains (`/api`, `MauriceAPI` + `DomainsAPI`)

| Method | Path | Request | Response | Screen |
|---|---|---|---|---|
| POST | `/api/conversations` | `{entry:"<collection>/<locale>/<slug>"}` | `{id, …}` (only `id` is read) | MauriceHandoff (then opens the Maurice app), AskMauriceView |
| POST | `/api/conversations/:id/messages` | `{content}`, 300 s | **NDJSON stream**, one JSON object per line, switched on `type`: `text_delta{text}` appends; `thinking` and `tool_call` show "working"; `error{error\|message}` shows failure; all other types are ignored. The turn continues server-side if the client drops. | AskMauriceView (used only when the Maurice app is not installed) |
| GET | `/api/conversations?limit=100` | — | bare `[{id, title?, opened_by?, unread?:Bool, created_at?, updated_at?, last_message_at?, message_count?}]`. Carnet keeps only `opened_by=="maurice"`. | DomainsShelfView |
| GET | `/api/conversations/:id` | — | `{id, title?, opened_by?, created_at?, messages:[{id, role:"user"\|"assistant"\|…, content (markdown), created_at?, author_id?}]}` | OpenedConversationView, AskMauriceView (past) |
| POST | `/api/conversations/:id/read` | — | 2xx | OpenedConversationView (on open) |
| GET | `/api/suggestions?conversation=<id>[&settle=1]` | 15 s, or 30 s with `settle` (the server answers once the post-reply pass is done) | `{entry?, suggestions:[Suggestion], pending:Int, kept:Int}` | AskMauriceView |
| POST | `/api/suggestions/:id/keep` | `{candidate?, episodes?:[String]}` (episodes only if non-empty), 40 s | 2xx | AskMauriceView |
| GET | `/api/suggestions/:id/episodes[?feed=]` | 40 s | `{feed, episodes:[{id, title, date?: ISO, description, image (URL), duration?: seconds}], suggested?}` | AskMauriceView (podcasts) |
| POST | `/api/suggestions/:id/search` | `{query?}`, 40 s | `{found:Int}` | AskMauriceView |
| POST | `/api/suggestions/:id/dismiss` | `{}` | 2xx | AskMauriceView |
| GET | `/api/domains` | — | `{domains:[{id, name, tagline?, kind?, mine?:Bool, count?:Int, brief?:{updated_at, model?, sources?:Int}}], companions:[{id, name, tagline?, mine?, book_id?: Int\|String, conversation_id?}]}` | DomainsShelfView, BookDetailView |
| GET | `/api/domains/:id/brief` | — | `{domain:{id, name, tagline?}, brief: null \| {text, updated_at, sources?:[String], read_until?, model? ("member" = hand-edited)}}`. 404 for a companion or a domain that is not yours. | DomainBriefView |
| PUT | `/api/domains/:id/brief` | `{text}` (empty erases) | `{brief: DomainBrief\|null}` | DomainBriefView |
| DELETE | `/api/domains/:id/brief` | — | 2xx | DomainBriefView |
| POST | `/api/domains/:id/brief/refresh` | —, 120 s | `200 {outcome:"written", sources:Int, brief}`, `200 {outcome:"unchanged", brief?}`, `429` (the night's allowance is spent), `502 {error}` (the model failed). The client branches on the status code. | DomainBriefView |
| POST | `/api/maurices` | `{name:<book title>, kind:"companion", tagline:"", context:[{type:"book", id:<bookId Int>, representation:"summary", scope:{mode:"progress"}}]}` | 2xx, body ignored | BookDetailView "Follow" |

`Suggestion`:
```
{ id, kind ("movies"|"series"|"books"|"music"|"podcasts"|"games"|"people"|"articles"|…), title,
  year?:Int, subtitle?, note, state ("kept"|…), existing:Bool, bound:Bool, links:Bool,
  candidates:[{id, title, year?, subtitle?}], entry?, query?, searchable?:Bool }
```

### 2.7 Dictation (`/api/dictation`, `HouseholdTranscription` in `Services/Dictation.swift`)

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| GET | `/api/dictation` | Bearer, **3 s timeout** | `{available:Bool, engine?: "local"\|…}` | Cached for 10 min per server. A failure is not retried for 60 s. A **404 counts as "no"**. `engine=="local"` means whisper.cpp at home: the audio stays in the house. |
| POST | `/api/dictation?duration=<seconds, 1 decimal>` | raw body `Content-Type: audio/mp4`: AAC, 16 kHz mono, ~32 kbps; **no language parameter** (Whisper detects it). 240 s timeout. Sent only for clips longer than 0.3 s. | `200 {text}` | Any non-200 means fall back (§5.1). |

## 3. Offline model and sync (`SyncService`, `OfflineQueue`, caches)

### 3.1 The outbox (`Shared/OfflineQueue.swift`)

It is a directory in the App Group container: `queue/<uuid>.json` holds the manifest, and `queue/files/<uuid>.dat` the payload bytes.

```
QueueItem { id: UUID, type: "articleScrape", createdAt: ISO-8601 date,
            payload: { url?, tags?, filename?, mimeType?, comment?, sourceClient?, title?, selection?, hasHtml?:Bool } }
```

- **Who enqueues:** only the share extension. It always enqueues **before** trying to send, and dequeues its own item by id on success.
- **articleScrape:**
  - The rendered DOM, when present, goes to the `.dat` file as UTF-8 and `hasHtml=true` is set.
  - Flushing calls `POST /api/v1/garden/articles` with `url`, `comment`, `tags`, `source_client` (default `ios-share`), `html`, `title` and `selection`. `locale` is always `"fr"`.
- **Flush trigger:** `NWPathMonitor` fires `syncNow()` each time the network path becomes satisfied, from sign-in onwards. That includes the first callback right after start, so effectively at launch too.
  - Items are sent in `createdAt` order. Each success is dequeued. A failure is kept, and `lastError` is set to the last one.
  - There is no retry backoff, no attempt count, no poison-item handling, and the failure is not surfaced in minimal scope (QueueView is dead).
- **Auth at flush time:** the queue holds **no member identity**. It flushes as whoever is active then, on whatever household is current. A capture made as member A and flushed after switching to B posts as B. Parity bug; an Android port should store `householdId` and `memberId` in each item.
- **No conflict handling:** article saves are idempotent server-side by URL. A duplicate URL comes back with `duplicate:true`, and the comment is appended to the existing fiche.

### 3.2 Read caches (all keyed by `SHA-256(baseURL)`, so households never collide)

All files are under Application Support, excluded from backup, except covers, which are in Caches.

| Cache | Location | Content | Policy |
|---|---|---|---|
| `ShelfCache` | `OfflineArticles/shelf-<h>.json` | The last `?view=shelf` response (not the archived half) | Shown immediately (labelled "Offline"); replaced on each successful fetch |
| `BookCache` | `OfflineBooks/<sha(base#bookId)>/` | `manifest.json` (`{chapterSlugs, sizeBytes, downloadedAt, book?, chapters?}`) + `<sha(slug)>.full` / `.sum` | Chapters are cached as they are read. "Download book" fetches all chapters, 6 in parallel with one retry each, and writes the manifest last. Also `library-<h>.json` holds the last `/books` list. |
| `ArticleCache` | `OfflineArticles/` | `articles-<h>.json` (list) + `<sha(base#locale/slug#text_rev)>.full` and `.styles` | The text is cached on first read. A new `text_rev` changes the file name, so the text is fetched again. |
| `GardenEntriesCache` | (GardenView, full scope) | Last `/entries` | Cache-first |
| `CoverCache` | `Caches/BookCovers/<sha(base#identity)>.jpg` + memory | Image bytes | Never expires. At most 4 cover fetches in flight (`CoverFetchLimiter`). Identities: `<bookId>`, `article:<locale>/<slug>`, `shelf:<entry.id>`, `garden:<c>/<l>/<s>`. |
| `ReadingPositionStore` | `ReadingPositions/positions-<h>.json` (dict) + `paused-<h>.json` (set of book ids) | §4.2 | Written on every debounced scroll |

Writes (notes, highlights, publish, archive and the rest) are **online only**. There is no write queue for them. Highlight and bookmark calls in the readers are fire-and-forget with `try?`: a failure is silently lost.

---

## 4. Reading

### 4.1 What is fetched and how it is rendered

- **Books:** the server extracts the EPUB into chapters. The client only ever gets **plain text per chapter** (`text`) and an optional per-chapter **summary** (`summary`; both are treated as plain text with `\n` paragraph breaks). There is no EPUB, HTML or pagination on the client. A book with no chapters offers "Extract" (§2.2), then optionally "Summarize all".
- **Articles:** the server captures the page, from the shared DOM or its own fetch. The client gets `text` (plain) plus `styles` ranges (`heading` drawn ~1.12× semibold, `strong` bold, `em` italic). The article's "summary" view reads `summary_long`, or else `summary`, from the list or detail.
- **Rendering** (`HighlightableText.swift`):
  - A UIKit `UITextView` inside a `UIScrollView`, with the system font, line spacing 0.35 × size, and paragraph spacing 0.88 × size.
  - Blank lines are **kept in the string but flattened visually**, because every offset counts them.
  - Highlights are background tints over `[start_offset, end_offset)`.
- **All offsets are UTF-16 code-unit offsets into the exact string the server returned** (NSRange semantics): highlight offsets, style ranges, and the saved character position. On Android, `String` indices are UTF-16 too, so use the server string untouched. Do not normalise newlines or trim.
- Reading modes: `full` | `summary`. Highlights carry `view`, and each mode shows only its own. Font size has 3 steps (15/17/19.5 pt), global.

### 4.2 Reading position

The local record (`ReadingPositionStore.Record`):
```
{ chapterIndex:Int, chapterSlug, lastView:"full"|"summary", fullChar:Int (top visible UTF-16 offset),
  summaryPara:Int (top visible paragraph index in the summary), updatedAt: Date (device clock) }
```
- Books use the key `"<bookId>"`. Articles use the key `"article:<locale>/<slug>"` (the code comment says this). **For articles, `summaryPara` actually stores a character offset.**
- **Books sync to the server:**
  - On chapter load, on every scroll (debounced 1 s), on chapter change and on leaving, the app calls `POST /reading-progress {chapter_index, chapter_slug, view, position}`.
  - `position` is `fullChar` in full mode and the **paragraph index** in summary mode. A paragraph is a non-blank line.
  - Writing is skipped entirely when the book is paused (local mirror of `enabled==0`).
- **Reconciliation** (BookDetailView on load):
  - The app reads `GET /reading-progress` and mirrors `enabled` into the local paused set.
  - The server copy replaces the local one only if there is no local record, or if `updated_at` (parsed as UTC) is **more than 90 s newer** than the local `updatedAt`.
  - When adopting, the other view's local position is kept if the chapter is the same.
  - "Resume" (`BookResumeView`) prefers the local record and falls back to the server.
- **Articles have no server-side position.**
- The shelf's "Continue" bar uses the server's `source.progress` (the newest unfinished one).
- Reset clears the local record, then calls `DELETE /reading-progress`. Pause/resume writes local state, then calls `POST /reading-progress/tracking {paused}`.

### 4.3 Highlights, bookmarks and notes from the reader

- **Text selection opens an edit-menu item "Highlight".** It opens `HighlightEditorSheet` (colour, note, and an optional résonance target), which on save creates the highlight. If a target was picked, it also calls `POST /garden/links`.
- Tapping an existing highlight opens the editor with delete.
- **Book bookmark:** one per chapter and view, toggled from the top bar. The note is edited through PUT.
- **The reader's "Note" button:** for books it calls `POST /garden/entries/from-book {book_id, text, quote?, where:{chapter_title}}`; for articles it calls `POST …/notes` on the article's entry, resolved via `GET /entries/articles/<locale>/<slug>`.

---

## 5. Dictation, notifications, the share extension, and the handoff to the Maurice app

### 5.1 Dictation (`DictationMic` + `Services/Dictation.swift`, 1570 lines)

There is a mic button beside every writing field: NoteSheet, SharedEditor and the AskMaurice composer. The transcript is **appended to the field and never sent automatically**.

Engine choice at each start:
1. If the member prefers the household server (default on, `carnet.dictation.preferHousehold`) and `GET /api/dictation` says `available`, the household server is used.
2. Otherwise Apple's SpeechAnalyzer, on iOS 26 and when it is installed for the locale.
   - If its model is not installed yet, the app starts the download. Meanwhile it uses the household server if it is **local**, else the legacy recogniser if usable, else waits for the model.
3. Otherwise the household server if it is local.
4. Otherwise SFSpeechRecognizer, on-device. It may use Apple's servers only after an explicit one-time consent (`carnet.dictation.allowServer`).

How the household path runs:
- The device records AAC 16 kHz mono while an on-device recogniser previews the words.
- On stop, the app POSTs the clip.
- If that fails, the preview text is used; if there is no preview, the clip is transcribed on the device.

The locale is the entry's own `locale`, not the phone's.

**For Android:** keep the household-server path (MediaRecorder AAC/MP4 at 16 kHz mono → the same POST) and use `SpeechRecognizer` as the live preview and fallback. The SpeechAnalyzer and SFSpeech specifics are iOS-only.

### 5.2 Notifications (remote only; no local notifications)

- **Registration:** after sign-in, the app asks for alert, sound and badge permission once, then registers for APNs. The hex device token goes to `POST /api/users/me/device-token` on **every** paired household that has an active member token, with `platform:"carnet-ios"` and `household_tag:<local household id>`. It re-uploads on every sign-in and member switch, because the token's owner changes.
- **What is pushed today:** a conversation Maurice opened on his own. The server side is `services/openedConversations.ts` → `services/push.ts`, according to the client comments.
- **Payload keys the client reads:** `conversationId` (String) and `householdTag` (String), beside `aps`. Exact alert fields: *(server unverified)*.
- **Foreground:** a banner and sound are shown.
- **Tap:** if `householdTag` names another paired household, the app switches to it. Then the shelf stack is reset to **Maurice → OpenedConversationView(conversationId)**, which loads the thread and marks it read. A cold-start tap is held until the UI is wired.
- **Android:** this needs FCM. The device-token route must accept an Android platform and the server must send through FCM. **No evidence was found that the server supports any non-APNs transport; treat this as server work.** Keep the same `conversationId`/`householdTag` data keys.

### 5.3 Share extension ("Save to Carnet")

**Activation:** anything conforming to `public.url`, `public.file-url` or `public.data`.

**From Safari**, a JS preprocessor runs in the page and returns:
- `{url: document.URL, title: document.title, html: outerHTML, selection}`;
- `html` is `""` if it exceeds 6,000,000 characters;
- `selection` is trimmed to 2000 characters.

**Content priority:** rendered web page > bare URL (also `text/plain` starting with `http`) > file.

**URL flow:**
1. The UI shows the title and a folded "Add a note" field. A non-empty selection pre-fills the note.
2. For a bare URL (not from Safari), it starts a system link preview (`LPMetadataProvider`) in parallel, for the title and image.
3. On Save, the app enqueues `articleScrape` first, then calls `POST /api/v1/garden/articles` (`selection` is omitted when it equals the comment), waiting at most **15 s**.
   - On success it dequeues.
   - If the result has no `image` and the link preview found one, it re-encodes it as JPEG ≤1200 px at quality 0.82 and calls `PUT /api/v1/garden/entries/articles/<locale or "fr">/<slug>/cover` (preview wait ≤10 s, upload ≤12 s).
   - It then shows the outcome:
     - `needs_capture` → "Kept as a bookmark — open it in Safari to capture it."
     - `duplicate` → "Already saved — your note was added."
     - otherwise → "Saved: <title>".
   - On timeout or failure: "Queued — Carnet will send it." The main app flushes it later.

**File flow:**

**Android:** an `ACTION_SEND` activity receiving `text/plain` (the URL) and files. Android has no rendered DOM, so the server fetches the URL itself (the paywall-bypass path is iOS/Safari-only). Use OpenGraph scraping or skip the cover step.

### 5.4 Handoff to and from the Maurice app

- **Outbound:**
  - "Talk with Maurice" on an entry (`MauriceHandoff`) first checks whether the `maurice://` scheme can be opened.
  - **If installed:** `GET …/conversations` for the entry. With none, it calls `POST /api/conversations {entry}` and opens **`maurice://conversations/<id>`**. Otherwise a dialog offers "New conversation" plus up to 6 past ones (`title · when`), each opening `maurice://conversations/<id>`.
  - **If not installed:** the conversation runs inside Carnet (`AskMauriceView`: NDJSON streaming plus suggestions and keep).
  - Links of the form `maurice://conversations/<id>` inside note markdown open the Maurice app, or Carnet's own read-only thread when it is not installed.
- **Inbound:** `carnet://entry/<collection>/<locale>/<slug>`, sent by the Maurice app from what a conversation offered to keep.
  - The shelf looks for that entry id (or a translation id) in the list. Failing that, it calls `GET /api/v1/garden/entries/<c>/<l>/<s>`, retrying after 0, 0.7, 1.5 and 3 s.
  - It then pushes the Entry page and refetches the shelf. Any other `carnet://` URL is ignored.
- **Android:** register `carnet://entry/...` and `maurice://conversations/...` as intent filters (or App Links), and use `PackageManager` to test whether Maurice is installed.
- **Other outbound links:**
  - The site page at `GardenLinks.webBase + web_path`. The default webBase is the **API host on port 4321**, because Settings is dead; this is probably wrong for tunnelled households, and garden pages need a browser session cookie anyway.
  - `working-copy://x-callback-url/open?repo=<username>&path=<file>`.
  - `obsidian://open?vault=<username>&file=<file without .md>`.
  - `public_url` of published cards.

---

## 7. Screen inventory (Swift line counts)

Total Swift is ≈23 k lines, including ≈1.6 k of theme and UI kit and ≈1.3 k of markdown rendering.

**Shell and identity**

| Screen | Lines | What it does |
|---|---|---|
| `CarnetApp.swift` | 122 | Root gate (pairing → picker → shell), deep link `carnet://`, push wiring |
| `PairingView` | 99 | Enter host, probe `/api/health`, pair |
| `MemberPickerView` | 522 | Roster grid, PIN overlay, invite-code enroll + PIN setup, admin sign-in / first-run setup, disconnect |
| `MemberSwitchSheet` | 317 | Switch household or member, add or disconnect household, privacy/terms, delete account |
| `AppShell` | 270 | Minimal: just ShelfView. |

**Shelf (the minimal-scope core)**

| Screen | Lines | What it does |
|---|---|---|
| `Shelf/ShelfView` | 736 | Home: every entry of every kind; kind chips; search; sort; swipe archive/delete; "Continue" bar; member menu (Entries / Archived / Notes / Maurice); offline cache; deep-link and push routing |
| `Shelf/EntryView` | 1541 | One entry: identity, source band (book progress or article read/summary), two-faced sheet (my notes / shared draft-published card), note composer, shared editor, publish + deploy polling, refresh details/cover, share with members, conversations with Maurice and their summaries, "more" links. Also hosts `NoteSheet`, `SharedEditor`, `WritePanel`, `BookResumeView`, `ArticleReadView`. |
| `Shelf/NotesView` | 198 | Every note, newest first by month; tap opens its entry |
| `Shelf/Paper` | 107 | Dotted/smooth paper and folded-corner visuals |

**Reading**

| Screen | Lines | What it does |
|---|---|---|
| `Reading/BookDetailView` | 819 | Book page: stats, resume, chapter list, extract/summarise with polling, offline download, pause/reset tracking, highlights, companion "Follow" |
| `Reading/ReaderView` | 490 | Chapter reader: full/summary, font, chapter nav, bookmark + note, highlights, note to entry, position sync |
| `Reading/ArticleReaderView` | 284 | Article reader: full/summary, styles, highlights, note, local-only position |
| `Reading/HighlightableText` | 535 | Text engine (UITextView bridge), selection → highlight, highlight editor + résonance |
| `Reading/HighlightsView` | 124 | Book's highlights list → jump into the reader |
| `Reading/ArticleDetailView` | 493 | *(full)* Article page: summary, highlights, comment, links |
| `Reading/LibraryView` | 460 | *(full)* Books / Articles / Domains shelves toggle; Calibre list with sort and offline |
| `Reading/ArticlesShelfView` | 248 | *(full)* Saved articles list |
| `Reading/UploadSection` | 82 | *(full)* Pick EPUB/PDF → `/calibre/add` |
| `Reading/BookCache`, `ArticleCache`, `ReadingPositionStore`, `CoverImage` | 179, 66, 120, 240 | Caches (§3.2) |
| `Reading/GridKit`, `ReaderChrome`, `ReadingSamples` | 539, 84, 66 | UI kit, reader chrome, DEBUG samples |

**Maurice and domains**

| Screen | Lines | What it does |
|---|---|---|
| `Maurice/AskMauriceView` | 659 | In-Carnet conversation from an entry (when the Maurice app is absent): streaming reply, suggestions to keep, candidates, podcast episodes, search again, dismiss |
| `Maurice/MauriceHandoff` | 214 | Open/new conversation in the Maurice app; entry conversation summaries |
| `Maurice/ReplyText` | 1297 | Markdown renderer for replies (use a library on Android) |
| `Domains/DomainsShelfView` | 292 | Domains, reading companions, conversations Maurice opened (unread) |
| `Domains/DomainBriefView` | 291 | Read/edit/erase/rewrite-now a domain's brief |
| `Domains/OpenedConversationView` | 131 | Read a conversation Maurice opened; mark read; "answer in Maurice" |

**Garden** *(full scope)*: `GardenView` 294 (all entries by collection), `GardenEntryView` 150 (faces + outbound links), `ResonanceViews` 278 (wiki-link text, link-target picker, résonance sheet).



**Services and shared code:**

| File | Lines |
|---|---|
| `Shared/CarnetAPI` | 998 |
| `OfflineQueue` | 101 |
| `GardenAPI` | 406 |
| `MauriceAPI` | 234 |
| `CalibreAPI` | 222 |
| `DomainsAPI` | 186 |
| `CarnetSession` | 291 |
| `SyncService` | 91 |
| `CarnetNotifications` | 127 |
| `GardenLinks` | 68 |
| `Dictation` | 1570 |
| `DictationMic` | 177 |
| Models: `Shelf` | 439 |
| Models: `Articles` | 286 |
| Models: `Reading` | 201 |
| Models: `Domains` | 193 |

**Share extension:** `ShareViewController` 388, `SharedContent` 157, plus the JS preprocessor.

---

## 8. Platform-specific parts an Android port can skip or replace

| iOS feature | Android port |
|---|---|
| App Group suite + share extension process separation | A single app process; one shared encrypted store |
| Safari JS preprocessor (rendered DOM → `html`) | Not possible on Android; send the URL only. The server fetches it, and paywalled sites become bookmarks (`needs_capture`). |
| `LPMetadataProvider` cover fetch | Optional: OpenGraph parse on the device, or skip it and rely on "Refresh details" |
| SpeechAnalyzer / SFSpeechRecognizer engines, model prefetch, Apple-server consent | Android `SpeechRecognizer` for preview/fallback. Keep the household Whisper upload. |
| APNs (`platform:"carnet-ios"`) | FCM. Needs server support (§5.2). |
| Liquid Glass / `Glass.swift` / iOS 26 chrome, Young Serif font registration | Material 3 equivalents |
| `NWPathMonitor` | `ConnectivityManager.NetworkCallback`, or WorkManager with a network constraint |
| DEBUG previews (`ReadingSamples`, `ReplyGallery`, `seedSamples`) | Skip |
| Working Copy / Obsidian deep links | Obsidian has an Android app (`obsidian://` works); Working Copy is iOS-only, so skip it |
| `CarnetScope.minimal` gating | Port the minimal scope first |
| Legacy key migration (`carnet.serverURL` → households) | Skip |

**Known gaps worth not copying:**
- No 401 handling.
- Queue items are not bound to a member or household.
- Highlight and bookmark writes are fire-and-forget (silent loss).
- `locale` is hard-coded to `"fr"` on article saves.
- The `webBase` default of `:4321` with no way to change it.
- Tokens stored outside the Keychain.

---

## 9. Server-verified behaviour (maurice/server @ cc39263)

File references are relative to `maurice/server/`. This section supersedes §2 where they differ.

### 9.0 Rules for every route

- **Error body.** Always `{"error": string}`. An exception that escapes a route gives `500 {"error":"Internal error"}` (`index.ts:141`). That includes malformed JSON on routes that call `c.req.json()` without a catch: `/auth/setup`, `/auth/login`, calibre bookmark and progress POST/PUT.
- **Dates.** Parse them leniently. The server sends all of these forms:
  - `"YYYY-MM-DD HH:MM:SS"`: UTC, but with no `T` and no `Z`. This is SQLite `datetime('now')`, used on most rows.
  - `"YYYY-MM-DD"`, date only.
  - ISO with a `Z`, and ISO with milliseconds.
  - ISO with an offset and microseconds, e.g. `2026-09-23T22:23:06.506038+02:00`.
- **Request size.** Bun's `maxRequestBodySize` is 4 GiB (`index.ts:976`). `idleTimeout` is 255 s, which matters for the slow LLM routes.
- **`proxyAuth`** (`src/middleware/auth.ts:212-286`):
  - It reads the Bearer header first, then the `maurice_session` cookie.
  - These paths are exempt, so their token is never read: `/api/auth*`, `/api/health`, `/healthz`.
  - **A bad or stale token on any other path gives 401 immediately, even on open routes** such as `/api/avatars/*` and `/api/garden-images/*`. Never attach a stale token to an image request.
- **`requireAuth`** reads only the Authorization header.
- **Unknown `/api/...` paths** fall through to the garden engine proxy, so their 404 is not JSON.
- **Every success and error status** for the routes Carnet calls is listed in the sections below.

### 9.1 Auth and users

**Session tokens**
- 64 hex characters.
- **No TTL and no refresh.** A session ends only through `POST /api/auth/logout` (Bearer → `{ok:true}`) or account erasure.
- So a 401 means the token was revoked, or the member or account was deleted.

**`POST /api/auth/setup`**
- Success is **201** `{user: User, token}`.
- Errors: 409 `"Admin already exists"`, 400 `"username and password required"`.

**`POST /api/auth/login`**

| Case | Status | Body / notes |
|---|---|---|
| Success (either branch) | 200 | `{user_id, token}` |
| Admin branch `{username, password}`, wrong credentials | 401 | `"Invalid credentials"`. This branch is not rate-limited. |
| Member branch `{user_id, pin}`, unknown id, wrong PIN, **or a member with no PIN** | 401 | `"Invalid credentials"` |
| Member has a PIN, but `pin` is missing | 401 | `"PIN required"` |
| More than 10 failures per IP in 15 min (200 globally) | 429 | `"Too many attempts. Try again later."` with a `Retry-After` header |
| Neither shape | 400 | `"Provide username+password or user_id+pin"` |

- **`pin` must be a JSON string.** A number gives a 500.
- An optional body field `device_id` exists. There are no device headers.

**`POST /api/auth/enroll`**
- `code` is normalised with `toUpperCase()`, then every character outside `[A-Z0-9]` is removed. Codes are 8 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, shown as `XXXX-XXXX`.
- **Open household invitation:** the first call returns `200 {needs_profile:true, household}`, which Carnet does not handle. Call again with `display_name` (≤40 characters) and optionally `avatar_color` (`#rrggbb`). That returns `{user_id, token, role:"standard", needs_pin:true}`.
- **Member-bound code:** reusable until it expires (7 days). It returns `{user_id, token, role, needs_pin: role != "guest"}`. `needs_pin` is true even when a PIN already exists.
- Errors: 401 `"Invalid or expired code"`, 404 `"User not found"`, 400 `"code required"`, and 429 with `Retry-After` (10 failures per IP per 10 min).
- The Maurice app also accepts invitation links: `maurice://join?server=&code=` and `https://<host>/join?code=`.

**`POST /api/auth/set-pin`**
- `pin` must match `^\d{4,6}$`. Otherwise 400 `"PIN must be 4–6 digits"` (note the en-dash).
- Success: `200 {ok:true}`.

**`GET /api/users/me`**
Returns the full User:

| Field | Type |
|---|---|
| `id` | String |
| `username` | String |
| `display_name` | String |
| `role` | `"admin"` \| `"standard"` \| `"guest"` |
| `avatar_color` | String |
| `avatar_url` | String or null |
| `profile_text` | String or null |
| `has_pin` | Bool |
| `is_child` | Bool |
| `created_at` | String |
| `last_active_at` | String or null |

**`GET /api/users`**
- Admins get full User objects.
- Everyone else gets `{id, display_name, avatar_color, avatar_url, has_pin, role}`, **with no `username`**.
- Guests see only themselves and their contacts.

**`/api/users/me/device-token`**
- `POST {token, platform?="ios", household_tag?}` returns `{ok:true}`. It upserts on `token`.
- `DELETE /api/users/me/device-token/:token` returns `{ok:true}`. Use it on sign-out.

**`POST /api/me/erase`**
- Returns `403 "session_required"` when called with a `maur_` token.
- Other errors:

| Status | `error` |
|---|---|
| 400 | `bad_scope` |
| 403 | `confirm_mismatch` |
| 403 | `bad_password` |
| 403 | `bad_pin` |
| 404 | `User not found` |
| 409 | `last_admin` |
| 429 | `too_many_attempts` (5 failures per user per 15 min) |
| 500 | `Erase failed` |

- The check is: password if the account has one, otherwise PIN if it has one, otherwise `confirm` alone.
- On success: 200 with an erase summary, and every session is gone.

**`/api/dictation`**
- `GET` returns `{available, engine:"local"|"scaleway"|null, max_seconds:600}`.
- `POST` returns `200 {text, engine, seconds}`.
  - `Content-Type` only chooses the file extension: wav, mpeg, webm, ogg, flac, otherwise m4a.
  - Limits: 25 MiB and 600 s.
- Errors:

| Status | `error` |
|---|---|
| 413 | `too_long`, or `The recording is too long.` |
| 400 | `The recording is empty.`, or `The recording could not be read.` |
| 503 | `This household has no transcription engine.`, or `Local transcription is not installed.` |
| 402 | A budget reason (free text) |
| 502 | `Transcription failed.`, or `The transcription service could not be reached.` |

### 9.2 Push: there is no Android path today

APNs only (`src/services/apns.ts`). The topic is `eu.chezmaurice.carnet.app` for any platform starting with `carnet`, and `eu.chezmaurice.maurice` otherwise.

**There is no FCM code anywhere.** An Android token would be posted to APNs, rejected as `BadDeviceToken`, and pruned. **Android push needs server work, or polling.**

Payload:
```json
{"aps":{"alert":{"title":"…","body":"…"},"sound":"default"},"conversationId":"…","householdTag":"…"}
```
There is no badge and no thread-id.

**When a push is sent:** only while the recipient has **no live `/api/me/ws` socket**. An open Maurice app anywhere suppresses the push to Carnet. Carnet receives every push the Maurice app does:
- room activity;
- being added to a conversation;
- a conversation Maurice opens on his own (triggered from the admin route).

A tap on any of them opens `OpenedConversationView`.

### 9.3 Calibre (`data-api/routes/calibre/*`)

Books, chapters, covers and status are household-wide. Bookmarks, highlights and progress are per member. Rows come from `SELECT *` and carry an extra `member_id`, plus `scope` on bookmarks.

**`GET /books`**
- **Always wrapped:** `{books:[{id, title, authors, tags, formats (upper-case, e.g. "EPUB"), series|null, added:ISO|null, chapters, summarized, indexed}]}`.
- No query parameters, no `description`. Ordered by Calibre title sort.
- Also exists: `GET /books/search?tags=a,b` (tags only).

**`GET /books/:id/metadata`**
- Returns `{id, title, authors, tags, formats, series, description}`. **`description` is usually HTML.**
- No `added`, no counts.

**`GET /books/:id/cover`**
- `image/jpeg`.
- 404 `"No cover"`.

**`GET /books/:id/chapters`**
- A **bare array**: `[{index, title, slug, wordCount, hasSummary, summaryWordCount}]`. Every field is always present.
- **Indices have gaps** (front matter is hidden) and often do not start at 0.
- **Slugs** look like `"0003-The Long Road"`: they contain spaces and Unicode, so percent-encode them in paths. They change when the book is re-extracted.

**`GET /books/:id/chapters/by-slug/:slug`**
- Returns `{title, text, wordCount}`.
- **`text` is plain UTF-8, with paragraphs joined by `"\n\n"`** (for EPUBs the server extracted) and one trailing `\n`.
- Also exists: `GET /books/:id/chapters/:chapterIndex`.
- Errors: 404 `"Chapter not found"`.

**`GET /books/:id/chapters/by-slug/:slug/summary`**
- Returns `{exists:false}` or `{exists:true, title, summary}`.
- `summary` is plain prose. Long chapters are summarised in chunks joined by `"\n\n---\n\n"`.

**`GET /books/:id/reading-progress`**
- Returns **the JSON literal `null`** when there is none.
- **A placeholder row with `chapter_index:-1, chapter_slug:""` can appear.** It is created by `/tracking` before any visit. Treat it as "no position".

**`POST /books/:id/reading-progress`**
- **Last-write-wins, not advance-only.**
- When paused (`enabled=0`) the server writes nothing and returns the row unchanged.
- 400 `"chapter_index and chapter_slug required"`.

**Tracking and reset**
- `POST /reading-progress/tracking {paused}` returns the row. 400 `"paused (boolean) required"`.
- `DELETE /reading-progress` returns `{ok:true}`.
- `POST /reading-progress/toggle` also exists.

**Bookmarks**
- `GET` returns a bare array, newest first.
- `POST` (toggle) returns `{bookmarked:false}` or `{bookmarked:true, bookmark}`.
- `PUT /:bid {note}` returns the bookmark row. **A missing `note` clears it.**
- `DELETE /:bid` returns `{ok:true}`.
- The `:bookId` path segment is ignored on PUT and DELETE.

**Highlights**
- `GET` returns a bare array and accepts `?view=full|summary`.
- `POST` returns **201**. Errors: 400 `"chapter_slug and quote required"`, 400 `"view must be one of: full, summary"`.
  - A highlight with a non-blank note opens the book's fiche in the garden.
- `PUT`: a missing `note` keeps it; `null` clears it.
- `DELETE` returns `{ok:true}`.
- **Colours are not validated** (default `yellow`). **Offsets are opaque to the server**: UTF-16 is purely the client's convention, and Kotlin `String` indices match it.

**`GET /books/:id/status`**
- Returns `{bookId, chapters, summarized, indexed, job:{action:"extract"|"summarize"|"index", state, error?, at:ISO}|null}`.
- The job is held in process memory, so it is lost when the server restarts.

**`POST /books/:id/summarize`**
- `202 {status:"started"}`, with **no `pid`**.
- Async errors appear only in `/status` (`job.state:"error"`).
- With `?sync=true`: 200, 409 `"No chapters yet: extract them first."`, **429** (member budget), or 502.
- 409 `"A <action> is already running for this book."`.

**`POST /books/:id/extract`**
- `202 {status:"started"}` for an EPUB.
- 422 `"Only a book with an EPUB file can be split into chapters here."`.

**`POST /add`**
- Success is **201** `{ok:true, bookId|null, filename, output}`.
- Errors: 400 `"file field is required"`, 400 `"Unsupported format: …"`, 500.
- **503** when `MAURICE_CALIBRE_DISABLED` is set (hosted households).

**No raw EPUB endpoint exists.** The Calibre-Web reverse proxy at `/calibre/*` is member-authenticated (downloads, OPDS); its exact paths were not verified.

### 9.4 Garden

**Shelf ids.** Besides `c/l/s` and `calibre/<n>`, rows another member shared have **`id = "@<username>/<collection>/<locale>/<slug>"`**. For those rows `shared` is null, `translations` is `[]`, and `captured` is false.

**Sharing restrictions.** With `?owner=<someone else>`, a non-GET request is allowed only on `…/notes` and `…/participants/:id`. Anything else gives 403 `"Only the owner changes this"`.

**`POST /garden/articles`**

| Status | When / body |
|---|---|
| 201 | New article, including a new bookmark (`needs_capture:true`) |
| 200 | `duplicate:true` (and then `comment` is null even though the comment was appended) |
| 400 | `"url is required"`, `"url is not a valid URL"`, `"Body must be JSON"` |
| 404 | `"No notes for this member"` |
| 413 | `"html exceeds the size limit"` (8 Mi UTF-16 characters, not bytes) |
| 422 | No title could be extracted, or no free slug |
| 502 | `"Failed to read the article: …"` |

- Additional fields in the response: `kind`, `canonical_url`, `lang`, `opened`, `card_web_path`, `text_retaken`, and `file` (an absolute server path; ignore it).
- `summary` is always null at save time: the summary is written in the background.
- A bad `locale` gives a 500.

**`GET /lookup`**
- Returns `{saved, article: {kind, slug, locale, file, title, url, canonical_url, saved_at} | null}`. This is a thin reference, not a SavedArticle.

**`GET /garden/articles`**
- Ordered by `saved_at` descending.
- Includes old cards (`kind:"card"`). `text_rev` is milliseconds since the epoch, as a string.

**`GET /:slug/text`**
- `styles` is always an array. `text` is plain, with blocks separated by `\n\n`.
- **Style offsets are UTF-16.**
- 404 `"No captured text for this article"`.

**`POST /:slug/comment`**
- Returns **201**. Errors: 400 `"comment is required"`.

**`POST /:slug/summary`**
- **The body is read only if the `Content-Type` contains "json"**; set it, or `force` is lost.
- Returns `{slug, locale, summary, summary_long, regenerated}`.
- Errors: 404, 422, 502. There is no 402 or 429.

**Article highlights**
- `POST` returns 201. The highlight carries an extra `locale` field.
- `PUT` and `DELETE` ignore both the slug and the locale: the lookup is by id and member only.

**`GET /garden/links/targets`**
- Top 20 matches, opened entries only. Each target carries an extra `published_url`.

**`POST /garden/links`**
- Returns 201 `{file, web_path, target}`.
- Errors: 400 `"comment or quote required"`, 404 `"No entry for <locale>/<slug>"`.

**`GET /entries?view=shelf`**
- `kinds` lists only non-zero counts.
- **In the list, `source` has no `summary`.** Only the detail page adds it.
- Ordered by `updated_at` descending.

**`GET /entries?view=notes`**

| Item kind | id | Fields |
|---|---|---|
| `note`, `quote` | null | Usual note fields; `where` is either `{page}` or `{chapter_title}` |
| `resonance` | null | Usual note fields |
| highlight | `"h:<n>"` | No `author`; `where` is `{chapter_slug, chapter_title, view}` |

- `author`, `initiator` and `from.entry_id` are always null in this view.
- Entries shared by other members are not included.

**`GET /entries/:c/:l/:s`** (the entry page)
- Confirmed: the ShelfEntry fields are flattened at the top level, with the detailed `mine` and `shared`. **The GET never carries `deploy`.**
- Fragment items have the form `{kind:"fragment", id:"f:NNN", date:null, text, summary}`.
- 404 `"No such entry"`: this also covers people, notes and pages.

**Writes**
- Success is **201** with the entry page, or **202** with `deploy` (publish).
- `deploy` is `{status, started_at, finished_at, error}`, all three nullable.
- Errors: 400, 404 `"No such entry: c/l/s"`, 409, 422, 500 `"Failed to write"`.

| Route | Status | Response / notes |
|---|---|---|
| `DELETE /entries/:c/:l/:s` | 200 or 202 | `{deleted:[ids], deploy?}`, **not the entry page** |
| `POST /entries/archive` | 200 | `{ids, archived}` |
| `PUT …/cover` | — | Raw JPEG or PNG, sniffed from the bytes, ≤8 MB. 400 `"No picture was sent"`, `"That picture is too large"`, `"A cover is a JPEG or a PNG"`. |
| `POST …/notes` | — | 400 `"text or quote required"`. **`where.page` must be a string** (a number gives 500). |
| `POST /entries/from-book` | — | Also accepts `locale`. 400 `"book_id required"`, 404 `"Book not found: <id>"`. |
| `PUT …/shared` | — | 400 `"body required"` |
| `POST`/`DELETE` `…/shared/publish` | 202 | 409 `"No site is set up for this member"`, 409 `"Nothing is written to publish"` |
| `POST …/participants` | 201 | Accepts `{member_id}` or `{username}`. 404 `"Unknown member"`, 400 `"The entry is already theirs"`, 403 `"Not allowed to add this member"`. |
| `DELETE …/participants/:id` | 200 | Entry page for the owner; `{ok:true}` for a member leaving |
| `GET /site/deploy` | — | In-memory: after a server restart the status reads `idle` |

**`GET …/conversations`**
- **`via` is `"entry"` or `"linked"`, not `"context"`** (`entryConversations.ts:75,135`).
- Conversations with 0 messages are omitted. Ordered by `updated_at` descending.

**`POST …/conversations/:id/summary`**
- `force` counts only as a literal `true`.
- Errors: 404, 409 `"This entry has no fiche to keep it in"`, 422 (too long), 502.

### 9.5 Conversations, suggestions, domains

**`POST /api/conversations`**
- Body `{entry?, maurice_id?}`. `title` is ignored.
- **201** for a new conversation. **200** when it reuses your existing empty conversation for the same entry.
- Errors: 404 `"No such entry"`, 404 `"Not found"`.
- Returns the full Conversation: `id, user_id, title|null, maurice_id, maurice_bound_by, origin, opened_by, entry_ref, created_at, updated_at`.

**`POST /api/conversations/:id/messages`**

*Before the stream* (plain JSON):

| Status | `error` |
|---|---|
| 404 | `"Not found"` |
| 400 | `"content required"` |
| 400 | `"at most 10 images per message"` |
| 409 | `"A reply is already in progress"` |

- `{ok:true, summoned:false}` (200, plain JSON) is returned in a multi-human room when Maurice is not addressed.
- **There is no 402 or 429.** Budget errors arrive inside the stream.
- Other accepted body fields:
  - `images`: up to 10 `data:` URIs;
  - `regenerate`;
  - `summon`;
  - `maurice_id`: **sending `null` unbinds the domain, so omit it**.

*The stream* (`application/x-ndjson`):

| type | Fields | Notes |
|---|---|---|
| `text_delta` | `text` | Batched about every 30 ms |
| `thinking` | — | At most once per second |
| `tool_call` | `tool`, `status`: `"start"\|"end"` | |
| `tool_data` | `tool`, `data` | |
| `usage` | `usage:{provider, model, rounds, input, output, cache_read, cache_write, cost, cost_uncached}` | |
| `image_loading` | `text` | Repeated as a keepalive |
| `image` | `image_url`, `text` | |
| `ping` | — | After 15 s of silence |
| `done` | `message_id` | |
| `error` | **`message` only** | The Swift client's `error` field never comes |

- `error` is not necessarily the last event.
- **`done` can be missing** when the reply is empty.
- **When the client disconnects, the turn keeps running.** Reattach with `GET /api/conversations/:id/turn`: 204, or NDJSON starting with `{type:"resume", text, data, tool, usage, started_at, finished}`. A finished turn stays reattachable for 60 s. Stop a turn with `POST /:id/turn/stop`.

**`GET /api/conversations`**
- `limit` defaults to 40, clamped to 1–100. Paging uses the keyset `before_at` + `before_id`.
- **`unread` is true only for a conversation Maurice opened and you have never read.**
- Each row also carries `participants[]`, `suggestions`, `suggestions_kept`, `maurice_ids` and `has_everyday_maurice`.

**`GET /api/conversations/:id`**
- Carries no `unread`, `message_count` or `last_message_at`.
- Message `role` can be `"system"`.
- Each message also carries `model`, `maurice_id`, `data`, `usage` and `conversation_id`.
- Extra fields: `participants`, and `entry:{ref, collection, title, image, web_path}` (shown to the owner only).

**`POST …/read`** always returns `{ok:true}` and never 404s.

**`GET /api/suggestions`**
- `settle` waits **at most 20 s**, and only for a 1:1 chat's post-reply pass.
- 404 `"not found"` (lower-case).
- Dismissed rows are never listed.
- Each suggestion also carries `conversation_id`, `message_id`, `image` and `web_path`.
- `entry` is null rather than absent.
- Candidates carry `image`. Their `year` is null rather than absent, and `subtitle` may be `""`.
- `kind` is one of movies, series, books, music, podcasts, games, people, articles, or the collection of the bound entry.

**Suggestion actions**
- **`/keep`** returns the suggestion view. Errors:
  - 409 `"a candidate must be picked"`
  - 409 `"the show's feed is not known"`
  - 400 `"unknown candidate"`
  - 400 `"only a podcast has episodes"`
  - 404 `No such entry: <ref>`
  - 422 `Could not parse: <ref>`
  - 502 when the fiche or article could not be written
  - 500 `"Failed to keep"`
- **`/episodes`**: up to 50 episodes. `image` may be `""`.
- **`/search`** returns the **full view plus `found`**. Errors: 409 `"already decided"`, 409 `"this is the conversation's own entry"`, 409 `"someone from the member's life is not looked up"`, 502.
- **`/dismiss`** returns the view with `state:"dismissed"`.

**`GET /api/domains`**
- Also returns a top-level `proposals:{open, unseen}`.
- Each domain row also carries `icon` (an SF Symbol name), `created_by`, `weight`, and `notes:{total, unreviewed, web_path}|null`.
- **`count` is the item count of the domain's context bundle, not a number of conversations.**
- Companion `tagline` is always a string. `book_id` and `conversation_id` can be null.
- Guests see the rows granted to them, with `mine:false` and `brief:null`.

**Domain brief**
- `GET` returns an extra `notes` field. `brief.sources` mixes conversation ids and `"mail:<path>"` strings.
- A companion's brief gives 404 `"A reading companion has no brief"`.
- `PUT` errors: 400 `` "`text` must be a string" ``.
- `DELETE` returns `{erased}`.
- `POST …/refresh` always returns the same body: `{outcome, sources, cost_usd, error, brief}`.

| outcome | HTTP |
|---|---|
| `written` | 200 |
| `unchanged` | 200 |
| `capped` | 429 |
| `failed` | 502 |

**`POST /api/maurices`**
- Returns **201** with the full Maurice.
- Errors: 400 `"name required"`, 400 `"invalid context"` (with `details`). 409 `domain_name_taken` applies to domains only.
- The book id is not checked. Guests are allowed.

### 9.7 Corrections to the earlier sections

- **§1.3:** a PIN-less member cannot log in with `{user_id}`; the server returns 401. Carnet's open-invitation enroll does not handle `needs_profile`.
- **§1.5:** tokens never expire, so a 401 means the session was revoked or the member deleted. A stale token also breaks the open image routes.
- **§2.2:** the books list is wrapped; chapters are a bare array; reading progress is last-write-wins; a paused POST is a no-op; `summarize` returns no pid.
- **§2.5:** `via` is `"entry"` or `"linked"`. There is a third id form, `@user/c/l/s`. `DELETE` and `archive` do not return the entry page.
- **§2.6:** the stream error field is `message`. Reattach is possible. `/search` returns the full view.
- **§5.2:** there is no FCM or Android push on the server. Pushes are suppressed while a socket is open.
