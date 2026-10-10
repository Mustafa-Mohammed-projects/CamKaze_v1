# Cam_Kaze (Kotlin)

Dual-mode camera for Android 13+ (arm64-v8a only): Jetpack Compose (Material 3) + CameraX.
No third-party crypto library: AES-256-GCM / PBKDF2 come from `javax.crypto`, keys from Android Keystore.

## Your icon and presplash
Replace these two files (placeholders are included so the project builds):

| file | what | tips |
|---|---|---|
| `app/src/main/res/drawable/icon.png` | launcher icon | square, transparent or full-bleed, 512x512. It is placed in the adaptive-icon safe zone automatically (dark `#121212` background, see `res/values/colors.xml` `ic_launcher_background`). |
| `app/src/main/res/drawable/presplash.png` | splash shown at launch | any size; shown centred (`ContentScale.Fit`) on `#121212` for ~0.9 s. Keep it under ~1 MB to keep the APK small. |

Keep the exact names (`icon.png`, `presplash.png`).

## Build on GitHub
Push to `main` -> **Actions -> Build APK** (unit tests, then `assembleRelease`) -> download the artifact.
Signed with the debug key unless you add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets.

## Features
* **Normal**: photos -> `Pictures/cam_kaze`, videos -> `Movies/cam_kaze` (MediaStore). Gallery grouped by day;
  long-press: Share, Move to Secure, Delete (overwritten first), Details. Pinch/slider zoom, flash/torch, front/back.
* **Secure**: photos are encrypted from the in-memory JPEG; videos are recorded to a private temp file, streamed
  through chunked AES-GCM and the plaintext is shredded at once. Encrypted videos play through a custom
  `MediaDataSource` (decrypted chunk by chunk in RAM, seekable). Thumbnails are encrypted separately.

## Security design
* Vault key `VK` is random; it is wrapped by (password -> PBKDF2-600k) **and** an Android Keystore key
  (StrongBox when available) => files copied off the phone cannot be brute-forced.
* Per-file keys `HMAC(VK, fileSalt)`; GCM header is authenticated; video chunks are bound to their index.
* Password >= 12 chars. Failed attempts are counted before verification, persisted, and throttled
  (5th failure 30 s, doubling, max 1 h). Optional: **erase all secure files after 30 failed attempts**
  (shred + destroy Keystore key + config), also available as "Erase vault now" and "Forgot password".
* Capture date/type/duration live **inside** the encryption; all `.kaze` files share one fixed mtime and random names.
* Shared copies: EXIF/XMP/comments stripped losslessly (only orientation kept), shredded by a JobScheduler job
  (~15 min, works with the app closed) + on app start + on resume.
* Secure delete = overwrite (random, zeros) + fsync + truncate + rename + unlink. Normal originals moved to
  Secure are overwritten in place when Android allows it, then deleted.
* `FLAG_SECURE` + Recents screenshot disabled on secure screens; key zeroed on pause (deferred only while a
  secure video is still being encrypted).

## Known limits
* Secure **video** needs a short-lived plaintext temp file inside the app's private no-backup folder (CameraX can only
  record to a seekable file). It is shredded right after encryption and at the next app start if interrupted.
* Overwriting cannot be guaranteed on flash storage (wear levelling/copy-on-write); the encryption + Keystore
  crypto-erase is the real protection.
* The JVM cannot guarantee every copy of a password/key leaves RAM (String/GC).
* No biometric unlock yet. Old v1 vaults cannot be opened by this version (the lock screen offers to erase them).
* Not yet built/run on a device by the author: expect small first-build fixes.
