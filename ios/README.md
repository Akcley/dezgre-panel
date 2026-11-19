# DEZGRE Mobile iOS 0.1.32 — initial native panel port

Isolated from Android and backend. Source base: Android 0.1.32 commit 39c5df776ffa63e646a1314e148f695cd34bc875.
Uses V1 login, store selection, refresh, panel-access and logout contracts with Keychain and WKWebView. Reuses Android vector logo.

On macOS with Xcode: brew install xcodegen librsvg; bash ios/prepare.sh.
Open ios/DEZGRE.xcodeproj, select your actual Apple Development team and provisioning configuration; run on a registered iPhone. The workflow builds a device-targeted unsigned IPA. An unsigned IPA cannot be installed normally.

For optional signing configure GitHub secrets IOS_CERTIFICATE_P12_BASE64, IOS_CERTIFICATE_PASSWORD, IOS_PROVISIONING_PROFILE_BASE64, IOS_TEAM_ID and optionally IOS_SIGNING_IDENTITY (default Apple Development). The profile must match com.dezgre.mobile and the distribution method. Store the password-protected P12 and profile privately. Never commit credentials. Android JKS cannot sign iOS.

Limits: first port not tested on an iPhone. The panel is loaded using the server access URL; design and store configuration remain server-controlled. Apple restrictions prevent Android-style automatic SMS sending and reading the system SMS inbox. Existing Swift notification helper is included but APNs registration, capability and backend delivery are not active or certified. Native push and custom sounds remain pending. Camera, microphone, attachments, session expiry and multi-store require device tests. No production or Android edits.

## 0.1.33
Ports the Android login layout and store selection, removes the native permanent toolbar, requests notification permission and registers a live APNs token via existing PUT /notifications/devices. No invented tokens or fake pushes. Unsigned IPA is for UI testing; APNs requires a compatible signed entitlement and backend APNS_* configuration. A signing profile must authorize push; the signed workflow selects the profile's APNs environment. Notification tap routing, custom sounds, web logout transition and real device delivery remain unverified. Android and production untouched.

## 0.1.37
Native status-bar safe area, bottom edge remains extended. WebKit playback follows Android gesture setting. Persistent device ID and recovery for stale refresh tokens. Prefetches scoped custom sound files from existing config into Library/Sounds as short PCM CAF; APNs server must reference matching filenames. Real-device media, background notifications, Apple signing and App Store readiness require verification. Android SMS gateway is not available on iOS.
