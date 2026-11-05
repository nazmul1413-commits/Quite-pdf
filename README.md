# Quiet PDF — Android source project

A minimal, offline-first PDF reader starter project for Android (Kotlin + Jetpack Compose).

## Features
- System PDF picker, recent documents, and last-reading-page persistence
- Page rendering with Android PdfRenderer
- Local bookmarks and app-side highlight overlays
- Text search using PDFBox-Android
- Light/dark app theme and night-reading toggle
- No ads, analytics, account, or network permission

## Notes
Highlights are stored as app-local overlays, not embedded into the original PDF. Search requires selectable text; scanned pages without OCR text cannot be searched. This is a starter source project; the APK must be built and tested.

Minimum Android version: Android 8.0 (API 26).
