# DEZGRE Mobile iOS 0.1.32 — initial native panel port

Isolated from Android and backend. Source base: Android 0.1.32 commit 39c5df776ffa63e646a1314e148f695cd34bc875.
Uses V1 login, store selection, refresh, panel-access and logout contracts with Keychain and WKWebView. Reuses Android vector logo.

On macOS with Xcode: brew install xcodegen librsvg; bash ios/prepare.sh.
Open ios/DEZGRE.xcodeproj, select your actual Apple Development team and provisioning configuration; run on a registered iPhone. The workflow builds a device-targeted unsigned IPA. An unsigned IPA cannot be installed normally.

For optional signing configure GitHub secrets IOS_CERTIFICATE_P12_BASE64, IOS_CERTIFICATE_PASSWORD, IOS_PROVISIONING_PROFILE_BASE64, IOS_TEAM_ID and optionally IOS_SIGNING_IDENTITY (default Apple Development). The profile must match com.dezgre.mobile and the distribution method. Store the password-protected P12 and profile privately. Never commit credentials. Android JKS cannot sign iOS.

Limits: first port not tested on an iPhone. The panel is loaded using the server access URL; design and store configuration remain server-controlled. Apple restrictions prevent Android-style automatic SMS sending and reading the system SMS inbox. Existing Swift notification helper is included but APNs registration, capability and backend delivery are not active or certified. Native push and custom sounds remain pending. Camera, microphone, attachments, session expiry and multi-store require device tests. No production or Android edits.
