# Maurice client ↔ server contract (for the Android / KMP port)

Source of truth read on 21 October 2026:

- Swift app: `maurice/app/Maurice` (commit `cc39263`), `maurice/app/Shared`, `maurice/app/MauriceShareExtension`.
- Server: `maurice/server/index.ts`, `maurice/server/src/routes/*`, `maurice/server/src/services/*`, `maurice/server/data-api/routes/*`.

Conventions in this document:

- Field names are exactly as on the wire (snake_case unless stated). Kotlin `@SerialName` should copy them verbatim.
- `T?` = may be `null` **or** absent. Decode every response leniently (`ignoreUnknownKeys = true`, `coerceInputValues = true`, explicit defaults): the server adds columns freely (`SELECT *` rows) and the Swift client relies on that tolerance.
- "Swift:" names the file/function that makes the call; "Screen:" names the view that triggers it.
- Where the server and the app disagree or the shape could not be confirmed, it says **unsure** or **note**.

---

## 1. Connection & authentication

### 1.1 What a "household" is

A device may be paired with several Maurice servers ("households", French *foyer*). Each household is one base URL with its own member accounts. The app keeps, per household, the members enrolled on this device and one session token per member. Exactly one household is "current" and one member of it is "active"; every request goes to the current household with the active member's token.

```kotlin
// persisted as JSON under UserDefaults key "maurice.households" (Swift: SessionStore.Household)
data class Household(
    val id: String,              // device-local UUID; ALSO the push routing tag ("household_tag")
    var serverURL: String,       // e.g. "https://maison.example.eu" (no trailing slash)
    var name: String,            // from /api/health "household", else the URL's host
    var color: String? = null,   // "#rrggbb" from /api/health household_color
    var icon: String? = null,    // SF Symbol name from /api/health household_icon (iOS-only meaning)
    var deviceUsers: List<DeviceUser>,
    var activeUserId: String?,
    var unread: Int? = null,     // from /api/users/me/unread, for the household switcher badge
)
data class DeviceUser(          // local cache of a ServerUser
    val id: String, val displayName: String, val avatarColor: String, val hasPin: Boolean,
    var role: String? = null,    // "admin" | "standard" | "guest"
    var avatarURL: String? = null, // "/api/avatars/<file>" (relative to serverURL) or absolute
    var username: String? = null,
)
```

If a household's `icon` is not a valid SF Symbol, the app derives an icon and colour deterministically from the household id (sum of unicode scalars mod 8) — see `foyerPalette` / `foyerIcons` in `ScopePickerView.swift`. Android can use its own icon set.

### 1.2 Finding the server (address normalisation)

The user types an address or scans/pastes an invitation (`PairingView.connect`):

1. If the text parses as an **invitation** (see 1.3), the invitation flow runs instead.
2. Otherwise normalise (`PairingView.normalizedURL`): trim whitespace; if it does not start with `http://` or `https://`, prefix `https://`; strip every trailing `/`; require a non-empty host. A bare `mac.local:3001` becomes `https://mac.local:3001`.
3. `GET {base}/api/health` (no auth). Must answer `status == "ok"`; any other value is "not a Maurice server"; a network error shows the reason, plus an "https" hint when the address was `http://`.
4. `pair(serverURL, name = household, color = household_color, icon = household_icon)`: if a household with the same `serverURL` exists it is re-selected (colour/icon refreshed), else a new `Household` with a fresh UUID is appended and made current. No token yet → the member picker shows.

`/api/health` response (server `index.ts`, public):

```json
{ "status": "ok", "version": "0.1.0", "setup_complete": true,
  "household": "Chez nous", "household_color": "#a6452e", "household_icon": "house" }
```
`household`, `household_color`, `household_icon` may be `null`. `version` is hard-coded `"0.1.0"` server-side (not a useful version check).

The app re-reads `/api/health` (8 s timeout) plus `/api/users/me/unread` for **every** paired household in parallel after loading and after receiving an invitation (`SessionStore.refreshFoyers`) to refresh names/colours/unread badges.

### 1.3 Invitations (QR code, link, typed code)

An invitation = server address + code. Two spellings reach the app (`SessionStore.Invitation.init?(_ raw:)`):

| Form | Parsed as |
|---|---|
| `https://<host>[:port]/join?code=XXXXXXXX` (what the QR code encodes; also pasted into the address field) | server = `scheme://host[:port]`, code = `code` |
| `maurice://join?server=<urlencoded base>&code=XXXXXXXX` (what the `/join` web page's "Open" button hands to the app) | server = `normalizedURL(server)`, code = `code` |

`code` must be non-empty. The QR code the app *displays* (admin's Members pane) encodes `Invitation.link` = `"{server}/join?code={code}"`. Codes are 8 characters; the UI formats them `XXXX-XXXX` (`InviteQRSheet.formatted`). The server's `/join` page (HTML, public) normalises the code and offers the `maurice://join` link.

Receiving an invitation (`SessionStore.receive`): pair the server (as 1.2 step 4, without the health check), set `activeUserId = nil`, keep the invitation as `pendingInvitation`, refresh households. The member picker (`UserPickerView`) then immediately enrols with the code (1.4.1).

Android: register an intent filter for the custom scheme `maurice://` (hosts `join`, `conversations`, `share`) and, optionally, an App Link for `https://*/join` (the household domain is arbitrary, so App Links cannot be verified in general — keep the in-app QR scanner, which is the primary path).

### 1.4 The member picker (who am I on this household)

`UserPickerView` shows the household's members as avatars. **The list itself needs a token**: `GET /api/users` is called with the token of *any* member already enrolled on this device for this household. On a fresh device there is none, so the grid is empty and the screen offers two buttons: "I have an invitation code" (enrol) and "Admin sign-in".

#### 1.4.1 Enrol with an invitation code (new device or new member)

```
POST /api/auth/enroll        (no auth)
{ "code": "ABCD1234", "device_id"?: "...", "display_name"?: "Odile", "avatar_color"?: "#2c5aa0" }
```
- First call: `{code}` only (plus `device_id` if known — **note**: the Swift app never actually sets `deviceId`, so `device_id` is never sent today).
- If the answer has `needs_profile: true` (an *open* invitation, i.e. someone not yet a member): the app asks for a name and one of 8 colours (`#2c5aa0 #7a4f6e #b97a1e #3d6b4f #a6452e #e3a7c2 #44504f #9c6b4a`) and re-sends `{code, display_name, avatar_color}`.
- Otherwise the answer carries `user_id` + `token` (+ `role`, `needs_pin`, `household`). The app then calls `GET /api/users/me` with that token to build the `DeviceUser`.
- `needs_pin` is `true` for every non-guest, **even one who already has a PIN** (a member's device code); the app then asks for a PIN again and overwrites it. The device-code answer has no `household` field.
- If `needs_pin == true` (members): the session is held until the member chooses a PIN — 4 to 6 digits, typed twice — sent with `POST /api/auth/set-pin {"pin":"1234"}` using the new token; only then is the member stored and made active. If `needs_pin` is false/absent (guests): stored and active immediately.
- Errors: the server's `error` string is shown verbatim.

#### 1.4.2 Admin sign-in (username + password)

`POST /api/auth/login {"username": "...", "password": "..."}` → `{user_id, token}`. The app stores the token, then `GET /api/users` with it and stores **all** returned members as device users (so their avatars appear; their tokens are still absent).

#### 1.4.3 Tapping a member

- If `has_pin` → a PIN overlay **always** shows (even when a token is cached), then `POST /api/auth/login {"user_id": "...", "pin": "1234"}`.
- If no PIN and a token for that member is cached → switch locally, no request.
- If no PIN and no token → `POST /api/auth/login {"user_id": "..."}`.
- Success: store the token for that member, make them active, upsert them in `deviceUsers`; if the grid was empty, fetch `GET /api/users` and store everyone.
- Failure under the PIN overlay shows a generic "wrong PIN" string. Server answers: 401 `Invalid credentials`, 401 `PIN required` (PIN-protected member, no `pin` sent), 429 `Too many attempts. Try again later.` with `Retry-After` (10 failures per IP per 15 min). Send `pin` as a JSON **string** (a number crashes the handler: 500).

#### 1.4.4 Connect disclosure (non-owners)

After a session exists, if the active member's role is not `admin` and the flag `maurice.connect_disclosure.<host>` is not set locally, a blocking modal explains that the server is run by someone else (verbatim English copy in `ContentView.ConnectDisclosureOverlay`). Accept → set the flag; Cancel → `unpair()` (forget the household). Purely local; Android should reproduce it (store-review requirement).

### 1.5 What is stored where

**Surprise: nothing is in the Keychain.** Session tokens and MCP tokens live in plain `UserDefaults` (`SessionStore.storeToken`). On Android use EncryptedSharedPreferences / DataStore + Keystore instead; the key layout below is only for reference.

| Key | Value |
|---|---|
| `maurice.households` | JSON array of `Household` (1.1) |
| `maurice.currentHousehold` | current household id |
| `maurice.token.<householdId>.<userId>` | session token (string) for that member on that household |
| `maurice.mcp-token.<householdId>.<userId>` | the member's `maur_…` API token (used for web sign-in links, 1.8) |
| `maurice.activeUserId` | mirror of the current household's active user id (for localisation lookups) |
| `maurice.prefs.<userId>.palette` / `.locale` / `.webTheme` | per-member local preferences (see 7) |
| `maurice.deviceId` | legacy, never written by current code |
| `maurice.serverURL`, `maurice.deviceUsers`, `maurice.token.<userId>`, `maurice.mcp-token.<userId>` | legacy single-household layout, migrated once at launch (`migrateLegacyIfNeeded`) — Android can ignore |

The full list of local keys is in section 7.

### 1.6 Headers sent

Every JSON request built by `APIClient.buildRequest`, `MauriceStore.requestStatus` and `ComposerStore.request`:

```
Authorization: Bearer <session token>      (omitted on /api/health, /api/auth/login, /api/auth/enroll)
Content-Type: application/json             (set even on GET and DELETE)
```

That is all. **No** app-version header, **no** member-selection header (the member is implied by the token: one token per member), **no** explicit language header, **no** device id. URLSession adds the system `Accept-Language` and a default `User-Agent` on its own; the server uses `Accept-Language` only for a few HTML pages (`/join`) and takes the *chat* language from the member's saved preference (`PATCH /api/users/me/preferences {locale}`), so the Android client must save the locale there when the member changes it.

Other request kinds:

- Multipart uploads (`APIClient.upload`, `ComposerStore.uploadFile`): `Content-Type: multipart/form-data; boundary=Boundary-<UUID>`; text fields first (e.g. `folder_id`), then the part `name="file"; filename="<name>"` with `Content-Type: application/octet-stream`. Timeout 180 s.
- Dictation upload: `Content-Type: audio/mp4`, raw body (section 4).
- WebSockets: the token rides in the query string, `?token=<percent-encoded token>` (a WebSocket cannot carry the header in URLSession). The server also accepts the `maurice_session` cookie there, unused by the app.
- Images (`/api/images/…`, `/api/avatars/…`, `/api/garden-images/…`, absolute CDN URLs) are loaded **without** any credentials (`AsyncImage`); the server serves those routes openly for that reason. Garden image paths of the form `/images/<member>/…` require a session (cookie or Bearer) — **note**: when a card hands such a path the app loads it unauthenticated and it will fail; prefer adding the Bearer header on Android for any same-host image.

Token formats accepted by the server (`middleware/auth.ts`):

- **Session token**: opaque string (the `sessions.id` row). Returned by login/enroll. **Sessions do not expire** (`expires_at` is null in v1) unless revoked server-side.
- **`maur_…` API token**: 32 random bytes, base64url, prefixed `maur_`. Scope `full`/`mcp` authenticate like a session (no device id). The app obtains one with `POST /api/users/me/mcp-token` and uses it only for web sign-in links and to show/copy it in Settings.

Timeouts used by the Swift client (`timeoutInterval` = idle time between bytes, not total duration):

| Call | Timeout |
|---|---|
| default JSON request | 180 s |
| `/api/health` + `/api/users/me/unread` in the household refresh | 8 s |
| `POST /api/v1/articles/read` | 45 s |
| `/api/suggestions/:id/keep`, `/episodes`, `/search` | 40 s |
| `POST /api/me/erase` | 600 s |
| `GET /api/me/export` (download) | 1800 s |
| `GET /api/dictation` (capability probe) | 3 s |
| `POST /api/dictation` | 240 s |
| chat NDJSON streams | URLSession default (60 s request timeout; the server pings every 15 s of silence) |

### 1.7 Errors and 401

- Every non-2xx JSON answer is decoded as `{"error": "<message>"}`; the app turns it into `APIError.server(status, message)` and usually shows the message verbatim (`"[status] message"` in the generic `errorDescription`). A body without `error` gives `"Request failed"`.
- `APIError.requestFailed` = no HTTP response.
- **There is no 401 handling at all.** A revoked token simply makes every call fail (lists stay empty, banners show `[401] Invalid or expired session`). The user recovers by "Switch member" (re-login) or "Disconnect". Recommendation for Android: on any 401 from an authenticated call, drop that member's token and route to the member picker.
- Network-level failures (URLError, POSIX) during background loads are shown as a quiet "notice" (`chat.notice.network`), anything the server said as an error banner (`ChatService.report`).
- Server timestamps are naive UTC strings `"YYYY-MM-DD HH:MM:SS"` (SQLite `datetime('now')`), sometimes ISO 8601 with `T…Z` and optional fractional seconds. **Always parse as UTC** (`parseServerDate`).

### 1.8 Opening the web garden signed in (`/login?token=`)

Several buttons open the member's web garden in the browser. A bare link would hit the members-only gate, so the app goes through:

```
{server}/login?token=<maur_ API token>[&theme=<webThemeId>][&to=<percent-encoded path>]
```
`to` is percent-encoded with only `A–Z a–z 0–9 - . _ ~` left bare. The token is the MCP token from `POST /api/users/me/mcp-token {"rotate": false}` (cached under `maurice.mcp-token.*`). The server sets a `maurice_session` cookie and redirects (details in the endpoint table, `/login`). Used by: Settings → "Open my garden", garden rows' ↗ button, fiche links in replies (`/g/...` links to this server), person-fiche links, `maurice-mail:<id>` links (opened as `/api/people/mail/<id>` through `/login`).

### 1.9 Member switching, sign-out, multi-household

- **Switch member** (Settings identity row, hidden for guests): sets the household's `activeUserId = nil` → the member picker shows → tapping another member logs in (1.4.3). The previous member's token stays stored (fast switch back unless they have a PIN).
- On any change of active member or household, `ChatService.onUserSwitch` disconnects both WebSockets, clears every in-memory list (conversations, messages, participants, roster, unread, proposals, streaming state) and reloads: `GET /api/users/me/preferences`, the `MauriceStore.load()` set, `GET /api/conversations?limit=40`, then opens the most recent conversation (or the one a notification asked for). The UI shows a loader for at least 2 s.
- **Guest** members (`role == "guest"`) are locked to their account: no switch button, no Files/Mail/Contacts/Import panes.
- **Disconnect** (Settings footer, picker footer, disclosure Cancel) = `unpair()`: delete every stored token of that household's device users and remove the household locally. **No server call** (no logout route is used).
- **Delete my account** (My data) logs the member out locally after the server confirms (`POST /api/me/erase {scope:"account"}`).
- Several households: the switcher lives in Settings → Household pane and in the picker. Push tokens are registered with **every** household (section 5).

---

## 2. Endpoint table

Every route below is relative to the household base URL. "Auth: Bearer" = `Authorization: Bearer <session token>` required (401 otherwise: `{"error":"Missing or invalid Authorization header"}`, `{"error":"Invalid or expired session"}`, `{"error":"Invalid API token"}` or `{"error":"User not found"}`). "Admin" = 403 `{"error":"Admin access required"}` for others. Error bodies are always `{"error": String}` unless stated (some add `detail`). An uncaught server exception (e.g. invalid JSON sent to a route that does not guard it) answers 500 `{"error":"Internal error"}`.

### 2.0 Shared object shapes

```kotlin
// GET /api/users rows, GET /api/users/me, PUT /api/users/:id/avatar
@Serializable data class ServerUser(
    val id: String,
    val display_name: String,
    val avatar_color: String,            // "#rrggbb"
    val avatar_url: String? = null,      // "/api/avatars/<uuid>.jpg|png", served WITHOUT auth
    val has_pin: Boolean,
    val role: String? = null,            // "admin" | "standard" | "guest"
    // Present on /api/users/me always, on /api/users only for an admin caller:
    val username: String? = null,
    val profile_text: String? = null,
    val is_child: Boolean? = null,
    val created_at: String? = null,      // naive UTC
    val last_active_at: String? = null,
)
```
**Note**: a non-admin's `GET /api/users` rows lack `username`. The Swift app builds `DeviceUser` from those rows on login, so `username` ends up `nil` for members who logged in by tapping (only enrolment uses `/api/users/me`), which breaks the "type your username" confirmation in My data → Erase. Android: refresh the active member with `GET /api/users/me` after every login.

```kotlin
@Serializable data class ServerParticipant(
    val member_id: String, val role: String /* "owner" | "member" */,
    val joined_at: String? = null, val username: String? = null,
    val display_name: String, val avatar_color: String, val avatar_url: String? = null,
)

// GET /api/conversations rows, POST /api/conversations, PATCH /api/conversations/:id
@Serializable data class ServerConversation(
    val id: String,
    val user_id: String? = null,          // creator
    val title: String? = null,            // null until auto-titled after the first message
    val maurice_id: String? = null,       // bound domain/companion; null = everyday Maurice
    val maurice_bound_by: String? = null, // "auto" = filed by the night (can be undone); "detached"; null = by hand
    val origin: String? = null,           // null = native; "anthropic" | "chatgpt" = imported
    val opened_by: String? = null,        // "member" | "maurice" (Maurice opened it himself)
    val entry_ref: String? = null,        // garden entry it is held from: "<collection>/<locale>/<slug>"
    val created_at: String, val updated_at: String,
    // list rows only:
    val message_count: Int? = null,
    val last_message_at: String? = null,
    val unread: Boolean? = null,          // opened by Maurice and never read
    val participants: List<ServerParticipant>? = null,
    val maurice_ids: List<String>? = null,        // domains that answered in it (unused by the app)
    val has_everyday_maurice: Boolean? = null,    // (unused by the app)
    val suggestions: Int? = null,         // pending Carnet suggestions
    val suggestions_kept: Int? = null,
)

@Serializable data class ServerMessage(
    val id: String,
    val conversation_id: String? = null,
    val role: String,                     // "user" | "assistant" (| "system", never shown)
    val content: String,                  // Markdown, see 3.7
    val model: String? = null,
    val author_id: String? = null,        // human author; null for Maurice
    val maurice_id: String? = null,       // which domain answered
    val data: List<DataBlock>? = null,    // structured tool results (3.6)
    val usage: TurnUsage? = null,         // 3.2
    val created_at: String,
)
@Serializable data class DataBlock(val tool: String, val data: JsonElement)

// GET /api/conversations/:id
@Serializable data class ServerConversationDetail(
    /* all ServerConversation base fields */ 
    val messages: List<ServerMessage>,    // messages from members the viewer blocked are removed
    val participants: List<ServerParticipant>,
    val entry: ConversationEntry? = null, // only for the conversation's owner
)
@Serializable data class ConversationEntry(
    val ref: String, val collection: String, val title: String,
    val image: String? = null, val web_path: String? = null,
)
```

### 2.1 Connection, auth, members

| Method & path | Auth | Request | Response | Swift caller → screen |
|---|---|---|---|---|
| `GET /api/health` | none | — | `{status:"ok", version:String, setup_complete:Boolean, household:String?, household_color:String?, household_icon:String?}` | `PairingView.connect`, `SessionStore.refreshFoyers` → Pairing, household switcher |
| `POST /api/auth/enroll` | none | `{code:String, device_id:String?, display_name:String? (≤40), avatar_color:String? ("#rrggbb")}` | open invitation, no name: `{needs_profile:true, household:String}`; open invitation with name: `{user_id, token, role:"standard", needs_pin:true}`; member device code: `{user_id, token, role, needs_pin: role != "guest"}`. Errors: 400 `code required`, 401 `Invalid or expired code`, 404 `User not found`, 429 (+`Retry-After`, 10 failures/IP/10 min) | `UserPickerView.enrollWithCode` → Picker (invite overlay) |
| `POST /api/auth/set-pin` | Bearer | `{pin:String}` (`^\d{4,6}$`) | `{ok:true}`; 400 `PIN must be 4–6 digits` | `UserPickerView.submitPinSetup` → PIN setup overlay |
| `POST /api/auth/login` | none | member: `{user_id:String, pin:String?, device_id:String?}` — **`pin` must be a JSON string**; admin: `{username, password, device_id?}` | `{user_id:String, token:String}`; 401 `Invalid credentials` / `PIN required`; 429 `Too many attempts. Try again later.` (+`Retry-After`; 10 failures/IP/15 min, member branch only); 400 `Provide username+password or user_id+pin` | `UserPickerView.loginUser`, `.adminLogin` → Picker |
| `POST /api/auth/logout` | Bearer | — | `{ok:true}` (deletes this session) | **not called by the app** (recommended on Android "Disconnect") |
| `GET /api/users` | Bearer | — | `[ServerUser]`; admin gets all fields, others the reduced set; guests see only themselves + their allowed contacts | `UserPickerView.fetchUsers`, `ChatService.loadRoster`, `MembersPane.load` → Picker, add-participant sheet, Members |
| `GET /api/users/me` | Bearer | — | `ServerUser` (full); 404 | `UserPickerView.enrollWithCode` |
| `GET /api/users/me/unread` | Bearer | — | `{unread:Int}` (rooms with new messages from others + unread Maurice-opened conversations) | `SessionStore.refreshFoyers` → household badges |
| `GET /api/users/me/preferences` | Bearer | — | `{theme:String ("auto"), palette:String ("auto"), locale:String?}` | `SessionStore.loadPreferences` → app load |
| `PATCH /api/users/me/preferences` | Bearer | `{palette:String?, locale:String?}` (app never sends `theme`; **never send `theme:null`** → 500) | same shape as GET | `SessionStore.savePreferences` → Settings language / appearance |
| `POST /api/users/me/device-token` | Bearer | `{token:String, platform:String ("ios"/"macos"), household_tag:String}` | `{ok:true}`; 400 `token required` | `ChatService.uploadDeviceToken` (section 5) |
| `POST /api/users/me/mcp-token` | Bearer | `{rotate:Boolean}` (`true` to rotate) | `{rawToken:"maur_…"}` (scope `mcp`, recoverable) | `SettingsView.loadToken/rotateToken`, `GardensStore.ensureToken`, `ChatView.gardenToken`, `PersonReviewView.gardenToken` → Settings MCP token, any "open in browser" |
| `PUT /api/users/{id}/avatar` | Bearer (self, or admin) | `{image:"data:image/jpeg;base64,…"}` (app sends a square-cropped JPEG) | updated `ServerUser` (new `avatar_url`); 400 `image required` / `Invalid image data URI`; 403; 404 | `SettingsView.uploadAvatar` → Settings identity |
| `GET /api/users/invites` | Admin | — | `{invites:[{code, expires_at (ISO), created_at}]}` | `MembersPane.load` |
| `POST /api/users/invites` | Admin | `{}` | 201 `{code, expires_at, created_at}` (open invitation, single use, 7 days) | `MembersPane.inviteSomeone` |
| `DELETE /api/users/invites/{code}` | Admin | — | `{ok:true}`; 404 `Invitation not found` | `MembersPane.revoke` |
| `GET /api/users/{id}/invite` | Admin | — | `{invite:{code, expires_at} \| null}` | `MembersPane.showDeviceCode` |
| `POST /api/users/{id}/invite` | Admin | `{}` | 201 `{code, expires_at}` (member's device code, reusable for 7 days, replaces the old one) | `MembersPane.showDeviceCode` |
| `DELETE /api/users/{id}/invite` | Admin | — | `{ok:true}` | `MembersPane.revoke` |
| `POST /api/users/{id}/block` | Bearer | `{}` | 201 `{ok:true}`; 400 `cannot block yourself`; 404 `Unknown member` | `ChatService.block` → message menu |
| `DELETE /api/users/{id}/block` | Bearer | — | `{ok:true}` | `ChatService.unblock` |
| `GET /api/web-themes` | none (token sent anyway) | — | `{themes:[{id, name, description?}]}` (`default` first) | `SettingsView.loadWebThemes`, `GardenPageView.loadWebThemes` → Settings garden theme, garden page |
| `GET /login?token=&theme=&to=` | token in query | browser navigation, not an API call | see 1.8 and 2.9 | Settings, gardens, reply links |
| `GET /join?code=` | none | browser page | HTML with the `maurice://join` button | (target of QR codes) |

### 2.2 Conversations

| Method & path | Auth | Request | Response | Swift caller → screen |
|---|---|---|---|---|
| `GET /api/conversations?limit=40[&before_at=&before_id=]` | Bearer | `limit` 1–100 (default 40); keyset cursor = last row's `updated_at` and `id` (both percent-encoded) | `[ServerConversation]` ordered `updated_at DESC, id DESC`. `hasMore = page.size >= limit` | `ChatService.loadConversations`, `.loadMoreConversations` → sidebar (infinite scroll) |
| `GET /api/conversations/search?q=&limit=40` | Bearer | `q` ≥ 2 chars (client rule), percent-encoded | `{q:String, results:[{conversation:ServerConversation, snippet:String, message_id:String?, hits:Int}]}`; matched words wrapped in `⟦ ⟧` in `snippet`. Discard answers whose `q` ≠ current query | `ChatService.searchConversations` → sidebar search |
| `POST /api/conversations` | Bearer | `{maurice_id:String?}` (also `entry` from Carnet — not used by this app) | 201 `ServerConversation` (200 when an empty one held from the same entry is reused); 404 if the domain is not usable | `ChatService.createConversation` → first send / "new with domain" |
| `GET /api/conversations/{id}` | Bearer (participant) | — | `ServerConversationDetail`; 404 → conversation vanished, drop it | `ChatService.loadMessages`, `.reloadActiveMessages`, `.refreshActiveEntry` → chat |
| `PATCH /api/conversations/{id}` | Bearer (creator) | `{title:String}` (non-empty) | `ServerConversation`; 400 `title required`; 404 | `ChatService.renameConversation` → details sheet / sidebar |
| `DELETE /api/conversations/{id}` | Bearer | — | `{ok:true}`; 404 | `ChatService.deleteConversation` (stops a running turn first), `.discardActiveIfEmpty` (auto-deletes an empty, just-created thread the user leaves) |
| `POST /api/conversations/{id}/read` | Bearer | `{}` (app sends `{"":""}`) | `{ok:true}` | `ChatService.selectConversation` |
| `PATCH /api/conversations/{id}/maurice` | Bearer (participant) | `{maurice_id:String \| null}` — **explicit `null`** unbinds (and marks it so the night never re-files it) | `{ok:true}`; 404 | `ChatService.setDomain` → details sheet domain picker, domain page |
| `GET /api/conversations/{id}/tool-families` | Bearer | — | `{selected:[String], all:Boolean, overridden:Boolean}` | `ChatService.conversationFamilies` → Tools sheet |
| `PATCH /api/conversations/{id}/tool-families` | Bearer (creator) | `{families:[String]}` to override; `{families:null}` or `{}` to inherit | `{ok:true}`; 404 | `ChatService.setConversationFamilies` |
| `POST /api/conversations/{id}/participants` | Bearer (participant) | `{member_id:String}` (or `{username}`) | 201 `[ServerParticipant]`; 403 `Not allowed to add this member` (guest allow-list, block); 404 `Unknown member` | `ChatService.addParticipant` → add-participant sheet |
| `DELETE /api/conversations/{id}/participants/{memberId}` | Bearer | — | `[ServerParticipant]` | `ChatService.removeParticipant` |
| `POST /api/conversations/{id}/leave` | Bearer | `{}` | `{ok:true}` | `ChatService.leaveRoom` → details sheet |
| `POST /api/conversations/{id}/reports` | Bearer | `{target_type:"message"\|"member", target_id:String, reason:"spam"\|"harassment_or_bullying"\|"sexual_content"\|"child_safety"\|"other", note:String?}` | 201 `{ok:true, report_id}`; 400 (bad fields, or not a shared room) | `ChatService.report` → ReportSheet |
| `POST /api/conversations/{id}/messages` | Bearer | see 3.1 | NDJSON stream, or `{ok:true, summoned:false}` | `APIClient.streamMessage`, `ChatService.postBubble` → composer |
| `GET /api/conversations/{id}/turn` | Bearer | — | 204, or NDJSON starting with `resume` (3.4) | `APIClient.streamTurn` |
| `POST /api/conversations/{id}/turn/stop` | Bearer | `{}` | `{ok:true}`; 404 `No reply in progress` | `APIClient.stopTurn` → ⏹ |
| `GET /api/conversations/{id}/participants` | Bearer | — | `[ServerParticipant]` | not called (participants come with the detail) |
| WS `/api/conversations/{id}/ws?token=` | token query | — | room events (3.5) | `RoomSocket` |
| WS `/api/me/ws?token=` | token query | — | user events (3.5) | `UserSocket` |
| `GET /api/images/{file}` | none | — | image bytes (png/jpeg) | message photos, generated images |
| `GET /api/avatars/{file}` | none | — | image bytes | avatars everywhere |
| `GET /api/garden-images/{member}/{path}` | none | — | cover art | cards, sources thumbnails (when the server hands such paths) |

### 2.3 Reports (operator moderation)

| Method & path | Auth | Request | Response | Swift caller → screen |
|---|---|---|---|---|
| `GET /api/reports?status=open` | Admin | — | `[ReportView]`, child_safety first: `{id, reporter_member_id, room_id, target_type, target_id, reason, note:String?, status:"open"\|"actioned"\|"dismissed", created_at, reporter_display_name:String?, room_title:String?, reported_message:{id, content, author_id:String?, created_at}?, reported_member:{id, display_name, username}?}` | `ChatService.loadOpenReports` → ReportsReviewView |
| `POST /api/reports/{id}/action` | Admin | `{remove_message:Boolean, eject_member:Boolean}` | plain report row (no hydrated fields); 404 | `ChatService.actionReport` |
| `POST /api/reports/{id}/dismiss` | Admin | `{}` | report row; 404 | `ChatService.dismissReport` |

### 2.4 Models, tool families, domains, reading companions

Vocabulary: one table `maurices` holds the member's **domains** (`kind:"domain"`, parts of their life with a *brief* Maurice keeps) and **reading companions** (`kind:"companion"`, a book followed at the reading position). A conversation is bound to one of them via `maurice_id`; `null` = "everyday Maurice". The Swift type is still called `Maurice`.

```kotlin
@Serializable data class MauriceRow(            // GET/POST/PATCH /api/maurices
    val id: String, val name: String,
    val kind: String = "domain",                // "domain" | "companion"
    val icon: String? = null,                   // SF Symbol name (night's or member's pick)
    val model: String? = null,                  // null = everyday/household default
    val temp: Double = 0.5,
    val thinking: Boolean? = null,              // only meaningful when the model's thinking == "optional"
    val tagline: String = "", val prompt: String = "",
    val users: List<String> = emptyList(),      // member ids allowed (creator by default)
    val context: List<JsonObject> = emptyList(),// frozen composer items WITH `snapshot` (2.5)
    val weight: Int = 0, val count: Int = 0,    // sums of the context snapshots
    val tool_families: List<String>? = null,    // null = inherit
    val created_by: String? = null,
    val created_at: String? = null, val updated_at: String? = null,
)
@Serializable data class ModelInfo(             // GET /api/models
    val id: String, val name: String,
    val tier: String,          // "cloud" | "local"
    val provider: String,      // anthropic | openai | mistral | zai | scaleway | ollama | <plugin>
    val sub: String, val desc: String,
    val note: String,          // "metered" | "private"
    val available: Boolean = true,
    val thinking: String = "none", // "none" | "optional" | "always"
    val is_default: Boolean = false,
)
@Serializable data class ToolFamily(            // GET /api/tool-families
    val id: String, val title: String, val icon: String /* SF Symbol */, val blurb: String,
    val count: Int, val group: String /* core | garden | experimental */, val alwaysOn: Boolean, // camelCase on the wire
)
@Serializable data class Brief(val text: String, val updated_at: String, val sources: List<String> = emptyList() /* conversation ids, "mail:<path>" */,
    val read_until: String? = null, val model: String? = null /* "member" when the member wrote it */)
@Serializable data class SeededNotes(val total: Int, val unreviewed: Int, val web_path: String? = null)
```

Domain proposals (the night groups unattached conversations and mail threads into proposed domains):

```kotlin
@Serializable data class DomainProposal(
    val id: String, val name: String, val summary: String, val one_line: String? = null,
    val state: String,          // proposed | adopted | dismissed | expired | superseded
    val presented: Boolean? = null,
    val verdict: String? = null,// "alive" | "lived" | null
    val conversations: Int, val mail_threads: Int? = null,
    val weight: Int,            // 1–5 dots relative to the biggest
    val share: Int,             // % of the member's conversations
    val recent_90_days: Int? = null, val months_active: Int? = null,
    val split_hint: String? = null,
    val from: String? = null, val to: String? = null,   // YYYY-MM-DD
    val domain_id: String? = null,                     // once adopted
    val seed: JsonObject? = null,                      // {state:"written"|"declined", at, notes?}
    val origin: String? = null,                        // mapping | split | model_split | member | merge
    val is_new: Boolean? = null,
    val sample: List<String>? = null,                  // "2026-01-02 — title" (or "… (mail)")
    val created_at: String? = null, val updated_at: String? = null,
)
// GET /api/domains/proposals/:id → {proposal: DomainProposal + conversations_list:[{id, date, title}] + mail_list:[{path, from, to, title}]}
```

| Method & path | Auth | Request | Response | Swift caller → screen |
|---|---|---|---|---|
| `GET /api/models` | Bearer | — | `[ModelInfo]` filtered to configured providers and the member's allowed models | `MauriceStore.loadModels` → model pill, domain editor |
| `GET /api/models/everyday` | Bearer | — | `{id:String}` (member's choice or household default) | `MauriceStore.loadEverydayModel` |
| `PUT /api/models/everyday` | Bearer | `{id:String\|null}` | `{id}`; 403 `model not allowed` | `MauriceStore.setModel` (everyday) → composer model pill |
| `GET /api/tool-families` | Bearer | — | `[ToolFamily]` (`web` first; `web`, `corpus` always on; `email` core+alwaysOn once a mailbox exists) | `MauriceStore.loadFamilies` → domain editor, tools sheet |
| `GET /api/maurices` | Bearer header (cookie not accepted) | — | bare array `[MauriceRow]` sorted by name (own rows; guests: rows granted to them) | `MauriceStore.loadMaurices` → everywhere |
| `POST /api/maurices` | Bearer | `{name (required), kind, model, temp, thinking, tagline, prompt, context:[ComposerItem], tool_families, icon?}` (app sends `model`/`thinking`/`tool_families` as explicit null when unset; `icon` only when set) | 201 `MauriceRow`; 400 `name required` / `{error:"invalid context", details:[{index, message}]}`; **409 `{error:"domain_name_taken", existing:{id, name}}`** (case/accent-insensitive) | `MauriceStore.save` → DomainEditor |
| `PATCH /api/maurices/{id}` | Bearer (creator) | any subset of the POST fields (`{"model": id}` from the model pill, `{"kind": …}` to re-sort) | 200 `MauriceRow`; 400; 404; 409 as above | `MauriceStore.save`, `.setModel`, `.setKind` |
| `DELETE /api/maurices/{id}` | Bearer (creator) | — | `{ok:true}` (its conversations become everyday) | `MauriceStore.delete` |
| `GET /api/domains` | Bearer | — | `{domains:[{id, name, tagline, kind, icon, created_by, mine:Boolean, count, weight, brief:{updated_at, model, sources:Int}?, notes:SeededNotes?}], companions:[{id, name, tagline, kind:"companion", created_by, mine, book_id, conversation_id:String?}], proposals:{open:Int, unseen:Int}}` | `MauriceStore.loadOverview` → domains list |
| `GET /api/domains/{id}/brief` | Bearer (own domain) | — | `{domain:{id, name, tagline}, brief:Brief?, notes:SeededNotes?}`; 404 (`A reading companion has no brief` / `Not found`) | `MauriceStore.loadBrief` → DomainBriefSheet |
| `PUT /api/domains/{id}/brief` | Bearer | `{text:String}` (empty = erase) | `{brief:Brief?}` (model becomes `"member"`) | `MauriceStore.saveBrief` |
| `DELETE /api/domains/{id}/brief` | Bearer | — | `{erased:Boolean}` | `MauriceStore.deleteBrief` |
| `POST /api/domains/{id}/brief/refresh` | Bearer | — (no body) | `{outcome:"written"\|"unchanged"\|"capped"\|"failed", sources:Int, cost_usd:Double? (euros), error:String?, brief:Brief?}` with status 200 (written/unchanged), **429** (capped), **502** (failed). Synchronous (one model call, seconds) | `MauriceStore.refreshBrief` → "Rewrite now" |
| `GET /api/domains/{id}/conversations?limit=100[&auto=1]` | Bearer | `limit` 1–200 (default 50); `auto=1` only night-filed | `{conversations:[{id, title, bound_by:"auto"\|null, bound_at, origin, updated_at}], total:Int, auto:Int}` | `ChatService.domainConversations` → brief sheet |
| `GET /api/domains/proposals` | Bearer | — | `{total_conversations:Int, seen_at:String?, unseen:Int, proposals:[DomainProposal] (open), settled:[DomainProposal]}` | `ChatService.loadProposals` (with every list load) → domains button badge, list |
| `POST /api/domains/proposals/seen` | Bearer | `{}` (app sends `{"":""}`) | `{open:Int, unseen:Int}` | `ChatService.markProposalsSeen` → opening the list |
| `POST /api/domains/proposals/apply` | Bearer | `{items:[{id, action:"adopt"\|"dismiss"\|"keep", name:String?, summary:String?, seed:Boolean}]}` (`keep` = rename only) | `{adopted:[{id, name, domain_id, conversations_bound, seeding}], dismissed:[{id, name}], renamed:[{id, name}], errors:[{id, error}]}` | `ChatService.applyProposals` → ProposalPage (then reloads proposals + conversations) |
| `GET /api/domains/proposals/{id}` | Bearer | — | `{proposal: Detail}` | `ChatService.proposalDetail` → ProposalPage |
| `POST /api/domains/proposals/{id}/restore` | Bearer | `{}` | `{proposal}`; 409 when not dismissed/expired | `ChatService.restoreProposal` |
| `POST /api/domains/proposals/merge` | Bearer | `{ids:[String] (≥2 open), name:String?}` | `{proposal, from:[String]}`; 422 | `ChatService.mergeProposals` |
| `POST /api/domains/proposals/{id}/split` | Bearer | `{parts:[{name, conversation_ids:[String], mail:[String]}]}` (app sends one part) | `{parts:[Detail], left:Int, original:Detail?}`; 409/422 | `ChatService.splitProposal` |

### 2.5 Composer context, books, articles

The **context tray** is a per-conversation list of things Maurice loads with every turn of that conversation. The server owns resolution and weighing; the app edits the list, asks the server to weigh it after each change, then saves it (`PUT`). Budget `200 000` tokens (estimated as chars/4). Tier from `f = total / 200000`: `warn` (f ≥ 1), `caution` (≥ 0.55), `inkSoft` (≥ 0.18), `light`; `over = total > 200000`. A domain's own baked-in context (`MauriceRow.context`) is shown read-only and its `weight` is added to the displayed total.

Item JSON sent by the app (`TrayItem.payload()`):

| `type` | Shape |
|---|---|
| `note` | `{type:"note", id:<slug>, recurse:Boolean (default = note is a MOC), include_archived:Boolean, exclude:[slug]}` |
| `book` | `{type:"book", id:<Int calibre id>, representation:"summary"\|"full" (forced "summary" when mode = progress), scope:{mode:"all"} \| {mode:"up_to", chapter:Int (visible index, inclusive)} \| {mode:"chapters", refs:[String]} \| {mode:"progress"}}` |
| `fiche` | `{type:"fiche", id:"<collection>/<locale>/<slug>", include_fragments:Boolean}` |
| `conversation` | `{type:"conversation", id}` plus `representation:"full"` only when pinned to the full transcript |
| `file` | `{type:"file", id}` |
| `folder` | `{type:"folder", id, recurse:Boolean (default true), exclude:[id]}` |

Sending an option a type does not take (e.g. `recurse` on a book) is a 400 `{error:"invalid items", details:[{index, message}]}`.

| Method & path | Request | Response | Swift caller → screen |
|---|---|---|---|
| `POST /api/v1/composer/weigh` | `{items:[Item]}` | `{items:[Weighed], total:Int, budget:200000, over:Boolean, tier:String}`; `Weighed` = `{type, id, title, weight}` + per type: note `moc, count, encrypted, archivedWithheld, heavy, missing?`; book `count, visibleCount, representation, progressChapter? (-1 = nothing read), progressPaused?, heavy, missing?`; conversation `count, representation, fullWeight, summarisable, summary:"ready"\|"stale"\|"pending"\|"none", uncovered?, heavy, missing?`; file `kind, na (binary → weight 0), path, missing?`; fiche `count, fragments, missing?`; folder `count, binaryCount, heavy, missing`. Items come back **in request order** (the app matches by index). Weighing a long conversation starts a background summary | `ComposerStore.reweigh` → context tray |
| `GET /api/v1/composer/context/{conversationId}` | — | `{items:[Item + snapshot], resolved_at}`; 403 not a participant. `snapshot` = `{weight, count, resolved_at}` + note `slugs, encrypted, archivedWithheld`; book `refs, representation, tracksProgress?, progressChapter?`; fiche `fragments`; file `kind, na, binaryIds`; folder `textIds, binaryIds`; conversation `representation, fullWeight, summary` | `ComposerStore.loadSpec` (on conversation change) |
| `PUT /api/v1/composer/context/{conversationId}` | `{items:[Item]}` | `{spec:{items, resolved_at}, total, budget, over, tier}`; 400; 403 | `ComposerStore.save` (after every edit) |
| `GET /api/v1/composer/conversations/{id}/summary?wait=1` | — (long-poll until the summary exists; ≤ 255 s server idle timeout) | `{representation, weight, fullWeight, count, hash, summarisable, summary, uncovered?, threshold:8000}` | `ComposerStore.awaitSummary` (when a conversation item is `pending`/`stale`, then reweigh + save) |
| `GET /api/v1/composer/notes/{slug}/resolve?recurse=true\|false&include_archived=true\|false&exclude=a,b` | — | `{id, recurse, resolved:{count, weight, encrypted, archivedWithheld, manualExcluded, hasChildren, slugs}, tree:Node}`, `Node = {id, title, moc, archived, encrypted, missing?, weight, childCount, included, excluded, children:[Node]}`; 404 | `ComposerStore.resolveNote` → note card tree preview |
| `GET /api/v1/composer/folders/{id}/resolve?recurse=&exclude=` | — | `{weight, count, textCount, binaryCount, title, hasChildren, manualExcluded, includedTextIds, includedBinaryIds, tree:FileNode}`, `FileNode = {id, name, isFolder, kind, weight, na, childCount, included, excluded, children}`; 404 | `ComposerStore.resolveFolder` → folder card |
| `GET /api/v1/composer/books/{id}/chapters` | — | `{id, title, authors:[String], chapters:[{index, ref, name, section_type:"front_matter"\|"body"\|"back_matter", hidden, word_count, has_summary, summary_tok, full_tok}], visible_count, coverage:{summarized, total}}`; 400; 404 (no library) | `ComposerStore.bookChapters` (cached) → book card |
| `GET /api/v1/composer/search?q=` | `q` (empty = everything), `limit` default 40 | `{q, results:[{type, id (Int for books, String otherwise), title, sub, badges, score, updated_at:ISO?}]}`; `badges`: note `{moc, archived, encrypted}`, fiche `{collection, fragments}`, book `{author, coverage}`, conversation `{date, hits?}`, file/folder `{kind}` | `ComposerStore.search` → AddContextSheet, domain editor |
| `POST /api/v1/calibre/books/{id}/reading-progress/tracking` | `{paused:Boolean}` | bookmark row `{member_id, book_id, chapter_index, chapter_slug, view, enabled:0\|1, position, updated_at}` | `ComposerStore.setReadingPaused` → book card (progress mode) |
| `DELETE /api/v1/calibre/books/{id}/reading-progress` | — | `{ok:true}` | `ComposerStore.resetReadingProgress` |
| `POST /api/v1/articles/read` | `{url:"http(s)://…", html:String?}` (≤ 8 MiB; without html the server fetches the URL) | `{url, title, site_name, author, published_at, lang, text, word_count}`; 400; 413; 422 (paywall/bot check/no text); 500 | `ChatService.readSharedArticle` → share flow (section 6) |

All `/api/v1/*` routes answer 401 `{"error":"Authentication required"}` without credentials.

### 2.6 Carnet suggestions, life facts, people review

**Carnet suggestions**: after each reply in a 1:1 conversation, the server names the works/people the exchange was about; they wait behind a mark in the conversation header until the member keeps (files into the garden) or dismisses them.

```kotlin
@Serializable data class EntrySuggestion(
    val id: String, val conversation_id: String? = null, val message_id: String? = null,
    val kind: String,          // movies | series | books | music | podcasts | games | people (or the bound entry's collection)
    val title: String, val year: Int? = null, val subtitle: String? = null, val image: String? = null,
    val note: String,          // what will be filed; empty for a shared article
    val state: String,         // proposed | kept | dismissed
    val existing: Boolean,     // already an entry: keeping only adds the note
    val bound: Boolean? = null, val links: Boolean? = null,
    val candidates: List<Candidate> = emptyList(), // >1 = must pick one to keep
    val query: String? = null, val searchable: Boolean? = null,
    val entry: String? = null,       // "<collection>/<locale>/<slug>" once kept
    val web_path: String? = null,    // "/g/<member>/…"
) { @Serializable data class Candidate(val id: String, val title: String, val year: Int? = null,
        val subtitle: String? = null, val image: String? = null, val track: String? = null) }
```

| Method & path | Auth | Request | Response | Swift caller → screen |
|---|---|---|---|---|
| `GET /api/suggestions?conversation={id}[&settle=1]` | Bearer | `settle` waits ≤ 20 s for the running pass (unused by the app) | `{entry:String?, suggestions:[EntrySuggestion], pending:Int, kept:Int}`; 404 | `SuggestionsDrawer.load` (full), `ChatService.refreshSuggestionCount` (reads only `pending`/`kept`) → header mark, drawer |
| `POST /api/suggestions/{id}/keep` | Bearer | `{candidate:String?, episodes:[String]}` (timeout 40 s) | `EntrySuggestion`; 400/404/409/422/502 `{error}` | `SuggestionsDrawer.keep` |
| `GET /api/suggestions/{id}/episodes[?feed=<candidate id>]` | Bearer | — (timeout 40 s) | `{feed, episodes:[{id, title, date:ISO?, description, image, duration:Int? (s)}], suggested:String?}` | `SuggestionsDrawer.episodes` → EpisodePicker (podcasts) |
| `POST /api/suggestions/{id}/search` | Bearer | `{query:String?}` (null = let Maurice rephrase) | `EntrySuggestion` + `found:Int` | `SuggestionsDrawer.search` |
| `POST /api/suggestions/{id}/dismiss` | Bearer | `{}` | `EntrySuggestion` | `SuggestionsDrawer.dismissRow` |

Changes are also announced on the user WebSocket (`suggestions` event, 3.5).

**Life facts and people review:**

| Method & path | Auth | Request | Response | Swift caller → screen |
|---|---|---|---|---|
| `POST /api/life-facts/{id}/keep` | Bearer | `{}` | `{id, text, state:"kept", conversation_id:String?, created_at, decided_at}`; 404 `not found` | `LifeFactCard.decide` → `fact` card |
| `POST /api/life-facts/{id}/dismiss` | Bearer | `{}` | same, `state:"dismissed"` | idem |
| `GET /api/people/{locale}/{basename}` | Bearer, not guest | `basename` matches `^[a-z0-9]+(-[a-z0-9]+)*-fiche$` | `PersonView` (below); 400 `not a fiche`; 404 `no such fiche` / `No notes`; 409 `the fiche cannot be read`; 403 guests | `PersonReviewView.load` → person review |
| `POST /api/people/{locale}/{basename}/review` | Bearer, not guest | `{target:"fiche"\|"relation"\|"proposal"\|"identity"\|"fragment"\|"all", action:"confirm"\|"reject"\|"edit", id:String?, text:String?}` — `identity` id = e-mail address, `fragment` id = 3-digit number, `edit` needs `text` (not for fiche/proposal/identity), `all` only `confirm`, `fiche`+`reject` only for a fiche Maurice made | `{ok:true, view:PersonView \| null}` (null = the fiche was deleted → close the screen); 400/404/409 `{error}` | `PersonReviewView.act` |
| `GET /api/people/mail/{id}` | session (opened in the **browser** via `/login?to=`) | — | **HTML page** of the e-mail (not JSON) | `ficheLink` (`maurice-mail:` links) |

```kotlin
@Serializable data class PersonView(
    val title: String, val locale: String, val basename: String,
    val status: String,                 // "pending" | "confirmed" | "rejected"
    val byMaurice: Boolean,             // camelCase on the wire!
    val relation: Relation,
    val identities: List<Identity>,
    val fragments: List<Fragment>,
    val exchanges: String? = null,      // markdown
    val pending: Int,
) {
    @Serializable data class Relation(val text: String? = null, val status: String? = null, val since: String? = null,
        val until: String? = null, val edited: Boolean? = null, val proposed: Proposed? = null)
    @Serializable data class Proposed(val text: String, val since: String? = null, val until: String? = null)
    @Serializable data class Identity(val address: String, val mailboxes: List<String>, val status: String,
        val source: String, val conflict: String? = null)
    @Serializable data class Fragment(val id: String, val summary: String, val status: String, val origin: String? = null,
        val address: String? = null, val mailbox: String? = null, val body: String, val edited: Boolean? = null)
}
```

### 2.7 Settings: files, mail, contacts, import, my data

**Files library** (`/api/files`, any member incl. guests server-side; the app hides it for guests)

```kotlin
@Serializable data class LibraryFolder(val id: String, val parent_id: String? = null, val name: String, val created_at: String? = null)
@Serializable data class LibraryFile(val id: String, val folder_id: String? = null, val name: String,
    val kind: String /* "text" | "img" | "pdf" | "file" (from extension) */, val size_bytes: Long,
    val token_estimate: Int? = null /* text only: ceil(utf8 bytes / 4) */, val created_at: String? = null)
@Serializable data class LibraryResponse(val folders: List<LibraryFolder>, val files: List<LibraryFile>,
    val summary: Summary) { @Serializable data class Summary(val count: Int, val size_bytes: Long, val quota_bytes: Long /* 5 GiB, informational */) }
```

| Method & path | Request | Response | Swift caller → screen |
|---|---|---|---|
| `GET /api/files` | — | `LibraryResponse` (flat lists, sorted by name; build the tree client-side) | `SettingsView.loadFiles`, `FilesLibraryView.load` → Settings index, Files pane |
| `POST /api/files/upload` | multipart: `file` (required), `folder_id` (optional; empty = root), `name` (optional override) | 200 `LibraryFile`; 400 `file required` | `FilesLibraryView.upload`, `ComposerStore.uploadFile` (→ then added to the context tray) |
| `POST /api/files/folder` | `{parent_id:String?, name:String}` (app always sends `parent_id:null`) | `LibraryFolder`; 400 `name required` | `FilesLibraryView.createFolder` |
| `PATCH /api/files/folder/{id}` | `{name?:String, parent_id?:String?}` | `LibraryFolder`; 404 | `FilesLibraryView.commitRename` |
| `DELETE /api/files/folder/{id}` | — | `{ok:true}` (recursive) | `FilesLibraryView.deleteFolder` |
| `PATCH /api/files/{id}` | `{name?:String, folder_id?:String?}` | `LibraryFile`; 404 | `FilesLibraryView.commitRename` |
| `DELETE /api/files/{id}` | — | `{ok:true}` | `FilesLibraryView.deleteFile` |
| `GET /api/files/{id}/raw` | — | raw bytes, `Content-Disposition: inline` | not called by the app |

**Mailboxes** (`/api/mail-accounts`, Bearer header required — a cookie is not enough)

```kotlin
@Serializable data class MailAccountRow(val id: String, val address: String, val name: String? = null,
    val provider: String? = null, val host: String? = null, val port: Int? = null,
    val security: String? = null /* "tls" | "starttls" */, val username: String? = null,
    val state: String /* "unchecked" | "ok" | "error" */, val last_error: String? = null,
    val checked_at: String? = null, val created_at: String? = null)

@Serializable data class MailScanStatus(
    val state: String,          // "running" | "paused" | "done" | "failed" | "idle" | "none" (no mailbox)
    val running: Boolean,       // poll GET /scan every 5 s while true
    val messages: Int, val seen: Int,
    val locations: Int? = null, val written: Int? = null, val job_id: String? = null,
    val started_at: String? = null, val updated_at: String? = null,   // ISO with offset
    val last_error: String? = null, val error: String? = null,
    val reading: Reading? = null,              // null = never asked
    val mailboxes: List<MailboxStatus>? = null,
    val documents: Documents? = null,          // null when no pass is running
    // extra keys merged in by some POSTs: decision, window, depth, started (see below)
) {
    @Serializable data class Reading(val state: String /* approved|declined|running|paused|done|failed */,
        val decided_at: String? = null, val years: Int? = null /* 50 = everything */, val job_id: String? = null)
    @Serializable data class Documents(val stage: String /* preparing|people|threads|finishing */,
        val done: Int, val total: Int, val written: Int, val started_at: String? = null)
}
@Serializable data class MailboxStatus(val address: String, val messages: Int, val untriaged: Int,
    val reading: MailboxReading, val estimate: MailboxEstimate? = null /* null when < 20 left */,
    val top_senders: List<MailboxSender>? = null /* ≤ 5 */, val approved: Boolean? = null)
@Serializable data class MailboxReading(val window: Int, val to_light: Int, val kept: Int, val skipped: Int, val to_read: Int, val read: Int)
@Serializable data class MailboxEstimate(val to_sort: Int, val to_read: Int, val hours: Double,
    val euros: Double? = null /* admins only — never show prices to members */, val basis: String /* history|formula */, val pending: Boolean)
@Serializable data class MailboxSender(val sender: String, val messages: Int, val set_aside: Int, val rule_days: Int? = null)
```

| Method & path | Request | Response | Swift caller (all in `SettingsView.MailPane` unless noted) |
|---|---|---|---|
| `GET /api/mail-accounts` | — | `{accounts:[MailAccountRow]}` | `SettingsView.loadMailAccounts`, `MailPane.load` |
| `POST /api/mail-accounts` | `{address, password, provider?:"gmail" (Workspace), host?, port?:Int, username?}` (app sends host/port/username only for unknown providers) | 201 `MailAccountRow` (state ok; header scan starts in background → poll `/scan`); 400 validation; 409 already added; **422 `{error, detail}`** login refused (account not kept) | `add` |
| `POST /api/mail-accounts/{id}/check` | `{}` | 200 `MailAccountRow` (a failed login is still 200 with `state:"error"`); 404 | `check` |
| `PUT /api/mail-accounts/{id}/password` | `{password}` | 200 row; 400; 404; 422 `{error, detail}` (old password restored) | `renew` |
| `POST /api/mail-accounts/{id}/forget` | `{history:Boolean}` | `{address, gone, kept, changed, removed, history:"kept"\|"rewritten"\|<error text>}`; 404; 422 | `forget` |
| `DELETE /api/mail-accounts/{id}` | — | `{ok:true}`; 404 | `remove` |
| `GET /api/mail-accounts/scan` | — | `MailScanStatus` (always 200; errors inside) | `loadScan` (polled every 5 s while `running`) |
| `POST /api/mail-accounts/scan` | `{}` | `MailScanStatus` (starts or joins the header walk) | `startScan` |
| `POST /api/mail-accounts/reading` | `{action:"approve"\|"decline"}` | `MailScanStatus` + `decision:{reading, already, job_id, decided_at}`; 400; 422. Does **not** start reading | `decideReading` |
| `POST /api/mail-accounts/reading/run` | `{}` | `MailScanStatus` + `started:Boolean`; 409 `the reading has not been approved` | `readNow` |
| `POST /api/mail-accounts/reading/window` | `{years:Int ≥ 1}` (capped 50 = all) | `MailScanStatus` + `window:{years, previous, changed, job_id}` + `started`; 400; 409; 422 | window picker |
| `POST /api/mail-accounts/{id}/reading` | `{}` | `MailScanStatus` + `started:true, running:true`; 404; 422 | approve one mailbox |
| `PUT /api/mail-accounts/senders` | `{address:String, days:Int?}` (0–36500; null deletes the rule) | `MailScanStatus` (after re-triage); 400 | sender rule |
| `POST /api/mail-accounts/reading/depth` | `{depth:"all"}` | view + `depth` + `started` | not called by the app |

**Address books** (`/api/contact-accounts`, Bearer header required)

```kotlin
@Serializable data class ContactAccountRow(val id: String, val url: String? = null, val username: String,
    val name: String? = null, val provider: String? = null, val state: String, val last_error: String? = null,
    val checked_at: String? = null, val synced_at: String? = null, val cards: Int, val created_at: String? = null)
```

| Method & path | Request | Response | Swift caller (`SettingsView.ContactsPane`) |
|---|---|---|---|
| `GET /api/contact-accounts` | — | `{accounts:[ContactAccountRow]}` | `SettingsView.loadContactAccounts`, `load` |
| `POST /api/contact-accounts` | `{username, password, url:String?}` (url required unless iCloud/Fastmail/Mailfence; https only) | 201 row; 400; 409; 422 `{error, detail?}` (Google unsupported, or read failed — not kept) | `add` |
| `POST /api/contact-accounts/{id}/sync` | `{}` | 200 row (failure = 200 with `state:"error"`); 404 | `sync` |
| `PUT /api/contact-accounts/{id}/password` | `{password}` | 200 row; 400; 404; 422 | `renew` |
| `DELETE /api/contact-accounts/{id}` | — | `{ok:true}`; 404 | `remove` |

**Import conversations from ChatGPT / Claude** (guests: 403 `guests_cannot_import`)

| Method & path | Request | Response | Swift caller → screen |
|---|---|---|---|
| `POST /api/import?provider=anthropic\|chatgpt` | multipart `file` = the provider's export `.zip` (can be hundreds of MB; client timeout 180 s idle) | `{job_id:"job_…", provider}`; 400 `bad_provider`/`bad_form`/`no_file`/`not_zip` (`{error, detail}`); 502 `corpus_unreachable` | `ImportProviderCard.upload` → Import pane |
| `GET /api/import/status?job=` | — | `{job_id, member_id, provider, phase:"parsing"\|"indexing"\|"done"\|"error", done:Int, total:Int, status:"running"\|"done"\|"failed", result?:{conversations, messages, range_from, range_to}, error?:String}`; unknown job = **200** `{error:"unknown job", job_id}` | `ImportProviderCard.poll` (every 1.5 s, up to 2000 times) |
| `GET /api/import/history?provider=` | — | `{member_id, provider, watermark:String?, history:[{id, provider, range_from:String?, range_to:String?, conversations:Int, messages:Int, ran_at, status:"done"\|"partial"\|"failed"}]}` | `ImportConversationsView.load`, `ImportSummary.load` → Settings index + Import pane |

**My data** (`/api/me`; import and erase refuse `maur_` tokens with 403 `session_required`)

| Method & path | Request | Response | Swift caller (`MyDataView`) |
|---|---|---|---|
| `GET /api/me/export` | — | stream `application/gzip`, `Content-Disposition: attachment; filename="<slug>-YYYYMMDD-HHMMSS.maurice-member.tar.gz"`; 500 `{error}` | `export` (download, timeout 1800 s, then "save to Files") |
| `POST /api/me/import` | multipart `file` = such an archive | `{from:String, created_at, report:{conversations, messages, rows, files, images, notes, skipped}}`; **409** already imported into this account; 400 `bad_form`/`no_file`/archive errors; 500 | `upload` |
| `POST /api/me/erase` | `{scope:"data"\|"account", confirm:<username>, password:String?, pin:String?}` (app puts the typed secret in **both** `password` and `pin`) | `{scope, conversations, handed_over, messages, rows, files, images, garden:Boolean, mail:Boolean, corpus:Boolean, snapshots:{rewritten, removed}, log_lines, residual:{garden_remotes:[String], published_site:Boolean, household_archives:Int, shared_rows:Int, offsite_backups:true}}`; errors with the **code as the `error` string**: 400 `bad_scope`, 403 `confirm_mismatch`/`bad_password`/`bad_pin`, 429 `too_many_attempts`, 409 `last_admin`, 404 `not_found`. App maps `error` to the localized key `mydata.error.<code>` | `EraseConfirmSheet.erase` (timeout 600 s); scope `account` → log out locally |
| `GET /api/me/git` | — | `{url:"<origin>/git/<username>.git", username, token:"maur_…" (scope git), export_conversations:Boolean}`; 404 `no_user`/`no_garden` | `loadGit(rotate:false)` |
| `POST /api/me/git/rotate` | `{}` | same, new token | `loadGit(rotate:true)` |
| `PUT /api/me/git/export` | `{conversations:Boolean}` | `{export_conversations:Boolean}`; 400 `conversations_boolean` | `setExportConversations` |

### 2.8 Dictation

| Method & path | Request | Response | Swift caller |
|---|---|---|---|
| `GET /api/dictation` | — | `{available:Boolean, engine:"local"\|"scaleway"\|null, max_seconds:600}` | `HouseholdTranscription.capability` |
| `POST /api/dictation?duration=<s>` | raw audio body, `Content-Type: audio/mp4` | `{text:String, engine, seconds:Double?}`; 400/402/413/502/503/500 `{error}` | `HouseholdTranscription.transcribe` (section 4) |

### 2.9 Web gardens (sidebar GARDENS section, garden page)

A member's notes are published as a private website (`/g/<username>/`); notes can be shared per note with other members, and a "shared garden" is the derived set of notes shared with the same people. In-app the app only lists and manages; reading happens in the browser via `/login` (1.8).

```kotlin
@Serializable data class ServerGarden(
    val id: String,              // audience key: sorted member ids joined by "+"; your own garden = your id
    val mine: Boolean,
    val members: List<GardenMember>,
    val web_theme: String,       // default "manuscript"
    val web_path: String,        // "/g/<me>/" or "/gardens/<urlencoded key>"
    val notes: List<GardenNote>, // newest first
)
@Serializable data class GardenMember(val member_id: String, val username: String? = null, val display_name: String,
    val avatar_color: String, val avatar_url: String? = null)
@Serializable data class GardenNote(val owner_id: String, val owner_username: String, val slug: String, val locale: String,
    val title: String, val updated_at: String? = null, val web_path: String,
    val kind: String? = null /* note | fiche | card */, val collection: String? = null,
    val unreviewed: Boolean? = null /* written by Maurice, not reviewed */)
// identity of a note row: "$owner_id/${kind ?: "note"}/$slug" (a note and a card may share a slug)
```

| Method & path | Request | Response | Swift caller → screen |
|---|---|---|---|
| `GET /api/v1/gardens` | — | `{gardens:[ServerGarden]}` (own garden first; guests get `[]`) | `GardensStore.load` → sidebar GARDENS, garden page |
| `PATCH /api/v1/gardens/{id}` | `{web_theme:String}` (id URL-path-encoded; `+` in ids) | `{ok:true, id, web_theme}`; 400; 403 | `GardensStore.setTheme` (for one's own garden it also saves `maurice.prefs.<uid>.webTheme` locally and calls `PATCH /api/users/me/preferences`, whose body carries only `palette`/`locale` — the web theme is not part of it) |
| `GET /api/v1/gardens/note/{ownerId}/{slug}/access` | — | `{owner_id, slug, title, members:[{member_id, username, display_name, avatar_color, avatar_url, tends:Boolean, is_owner, is_self}]}`; 400/403/404 | `GardensStore.noteAccess` → NoteAccessSheet |
| `POST /api/v1/gardens/note/{ownerId}/{slug}/share` | `{member_id}` | `{ok:true}`; 400/403/404 | `GardensStore.share` |
| `POST /api/v1/gardens/note/{ownerId}/{slug}/leave` | `{}` | `{ok:true}`; 400 (owner cannot leave) / 403 | `GardensStore.leave` |
| `GET /login?token=<maur_>&theme=&to=` | browser | Accepts **only `maur_` API tokens** (scope mcp/full; a session token gives a 400 HTML page). Sets cookie `maurice_session=<maur_ token>; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000` (30 days), then 302 to `to` (a path starting with a single `/`; `?theme=` appended if `to` has no query) or to `/g/<username>/` (+`fr/` for French members) `?theme=`. **Caveat**: a browser already holding a stale/revoked `maurice_session` cookie gets a JSON 401 before the handler runs | `GardensStore.open`, `SettingsView.openGarden/openTokenLogin`, `openInGarden`, `ChatView.openSignedIn` |
| `GET /api/web-themes` | — | `{themes:[{id, name, description?}]}` | Settings garden pane, garden page |

---

## 3. Chat streaming in detail

### 3.1 Starting a turn

```
POST /api/conversations/{id}/messages
Authorization: Bearer …
Content-Type: application/json

{ "content": "texte du membre",
  "summon": true,                    // ➤ send: Maurice answers
  "maurice_id": "<domain id>" | null,// ALWAYS present: the armed domain/companion; null = everyday Maurice
  "image": "data:image/jpeg;base64,…",        // exactly one photo
  "images": ["data:image/jpeg;base64,…", …],  // two or more photos (max 10)
  "regenerate": true }               // only for "answer again"; then content = "" and no images
```

Rules:

- The conversation must exist first: if none is selected, the app first `POST /api/conversations {"maurice_id": <armed or null>}` (201, or 200 when Carnet reuses an empty one) and uses the returned `id`.
- One photo goes as `image`, several as `images` (older servers only read `image`). Photos are re-encoded client-side to JPEG quality 0.85, long edge ≤ 1568 px and ≤ 1568 visual tokens (`ceil(w/28)*ceil(h/28)`), then sent as `data:image/jpeg;base64,…` (`ChatView.normalizeToJPEG`). The server stores each and prefixes the stored message with `![photo](/api/images/<file>)` lines.
- `maurice_id` arms the conversation (server calls `setConversationMaurice`) and selects who answers. 404 if the member may not use that domain.
- `content` may embed **fenced snippets** that the app renders as cards (section 3.7). The server also requires `content.trim()` non-empty or at least one image (400 `content required`).
- **💬 "bubble" post** (talk to the humans of a shared room without Maurice): same route with `{"content", "summon": false, "image"/"images"}` — the server answers plain JSON `{"ok": true, "summoned": false}`, **not** a stream (`ChatService.postBubble`).
- In a multi-member room, an old client omitting `summon` is summoned only by `@maurice`/`@claude`; the app always sends `summon` explicitly.

Optimistic UI: before the request the app appends a local user message with a random UUID, `author_id` = active member, content = the text preceded by one `![photo](pending)` line per photo. It is reconciled when the server echoes the stored message over the room WebSocket (3.5) — the last local user message of this author whose id is not yet server-known is replaced.

Responses before the stream starts:

| Status | Body | Meaning / app behaviour |
|---|---|---|
| 200 | `application/x-ndjson` stream | the turn runs |
| 200 | `{"ok":true,"summoned":false}` | non-summon post (no stream) |
| 400 | `{"error":"content required"}` / `{"error":"at most 10 images per message"}` | shown |
| 404 | `{"error":"Not found"}` | conversation unknown / not a participant / domain not usable |
| 409 | `{"error":"A reply is already in progress"}` | a turn is already running in this conversation (another device, or a regenerate during a reply). The user message **is stored anyway** (non-regenerate case). The app drops its optimistic copy, shows `chat.error.busy`, and **re-attaches** to the running turn with `GET /turn` (3.4). |

### 3.2 The NDJSON events

Response headers: `Content-Type: application/x-ndjson`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`. One JSON object per line (`\n`), UTF-8. Blank lines are skipped; a line that does not decode is ignored. Every event has `type`; other fields depend on it. Unknown `type` values must be ignored (the Swift decoder drops the line).

| `type` | Fields | Meaning | App handling (`ChatService.render`) |
|---|---|---|---|
| `text_delta` | `text: String` | next chunk of the visible reply (server batches deltas every ~30 ms) | `streamingText += text`; clears the "Thinking" phase |
| `thinking` | — | a reasoning model is thinking (≤ 1 per second) | show "Thinking…" in the activity line if no tool is running |
| `ping` | — | keepalive after 15 s of silence; also on WebSockets | ignore (resets idle timers) |
| `tool_call` | `tool: String`, `status: "start" \| "end"` | a tool started / ended. MCP tools are named `server__tool` (e.g. `garden__read_note`, `corpus__search`); built-ins like `web_search` | activity line: fold by tool name, count repeated calls (`TurnActivity.begin/end`) |
| `tool_data` | `tool: String`, `data: JSON (any)` | the raw structured result of a tool, rendered beside the prose (ground truth) | append `DataBlock(tool, data)` to `streamingData` (section 3.6) |
| `image_loading` | `text: String` (the prompt) | an image is being generated/edited; repeated every 15 s as keepalive | clear text, show a generating placeholder |
| `image` | `image_url: String` (e.g. `/api/images/abc.png`), `text: String` (prompt) | the generated image | text becomes `![prompt](image_url)` |
| `usage` | `usage: TurnUsage` | cost of the turn, once, just before the end | keep for the cost footer |
| `done` | `message_id: String` | **terminal**: the reply is persisted under this id | stop reading; finalise (3.3) |
| `error` | `message: String` | **terminal for the app**: provider failure, budget refusal, image failure… | stop reading, show `message` in the red banner |
| `resume` | see 3.4 | first line of a re-attached stream only | replace the streaming state with the snapshot |

```kotlin
@Serializable data class TurnUsage(
    val provider: String, val model: String, val rounds: Int,
    val input: Int, val output: Int, val cache_read: Int, val cache_write: Int,
    val cost: Double? = null,           // EUROS despite the server comment saying USD (prices are in EUR); null = no price on file
    val cost_uncached: Double? = null,
)
```

Typical order: `thinking*` → (`tool_call start` → `tool_call end` → `tool_data`?)* interleaved with `text_delta*` → [`image_loading`… `image`] → [`tool_data` (tool `"review"`) + `text_delta` (the review footer)] → `usage` → `done`. Server details that matter:

- The server ignores the provider's own `done` and sends its own `done` **after** persisting the reply; the persisted message is published on the room WebSocket **before** `done` is written, so the client may receive the WS `message` first (dedupe by id).
- A provider `error` is forwarded as `error`; the server may still persist what streamed and later send `done` on the same stream — the Swift app stops reading at the first `error` (it shows the error; the persisted message then arrives over the room socket or on the next reload).
- **A turn that leaves nothing to store ends with `error`, never `done`** (server change of 21 Oct 2026): the model came back without a word, or ⏹ arrived before the first one. `{"type":"error","message":"…"}` in the member's language (empty reply: « Maurice est revenu sans réponse. Réessayez, ou choisissez un autre modèle. » / "Maurice came back without an answer. Try again, or pick another model."; stopped: « Arrêté avant toute réponse. » / "Stopped before any answer."). Never twice (a provider error already sent is not doubled); no assistant message is stored; `GET /turn` within 60 s gives `resume` with `finished:true, text:""` then the same error. The client ends the turn on it, does not re-attach, and may keep silent about "Stopped…" after its own ⏹. **Older servers** closed such a stream with no terminal event: treat a `resume` with `finished:true` followed by nothing as the end, then re-read the conversation.
- Stop (⏹) ends the turn on the server, which persists the partial text and sends `done` with its id (or nothing if there was no text).
- The server keeps the turn running when the HTTP client disappears (the turn belongs to the conversation, not the request).

### 3.3 Finishing a turn (client side)

On `done` (`ChatService.followTurn`):

1. If `message_id` is not already in the thread (the WS may have delivered it), append an assistant message `{id: message_id, role: "assistant", content: streamingText, data: streamingData or null, usage: streamingUsage, created_at: now}`.
2. Keep the turn's activity recap (tools used, counts, duration) under that message id — **session-only**, the server does not persist it.
3. Let the "typewriter" reveal finish (max 10 s), then show the final message.
4. Reset streaming state, then `GET /api/conversations?limit=40` (refresh list ordering/titles).

On `error`: show the message, reset streaming state, refresh the list. On "nothing running" (204 from `/turn`): re-read the conversation (`GET /api/conversations/{id}`) because the reply may have been persisted meanwhile.

The typewriter: the app reveals the streamed text at ~120 chars/s plus a catch-up term (`StreamingRow`); purely cosmetic.

### 3.4 Re-attach / resume

```
GET /api/conversations/{id}/turn
→ 204 No Content            nothing running and nothing finished in the last 60 s
→ 404 {"error":"Not found"} not a participant
→ 200 application/x-ndjson  first line "resume", then the live events to the terminal one
```

```json
{"type":"resume",
 "text":"everything streamed so far",
 "data":[{"tool":"corpus__search","data":{…}}, …],   // NOTE: `data` is an ARRAY of blocks here, unlike tool_data
 "tool":"garden__read_note" | null,                   // tool running right now
 "usage":{…} | null,
 "started_at":"2026-10-21T08:12:03.123Z",             // ISO 8601
 "finished":false}
```

If the turn already finished (within 60 s), `finished: true` and the next line is its terminal event (`done` or `error`), then the stream closes. Keepalive `ping`s every 15 s of silence as on the POST stream.

When the Swift client re-attaches (`followTurn` loop):

- A stream that ends **without** `done`/`error` = lost connection, not lost reply. The activity line shows "Connection lost, Maurice continues" and the app calls `GET /turn` after 1 s (2 s after a failed attach).
- Gives up (red banner `chat.error.lost`) after 3 consecutive failed attach requests, or a non-network error, or 6 dropped streams in total.
- A 4xx/5xx other than 409 on the POST is final (banner, no retry).
- When the app returns to the foreground after ≥ 3 s away: both WebSockets reconnect immediately; if a turn was being followed, the current stream reader is cancelled to force a fresh `GET /turn`; if the follower had already given up and the turn started < 5 min ago, it follows it afresh.
- On iOS the follow runs under a background-task grace period so a nearly finished reply survives a few seconds in another app. Android: a foreground-safe coroutine is enough; re-attach on resume.
- `resume` handling: `streamingText = text`, `streamingData = data`, `streamingUsage = usage`, mark `tool` (if any) as the running step without counting it again.

### 3.5 Stop, regenerate, live room channel

- **Stop (⏹)**: `POST /api/conversations/{id}/turn/stop` (body `{}`) → `{"ok":true}`, or 404 `{"error":"No reply in progress"}` (ignored). Fire-and-forget, then cancel the local reader. Dropping the HTTP request alone does **not** stop generation.
- **Regenerate**: if the thread ends with an assistant message, remove it locally, then `POST …/messages {"content":"", "summon": true, "maurice_id": <armed>, "regenerate": true}` (the Swift code passes the armed id only for normal sends; on regenerate `maurice_id` is sent as `null` — **note**: this re-arms the conversation to the everyday Maurice; Android should send the conversation's current `maurice_id` instead). The server deletes the last assistant message and answers again; 409 if a turn is running.
- **"Answer again"** is offered when a 1:1 thread ends with the member's own message and nothing is streaming.

**Room WebSocket** — `wss://{host}/api/conversations/{id}/ws?token=…` (http → ws, https → wss). Opened for the conversation on screen. 403 if not a participant. Server → client text frames, JSON:

| `type` | Fields | App handling |
|---|---|---|
| `message` | `message: ServerMessage` | append unless id known; reconcile the sender's own optimistic copy |
| `participants` | `participants: [ServerParticipant]` | replace roster |
| `summoned` | `by: String` (member id) | observers who did not send show "Maurice is thinking" until an assistant message arrives |
| `ping` | — | every 40 s, ignore |

**User WebSocket** — `wss://{host}/api/me/ws?token=…`, opened once per active member after the first conversation-list load. Frames:

| `type` | Fields | App handling |
|---|---|---|
| `conversation_added` | `conversationId`, `title`, `by` (display name) | mark unread, reload list, local notification "`by` added you to a conversation" |
| `conversation_opened` | `conversationId`, `title`, `author` ("Maurice"), `preview` | Maurice opened a conversation on his own: unread + reload + notification |
| `activity` | `conversationId`, `title`, `author`, `preview` (≤ 140 chars, images stripped) | if not currently viewing that conversation in the foreground: unread dot, move to top, local notification "`author`: `preview`" |
| `suggestions` | `conversationId`, `count` (pending), `kept` | update the Carnet-suggestions badge; pulse when `count` grew |
| `ping` | — | ignore |

Both sockets are receive-only (the server ignores client frames). Reconnect policy (`RoomSocket`/`UserSocket`): on any receive failure, reconnect after a backoff starting at 1 s, doubling to 30 s max, reset to 1 s on any received frame; after each reconnect the owner refetches (room: `GET /api/conversations/{id}`; user: `GET /api/conversations?limit=40`) because the bus does not replay missed events. A conversation fetch answering 404 means it was deleted elsewhere: drop it and close its socket (otherwise it reconnects forever).

Server-side push fallback: `activity`, `conversation_added` and `conversation_opened` are also pushed (section 5) **only to members with no open user socket**. So an Android client that keeps `/api/me/ws` open in the foreground gets WS events, and push otherwise.

### 3.6 Structured tool data (`tool_data` blocks and persisted `messages[].data`)

Each assistant message may carry `data: [{tool, data}]` — the same blocks the stream sent as `tool_data`. The `data` payload is arbitrary JSON; a payload with a string field `card` has a purpose-built view, everything else gets a generic key/value / table fallback (`DataBlockBody`). The app preserves object key order for display.

Card kinds drawn **under the reply** as part of the answer (`DataCardStack.answerKinds`):

| `card` | Payload fields read by the app | View |
|---|---|---|
| `candidates` | `collection` (`movies` `series` `books` `podcasts` `games` `music` `people`), `results[]: {id (int or string), title, year?, subtitle?, summary?, image? (absolute CDN URL or server path)}` | "Which one did you mean?" list (max 8). Tapping **sends a normal chat message** built from the localized template `fiche.open_prefill` with title, collection and an id token (`tmdb <id>`, `google books <id>`, `podcast index <id>`, `igdb <id>`, `musicbrainz <id>`, `wikidata <id>`). |
| `media` | `title`, `image`, `year`, `collection`, `director` (movies), `platform`+`seasons` (series), `author` (books), `host` (podcasts), `artist` (music), `developer`+`platforms[]` (games), `card_state` (`published`/`draft`/other), `recto_chars`, `verso_chars`, `fiche_created` (bool), `fiche_url`, `card_url` (server paths, opened in the browser as `{server}{path}`) | fiche card |
| `fact` | `id`, `text`, `state` | "Maurice noted this about you": Keep → `POST /api/life-facts/{id}/keep`, Dismiss → `POST /api/life-facts/{id}/dismiss` (body `{}`), answer `{state}` |
| `review` | `footer` (the markdown footer text also appended to the reply), `fiches[]: {title, locale, basename, url, items[]: {kind: person/relation/identity/fragment, id?, label, anchor}}` | "To check" card; each row opens the person-review screen. When this block exists the app **removes the `footer` text from the displayed reply** (`withoutReviewFooter`). |

Evidence kinds, summarised in one line under the reply and opened in a drawer:

| `card` | Payload |
|---|---|
| `sources` | `origin: "web" \| "corpus"`, `query?`, `count`, `results[]: {title, subtitle?, kind (note/fiche/card/fragment/conversation/book/thought/web), image?, url?, snippet?, score?}`. The app de-duplicates sources across searches (web by normalised URL, others by title+subtitle) and tallies "N web searches, N memory searches, N tool calls". |
| *(none)* | raw tool rows; `tool == "web_search"` counts as web, `corpus__search` as memory, the rest as tool calls |

Other tool names the app reacts to: `domain_brief` and `domain_recognised` (payload `{card?: "domain_recognised", domain_id, domain}`) → "domain pastilles" in the header linking to that domain's brief.

### 3.7 Message content conventions (client ⇄ server, both directions)

Message `content` is Markdown. Conventions the app produces and parses (`ChatSnippets.swift`):

- Images: lines `![photo](/api/images/<file>)` at the top of a user message (server-written); generated images: the whole assistant content is `![prompt](/api/images/<file>)`. Load `{server}{path}` without auth.
- **Long paste** (≥ 1000 chars or ≥ 20 lines inserted at once): taken out of the field as a card and sent as a fenced block with info string `pasted`:
  ```
  ```pasted
  …text…
  ```
  ```
  The fence is one backtick longer than the longest backtick run inside (min 3).
- **Shared article** (share extension, section 6): fenced block with info string `article`; first paragraph is a header of `key: value` lines among `title`, `site`, `author`, `date` (YYYY-MM-DD), `url` (empty values omitted, newlines flattened), then a blank line, then the article text. The server recognises it (`offerSharedArticle`) and offers the article as a garden entry.
- Order on send: all pasted/article blocks first, then the typed text, joined by blank lines (`PastedText.compose`).
- The highlighted passage from Safari is pre-filled in the field as a Markdown quote (`> …`).
- Math: TeX (`$…$`, `$$…$$`, `\(...\)`, `\[...\]`) in replies is rendered client-side (SwiftMath); copy gives back TeX.
- Links in replies: `https://<this server>/g/...` open signed-in through `/login` (1.8); `/g/<member>/<locale>/fiches/people/<basename>` opens the in-app person review instead; `maurice-mail:<id>` (in person fiches) opens `/api/people/mail/<id>` signed-in; everything else opens normally.

### 3.8 Cost display

Optional per-reply footer (Settings → Usage → "Show what each reply cost", key `maurice.showTurnCost`): from `message.usage` — cost in EUR (0 → "0 €", < 0.01 → 4 decimals, else 2), tokens (`1.2k`, `3.40M`), cache hit rate = `cache_read / (input + cache_read + cache_write)`, saved = `cost_uncached - cost`. The conversation details sheet sums all assistant messages client-side (`ConversationUsage`). No server route.

---

## 4. Dictation

Dictation fills the composer field; **it never sends anything by itself**. The member reads and edits, then sends.

### 4.1 Engine choice (Swift `Dictation.chooseEngine`)

Three engines on iOS: Apple SpeechAnalyzer (iOS 26, on-device), the **household server** (Whisper, `/api/dictation`), and SFSpeechRecognizer (old Apple recogniser, optionally through Apple's servers with explicit consent). Order:

1. If `maurice.preferHouseholdDictation` (default **true**) and the server says `available` → household server.
2. Else SpeechAnalyzer if installed for the language.
3. Else the household server only if its engine is `local` (audio stays in the house).
4. Else SFSpeech.

Android port: use the household server as the primary engine; optionally Android's `SpeechRecognizer` for a live preview while recording and as a fallback when the server is unavailable. The "Apple servers consent" (`maurice.allowServerDictation`) is iOS-only.

While recording for the server, the iOS app runs an on-device recogniser in parallel to show words as they are spoken (preview); the server's text replaces the preview at the end. If the server fails, the preview is used; if there is no preview, the device recognises the recorded file. A clip ≤ 0.3 s is treated as "nothing said" (not uploaded).

### 4.2 Capability probe

```
GET /api/dictation          (Bearer; client timeout 3 s)
→ 200 {"available": true, "engine": "local" | "scaleway" | null, "max_seconds": 600}
→ 404 on servers too old to have the route (treat as unavailable, remember it)
```
The app caches the answer per base URL for 10 minutes, and a failure to reach the server for 1 minute. `engine == "local"` means whisper.cpp on the household machine; `scaleway` means a cloud provider (metered, ≈ €0.003/min).

### 4.3 Upload

```
POST /api/dictation?duration=12.3        (seconds, one decimal, "%.1f")
Authorization: Bearer …
Content-Type: audio/mp4
<raw bytes of the .m4a file>             (client timeout 240 s)
→ 200 {"text": "…", "engine": "local" | "scaleway", "seconds": 12.3 | null}
```

- Audio produced by the app: **AAC in an MPEG-4 (.m4a) container, 16 kHz, mono, ~32 kbit/s** (≈ 0.25 MB per minute), recorded while speaking to a temp file and uploaded whole after the stop (no streaming). **No language parameter**: Whisper auto-detects (a `language` parameter would be ignored anyway).
- The server also accepts, by `Content-Type`: `audio/wav`, `audio/mpeg`/`mp3`, `audio/webm`, `audio/ogg`, `audio/flac`; anything else is treated as m4a. Android can send `audio/mp4` (MediaRecorder `AAC` + `MPEG_4`, 16 kHz mono) or `audio/ogg`/`audio/webm` (Opus).
- Limits: 25 MB body, 600 s (+5 s tolerance, checked against `duration` and the decoded length).
- `text` may be `""` (silence, or a hallucination the server filtered).
- Errors (`{"error": "<English sentence>"}`): 400 empty/unreadable recording, 402 spending limit reached (cloud engine), 413 too long (`{"error":"too_long"}` when the size cap trips), 502 transcription failed / provider unreachable, 503 no engine / local engine not installed, 500 unexpected. The Swift app treats any non-200 as "server could not" and falls back (4.1).
- Vocabulary: the app passes names (members, domains, "Maurice") to the on-device recogniser only; the server adds its own vocabulary.

### 4.4 Composer integration (behaviour worth keeping)

- The dictated text is inserted at the end of the current field content; a manual edit during dictation stops the final text from overwriting it.
- Pressing Send while a server transcription is pending waits for it.
- Leaving the chat or backgrounding the app stops dictation (`userStopped = false`): the text is still delivered but not auto-acted upon.
- The Action-Button intent starts a **new** conversation with an empty field, then dictation (iOS-only, section 9).

---

## 5. Push notifications

### 5.1 What the app does (APNs)

1. After the first conversation-list load, the app asks for notification permission (alert + sound) once; if granted it registers for remote notifications.
2. The APNs device token is hex-encoded (lowercase, 2 chars per byte).
3. It is registered with **every paired household** for which the device has an active member token (not only the current one), tagged with that household's local id:
   ```
   POST /api/users/me/device-token        (Bearer = that household's active member token)
   {"token": "<hex>", "platform": "ios" | "macos", "household_tag": "<Household.id>"}
   → 200 {"ok": true}      400 {"error":"token required"}
   ```
   Re-registered at each list load and whenever the system hands a new token. Re-registering a token moves it to the calling member (table `device_tokens`, `token` is the primary key). The app never unregisters (`DELETE /api/users/me/device-token/:token` exists server-side — **note**: it does not check ownership).
4. Tapping a notification reads `conversationId` and `householdTag` from the payload; if the tag names another paired household, the app switches household first, then opens the conversation (`ChatService.openFromNotification`).
5. Foreground: banners are shown even when the app is open (`willPresent` → `.banner, .sound`). Local notifications for WebSocket events (3.5) carry only `conversationId`.

### 5.2 What the server sends and when

Push is a **fallback for members without a live user WebSocket** (`userHasSocket`): the server publishes on `/api/me/ws` and pushes only if no socket is open for that member.

| Trigger | `title` | `body` | `conversationId` |
|---|---|---|---|
| Activity in a conversation (a human message, or Maurice's reply) — every participant except the sender | conversation title, else the author's name | `"<author>: <preview>"` (≤ 140 chars, images stripped, pasted/article blocks reduced) | yes |
| Added to a room | conversation title, else `"New conversation"` | `"<by> added you to a conversation"` | yes |
| Maurice opened a conversation on his own | its title | `"Maurice: <preview>"` | yes |

APNs payload (`src/services/apns.ts`): headers `apns-push-type: alert`, `apns-priority: 10`, topic `eu.chezmaurice.maurice` (or `eu.chezmaurice.carnet.app` when `platform` starts with `carnet`):
```json
{ "aps": { "alert": { "title": "…", "body": "…" }, "sound": "default" },
  "conversationId": "<uuid>",
  "householdTag": "<the device's local household id, if registered>" }
```
Tokens answered `BadDeviceToken`/`Unregistered`/410 are deleted.

### 5.3 What an Android (non-APNs) channel needs

- **Today, registering an FCM token would be harmful**: `pushToUser` sends *every* token to APNs regardless of `platform`; APNs rejects it as `BadDeviceToken` and the server **deletes** it. Do not register Android tokens until the server changes.
- Server change needed (small): in `services/push.ts`, branch on `platform` (e.g. `"android"`) to an FCM HTTP v1 sender; send a **data** message (or notification + data) with the same keys: `title`, `body`, `conversationId`, `householdTag`. The client contract (`POST /api/users/me/device-token {token, platform:"android", household_tag}`) can stay identical.
- Alternative without server change: keep `/api/me/ws` open in a foreground service (heavy) or rely on WebSocket events while the app is in the foreground only (what iOS effectively does when active).
- Client: use the household tag to route the tap exactly as iOS (5.1 step 4).

---

## 6. Share extension flow (iOS "Share to Maurice")

1. **Extension** (`MauriceShareExtension/ShareViewController.swift`, `SharedPageLoader.swift`, `ShareExtensionPreprocessor.js`). From Safari, a JS preprocessor returns `{url, title, html (document.documentElement.outerHTML, dropped if > 6 000 000 chars), selection (≤ 2000 chars)}`. From other apps only a URL (`public.url` or `public.plain-text` that parses as http(s)) arrives.
2. The extension does **no network call** (it has no token). It writes a `SharedPage` JSON file into the app-group container `group.eu.chezmaurice.maurice/share-inbox/<UUID>.json`:
   ```json
   {"url":"https://…","title":"…","html":"<html…>","selection":"…","sharedAt": 783000000.0}
   ```
   (`sharedAt` is a Swift `Date` encoded with the default `JSONEncoder` = seconds since 2001-01-01; irrelevant cross-platform.)
3. It opens `maurice://share` and closes. If it cannot open the app, the page stays in the inbox and is taken the next time the app comes to the foreground.
4. **App** (`ShareInbox.take`, `ChatView.takeSharedArticle`): takes the most recent page (deleting all inbox files); ignores it if older than 6 h. It starts a **new conversation** (locally; created on first send), then:
   ```
   POST /api/v1/articles/read      (Bearer, timeout 45 s)
   {"url":"https://…", "html":"<html…>" | null}    // html omitted/null when empty → the server fetches the URL itself
   → {"url","title","site_name","author","published_at","text"}
   ```
   Any failure falls back to an article with just the page title and URL (empty text = "link only").
5. The article becomes a card above the composer field (`Snippet.article`), sent as the ```` ```article ```` fenced block (3.7) when the member sends. The selection, if short, is pre-filled as a quote. Nothing is sent automatically.

Android equivalent: an `ACTION_SEND` (`text/plain`, `text/html`) activity in the same app — no inbox file needed since the activity can read the token directly; call `/api/v1/articles/read` with `html = null` (Android browsers do not hand over the DOM; the server fetches the page, which fails behind paywalls).

---

## 7. Local persistence and caches

**No offline cache of conversations or messages**: every list and thread is refetched from the server at launch, on member switch, on socket reconnect and on foreground. Everything below is either configuration or ephemeral.

### 7.1 UserDefaults keys (→ Android DataStore)

| Key | Type | Scope | Meaning |
|---|---|---|---|
| `maurice.households` | JSON `[Household]` | device | paired households, their device users, active user, unread |
| `maurice.currentHousehold` | String | device | current household id |
| `maurice.token.<householdId>.<userId>` | String | household+member | session token (**secret**) |
| `maurice.mcp-token.<householdId>.<userId>` | String | household+member | `maur_` API token (**secret**) |
| `maurice.activeUserId` | String | device | mirror of active member id (localisation helper) |
| `maurice.prefs.<userId>.palette` | String, default `"auto"` | member | colour palette (also synced to the server) |
| `maurice.prefs.<userId>.locale` | String? (`en fr it de es pt nl`, nil = system) | member | UI language (also synced) |
| `maurice.prefs.<userId>.webTheme` | String, default `"manuscript"` | member | web garden theme (**local only** + `PATCH /api/v1/gardens/:id`; not in `/api/users/me/preferences`) |
| `maurice.defaultMaurice.<householdId>` | String? | household | the domain/companion a new conversation arms by default = the last one sent to |
| `maurice.connect_disclosure.<host>` | Bool | device | disclosure acknowledged for that server host |
| `maurice.showTurnCost` | Bool, default false | device | per-reply cost footer |
| `maurice.allowServerDictation` | Bool, default false | device | iOS: allow Apple's servers for SFSpeech (iOS-only) |
| `maurice.preferHouseholdDictation` | Bool, default **true** | device | send dictation to the household server (Whisper) first |
| `maurice.thinkingPulse` | String | device | style of the activity-line animation |
| `maurice.context.sort` | String | device | sort order in the "Add context" picker |
| `maurice.garden.sort` | String | device | sort order of the garden page's note list |

Note: the "per-member" prefs are keyed by `userId` only (not household); fine because user ids are UUIDs.

### 7.2 Files

- App-group inbox `share-inbox/*.json` (section 6).
- Dictation clips `tmp/dictation-<UUID>.m4a`, deleted after upload.
- Downloads (`/api/me/export`) into `tmp/<UUID>/<server-given name>.tar.gz`, then handed to the system file mover.

### 7.3 In-memory caches

| What | Key | Lifetime |
|---|---|---|
| Dictation capability (`GET /api/dictation`) | base URL | 10 min; "unreachable" remembered 1 min |
| Book chapters (`/api/v1/composer/books/:id/chapters`) | book id | ComposerStore lifetime |
| Tool-activity recap per assistant message | message id | session (not persisted anywhere) |
| Suggestion counts per conversation | conversation id | until next list load |
| Known message ids (WS dedupe) | conversation | until conversation change |
| Unread conversation ids | conversation id | until opened / member switch |

---

## 8. Screen inventory

Swift line counts are for the file; a file often holds several views. "Size" is a rough porting weight: S < 300 lines of real UI, M 300–900, L > 900.

### 8.1 Onboarding and identity

| Screen (Swift) | Lines | Size | What it shows / does |
|---|---|---|---|
| `PairingView` (`PairingView.swift`) | 298 | S | First launch: address field + Connect, "Scan an invitation" (camera QR). Health check, pairing. Also presented as a sheet to add another household. |
| `UserPickerView` (`UserPickerView.swift`) | 690 | M | Household chips + add, member avatar grid (lock icon if PIN), PIN overlay, admin username/password overlay, invitation-code overlay (2 steps for open invitations), mandatory PIN setup after enrolment, Disconnect. |
| Connect disclosure (`ContentView.swift`) | 257 (file) | S | Blocking modal for non-admins on a server they do not run. |
| Loader (`HatLoader` in `Hats.swift`) | 337 (file) | S | Animated hat during the initial load (≥ 2 s). |

### 8.2 Conversations list (sidebar)

| Screen | Lines | Size | What it shows / does |
|---|---|---|---|
| `SidebarView` + `ConversationRow`, `SearchHitRow`, `GardenRow`, `SidebarSectionHead` (`ScopePickerView.swift`) | 1729 (file) | L | Wordmark, GARDENS section (my garden + shared note sets, ↗ browse / › manage), CONVERSATIONS list with infinite scroll (40 per page, keyset cursor), unread dots, participant avatar stacks, domain marks, origin badges (imported), floating search (full-text, debounced 220 ms, ≥ 2 chars, `⟦ ⟧` highlights), identity chip → Settings, Domains button with "new proposals" badge, new conversation. Swipe/context actions: rename, delete. |
| `GardenPageView`, `NoteAccessSheet`, `GardenThemeChips` (`ScopePickerView.swift`) | (same file) | M | A garden's page in the main pane: its notes (filter, sort, paging), theme chips, per-note access sheet (who it is shared with; share with a member; leave a note shared with me). |

### 8.3 Chat

| Screen | Lines | Size | What it shows / does |
|---|---|---|---|
| `ChatView` (`ChatView.swift`) | 3870 (file) | **L** (largest) | Header (title, domain pastilles, participants, Carnet-suggestions mark, details button), message list (user bubbles with photos/paste/article cards, assistant replies as rich Markdown with TeX, tables, code; per-reply activity recap, sources line + drawer, data cards, cost footer, time), streaming row with typewriter reveal and activity line ("Searching the web ×2…", "Thinking…", "Connection lost, Maurice continues"), error banner / quiet network notice, "answer again" button, empty state with domain greeting. |
| `ComposerBar` (in `ChatView.swift`) | ~1100 of the file | L | Text field (multi-line), ➤ send (summons Maurice) and 💬 post (rooms), photo picker (≤ 10) / camera / paste image, file attach (uploads to the Files library and adds it as context), long-paste-to-card, shared-article card, dictation button with waveform and states, model/domain pill (the armed domain + its model; switching model calls `PUT /api/models/everyday` or `PATCH /api/maurices/:id`), stop button while streaming. |
| `ConversationDetailsSheet`, `ConversationToolsSheet`, `AddParticipantSheet` (`ChatView.swift`) | — | M | Rename, bind to one of my domains (or none), per-conversation tool families, participants (add from roster, remove), conversation cost summary, delete, leave room, review reports (admin), edit domain. |
| `ReportSheet`, `ReportsReviewView` (`ChatView.swift`) | — | S | Report a message/member (5 reasons + note); operator review of open reports (remove message / eject member / dismiss). |
| `ReplyText` (`ReplyText.swift`) | 1636 | L (rendering engine) | One selectable attributed text per reply: Markdown blocks, code with copy, quotes, tables, TeX via SwiftMath. On Android: a Markdown renderer (e.g. Markwon / compose-markdown) + a math renderer. |
| `SourcesCard.swift` | 409 | M | Sources tally line, thumbnails stack, drawer grouping web/memory/tool results. |
| `ChatSnippets.swift` | 420 | S | Paste/article card views + encoding rules (3.7). |
| `LifeFactCard.swift` | 101 | S | Keep/dismiss a remembered fact. |
| `CarnetSuggestions.swift` (`CarnetSuggestionsButton`, `SuggestionsDrawer`, `EpisodePicker`) | 801 | M | Per-conversation list of things worth keeping in the garden (works, people): keep (pick a candidate identity, podcast episodes), search again, dismiss; kept rows open in Carnet (`carnet://entry/<ref>`) or the web. |
| `PersonReviewView` + `ReviewCard` (`PersonReviewView.swift`) | 756 | M | Review a person fiche written from mail: relation, identities (addresses), fragments; ✓ confirm / ✗ reject / ✎ edit per element, confirm all, accept/reject Maurice's proposed revision. |
| `ThinkingPulse.swift` | 93 | S | Little animation styles for the activity line. |

### 8.4 Composer context ("what Maurice reads")

| Screen | Lines | Size | What it shows / does |
|---|---|---|---|
| `ContextTray`, `TrayCard` + bodies (`ComposerViews.swift`) | 1421 | L | The tray of context items above the composer: notes (recurse into a MOC, include archived, exclude sub-notes with a tree preview), books (summary/full, all / up to chapter / chosen chapters / follow reading progress with pause + reset), fiches (with fragments), conversations (summary vs full transcript, summary status), files and folders (tree preview, binary attachments). Weight readout vs a 200 000-token budget (light/… tiers). Saved per conversation. |
| `AddContextSheet` (`ComposerViews.swift`) | — | M | Search across notes/books/conversations/files/folders/fiches, sort, toggle items, upload a new file. |

### 8.5 Domains (life areas) and reading companions

| Screen | Lines | Size | What it shows / does |
|---|---|---|---|
| `DomainsListSheet`, `ProposalListRow`, `ProposalPage` (`DomainsViews.swift`) | 978 | L | My domains and reading companions; the night's **proposals** (new badge, weight dots, share, sample titles): adopt (optionally seeding notes), rename, dismiss, restore, merge several, split (cut chosen conversations/mail threads into a new proposal). |
| `DomainGreeting`, `DomainPastilles` (`DomainsViews.swift`) | — | S | Empty-conversation greeting for a domain; header pastilles for domains in play. |
| `DomainBriefSheet` (`DomainBriefView.swift`) | 402 | M | The brief Maurice keeps on a domain: read, edit (member's wording), erase, "rewrite now" (outcomes written/unchanged/capped/failed), bound conversations (incl. those auto-filed by the night, which can be taken out), seeded notes link. |
| `DomainEditor`, `ToolFamilyPicker`, `ModelCard`, `DomainPreview` (`DomainEditorView.swift`) | 801 | M | Create/edit a domain or companion: name, icon, kind, model (+ thinking switch when optional), temperature, tagline, prompt, baked-in context items, tool families. 409 → "a domain with this name exists". |

### 8.6 Settings

| Screen | Lines | Size | What it shows / does |
|---|---|---|---|
| `SettingsView` root (`SettingsView.swift`) | 3207 (file) | L | Identity (avatar: camera or library → square crop → upload), switch member, household, members (admin), language, appearance, Files, Mail, Contacts, Import, web garden theme + "open my garden", MCP token (masked, copy, rotate, open web sign-in), usage cost toggle, activity animation, dictation toggles, My data, Safety links, server URL + Disconnect. |
| `MembersPane`, `InviteQRSheet` | — | M | Admin: list members, create an open invitation (QR + code + share link + revoke), show/create a member's device code. |
| `MailPane` | ~900 of the file | L | Add IMAP mailboxes (provider-specific help, app passwords, custom host/port/login), check, renew password, forget (with history rewrite), remove; header scan progress (polled every 5 s while running); the "reading" consent (approve/decline, years window, per-mailbox approve, run now); top senders with per-sender rules; documents progress. |
| `ContactsPane` | ~260 | M | Add CardDAV address books (iCloud/Fastmail/Mailfence/other URL), sync, renew password, remove. |
| `FilesLibraryView` | ~300 | M | The member's file library: folder tree, upload (document picker / drop), new folder, rename, delete, quota summary. |
| `ImportConversationsView` (`ImportConversationsView.swift`) | 225 | S | Import a ChatGPT or Claude export (.zip): upload, poll job status every 1.5 s (parsing → indexing n/total → done), history of past imports. |
| `MyDataView` (`MyDataView.swift`) | 395 | M | Export everything (tar.gz download), import such an archive, git clone access for the garden (URL, username, token, rotate, nightly conversation export toggle), erase my data, delete my account (type username + password/PIN). |
| `SafetySettingsView` (`SettingsView.swift`) | — | S | Static links (NCMEC, Belgian child-safety hotline, Apple report a problem, terms, privacy). |

### 8.7 Shared/visual building blocks (no server calls)

`Hats.swift` (337: hat glyphs, member avatars and stacks), `BoaterHat.swift` (26), `Glass.swift` (186), `MauriceTheme.swift` (149: palettes `cream`… and the `auto` scheme), `SortMenu.swift` (67), `SearchKeyboard.swift` (25), `ReplyGallery.swift` (169, DEBUG-only snapshot gallery — skip).

---

## 9. Platform-specific pieces an Android port can skip or replace

| Item | Where | Android |
|---|---|---|
| Liquid Glass styling (`glassProminentButton`, `glassBorderedButton`, `GlassGroup`, `plainGlass`) | `Glass.swift` | Skip; Material 3 equivalents. |
| iPadOS 26 window controls (`SceneDelegate.preferredWindowingControlStyle`, `TrafficLightInset`, `Platform.isPad` toolbars) | `MauriceApp.swift`, `ChatView.swift` | Skip. |
| macOS-only: hidden title bar, focus-ring removal, `NSWorkspace`, macOS push (`platform: "macos"`), wider sidebar | various `#if os(macOS)` | Skip. |
| App Intent "Dictate to Maurice" + App Shortcuts (Action Button), `DictationRequest` 20-s window | `DictateIntent.swift` | Optional: a launcher shortcut / Quick Settings tile that opens a new conversation and starts dictation. |
| On-device speech engines: SpeechAnalyzer (iOS 26), SFSpeechRecognizer, Apple-server consent (`maurice.allowServerDictation`), model prefetch | `Dictation.swift` (≈ 1400 of 1570 lines) | Replace by household Whisper (`/api/dictation`) + optional Android `SpeechRecognizer` for live preview/fallback. |
| Share extension + app group inbox + Safari JS preprocessor | `MauriceShareExtension/*`, `Shared/ShareInbox.swift` | Replace by an `ACTION_SEND` activity (section 6). |
| APNs registration (`AppDelegate`, hex device token) | `MauriceApp.swift` | Replace by FCM — needs a server change (section 5). |
| iOS background-task grace for streaming (`BackgroundGrace`) | `ChatService.swift` | Not needed; re-attach on resume. |
| Local notifications with `UNUserNotificationCenter` (foreground banners) | `ChatService.NotificationManager` | `NotificationCompat` + channels. |
| `UIViewRepresentable` text views (`ReplyUITextView`, `SelectableTextView`, image-paste enabler) | `ReplyText.swift`, `ChatView.swift` | Compose equivalents. |
| SF Symbols as data (household `icon`, domain `icon`, tool-family `icon`) | server strings | Map common names to Material icons; fall back to a generic icon. |
| QR scanner (AVFoundation) / QR generation (CoreImage) | `PairingView.swift` | CameraX + ML Kit / ZXing. |
| Carnet deep links `carnet://entry/<ref>` | `CarnetSuggestions.swift` | Only if the Android Carnet exists; else open `web_path` signed-in. |
| DEBUG `ReplyGallery` | `ReplyGallery.swift` | Skip. |
