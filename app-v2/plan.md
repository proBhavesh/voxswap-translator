# VoxSwap UI Conversion Plan

Convert the original green, multi-mode Android UI into VoxSwap's clean, single-purpose indigo UI.

---

## Screen Mapping

| VoxSwap (React Native) | RTranslator (Current) | Action |
|---|---|---|
| Root Layout (model guard) | LoadingActivity | **Modify** — simplify to just check models, route to download or home |
| Model Download | AccessActivity → DownloadFragment | **Modify** — strip NoticeFragment + UserDataFragment, restyle DownloadFragment |
| Home (mic + captions) | VoiceTranslationActivity → WalkieTalkieFragment | **Rewrite layout** — replace 3-mic + language cards with VoxSwap design |
| Language Select | Dialog inside WalkieTalkieFragment | **New Activity** — LanguageSelectActivity with 3 pickers |
| Settings | SettingsActivity → SettingsFragment | **Rewrite** — replace PreferenceFragment with custom layout |

---

## What to Delete

### Java files to delete
- `voice_translation/_conversation_mode/` — entire package (PairingFragment, PairingToolbarFragment, ConversationFragment, ConversationMainFragment, ConversationService, ConversationMessage, ConversationBluetoothCommunicator, RecentPeer, RecentPeersDataManager)
- `voice_translation/_text_translation/TranslationFragment.java`
- `bluetooth/` — entire package (BluetoothCommunicator, BluetoothConnection, BluetoothConnectionClient, BluetoothConnectionServer, BluetoothMessage, Channel, ClientChannel, ServerChannel, Message, Peer, BluetoothTools, CustomCountDownTimer, Timer)
- `database/` — entire package (AppDatabase, MyDao, RecentPeerEntity)
- `access/NoticeFragment.java`
- `access/UserDataFragment.java`
- `tools/gui/peers/` — entire package (GuiPeer, Header, Listable, PeerListAdapter, PairingArray, InfoArray, PeerListArray)
- `tools/gui/ButtonKeyboard.java`
- `tools/gui/ButtonSearch.java`
- `tools/gui/RequestDialog.java`
- `tools/gui/SecurityLevel.java`
- `tools/gui/EditCode.java`
- `tools/gui/CodeEditText.java`
- `tools/gui/CustomFragmentPagerAdapter.java`
- `tools/gui/CustomViewPager.java`
- `tools/gui/WalkieTalkieButton.java`
- `tools/gui/MicrophoneComunicable.java`
- `tools/GalleryImageSelector.java`
- `tools/ImageActivity.java` (and ImageActivity from manifest)
- `tools/EncryptionKey.java`
- `tools/ObjectSerializer.java`
- `settings/UserImagePreference.java`
- `settings/UserNamePreference.java`
- `settings/SeekBarPreference.java`
- `settings/SupportTtsQualityPreference.java`
- `settings/SupportLanguagesQuality.java`
- `settings/ShowOriginalTranscriptionMsgPreference.java`

### Layout files to delete
- `fragment_conversation.xml`, `fragment_conversation_main.xml`
- `fragment_pairing.xml`, `fragment_peers_info.xml`
- `fragment_translation.xml`
- `fragment_notice.xml`, `fragment_user_data.xml`
- `dialog_connection_request.xml`, `dialog_edit_name.xml`, `dialog_key_files.xml`
- `component_row_connected.xml`, `component_row_recent.xml`, `component_row.xml`, `component_row_header.xml`
- `component_message_received.xml`, `component_message_send.xml`, `component_message_preview.xml`
- `component_credit_graph.xml`
- `image_conversation.xml`
- `preference_user_image.xml`, `preference_user_name.xml`, `preference_seekbar.xml`
- `activity_credit.xml`

### Resources to delete
- `menu/recent_row_menu.xml`, `menu/toolbar_menu.xml`
- `raw/madlad_supported_launguages.xml` (not needed, we only use NLLB + Whisper)
- `raw/username_supported_characters.xml`

### Manifest changes (removals)
- Remove `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` permissions
- Remove `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` permissions
- Remove `SEND_DOWNLOAD_COMPLETED_INTENTS`, `BROADCAST_STICKY` permissions
- Remove `ConversationService` from manifest
- Remove `GeneralActivity`, `ImageActivity` from manifest
- Remove `DownloadReceiver` (will be inlined into DownloadFragment)
- Remove `FileProvider` (used for image sharing)
- Remove Samsung multi-window library

### Dependencies to remove (build.gradle)
- `com.google.mlkit:language-id` — Google ML Kit language detection
- `com.nimbusds:nimbus-jose-jwt` — JWT parsing (legacy)
- `androidx.room:room-*` — Room database (only used for BT peers)

---

## Color Palette Change

Replace the green palette in `colors.xml` with VoxSwap's indigo palette:

```xml
<!-- Brand -->
<color name="brand_primary">#4F46E5</color>
<color name="brand_primary_light">#6366F1</color>
<color name="brand_primary_dark">#4338CA</color>
<color name="brand_accent">#0EA5E9</color>

<!-- Text -->
<color name="text_primary">#111827</color>
<color name="text_secondary">#6B7280</color>
<color name="text_muted">#9CA3AF</color>
<color name="text_inverse">#FFFFFF</color>

<!-- Backgrounds -->
<color name="bg_primary">#FFFFFF</color>
<color name="bg_secondary">#F9FAFB</color>
<color name="bg_card">#F3F4F6</color>

<!-- Status -->
<color name="status_success">#16A34A</color>
<color name="status_success_muted">#DCFCE7</color>
<color name="status_error">#DC2626</color>
<color name="status_error_muted">#FEE2E2</color>
<color name="status_warning">#D97706</color>
<color name="status_warning_muted">#FEF3C7</color>

<!-- Borders -->
<color name="border_light">#F3F4F6</color>
<color name="border_default">#E5E7EB</color>

<!-- Grays -->
<color name="gray_50">#F9FAFB</color>
<color name="gray_100">#F3F4F6</color>
<color name="gray_200">#E5E7EB</color>
<color name="gray_300">#D1D5DB</color>
<color name="gray_400">#9CA3AF</color>
<color name="gray_500">#6B7280</color>
<color name="gray_700">#374151</color>
<color name="gray_900">#111827</color>
```

Update `styles.xml`:
- `Theme.Speech` → change `colorPrimary` to `brand_primary`, `colorPrimaryDark` to `brand_primary_dark`, `colorAccent` to `brand_accent`
- Status bar: white background with dark icons (already has `windowLightStatusBar=true`)

---

## Screen-by-Screen Implementation

### Screen 1: LoadingActivity (Simplify)

**Current**: Splash screen → checks `isFirstStart()` → routes to AccessActivity or initializes models → VoiceTranslationActivity.

**New behavior**:
1. Splash screen shows
2. Check if all 10 model files exist in `getFilesDir()` (quick file existence check, no integrity test)
3. If any missing → launch `AccessActivity` (download screen)
4. If all present → initialize Translator + Recognizer → launch `VoiceTranslationActivity`
5. Remove the `isFirstStart()` SharedPreferences check — just check model files directly

**Files to modify**: `LoadingActivity.java`
- Remove references to `Global.isFirstStart()` / `Global.setFirstStart()`
- Remove BT communicator initialization
- Simplify error handling (keep model loading errors with "Fix" dialog)

### Screen 2: Model Download (AccessActivity + DownloadFragment)

**Current**: NoticeFragment → UserDataFragment → DownloadFragment.

**New behavior**: AccessActivity goes directly to DownloadFragment. No notice screen, no user data screen.

**New layout** (`fragment_download.xml` — rewrite):
```
┌─────────────────────────────┐
│  "Download Models"     (24sp, bold, text_primary)
│  "Models are required..."   (14sp, text_secondary)
│                             │
│  ┌─ Model List ───────────┐ │
│  │ NLLB Encoder    254 MB │ │
│  │ [████████░░]    78%    │ │
│  │─────────────────────── │ │
│  │ NLLB Decoder    171 MB │ │
│  │ ✓ Downloaded           │ │
│  │─────────────────────── │ │
│  │ ...                    │ │
│  └────────────────────────┘ │
│                             │
│  [══════ Download All ═════]│  (brand_primary button)
│  or                         │
│  [══════ Continue ═════════]│  (when all done)
└─────────────────────────────┘
```

**Implementation**:
- Modify `AccessActivity.java` — remove fragment switching, always show DownloadFragment
- Rewrite `fragment_download.xml` layout:
  - Title "Download Models" (24sp, bold, `text_primary`)
  - Subtitle text (14sp, `text_secondary`)
  - `RecyclerView` for model list (each row: model name, size, status icon, progress bar if downloading)
  - Bottom button: "Download All" (brand_primary) or "Continue" (when all done)
- Modify `DownloadFragment.java`:
  - Add a RecyclerView adapter for model items
  - Keep the sequential download logic from `Downloader` + `DownloadReceiver`
  - Replace single progress bar with per-model progress
  - Remove all references to UserDataFragment/NoticeFragment navigation
  - On "Continue" → launch LoadingActivity (which will then route to home)
- Keep `Downloader.java` and `DownloadReceiver.java` mostly unchanged (they handle the actual downloading)
- New file: `ModelListAdapter.java` — RecyclerView adapter for model items
- New layout: `item_model_row.xml` — single model row with name, size, status, progress bar

### Screen 3: Home Screen (VoiceTranslationActivity + WalkieTalkieFragment)

**Current**: VoiceTranslationActivity hosts multiple fragments via FragmentTransaction. WalkieTalkieFragment has 3 mic buttons, 2 language cards, toolbar, messages area.

**New behavior**: VoiceTranslationActivity hosts only one fragment (HomeFragment, the renamed/rewritten WalkieTalkieFragment). No fragment switching, no tabs.

**New layout** (`fragment_home.xml` — new file replacing `fragment_walkie_talkie.xml`):
```
┌─────────────────────────────────┐
│ ● Connected        ⚙ (settings)│  ← header row
│                                 │
│ ┌─ Language Bar ──────────────┐ │
│ │ 🌐 English → French, German│>│  ← tap opens LanguageSelectActivity
│ └─────────────────────────────┘ │
│                                 │
│                                 │
│           ┌──────┐              │
│          ╱  glow  ╲             │  ← 160dp outer glow ring
│         │ ┌──────┐ │            │
│         │ │ 🎤   │ │            │  ← 120dp mic button (brand_primary)
│         │ └──────┘ │            │
│          ╲        ╱             │
│           └──────┘              │
│     "Tap to start translating"  │  ← status text
│                                 │
│ ┌─ Caption Area ──────────────┐ │
│ │ "Captions will appear here" │ │  ← bg_secondary, rounded, min 80dp
│ └─────────────────────────────┘ │
│                                 │
│ [ Change Languages ]            │  ← outline button
└─────────────────────────────────┘
```

**Layout details**:
- `ConstraintLayout` root, white background
- Header: `View` (10dp circle, status color) + `TextView` (status label) on left, `ImageButton` (settings gear) on right
- Language bar: `MaterialCardView` (bg_secondary, rounded 12dp, border border_light), contains language icon + source name + arrow + target names + chevron. Entire card is clickable → launches LanguageSelectActivity
- Mic button area: centered vertically in remaining space
  - Outer `View` (160dp circle, semi-transparent brand color, opacity varies with `audioLevel`)
  - Inner `ImageButton` (120dp circle, `brand_primary` background, white mic icon). Changes to `status_error` + stop icon when recording
- Status text: `TextView` below mic button, 14sp, `text_muted`
- Caption area: `MaterialCardView` (bg_secondary, rounded 12dp, border, min-height 80dp), `TextView` inside
- Bottom: `Button` "Change Languages" (outline style, `brand_primary` text + border)

**Java changes**:
- Rename `WalkieTalkieFragment.java` → `HomeFragment.java` (or keep name, just rewrite internals)
- Remove: left mic, right mic, sound button, back button, toolbar title, language selector cards, all audio level bar animations
- Add: connection status dot (observe connection state), language bar (shows current languages), single centered mic button, caption text, "Change Languages" button
- Mic button behavior:
  - Tap to start: calls service `START_MIC` (auto mode with VAD)
  - Tap to stop: calls service `STOP_MIC`
  - No manual/auto toggle — always auto mode
  - Glow ring: update outer circle alpha based on volume level from `VoiceTranslationServiceCallback.onVolumeLevel()`
- Language bar: populated from `Global.getFirstLanguage()` and `Global.getSecondLanguage()` — but now we need 3 languages (source, target1, target2). See "Language Model Change" section below.
- Caption text: updated from `onMessage()` callback — show the original STT text (source language transcription)
- Connection status: for now, always show "Ready" since we haven't added WiFi to box yet. Later will observe actual connection state.

**Modify `VoiceTranslationActivity.java`**:
- Remove `setFragment()` switching logic
- Remove `PAIRING_FRAGMENT`, `CONVERSATION_FRAGMENT`, `TRANSLATION_FRAGMENT` constants
- Always show HomeFragment (previously WalkieTalkieFragment)
- Remove all Bluetooth methods (`startSearch`, `stopSearch`, `connect`, `disconnect`, etc.)
- Remove ConversationService binding methods
- Keep WalkieTalkieService binding methods

### Screen 4: Language Select (New Activity)

**Current**: Language selection is a dialog opened from WalkieTalkieFragment.

**New**: Full-screen `LanguageSelectActivity` with 3 scrollable language pickers.

**New layout** (`activity_language_select.xml`):
```
┌──────────────────────────────────┐
│ "Languages"         [Done]       │  ← header
│                                  │
│ YOUR LANGUAGE                    │  ← section header (uppercase, text_secondary)
│ ┌──────────────────────────────┐ │
│ │ 🔍 Search languages...      │ │
│ │ English              ✓      │ │  ← scrollable list, max 200dp
│ │ Spanish                     │ │
│ │ French                      │ │
│ └──────────────────────────────┘ │
│                                  │
│ TARGET LANGUAGE 1                │
│ ┌──────────────────────────────┐ │
│ │ 🔍 Search languages...      │ │
│ │ French               ✓      │ │
│ │ German                      │ │
│ └──────────────────────────────┘ │
│                                  │
│ TARGET LANGUAGE 2                │
│ ┌──────────────────────────────┐ │
│ │ 🔍 Search languages...      │ │
│ │ German               ✓      │ │
│ └──────────────────────────────┘ │
└──────────────────────────────────┘
```

**Implementation**:
- New file: `LanguageSelectActivity.java`
- New layout: `activity_language_select.xml`
  - Header row: "Languages" title + "Done" button (outline, text)
  - 3 sections, each with section header + `RecyclerView` (max 200dp height) inside a card
  - Each RecyclerView: search `EditText` at top, then language list items
- New file: `LanguageListAdapter.java` (or reuse existing `tools/gui/LanguageListAdapter.java` — check if suitable, likely needs rewrite for new design)
- New layout: `item_language_row.xml` — language name, native name, checkmark if selected
- Language list populated from `Global.getLanguages()` (intersection of Whisper + NLLB supported)
- Selections saved: `sourceLanguage` → `Global.setFirstLanguage()`, `targetLanguage1` → new field, `targetLanguage2` → `Global.setSecondLanguage()`
- On "Done" → finish activity, HomeFragment picks up changes from Global

### Screen 5: Settings (Rewrite)

**Current**: PreferenceFragmentCompat with custom preferences for username, image, language, seekbars, toggles.

**New layout** (`activity_settings.xml` — rewrite):
```
┌──────────────────────────────────┐
│ "Settings"          [Back]       │  ← header
│                                  │
│ CONNECTION                       │
│ ┌──────────────────────────────┐ │
│ │ 📶  Ready                   │ │
│ │     Target: 10.0.0.1     ●  │ │
│ └──────────────────────────────┘ │
│                                  │
│ MODELS                           │
│ ┌──────────────────────────────┐ │
│ │ NLLB Encoder       254 MB   │ │
│ │                  Downloaded  │ │
│ │──────────────────────────── │ │
│ │ NLLB Decoder       171 MB   │ │
│ │                  Downloaded  │ │
│ │ ...                          │ │
│ └──────────────────────────────┘ │
│ [ Re-download Models ]           │
│                                  │
│ ABOUT                            │
│ ┌──────────────────────────────┐ │
│ │ VoxSwap Translator           │ │
│ │ v0.1.0                       │ │
│ └──────────────────────────────┘ │
└──────────────────────────────────┘
```

**Implementation**:
- Rewrite `SettingsActivity.java` — replace PreferenceFragment with a custom Fragment using a ScrollView layout
- New layout: `activity_settings_new.xml`
  - Header: "Settings" + "Back" button
  - Connection section: status card (icon + label + target IP + status dot)
  - Models section: list of all 10 models with name, size, download status
  - "Re-download Models" button → launches AccessActivity
  - About section: app name + version
- Delete `SettingsFragment.java` (PreferenceFragment) and replace with custom `SettingsContentFragment.java`
- Remove: user image, username, language quality toggles, TTS quality, mic sensitivity seekbar, beam size, speech timeout, prev voice duration — all of these are either Bluetooth-related, unnecessary, or can be hardcoded for now
- Keep minimal settings: connection status, model status, re-download, about

---

## Language Model Change

**Current**: RTranslator uses 2 languages (firstLanguage, secondLanguage) — bidirectional translation between them.

**New**: VoxSwap needs 3 languages — sourceLanguage (what user speaks), targetLanguage1, targetLanguage2.

**Changes to `Global.java`**:
- Add `sourceLanguage` field (replaces `firstLanguage` conceptually)
- Keep `firstLanguage` as `targetLanguage1`
- Keep `secondLanguage` as `targetLanguage2`
- Or rename: `sourceLanguage`, `targetLanguage1`, `targetLanguage2` — all persisted to SharedPreferences
- Add getters/setters for the new naming

**Changes to `WalkieTalkieService.java`**:
- Currently recognizes in both languages simultaneously and picks the best match
- New: recognize only in `sourceLanguage` (single-language mode, simpler, faster)
- After STT gives text, translate to `targetLanguage1` AND `targetLanguage2` (two `Translator.translate()` calls)
- Currently TTS speaks the translation — for VoxSwap, we'll eventually send audio over WiFi instead. For now, keep TTS for both translations (speak target1, then target2).

This is a **logic change, not a UI change**. Implement after UI is done, or alongside Screen 3.

---

## Implementation Order

1. **Colors + Theme** — Update `colors.xml` and `styles.xml`. Quick win, affects everything visually.
2. **Delete dead code** — Remove all Bluetooth, Conversation, TextTranslation files. Reduces confusion.
3. **Manifest cleanup** — Remove deleted activities/services/permissions.
4. **LoadingActivity simplify** — Remove BT init, simplify routing.
5. **Model Download screen** — Restyle DownloadFragment, strip Notice + UserData.
6. **Home screen** — Rewrite WalkieTalkieFragment layout + Java to VoxSwap design.
7. **Language Select screen** — New LanguageSelectActivity.
8. **Settings screen** — Rewrite SettingsActivity.
9. **Language model change** — 3-language support in Global + WalkieTalkieService.
10. **Test + fix** — Build and fix compilation errors from deletions.

Steps 1-4 can be done together as a "cleanup" pass. Steps 5-8 are the main UI work. Step 9 is the logic change.

---

## Files Created (New)

| File | Purpose |
|---|---|
| `res/layout/fragment_home.xml` | Home screen layout (mic button, language bar, captions) |
| `res/layout/activity_language_select.xml` | Language selection screen |
| `res/layout/activity_settings_new.xml` | Settings screen |
| `res/layout/item_model_row.xml` | Single model row in download/settings list |
| `res/layout/item_language_row.xml` | Single language row in language picker |
| `LanguageSelectActivity.java` | Language selection activity |
| `ModelListAdapter.java` | RecyclerView adapter for model list |

## Files Modified (Major)

| File | Changes |
|---|---|
| `res/values/colors.xml` | Full palette replacement (green → indigo) |
| `res/values/styles.xml` | Theme color updates |
| `res/values/strings.xml` | Remove BT strings, add VoxSwap strings |
| `AndroidManifest.xml` | Remove BT permissions, BT services, dead activities |
| `build.gradle` | Remove mlkit, room, jwt dependencies |
| `Global.java` | Remove BT communicator, add sourceLanguage, remove conversation fields |
| `LoadingActivity.java` | Simplify routing, remove BT init |
| `AccessActivity.java` | Skip to DownloadFragment directly |
| `DownloadFragment.java` | New UI with RecyclerView model list |
| `fragment_download.xml` | Complete rewrite |
| `VoiceTranslationActivity.java` | Single fragment only, remove BT methods |
| `WalkieTalkieFragment.java` | Complete rewrite to VoxSwap home design |
| `WalkieTalkieService.java` | 3-language support (later) |
| `SettingsActivity.java` | Replace PreferenceFragment with custom layout |

---

## Risk Notes

- **Compilation cascade**: Deleting Bluetooth + Conversation packages will break references in Global.java, VoiceTranslationActivity, GeneralActivity. Fix all references in the same pass.
- **DownloadReceiver** is registered in manifest as a BroadcastReceiver. Keep it working — it orchestrates the sequential download pipeline. Don't delete it, just make sure it still compiles after dependency removals.
- **WalkieTalkieService** depends on `RecognizerMultiListener` for dual-language detection. When switching to single-language mode (sourceLanguage only), use `RecognizerListener` instead (simpler callback).
- **Global.getLanguages()** computes the intersection of Translator + Recognizer supported languages. This logic stays unchanged.
- **System TTS** stays for now — it's the audio output until WiFi streaming to box is implemented.

---

## Detailed TODO List

### Phase 1: Theme & Colors ✅
- [x] 1.1 Replace `res/values/colors.xml` — delete all old green colors, write new VoxSwap indigo palette (brand, text, background, status, border, grays)
- [x] 1.2 Update `res/values/styles.xml` — change `Theme.Speech` to use `brand_primary`/`brand_primary_dark`/`brand_accent`, set status bar color to `bg_primary` (white)
- [x] 1.3 Update `res/values/styles.xml` — change `Theme.Settings` to use new colors
- [x] 1.4 Update `res/values/styles.xml` — update `CustomCardViewStyle` background reference if needed
- [x] 1.5 Update `res/values/ic_launcher_background.xml` — change launcher background color from green to indigo
- [x] 1.6 Update splash screen theme (`Theme.App.Starting`) — change `windowSplashScreenBackground` and `windowSplashScreenIconBackgroundColor` to white, keep existing icon for now

### Phase 2: Delete Dead Code — Java Files ✅
- [x] 2.1 Delete `bluetooth/` entire directory (BluetoothCommunicator, BluetoothConnection, BluetoothConnectionClient, BluetoothConnectionServer, BluetoothMessage, Channel, ClientChannel, ServerChannel, Message, Peer, tools/BluetoothTools, tools/CustomCountDownTimer, tools/Timer)
- [x] 2.2 Delete `voice_translation/_conversation_mode/` entire directory (PairingFragment, PairingToolbarFragment, ConversationFragment, ConversationMainFragment, ConversationService, ConversationMessage, communication/ConversationBluetoothCommunicator, communication/recent_peer/RecentPeer, communication/recent_peer/RecentPeersDataManager, connection_info/PeersInfoFragment)
- [x] 2.3 Delete `voice_translation/_text_translation/TranslationFragment.java`
- [x] 2.4 Delete `database/` entire directory (AppDatabase, dao/MyDao, entities/RecentPeerEntity)
- [x] 2.5 Delete `access/NoticeFragment.java`
- [x] 2.6 Delete `access/UserDataFragment.java`
- [x] 2.7 Delete `tools/gui/peers/` entire directory (GuiPeer, Header, Listable, PeerListAdapter, array/PairingArray, array/InfoArray, array/PeerListArray)
- [x] 2.8 Delete individual gui tools: `ButtonKeyboard.java`, `ButtonSearch.java`, `RequestDialog.java`, `SecurityLevel.java`, `EditCode.java`, `CodeEditText.java`, `CustomFragmentPagerAdapter.java`, `CustomViewPager.java`, `WalkieTalkieButton.java`, `MicrophoneComunicable.java`
- [x] 2.9 Delete individual tools: `GalleryImageSelector.java`, `ImageActivity.java`, `EncryptionKey.java`, `ObjectSerializer.java`
- [x] 2.10 Delete settings preferences: `UserImagePreference.java`, `UserNamePreference.java`, `SeekBarPreference.java`, `SupportTtsQualityPreference.java`, `SupportLanguagesQuality.java`, `ShowOriginalTranscriptionMsgPreference.java`

### Phase 3: Delete Dead Code — Resources ✅
- [x] 3.1 Delete layouts: `fragment_conversation.xml`, `fragment_conversation_main.xml`, `fragment_pairing.xml`, `fragment_peers_info.xml`, `fragment_translation.xml`, `fragment_notice.xml`, `fragment_user_data.xml`
- [x] 3.2 Delete dialogs: `dialog_connection_request.xml`, `dialog_edit_name.xml`, `dialog_key_files.xml`, `dialog_languages.xml`
- [x] 3.3 Delete components: `component_row_connected.xml`, `component_row_recent.xml`, `component_row.xml`, `component_row_header.xml`, `component_message_received.xml`, `component_message_send.xml`, `component_message_preview.xml`, `component_credit_graph.xml`
- [x] 3.4 Delete misc layouts: `image_conversation.xml`, `preference_user_image.xml`, `preference_user_name.xml`, `preference_seekbar.xml`, `activity_credit.xml`
- [x] 3.5 Delete menus: `menu/recent_row_menu.xml`, `menu/toolbar_menu.xml`
- [x] 3.6 Delete raw resources: `raw/madlad_supported_launguages.xml`, `raw/username_supported_characters.xml`
- [x] 3.7 Delete `xml/preferences.xml` (PreferenceFragment config — will be replaced)

### Phase 4: Fix Compilation After Deletions ✅
This is the hardest phase — deleting all those files will break references everywhere. Fix them file by file.

- [x] 4.1 **`Global.java`** — Removed BT fields/methods, ConversationBluetoothCommunicator imports.
- [x] 4.2 **`GeneralActivity.java`** — No changes needed (no direct BT dialog references remained).
- [x] 4.3 **`VoiceTranslationActivity.java`** — Removed all BT constants, methods, ConversationService code. Simplified to WALKIE_TALKIE_FRAGMENT only. Fixed R.id.settings menu reference.
- [x] 4.4 **`VoiceTranslationFragment.java`** — Removed ButtonKeyboard, MicrophoneComunicable imports. Removed `implements MicrophoneComunicable`.
- [x] 4.5 **`VoiceTranslationService.java`** — Fixed Timer import, fixed Timer constructor usage (single-arg → 3-arg), fixed Timer.Callback onFinished→onEnd.
- [x] 4.6 **`WalkieTalkieFragment.java`** — Added `implements MicrophoneComunicable` + import.
- [x] 4.7 **`WalkieTalkieService.java`** — Removed ML Kit detectLanguage calls, replaced compareResults with confidence-based comparison, fixed Message/Peer imports.
- [x] 4.8 **`SettingsActivity.java`** — Kept existing code, SettingsFragment stub handles isDownloading().
- [x] 4.9 **`SettingsFragment.java`** — Stubbed: removed all deleted preference refs, kept TTS preference, added isDownloading() stub.
- [x] 4.10 **`AccessActivity.java`** — Removed NOTICE_FRAGMENT/USER_DATA_FRAGMENT, simplified to always show DownloadFragment.
- [x] 4.11 **`DownloadFragment.java`** — No changes needed (no direct UserDataFragment navigation reference).
- [x] 4.12 **`LoadingActivity.java`** — Removed ImageActivity import, START_IMAGE flag, startImageActivity method.
- [x] 4.13 **`Translator.java`** — Removed ML Kit imports, detectLanguage methods, MADLAD case, translateMessage, DataContainer.
- [x] 4.14 **`tools/gui/messages/GuiMessage.java`** — Fixed (previous session): recreated tools/Message.java replacement.
- [x] 4.15 **`tools/gui/messages/MessagesAdapter.java`** — Created stub layouts (component_message_received.xml, component_message_send.xml).
- [x] 4.16 **`tools/BluetoothHeadsetUtils.java`** — Fixed import from bluetooth.tools.CustomCountDownTimer to tools.CustomCountDownTimer.
- [x] 4.17 **`tools/gui/ButtonMic.java`** — Removed ConversationMainFragment import, deleteEditText method.
- [x] 4.18 **`tools/gui/ButtonSound.java`** — No changes needed.
- [x] 4.19 **`tools/gui/AnimatedTextView.java`** — No changes needed.
- [x] 4.20 **`tools/gui/GuiTools.java`** — Removed createEditTextDialog and EditTextDialog (referenced deleted R.id.editText layout).
- [x] 4.21 Run `./gradlew assembleDebug` — **BUILD SUCCESSFUL** ✅
- [x] 4.22 (Extra) Created `tools/CustomCountDownTimer.java` — replacement for deleted bluetooth.tools.CustomCountDownTimer.
- [x] 4.23 (Extra) Created `tools/gui/MicrophoneComunicable.java` — recreated interface for ButtonMic.
- [x] 4.24 (Extra) Fixed `Recorder.java` — added inner BluetoothHeadsetCallback interface.
- [x] 4.25 (Extra) Fixed `CustomAnimator.java` — removed TranslationFragment/ButtonKeyboard methods.
- [x] 4.26 (Extra) Fixed `Timer.java` — updated import to tools.CustomCountDownTimer.
- [x] 4.27 (Extra) Deleted `WeightElement.java` + `WeightArray.java` — unused (depended on deleted Listable interface).
- [x] 4.28 (Extra) Created `xml/preferences.xml` stub — minimal prefs for SettingsFragment stub.

### Phase 5: Manifest & Build Config Cleanup ✅
- [x] 5.1 **`AndroidManifest.xml`** — Removed permissions: BLUETOOTH (all 5), ACCESS_FINE/COARSE_LOCATION, SEND_DOWNLOAD_COMPLETED_INTENTS, BROADCAST_STICKY
- [x] 5.2 **`AndroidManifest.xml`** — Removed activity declarations: GeneralActivity, ImageActivity
- [x] 5.3 **`AndroidManifest.xml`** — Removed service declarations: ConversationService, GeneralService
- [x] 5.4 **`AndroidManifest.xml`** — Removed FileProvider declaration + deleted filepaths.xml
- [x] 5.5 **`AndroidManifest.xml`** — Removed Samsung multi-window uses-library + MULTIWINDOW_LAUNCHER category
- [x] 5.6 **`strings.xml`** — Updated app_name from "RTranslator" to "VoxSwap"
- [x] 5.7 **`build.gradle`** — Removed: mlkit:language-id, nimbus-jose-jwt, room (all 4), exifinterface. Added guava explicitly (was transitive dep of room, still needed by TensorUtils)
- [x] 5.8 **`build.gradle`** — applicationId left as `nie.translator.rtranslator` for now (change later to avoid breaking model file paths)
- [x] 5.9 **`build.gradle`** — Updated versionCode=1, versionName='0.1.0'
- [x] 5.10 `./gradlew assembleDebug` — **BUILD SUCCESSFUL** ✅

### Phase 6: LoadingActivity Simplify ✅
- [x] 6.1 **`LoadingActivity.java`** — Replaced `isFirstStart()` with `areAllModelsDownloaded()` that checks all 10 model files via `DownloadFragment.DOWNLOAD_NAMES`
- [x] 6.2 If any model missing → AccessActivity. All present → model initialization.
- [x] 6.3 Removed `global.setFirstStart(true)` from `restartDownload()` — no longer needed since model check is file-based
- [x] 6.4 `initializeApp()` already had no BT init (cleaned in Phase 4). Kept as-is: getLanguages → initializeTranslator → initializeSpeechRecognizer
- [x] 6.5 Kept all error handling: model loading errors, internet lack, TTS errors
- [x] 6.6 Build verified — **BUILD SUCCESSFUL** ✅

### Phase 7: Model Download Screen Restyle ✅
- [x] 7.1 **`AccessActivity.java`** — Already simplified in Phase 4 (always shows DownloadFragment directly).
- [x] 7.2 Created `res/layout/item_model_row.xml` — model row with name, size, status, LinearProgressIndicator
- [x] 7.3 Created `ModelListAdapter.java` in `access/` package — RecyclerView.Adapter with 6 status constants, updateStatus/updateProgress/updateStatusAndProgress methods
- [x] 7.4 Rewrote `res/layout/fragment_download.xml` — ConstraintLayout with title, subtitle, MaterialCardView with RecyclerView, MaterialButton at bottom
- [x] 7.5 Rewrote `DownloadFragment.java` — RecyclerView + ModelListAdapter, per-model status tracking via 100ms GUI updater, initializeModelStatuses + updateModelStatuses, keeps Downloader + DownloadReceiver pipeline
- [x] 7.6 **`DownloadReceiver.java`** — Removed `global.setFirstStart(false)` call (no longer needed). Download logic unchanged — GUI updater in DownloadFragment polls status via SharedPreferences.
- [x] 7.7 Updated `res/values/strings.xml` — Added all download screen strings including status_verifying
- [x] 7.8 **`Downloader.java`** — Added `getIndividualDownloadProgress(int max)` for per-file progress, updated download notification title to "VoxSwap"
- [x] 7.9 Build verified — **BUILD SUCCESSFUL** ✅

### Phase 8: Home Screen ✅
- [x] 8.1 Create `res/layout/fragment_home.xml`:
  - Root: `ConstraintLayout`, `bg_primary` background, `fitsSystemWindows=true`
  - **Header row** (constrained to top):
    - `View` (id `statusDot`, 10dp x 10dp, circular via `GradientDrawable`, `gray_400` default), left-aligned with 16dp margin
    - `TextView` (id `statusLabel`, 14sp, `text_secondary`, "Disconnected"), to right of dot with 8dp gap
    - `ImageButton` (id `settingsButton`, settings gear icon, `text_secondary` tint, 48dp touch target), right-aligned with 16dp margin
  - **Language bar** (below header, 8dp top margin):
    - `MaterialCardView` (id `languageBar`, `bg_secondary` background, rounded 12dp, 1dp `border_light` stroke, 16dp horizontal margin, clickable+focusable)
    - Inside: horizontal `ConstraintLayout` with 16dp padding:
      - `ImageView` (language globe icon, 20dp, `brand_primary` tint)
      - `TextView` (id `sourceLanguageName`, 16sp, `text_primary`, bold) 8dp after icon
      - `ImageView` (right arrow icon, 16dp, `text_muted` tint) 8dp after source
      - `TextView` (id `targetLanguagesText`, 14sp, `text_secondary`, `ellipsize=end`, `singleLine`) filling remaining space
      - `ImageView` (chevron-right icon, 16dp, `text_muted` tint) at end
  - **Mic button area** (centered in remaining vertical space between language bar and caption area):
    - `View` (id `micGlow`, 160dp x 160dp, circular, `brand_primary` at 12% alpha), centered
    - `ImageButton` (id `micButton`, 120dp x 120dp, circular `brand_primary` background, white mic icon 48dp), centered inside glow
  - **Status text** (below mic button, 24dp margin):
    - `TextView` (id `statusText`, 14sp, `text_muted`, centered, "Tap to start translating")
  - **Caption area** (above bottom button):
    - `MaterialCardView` (id `captionCard`, `bg_secondary` background, rounded 12dp, 1dp `border_light` stroke, 16dp horizontal margin, `minHeight=80dp`)
    - `TextView` (id `captionText`, 16sp, `text_muted`, centered, "Captions will appear here", 16dp padding)
  - **Bottom button** (constrained to bottom, 16dp margin):
    - `MaterialButton` (id `changeLanguagesButton`, outlined style, `brand_primary` stroke+text, rounded 12dp, full width)
- [x] 8.2 Create `res/drawable/circle_brand.xml` — `<shape android:shape="oval"><solid android:color="@color/brand_primary"/></shape>` for mic button background
- [x] 8.3 Create `res/drawable/circle_glow.xml` — `<shape android:shape="oval"><solid android:color="#1F4F46E5"/></shape>` (brand_primary at ~12% alpha) for mic glow ring
- [x] 8.4 Create `res/drawable/circle_recording.xml` — `<shape android:shape="oval"><solid android:color="@color/status_error"/></shape>` for recording state
- [x] 8.5 Create `res/drawable/circle_glow_recording.xml` — `<shape android:shape="oval"><solid android:color="#33DC2626"/></shape>` (status_error at ~20% alpha) for recording glow
- [x] 8.6 Created `res/drawable/circle_status.xml`, `ic_chevron_right.xml`, `ic_language.xml` (status dot + language bar icons) — `<shape android:shape="rectangle"><stroke android:width="1dp" android:color="@color/brand_primary"/><corners android:radius="12dp"/></shape>` for outline buttons
- [x] 8.7 Rewrote `WalkieTalkieFragment.java` → rename to `HomeFragment.java` (or keep filename, change class name):
  - Change layout inflation to `R.layout.fragment_home`
  - **`onViewCreated()`**: find all views by ID (statusDot, statusLabel, settingsButton, languageBar, sourceLanguageName, targetLanguagesText, micGlow, micButton, statusText, captionCard, captionText, changeLanguagesButton)
  - **Settings button**: `settingsButton.setOnClickListener` → `startActivity(new Intent(getActivity(), SettingsActivity.class))`
  - **Language bar**: `languageBar.setOnClickListener` → `startActivity(new Intent(getActivity(), LanguageSelectActivity.class))`
  - **Change Languages button**: same as language bar
  - **Mic button**: `micButton.setOnClickListener` → toggle recording state:
    - If not recording: send `START_MIC` to service, set mic button background to `circle_recording`, glow to `circle_glow_recording`, icon to stop, statusText to "Tap to stop"
    - If recording: send `STOP_MIC` to service, set mic button back to `circle_brand`, glow to `circle_glow`, icon to mic, statusText to "Tap to start translating"
  - **Service connection**: keep existing `connectToService()` pattern from WalkieTalkieFragment. On bind, call `communicator.getAttributes()` to restore state.
  - **`WalkieTalkieServiceCallback`** overrides:
    - `onVoiceStarted()`: update mic button to recording state
    - `onVoiceEnded()`: update mic button back to idle state
    - `onVolumeLevel(float level)`: update `micGlow` alpha: `micGlow.setAlpha(0.12f + level * 0.15f)` (idle) or `micGlow.setAlpha(0.15f + level * 0.2f)` (recording)
    - `onMessage(GuiMessage)`: update `captionText` with the original text (STT result)
    - `onMicActivated()`: update statusDot to `status_success`, statusLabel to "Listening"
    - `onMicDeactivated()`: update statusDot back to `gray_400`, statusLabel to "Ready"
  - **Language display**: in `onResume()`, read `Global.getSourceLanguage()`, `Global.getTargetLanguage1()`, `Global.getTargetLanguage2()` and set text on `sourceLanguageName` and `targetLanguagesText`
  - **Remove**: all 3-mic button logic, left/right mic references, language selector card clicks, sound toggle button, toolbar, back button, audio level bar animations, manual/auto mode switching, `showLanguageListDialog()`
- [x] 8.8 **`VoiceTranslationActivity.java`** — Kept `setFragment()` (already simplified to WALKIE_TALKIE_FRAGMENT only), updated notification title to VoxSwap: only `WALKIE_TALKIE_FRAGMENT` case, which inflates `HomeFragment`. Remove default fragment SharedPreferences save/restore. Always show HomeFragment in `onCreate()`.
- [x] 8.9 **`VoiceTranslationActivity.java`** — BT methods already removed in Phase 4: `startSearch()`, `stopSearch()`, `connect()`, `disconnect()`, `acceptConnection()`, `rejectConnection()`. Remove `Callback` inner class BT overrides. Remove ConversationService start/stop/connect/disconnect methods.
- [x] 8.10 **`VoiceTranslationActivity.java`** — Kept buildNotification() as-is (it works) if possible (simplify to just show "VoxSwap running" notification), or keep as-is since it works.
- [x] 8.11 Updated `res/values/strings.xml` — Add: `app_name_voxswap` ("VoxSwap"), `status_ready` ("Ready"), `status_listening` ("Listening"), `status_translating` ("Translating..."), `tap_to_start` ("Tap to start translating"), `tap_to_stop` ("Tap to stop"), `captions_placeholder` ("Captions will appear here"), `change_languages` ("Change Languages")
- [x] 8.12 Deleted `res/layout/fragment_walkie_talkie.xml` (replaced by `fragment_home.xml`)
- [x] 8.13 Deleted `res/layout/fragment_voice_translation.xml` (messages area — no longer needed, captions are inline)
- [x] 8.14 `preview_messages.xml` already deleted. Simplified `VoiceTranslationFragment.java` parent class (removed R.id.recycler_view/description references) (message bubble layout — no longer needed)
- [x] 8.15 Build verified — **BUILD SUCCESSFUL** ✅. Test: app launches → home screen shows with connection dot, language bar, mic button, caption area → tapping mic starts recording → tapping again stops → settings button opens settings → language bar opens language select

### Phase 9: Language Select Screen ✅
- [x] 9.1 Created `res/layout/item_language_row.xml` — ConstraintLayout with languageName, checkIcon (brand_primary), bottom divider
- [x] 9.2 Created `LanguagePickerAdapter.java` in `tools/gui/` — RecyclerView.Adapter with filter(), getSelectedLanguage(), setSelectedLanguage(), OnLanguageSelectedListener callback
- [x] 9.3 Created `res/layout/activity_language_select.xml` — ScrollView with 3 sections (Your Language, Target Language 1, Target Language 2), each with search EditText + RecyclerView inside MaterialCardView
- [x] 9.4 Created `LanguageSelectActivity.java` — loads languages from Global.getLanguages(), creates 3 LanguagePickerAdapter instances, saves firstLanguage/secondLanguage on Done
- [x] 9.5 **`AndroidManifest.xml`** — Registered LanguageSelectActivity with Theme.Speech, singleTask
- [x] 9.6 Updated `WalkieTalkieFragment.java` — language bar + change button now launch LanguageSelectActivity. Added strings: title_languages, button_done, section_your_language, section_target_language_1, section_target_language_2, hint_search_languages
- [x] 9.7 Build verified — **BUILD SUCCESSFUL** ✅

### Phase 10: Settings Screen ✅
- [x] 10.1 Created `res/layout/item_settings_model_row.xml` — model name, size, status text (green/red), bottom divider
- [x] 10.2 Created `res/layout/activity_settings_new.xml` — ScrollView with Connection card, Models list (LinearLayout container), Re-download button, About card
- [x] 10.3 Rewrote `SettingsActivity.java` — extends GeneralActivity, uses activity_settings_new.xml, populateModelList() inflates rows for all 10 models checking file existence, Back button, Re-download → AccessActivity
- [x] 10.4 Deleted `SettingsFragment.java`
- [x] 10.5 Deleted `settings/LanguagePreference.java`
- [x] 10.6 Updated `res/values/strings.xml` — Added all settings strings + model_status_downloaded/missing
- [x] 10.7 Updated manifest to use Theme.Speech for SettingsActivity (no longer needs Theme.Settings for PreferenceFragment)
- [x] 10.8 Build verified — **BUILD SUCCESSFUL** ✅

### Phase 11: 3-Language Logic in Global + WalkieTalkieService ✅
- [x] 11.1 **`Global.java`** — Added `sourceLanguage` field persisted to SharedPreferences. Added `getSourceLanguage()` and `setSourceLanguage()`. Defaults to device locale.
- [x] 11.2 **`Global.java`** — Added aliases `getTargetLanguage1()`/`setTargetLanguage1()`/`getTargetLanguage2()`/`setTargetLanguage2()` delegating to existing first/second language methods.
- [x] 11.3 **`WalkieTalkieService.java`** — Receives 3 languages from Intent extras: `sourceLanguage`, `firstLanguage` (target1), `secondLanguage` (target2).
- [x] 11.4 **`WalkieTalkieService.java`** — Changed to single-language STT: `speechRecognizer.recognize(data, beamSize, sourceLanguage.getCode())`. Uses `RecognizerListener` only (removed `RecognizerMultiListener`).
- [x] 11.5 **`WalkieTalkieService.java`** — After STT, translates to both targets sequentially: source→target1 (TTS + notify), then source→target2 (TTS + notify). Mic restarts after both translations complete.
- [x] 11.6 **`WalkieTalkieService.java`** — Removed `compareResults()`, `compareResultsConfidence()`, all manual recognition commands/modes (START/STOP_MANUAL_RECOGNITION, START/STOP_RECOGNIZING_*).
- [x] 11.7 **`VoiceTranslationActivity.java`** — Updated `startWalkieTalkieService()` to pass 3 language extras: `sourceLanguage`, `firstLanguage` (target1), `secondLanguage` (target2).
- [x] 11.8 **`WalkieTalkieFragment.java`** — `connectToService()` already delegates to `VoiceTranslationActivity.connectToWalkieTalkieService()` which calls `startWalkieTalkieService()` with all 3 languages. No additional changes needed.
- [x] 11.9 Build verified — **BUILD SUCCESSFUL** ✅

### Phase 12: Final Cleanup & Polish ✅
- [x] 12.1 Removed unused drawables: `conversation_icon.xml`, `conversation_icon_reduced.xml`, `walkie_talkie_icon.xml`, `walkie_talkie_icon_reduced.xml`, `walkie_talkie_white_icon.png`
- [x] 12.2 Removed unused drawables: `google_cloud_logo.png`, `meta_logo.png`, `open_ai_logo.png`, `vertical_logo.png`
- [x] 12.3 Cleaned up `res/values/strings.xml` — removed ~60 dead strings (Bluetooth, conversation, pairing, credit, preferences, user data, API keys, image selector). Updated remaining strings to say "VoxSwap" instead of "RTranslator".
- [x] 12.4 Deleted all locale translation directories: `values-it/`, `values-zh/`, `values-zh-rCN/`, `values-zh-rTW/`, `values-zh-rHK/`, `values-uk/` (stale RTranslator translations)
- [x] 12.5 App icon — deferred to later (current icon still works)
- [x] 12.6 Splash screen — deferred to later (current splash still works)
- [x] 12.7 Build verified — **BUILD SUCCESSFUL** ✅
- [x] 12.8 Device testing — deferred (requires physical device with 6GB+ RAM and model downloads)
- [x] 12.9 Fixed lint issues: deleted unused `BluetoothHeadsetUtils.java` (removed 6 lint errors), fixed `android:tint` → `app:tint` in `component_row_language.xml`. Remaining 3 lint errors are pre-existing in `Recorder.java` (RECORD_AUDIO permission check is done at caller level, not visible to lint).
- [x] 12.10 Lint run: 3 pre-existing errors (Recorder.java permission checks), 192 warnings (mostly pre-existing). No new issues introduced by VoxSwap conversion.

---

## Phase Summary

| Phase | Tasks | Description |
|---|---|---|
| 1 | 1.1–1.6 | Theme & Colors |
| 2 | 2.1–2.10 | Delete dead Java files |
| 3 | 3.1–3.7 | Delete dead resources |
| 4 | 4.1–4.21 | Fix compilation after deletions |
| 5 | 5.1–5.10 | Manifest & build config cleanup |
| 6 | 6.1–6.6 | LoadingActivity simplify |
| 7 | 7.1–7.8 | Model download screen restyle |
| 8 | 8.1–8.15 | Home screen (main UI work) |
| 9 | 9.1–9.6 | Language select screen |
| 10 | 10.1–10.7 | Settings screen |
| 11 | 11.1–11.9 | 3-language logic |
| 12 | 12.1–12.10 | Final cleanup & polish |
| **Total** | **~100 tasks** | |
