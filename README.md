# ZipExtract

Aplikasi Android untuk mengelola file: **buat ZIP**, **extract**, **kompresi**, serta operasi file seperti **copy / cut / paste**, rename, hapus, dan buat folder.

## Fitur

- File browser (navigasi folder storage)
- Seleksi multi-file (tap lama / mode seleksi)
- Buat ZIP (opsional kompresi maksimal)
- Extract ZIP ke folder baru
- Copy, Cut, Paste / Move
- Rename, Delete, Buat folder
- Progress indikator saat zip / extract / paste
- Buka file `.zip` dari app lain (intent VIEW)

## Stack

- Kotlin
- Jetpack Compose + Material 3
- Coroutines
- `java.util.zip` (ZipInputStream / ZipOutputStream)

## Cara jalankan (lokal)

1. Buka folder project ini di **Android Studio** (Ladybug / versi baru).
2. Biarkan Gradle sync selesai.
3. Hubungkan device/emulator (API 26+).
4. Run konfigurasi `app`.

Saat pertama dibuka, app akan meminta **izin akses semua file** (Android 11+) agar bisa browse & menulis di storage.

## Build APK di GitHub Actions

Setiap push ke `main` akan otomatis build APK debug.

1. Buka tab **Actions** di repo GitHub.
2. Pilih workflow **Build APK**.
3. Setelah selesai, unduh artifact **ZipExtract-debug**.

Bisa juga dijalankan manual lewat **Actions → Build APK → Run workflow**.

## Struktur

```
app/src/main/java/com/zipextract/app/
  MainActivity.kt
  data/
    Models.kt
    FileOperations.kt
    ZipManager.kt
  ui/
    FileBrowserViewModel.kt
    FileBrowserScreen.kt
    theme/Theme.kt
```

## Catatan

- Min SDK 26, Target SDK 35
- Untuk production, pertimbangkan Scoped Storage / SAF jika tidak ingin memakai `MANAGE_EXTERNAL_STORAGE`
- Belum mendukung RAR/7z (hanya ZIP / JAR / APK sebagai archive)
