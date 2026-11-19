import SwiftUI
import WebKit
import Security
import UIKit

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
    init?(_ json: [String:Any]) {
        if let n = json["id"] as? NSNumber { id = n.int64Value }
        else if let s = json["id"] as? String, let n = Int64(s) { id = n }
        else { return nil }
        name = json["name"] as? String ?? "Tienda"
        role = json["role"] as? String ?? ""
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
        let raw = json["url"] as? String ?? (json["data"] as? [String:Any])?["redirect_url"] as? String ?? ""
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
    func makeCoordinator() -> Coordinator { Coordinator(initialHost:url.host ?? "", onError:onError) }
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = dataStore
        config.allowsInlineMediaPlayback = true
        let web = WKWebView(frame:.zero, configuration:config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true
        web.scrollView.bounces = false
        web.customUserAgent = nil
        web.configuration.applicationNameForUserAgent = "DEZGRE-Mobile iOS"
        web.load(URLRequest(url:url))
        return web
    }
    func updateUIView(_ web: WKWebView, context: Context) {}
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let initialHost: String
        let onError: (String) -> Void
        init(initialHost:String, onError:@escaping (String)->Void) { self.initialHost=initialHost; self.onError=onError }
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
private struct Root: View {
    @StateObject private var model = Model()
    var body: some View {
        VStack(spacing:0) {
            if let url=model.panelURL {
                HStack {
                    Button("Recargar") { Task { await model.retry() } }
                    Spacer()
                    Button("Cerrar sesión",role:.destructive) { Task { await model.logout() } }
                }.font(.footnote).padding(.horizontal).padding(.vertical,8).disabled(model.busy)
                Panel(url:url,dataStore:model.webData,onError:{ model.message=$0 }).id(url)
            } else {
                ScrollView {
                    VStack(alignment:.leading,spacing:18) {
                        Image("Brand").resizable().scaledToFit().frame(width:80,height:80).accessibilityLabel("DEZGRE")
                        Text("DEZGRE").font(.largeTitle.bold())
                        if !model.stores.isEmpty {
                            Text("Selecciona tu tienda").font(.title2.bold())
                            ForEach(model.stores) { store in
                                Button { Task { await model.select(store) } } label: {
                                    HStack { VStack(alignment:.leading) { Text(store.name).font(.headline); Text(store.role).font(.caption) }; Spacer(); Image(systemName:"chevron.right") }.padding().frame(maxWidth:.infinity).background(.thinMaterial,in:RoundedRectangle(cornerRadius:12))
                                }.buttonStyle(.plain)
                            }
                            Button("Volver",action:model.backToLogin)
                        } else if model.hasSession {
                            Text("Abriendo tu espacio…").font(.title2.bold())
                            Button("Reintentar") { Task { await model.retry() } }
                            Button("Cerrar sesión",role:.destructive) { Task { await model.logout() } }
                        } else {
                            Text("Inicia sesión").font(.title2.bold())
                            TextField("Correo electrónico",text:$model.email).keyboardType(.emailAddress).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                            SecureField("Contraseña",text:$model.password).textContentType(.password).textFieldStyle(.roundedBorder)
                            Button { Task { await model.login() } } label: { Text("Entrar").frame(maxWidth:.infinity).padding(6) }.buttonStyle(.borderedProminent)
                        }
                    }.padding(24).frame(maxWidth:480).frame(maxWidth:.infinity,alignment:.center).disabled(model.busy)
                }
            }
            if model.busy { ProgressView().padding(8) }
            if !model.message.isEmpty { Text(model.message).font(.footnote).foregroundStyle(.red).padding(12) }
        }.tint(Color(red:0.47,green:0.16,blue:0.93)).task { await model.start() }
    }
}
@main struct DEZGREApp: App {
    var body: some Scene { WindowGroup { Root() } }
}
