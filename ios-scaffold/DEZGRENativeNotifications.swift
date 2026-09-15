import Foundation
import Security
import UIKit
import UserNotifications

// This file is intentionally transport-ready but not wired to an iOS target yet.
// It contains no backend secret and uses only the certified API V1 contract.

enum DEZGREEventType: String {
    case webOrderCreated = "WEB_ORDER_CREATED"
    case aiOrderCreated = "AI_ORDER_CREATED"
    case whatsappDisconnected = "WHATSAPP_CONNECTION_DISCONNECTED"
    case whatsappReconnectStarted = "WHATSAPP_RECONNECT_STARTED"

    var route: DEZGRERouteKind {
        switch self {
        case .webOrderCreated, .aiOrderCreated:
            return .orders
        case .whatsappDisconnected, .whatsappReconnectStarted:
            return .connections
        }
    }
}

enum DEZGRERouteKind {
    case orders
    case connections
}

struct DEZGRENotificationRoute {
    let kind: DEZGRERouteKind
    let resourceId: String?
    let storeId: String
    let eventId: String
}

struct DEZGREPushEvent {
    let eventId: String
    let eventType: DEZGREEventType
    let storeId: String
    let title: String
    let body: String
    let resourceId: String?
    let occurredAt: String?

    init?(userInfo: [AnyHashable: Any]) {
        guard
            let rawEventId = DEZGREPushEvent.string(userInfo, "eventId", "event_id"),
            let rawType = DEZGREPushEvent.string(userInfo, "eventType", "event_type", "eventKey", "event_key"),
            let type = DEZGREEventType(rawValue: rawType.uppercased()),
            let storeId = DEZGREPushEvent.string(userInfo, "storeId", "store_id")
        else {
            return nil
        }

        eventId = rawEventId
        eventType = type
        self.storeId = storeId
        title = DEZGREPushEvent.string(userInfo, "title") ?? DEZGREPushEvent.defaultTitle(type)
        body = DEZGREPushEvent.string(userInfo, "body", "message") ?? ""
        resourceId = DEZGREPushEvent.string(
            userInfo,
            "resourceId", "resource_id",
            "orderId", "order_id",
            "connectionId", "connection_id"
        )
        occurredAt = DEZGREPushEvent.string(userInfo, "occurredAt", "occurred_at")
    }

    var route: DEZGRENotificationRoute {
        DEZGRENotificationRoute(
            kind: eventType.route,
            resourceId: resourceId,
            storeId: storeId,
            eventId: eventId
        )
    }

    private static func string(_ payload: [AnyHashable: Any], _ keys: String...) -> String? {
        for key in keys {
            if let value = payload[key] as? String {
                let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !clean.isEmpty && clean.lowercased() != "null" { return clean }
            } else if let value = payload[key] {
                let clean = String(describing: value).trimmingCharacters(in: .whitespacesAndNewlines)
                if !clean.isEmpty && clean.lowercased() != "null" { return clean }
            }
        }
        return nil
    }

    private static func defaultTitle(_ type: DEZGREEventType) -> String {
        switch type {
        case .webOrderCreated: return "Nueva venta web"
        case .aiOrderCreated: return "Nueva venta de NATI"
        case .whatsappDisconnected: return "WhatsApp desconectado"
        case .whatsappReconnectStarted: return "Reconexión de WhatsApp"
        }
    }
}

final class DEZGREEventDeduplicator {
    private let defaults: UserDefaults
    private let key = "dezgre.notification.seen.v1"
    private let ttl: TimeInterval = 7 * 24 * 60 * 60
    private let maxEvents = 256
    private let lock = NSLock()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func markIfNew(_ eventId: String) -> Bool {
        let clean = eventId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }

        let now = Date().timeIntervalSince1970
        var seen = defaults.dictionary(forKey: key) as? [String: Double] ?? [:]
        seen = seen.filter { now - $0.value <= ttl }
        guard seen[clean] == nil else {
            defaults.set(seen, forKey: key)
            return false
        }
        seen[clean] = now
        if seen.count > maxEvents {
            for candidate in seen.sorted(by: { $0.value < $1.value }).prefix(seen.count - maxEvents) {
                seen.removeValue(forKey: candidate.key)
            }
        }
        defaults.set(seen, forKey: key)
        return true
    }
}

final class DEZGREPushTokenStore {
    private let service = "com.dezgre.mobile.push.v1"
    private let tokenAccount = "apns-token"
    private let deviceIdKey = "dezgre.push.device-id.v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func save(token: String) -> Bool {
        let clean = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, let data = clean.data(using: .utf8) else { return false }
        deleteToken()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: tokenAccount,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data
        ]
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func loadToken() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: tokenAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let token = String(data: data, encoding: .utf8)
        else { return nil }
        return token
    }

    func deviceId() -> String {
        if let existing = defaults.string(forKey: deviceIdKey), !existing.isEmpty { return existing }
        let generated = UUID().uuidString.lowercased()
        defaults.set(generated, forKey: deviceIdKey)
        return generated
    }

    func registrationPayload(appVersion: String) -> [String: Any]? {
        guard let token = loadToken(), !token.isEmpty else { return nil }
        return [
            "platform": "ios",
            "provider": "apns",
            "pushToken": token,
            "deviceId": deviceId(),
            "appVersion": appVersion
        ]
    }

    private func deleteToken() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: tokenAccount
        ]
        SecItemDelete(query as CFDictionary)
    }
}

final class DEZGRENotificationCoordinator: NSObject, UNUserNotificationCenterDelegate {
    private let center: UNUserNotificationCenter
    private let tokenStore: DEZGREPushTokenStore
    private let deduplicator: DEZGREEventDeduplicator

    var onRoute: ((DEZGRENotificationRoute) -> Void)?

    init(
        center: UNUserNotificationCenter = .current(),
        tokenStore: DEZGREPushTokenStore = DEZGREPushTokenStore(),
        deduplicator: DEZGREEventDeduplicator = DEZGREEventDeduplicator()
    ) {
        self.center = center
        self.tokenStore = tokenStore
        self.deduplicator = deduplicator
        super.init()
    }

    func configure() {
        center.delegate = self
    }

    func requestPermission(completion: @escaping (Bool) -> Void) {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async { completion(granted) }
        }
    }

    func registerForRemoteNotifications() {
        DispatchQueue.main.async {
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    @discardableResult
    func acceptAPNSToken(_ deviceToken: Data) -> Bool {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        return tokenStore.save(token: token)
    }

    func registrationPayload(appVersion: String) -> [String: Any]? {
        tokenStore.registrationPayload(appVersion: appVersion)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        guard let event = DEZGREPushEvent(userInfo: notification.request.content.userInfo),
              deduplicator.markIfNew(event.eventId)
        else {
            completionHandler([])
            return
        }
        completionHandler([.banner, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let event = DEZGREPushEvent(userInfo: response.notification.request.content.userInfo) {
            onRoute?(event.route)
        }
        completionHandler()
    }
}
