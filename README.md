# ZipExtract

Aplikasi Android untuk mengelola file: **buat ZIP**, **extract**, **kompresi**, **baca PDF**, **lihat foto/gambar**, serta operasi file seperti **copy / cut / paste**, rename, hapus, dan buat folder.

## Fitur

- File browser (navigasi folder storage)
- Seleksi multi-file (tap lama / mode seleksi)
- Buat ZIP (opsional kompresi maksimal)
- Extract ZIP ke folder baru
- Baca PDF (scroll per halaman via `PdfRenderer`)
- Lihat foto/gambar (JPG, PNG, WEBP, GIF, BMP, …) dengan zoom & pan
- Copy, Cut, Paste / Move
- Rename, Delete, Buat folder
- Progress indikator saat zip / extract / paste
- Buka file `.zip` / PDF / gambar dari app lain (intent VIEW)

## Stack

- Kotlin
- Jetpack Compose + Material 3
- Coroutines + Coil (gambar)
- `java.util.zip` + Android `PdfRenderer`

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
    viewer/
      PdfViewerScreen.kt
      ImageViewerScreen.kt
```

## Catatan

- Min SDK 26, Target SDK 35
- Untuk production, pertimbangkan Scoped Storage / SAF jika tidak ingin memakai `MANAGE_EXTERNAL_STORAGE`
- Archive: ZIP / JAR / APK (belum RAR/7z)
- Beberapa format gambar khusus (mis. HEIC) mungkin tidak ter-decode di semua device
