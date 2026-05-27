# Naice Notes

A small personal Android app for sectioned bullet-list note-taking, with a home-screen widget and a recipe-photo → ingredient extractor.

Built for Android 14+ (API 34), targeted at a Samsung S24 sideload. Single-user, local-only storage (Room), no accounts, no cloud sync.

## Features

- **Sections + checkable items** — pill-style section tabs across the top, swipe to delete (with undo), drag to reorder, tap text to inline-edit
- **Home-screen widget** — mirrors a chosen section, tap to toggle items, "+" for quick-add, tap the logo to open the app at that section. Classic `AppWidgetProvider` + `RemoteCollectionItems` (API 31+) for atomic, flash-free updates
- **Recipe scan** — pick a recipe photo, the app sends it to a private n8n webhook which proxies a multimodal LLM, returns extracted ingredient strings, the app shows a review screen before appending to a section

## Recipe-scan backend (private)

The recipe-scan flow depends on a personal n8n webhook that calls an LLM behind credentials I'm not publishing. If you build this repo without configuring `RECIPE_SCAN_URL` and `RECIPE_SCAN_SECRET` in `local.properties`, the rest of the app still works — only the "scan recipe" button surfaces a "not configured" message.

To wire up your own backend, the shape the app expects is:

| Request | Response |
|---|---|
| `POST` with multipart body containing `image` (JPEG) and header `X-Auth: <shared-secret>` | `200 {"ingredients": ["2 cups flour", "1 tsp salt", ...]}` |

Any backend that satisfies that contract — n8n, a Cloud Function, a small server — will work. See `local.properties.example` for the two values the build needs.

## Building

Requires Android Studio (Hedgehog or later) and JDK 17+.

```bash
cp local.properties.example local.properties
# edit local.properties — fill in sdk.dir, and (optionally) the recipe-scan vars
./gradlew :app:assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

To install over wireless ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.x |
| UI | Jetpack Compose, Material 3 |
| DB | Room (Flow-based observables) |
| Widget | Classic `AppWidgetProvider` + `RemoteViews` + `RemoteCollectionItems` (Glance was tried first but never refreshed on Samsung One UI) |
| Network | OkHttp + kotlinx.serialization |
| Image picker | `ActivityResultContracts.PickVisualMedia` |
| Reorder | [`sh.calvin.reorderable`](https://github.com/Calvin-LL/Reorderable) |

## License

MIT — see [LICENSE](./LICENSE).
