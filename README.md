# DEZGRE Mobile — isolated build branch

This branch is an isolated build workspace for the native DEZGRE Android/iOS app.

- It does not modify `Akcley/dezgre-system`.
- It calls only `https://api.dezgre.com/v1`.
- Android 0.1.0 implements login, multi-store selection, home, `/me`, `/store`, `/stores`, products and product detail.
- Bearer storage uses Android Keystore AES-GCM.
- No orders, NATI, WhatsApp, Shalom, database access, web auth or cookies are implemented.
- The source ZIP also contains an iOS SwiftUI/URLSession/Keychain counterpart.

The APK produced by GitHub Actions is published at `dist/DEZGRE-Mobile-v0.1.0.apk` on this branch.
