# DEZGRE Mobile — isolated build branch

This branch is an isolated build workspace for the native DEZGRE Android/iOS app.

- It does not modify `Akcley/dezgre-system`.
- It calls only `https://api.dezgre.com/v1`.
- Android 0.1.1 implements login, multi-store selection, home, `/me`, `/store`, `/stores`, products and product detail.
- Bearer storage uses Android Keystore AES-GCM.
- The Android UI now follows the responsive DEZGRE web visual language: dark mobile topbar/drawer, DEZGRE typography hierarchy, purple accents, responsive product cards, profile and notification controls.
- Android notification test channels use audible high-importance channels with sound and vibration; personalized remote sounds remain gated on certified Bubble V1 notification endpoints.
- Preview APK builds use a stable preview signing key restored by CI so later preview versions can update over an installed stable-signed preview build.
- No orders, NATI, WhatsApp, Shalom, database access, web auth or cookies are implemented.
- The source ZIP also contains an iOS SwiftUI/URLSession/Keychain counterpart.

The current Android preview APK is published at `dist/DEZGRE-Mobile-v0.1.1.apk` on this branch.
