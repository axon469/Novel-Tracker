# Novel Tracker DB

A minimalistic app for collecting the novels you read and the details that matter.

Built as a single self-contained HTML file. It runs in any modern browser as a demo and is designed to be packaged as an Android app using a WebView.

**Version:** 1.0.0

## Features (v1.0.0)

- Floating bottom navigation: Home, Add, Settings
- Empty Home screen, ready for the novel list
- Settings
  - **Theme:** Dark, Light, or System
  - **Design style:** Dots, Grid, or Plain background
  - **General:** Week start (Saturday, Sunday, Monday)
  - **About:** App name and version
- Preferences saved on the device
- Mobile-first layout with safe-area support for notches and system bars
- No external libraries, fonts, or network requests, so it works offline

## Project structure

```
.
├── index.html    # The entire app: markup, styles, and scripts
└── README.md
```

## Run the demo

Open `index.html` in a browser. For the best preview, use your browser's mobile view.

## Android WebView

The Android project should load the file from its assets folder:

```
app/src/main/assets/index.html
```

```kotlin
webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true   // required to save settings
webView.loadUrl("file:///android_asset/index.html")
```

Handle the system back button so sub-screens in Settings close correctly:

```kotlin
onBackPressedDispatcher.addCallback(this) {
    if (webView.canGoBack()) webView.goBack() else finish()
}
```

For correct spacing under system bars, enable edge-to-edge display in the activity.

## Roadmap

- Add novels and their details
- Backup and data
- Stats and more settings

## License

License to be decided.
