import SwiftUI
import WebKit
import Security
import UIKit
import UserNotifications
import os

private struct APIError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}
private enum Vault {
    static let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.dezgre.mobile.session", kSecAttrAccount as String: "session"]
    static func load() -> [String:String] {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return [:] }
        return (try? JSONDecoder().decode([String:String].self, from: data)) ?? [:]
    }
    static func save(_ value: [String:String]) throws {
        let data = try JSONEncoder().encode(value)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        if status == errSecItemNotFound {
            var q = query
            q[kSecValueData as String] = data
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            if SecItemAdd(q as CFDictionary, nil) == errSecSuccess { return }
        }
        throw APIError(status: 0, message: "No se pudo guardar la sesión de forma segura.")
    }
    static func clear() { SecItemDelete(query as CFDictionary) }
}

private final class API {
    static let base = "https://api.dezgre.com/v1"
    let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.httpShouldSetCookies = false
        return URLSession(configuration: config)
    }()
    func request(_ method: String, _ path: String, bearer: String? = nil, body: [String:Any]? = nil) async throws -> [String:Any] {
        guard let url = URL(string: Self.base + path) else { throw APIError(status: 0, message: "Ruta inválida.") }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("DEZGRE-Mobile iOS", forHTTPHeaderField: "User-Agent")
        if let bearer = bearer, !bearer.isEmpty { req.setValue("Bearer " + bearer, forHTTPHeaderField: "Authorization") }
        if let body = body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw APIError(status: 0, message: "Respuesta inválida.") }
        let json = ((try? JSONSerialization.jsonObject(with: data)) as? [String:Any]) ?? [:]
        guard (200...299).contains(http.statusCode) else { throw APIError(status: http.statusCode, message: json["error"] as? String ?? "No se pudo completar la operación.") }
        return json
    }
}

private struct Store: Identifiable {
    let id: Int64
    let name: String
    let role: String
    let domain: String
    init?(_ json: [String:Any]) {
        if let n = json["id"] as? NSNumber { id = n.int64Value }
        else if let s = json["id"] as? String, let n = Int64(s) { id = n }
        else { return nil }
        name = json["name"] as? String ?? "Tienda"
        role = json["role"] as? String ?? ""
        domain = json["domain"] as? String ?? ""
    }
}

@MainActor private final class Model: ObservableObject {
    @Published var email = ""
    @Published var password = ""
    @Published var message = ""
    @Published var busy = false
    @Published var stores: [Store] = []
    @Published var panelURL: URL?
    @Published var hasSession = false
    private let api = API()
    private let pushStore = DEZGREPushTokenStore()
    private var ticket = ""
    private var tokens = Vault.load()
    private var didStart = false
    private var pushRegisteredFor = ""
    private var registeringPush = false
    private var pushRequested = false
    private let logger = Logger(subsystem: "com.dezgre.mobile", category: "push")
    let webData = WKWebsiteDataStore.nonPersistent()
    var deviceId: String { pushStore.deviceId() }

    func start() async {
        guard !didStart else { return }
        didStart = true
        guard !tokens.isEmpty else { return }
        await retry()
    }
    func login() async {
        guard !busy else { return }
        guard !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !password.isEmpty else { message = "Completa tu correo y contraseña."; return }
        busy = true; message = ""
        defer { busy = false }
        do {
            let result = try await api.request("POST", "/auth/login", body: ["email":email.trimmingCharacters(in:.whitespacesAndNewlines), "password":password, "deviceId":deviceId])
            password = ""
            if let access = result["access_token"] as? String, !access.isEmpty {
                try persist(result)
                try await openPanel()
            } else if result["needs_store_selection"] as? Bool == true,
                      let selection = result["selection_ticket"] as? String,
                      let rows = result["stores"] as? [[String:Any]] {
                ticket = selection
                stores = rows.compactMap(Store.init)
                if stores.isEmpty { throw APIError(status:0, message:"No hay tiendas disponibles.") }
            } else { throw APIError(status:0,message:"La API no devolvió una sesión válida.") }
        } catch { message = error.localizedDescription }
    }
    func select(_ store: Store) async {
        guard !busy else { return }; busy = true; message = ""
        defer { busy = false }
        do {
            let result = try await api.request("POST", "/auth/select-store", bearer:ticket, body:["storeId":store.id])
            try persist(result)
            ticket = ""; stores = []
            try await openPanel()
        } catch { message = error.localizedDescription }
    }
    private func persist(_ json: [String:Any]) throws {
        guard let access = json["access_token"] as? String, !access.isEmpty else { throw APIError(status:0,message:"Sesión inválida.") }
        let next = ["access":access,"refresh":json["refresh_token"] as? String ?? ""]
        do { try Vault.save(next) } catch { Vault.clear(); tokens = [:]; hasSession = false; throw error }
        tokens = next; hasSession = true
    }
    private func refresh() async throws {
        guard let r = tokens["refresh"], !r.isEmpty else { throw APIError(status:401,message:"Tu sesión terminó. Inicia sesión nuevamente.") }
        let result = try await api.request("POST", "/auth/refresh", body:["refreshToken":r,"deviceId":deviceId])
        try persist(result)
    }
    private func accessPanel() async throws -> URL {
        let json = try await api.request("GET", "/mobile/panel-access", bearer:tokens["access"])
        let first = json["url"] as? String ?? ""
        let raw = first.isEmpty ? ((json["data"] as? [String:Any])?["redirect_url"] as? String ?? "") : first
        guard let url = URL(string:raw), url.scheme == "https", url.host != nil, url.user == nil, url.password == nil else {
            throw APIError(status:0,message:"No se recibió una dirección segura del panel.")
        }
        return url
    }
    private func openPanel() async throws {
        if tokens["access"]?.isEmpty != false { try await refresh() }
        do { panelURL = try await accessPanel() }
        catch let error as APIError where error.status == 401 || error.status == 403 {
            try await refresh()
            panelURL = try await accessPanel()
        }
        hasSession = true
        if !pushRequested {
            pushRequested = true
            PushDelegate.request()
        }
        await syncPush()
    }
    func syncPush() async {
        guard hasSession, let access = tokens["access"], !access.isEmpty,
              let token = PushDelegate.currentToken, !token.isEmpty else { return }
        let identity = access + ":" + token
        guard !registeringPush, pushRegisteredFor != identity else { return }
        registeringPush = true
        defer { registeringPush = false }
        do {
            _ = try await api.request("PUT", "/notifications/devices", bearer:access, body:[
                "platform":"ios", "provider":"apns", "pushToken":token,
                "deviceId":deviceId, "appVersion":"0.1.34"
            ])
            if tokens["access"] == access { pushRegisteredFor = identity }
            logger.info("APNs device registered in DEZGRE")
        } catch { logger.error("APNs registration to DEZGRE failed") }
    }
    func webLoginReached() {
        guard panelURL != nil else { return }
        Vault.clear(); tokens = [:]; pushRegisteredFor = ""; hasSession = false
        panelURL = nil; password = ""; stores = []; ticket = ""; message = ""
        webData.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
    }
    func retry() async {
        guard !busy else { return }; busy = true; message = ""; hasSession = !tokens.isEmpty
        defer { busy = false }
        do { try await openPanel() }
        catch let error as APIError where error.status == 401 || error.status == 403 {
            Vault.clear(); tokens = [:]; hasSession = false; panelURL = nil; message = error.message
            webData.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
        } catch { message = error.localizedDescription }
    }
    func logout() async {
        guard !busy else { return }; busy = true; message = ""
        defer { busy = false }
        do {
            if let access = tokens["access"], !access.isEmpty {
                _ = try await api.request("POST", "/auth/logout", bearer:access, body:["deviceId":deviceId])
            }
            Vault.clear(); tokens = [:]; hasSession = false; panelURL = nil; stores = []; ticket = ""; password = ""
            webData.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
        } catch { message = error.localizedDescription }
    }
    func backToLogin() { stores = []; ticket = ""; password = ""; message = "" }
}

private struct Panel: UIViewRepresentable {
    let url: URL
    let dataStore: WKWebsiteDataStore
    let onError: (String) -> Void
    let onLogin: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(initialHost:url.host ?? "", onError:onError, onLogin:onLogin) }
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = dataStore
        config.allowsInlineMediaPlayback = true
        config.applicationNameForUserAgent = "DEZGRE-Mobile/iOS"
        let web = WKWebView(frame:.zero, configuration:config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true
        web.scrollView.bounces = false
        web.customUserAgent = nil
        web.load(URLRequest(url:url))
        return web
    }
    func updateUIView(_ web: WKWebView, context: Context) {}
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let initialHost: String
        let onError: (String) -> Void
        let onLogin: () -> Void
        init(initialHost:String, onError:@escaping (String)->Void, onLogin:@escaping ()->Void) {
            self.initialHost=initialHost; self.onError=onError; self.onLogin=onLogin
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let url = webView.url, allowed(url) else { return }
            let path = url.path.lowercased()
            if path == "/panel/login" || path.hasSuffix("/panel/login") || path == "/admin/login" {
                onLogin()
            }
        }
        private func allowed(_ url:URL) -> Bool {
            guard url.scheme == "https", let host=url.host?.lowercased() else { return false }
            return host == initialHost.lowercased() || host == "dezgre.com" || host.hasSuffix(".dezgre.com")
        }
        func webView(_ webView:WKWebView, decidePolicyFor action:WKNavigationAction, decisionHandler:@escaping (WKNavigationActionPolicy)->Void) {
            guard let url=action.request.url else { decisionHandler(.cancel); return }
            if action.targetFrame?.isMainFrame == false { decisionHandler(.allow); return }
            if allowed(url) { decisionHandler(.allow) }
            else { decisionHandler(.cancel); if ["https","mailto","tel"].contains(url.scheme ?? "") { UIApplication.shared.open(url) } }
        }
        func webView(_ webView:WKWebView, createWebViewWith configuration:WKWebViewConfiguration, for navigationAction:WKNavigationAction, windowFeatures:WKWindowFeatures)->WKWebView? {
            if navigationAction.targetFrame == nil, let url=navigationAction.request.url {
                if allowed(url) { webView.load(navigationAction.request) } else if ["https","mailto","tel"].contains(url.scheme ?? "") { UIApplication.shared.open(url) }
            }
            return nil
        }
        func webView(_ webView:WKWebView, didFailProvisionalNavigation navigation:WKNavigation!, withError error:Error) {
            if (error as NSError).code != NSURLErrorCancelled { onError("No se pudo cargar DEZGRE. Revisa tu conexión.") }
        }
        func webView(_ webView:WKWebView, didFail navigation:WKNavigation!, withError error:Error) {
            if (error as NSError).code != NSURLErrorCancelled { onError("No se pudo cargar DEZGRE.") }
        }
    }
}
private let bg = Color(red:247/255,green:247/255,blue:247/255)
private let ink = Color(red:48/255,green:55/255,blue:79/255)
private let muted = Color(red:139/255,green:146/255,blue:162/255)
private let fieldBorderColor = Color(red:225/255,green:228/255,blue:235/255)
private let accent = Color(red:177/255,green:68/255,blue:178/255)

private struct Root: View {
    @StateObject private var model = Model()
    @State private var passwordVisible = false
    var body: some View {
        Group {
            if let url=model.panelURL {
                ZStack(alignment:.bottom) {
                    Panel(url:url,dataStore:model.webData,onError:{ model.message=$0 },onLogin:{ model.webLoginReached() }).id(url)
                    if !model.message.isEmpty {
                        VStack(spacing:8) {
                            Text(model.message).font(.footnote)
                            Button("Reintentar") { Task { await model.retry() } }
                        }.padding().background(.regularMaterial,in:RoundedRectangle(cornerRadius:12)).padding()
                    }
                }
            } else {
                ScrollView {
                    VStack(spacing:0) {
                        if !model.stores.isEmpty {
                            selection
                        } else if model.hasSession {
                            Image("Brand").resizable().scaledToFit().frame(width:86,height:86)
                            Text("Abriendo tu espacio…").font(.system(size:22,weight:.bold)).foregroundColor(ink).padding(.top,18)
                            if !model.busy {
                                Button("Reintentar") { Task { await model.retry() } }.padding()
                                Button("Cerrar sesión",role:.destructive) { Task { await model.logout() } }
                            }
                            errorText
                        } else {
                            login
                        }
                        if model.busy { ProgressView().padding(.top,16) }
                    }.padding(.horizontal,24).padding(.top,model.stores.isEmpty ? 54 : 34)
                    .padding(.bottom,34).frame(maxWidth:480).frame(maxWidth:.infinity).disabled(model.busy)
                }.background(bg.ignoresSafeArea()).preferredColorScheme(.light)
            }
        }.tint(accent).task { await model.start() }
        .onReceive(NotificationCenter.default.publisher(for:PushDelegate.tokenChanged)) { _ in Task { await model.syncPush() } }
    }
    private var errorText: some View {
        Group { if !model.message.isEmpty { Text(model.message).font(.system(size:13)).foregroundColor(.red).multilineTextAlignment(.center).padding(.bottom,10) } }
    }
    private var login: some View {
        VStack(spacing:0) {
            Image("Brand").resizable().scaledToFit().frame(width:86,height:86).accessibilityLabel("DEZGRE").padding(.bottom,18)
            Text("DEZGRE MOBILE").font(.system(size:14,weight:.bold)).tracking(1.68).foregroundColor(ink)
            Text("Accede a tu operación").font(.system(size:25,weight:.bold)).foregroundColor(ink).padding(.top,9).padding(.bottom,34)
            TextField("Correo electrónico",text:$model.email).keyboardType(.emailAddress).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                .font(.system(size:16)).foregroundColor(ink).padding(.horizontal,16).frame(height:58).background(Color.white,in:RoundedRectangle(cornerRadius:13)).overlay(RoundedRectangle(cornerRadius:13).stroke(fieldBorderColor,lineWidth:1))
            HStack(spacing:0) {
                Group {
                    if passwordVisible { TextField("Contraseña",text:$model.password).textInputAutocapitalization(.never).autocorrectionDisabled() }
                    else { SecureField("Contraseña",text:$model.password).textContentType(.password) }
                }.font(.system(size:16)).foregroundColor(ink).padding(.leading,16)
                Button(passwordVisible ? "Ocultar" : "Mostrar") { passwordVisible.toggle() }.font(.system(size:12,weight:.bold)).foregroundColor(accent).frame(width:78,height:58)
            }.frame(height:58).background(Color.white,in:RoundedRectangle(cornerRadius:13)).overlay(RoundedRectangle(cornerRadius:13).stroke(fieldBorderColor,lineWidth:1)).padding(.top,12)
            errorText.padding(.top,14)
            Button { Task { await model.login() } } label: {
                Text(model.busy ? "Entrando…" : "Entrar").font(.system(size:16,weight:.bold)).foregroundColor(.white).frame(maxWidth:.infinity).frame(height:56).background(accent,in:RoundedRectangle(cornerRadius:13))
            }.buttonStyle(.plain)
        }
    }
    private var selection: some View {
        VStack(alignment:.leading,spacing:0) {
            Text("DEZGRE · TIENDAS").font(.system(size:12,weight:.bold)).tracking(1).foregroundColor(accent).padding(.bottom,8)
            Text("Selecciona tu tienda").font(.system(size:29,weight:.bold)).foregroundColor(ink).padding(.bottom,7)
            Text("Tu usuario tiene acceso a varias tiendas. Elige con cuál quieres trabajar en esta sesión.").font(.system(size:14)).foregroundColor(muted).padding(.bottom,22)
            ForEach(model.stores) { store in
                Button { Task { await model.select(store) } } label: {
                    StoreCard(store:store)
                }.buttonStyle(.plain).padding(.bottom,12)
            }
            errorText
            Button("Volver",action:model.backToLogin).font(.system(size:14,weight:.semibold)).padding(.top,8)
        }
    }
}

private struct StoreCard: View {
    let store: Store
    var body: some View {
        VStack(alignment:.leading, spacing:10) {
            HStack(spacing:12) {
                avatar
                identity
                Spacer()
                Image(systemName:"chevron.right").foregroundColor(muted)
            }
            if !store.domain.isEmpty {
                Text(store.domain).font(.system(size:12)).foregroundColor(muted)
            }
        }
        .padding(14)
        .background(Color.white, in:RoundedRectangle(cornerRadius:14))
        .overlay { RoundedRectangle(cornerRadius:14).stroke(fieldBorderColor,lineWidth:1) }
    }
    private var avatar: some View {
        Text(String(store.name.prefix(1)).uppercased())
            .font(.system(size:18,weight:.bold))
            .foregroundColor(.white)
            .frame(width:44,height:44)
            .background(accent,in:RoundedRectangle(cornerRadius:10))
    }
    private var identity: some View {
        VStack(alignment:.leading,spacing:3) {
            Text(store.name).font(.system(size:18,weight:.bold)).foregroundColor(ink)
            Text(store.role).font(.system(size:12,weight:.bold)).foregroundColor(accent)
        }
    }
}

final class PushDelegate: NSObject, UIApplicationDelegate {
    static let tokenChanged = Notification.Name("DEZGREAPNSTokenChanged")
    static var currentToken: String?
    private let coordinator = DEZGRENotificationCoordinator()
    private let logger = Logger(subsystem:"com.dezgre.mobile",category:"apns")
    func application(_ application:UIApplication,didFinishLaunchingWithOptions launchOptions:[UIApplication.LaunchOptionsKey:Any]? = nil)->Bool {
        coordinator.configure()
        return true
    }
    static func request() {
        UNUserNotificationCenter.current().requestAuthorization(options:[.alert,.sound,.badge]) { granted,error in
            if granted { DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() } }
        }
    }
    func application(_ application:UIApplication,didRegisterForRemoteNotificationsWithDeviceToken deviceToken:Data) {
        _ = coordinator.acceptAPNSToken(deviceToken)
        Self.currentToken = deviceToken.map { String(format:"%02x",$0) }.joined()
        NotificationCenter.default.post(name:Self.tokenChanged,object:nil)
        logger.info("Received APNs token")
    }
    func application(_ application:UIApplication,didFailToRegisterForRemoteNotificationsWithError error:Error) {
        logger.error("APNs device registration failed: \(error.localizedDescription, privacy: .public)")
    }
}
@main struct DEZGREApp: App {
    @UIApplicationDelegateAdaptor(PushDelegate.self) var delegate
    var body: some Scene { WindowGroup { Root() } }
}
