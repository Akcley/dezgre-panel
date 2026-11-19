import SwiftUI
import WebKit
import Security
import UIKit
import UserNotifications
import os

private struct APIError: LocalizedError {
    let status: Int
    let message: String
    let code: String?
    init(status:Int,message:String,code:String? = nil) { self.status=status; self.message=message; self.code=code }
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
        guard (200...299).contains(http.statusCode) else { throw APIError(status: http.statusCode, message: json["error"] as? String ?? "No se pudo completar la operación.", code:json["code"] as? String) }
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
    @Published var panelColor = Color(uiColor: .systemBackground)
    @Published var panelDark = false
    @Published var pushIssue: String?
    private var reportedPushIssue = ""
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
    var deviceId: String { tokens["deviceId"] ?? pushStore.deviceId() }
    private var soundSyncing = false

    func start() async {
        guard !didStart else { return }
        didStart = true
        guard !tokens.isEmpty else { return }
        if tokens["deviceId"] == nil && pushStore.existingDeviceId() == nil {
            clearSession(message:"Inicia sesión nuevamente para vincular esta instalación.")
            return
        }
        await retry()
    }
    func login() async {
        guard !busy else { return }
        guard !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !password.isEmpty else { message = "Completa tu correo y contraseña."; return }
        busy = true; message = ""
        Vault.clear(); tokens=[:]; ticket=""; stores=[]
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
        let next = ["access":access,"refresh":json["refresh_token"] as? String ?? "", "deviceId":deviceId]
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
        Task { await self.syncSounds() }
        if let issue = PushDelegate.currentIssue { reportPushIssue(issue) }
    }
    func reportPushIssue(_ issue: String) {
        guard hasSession, issue != reportedPushIssue else { return }
        reportedPushIssue = issue
        pushIssue = issue
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
                "deviceId":deviceId, "appVersion":"0.1.37"
            ])
            if tokens["access"] == access { pushRegisteredFor = identity }
            logger.info("APNs device registered in DEZGRE")
        } catch {
            logger.error("APNs registration to DEZGRE failed")
            let code = (error as? APIError)?.status ?? 0
            reportPushIssue("Apple entregó un token, pero DEZGRE no pudo registrar el dispositivo (HTTP " + String(code) + ").")
        }
    }
    func syncSounds() async {
        guard hasSession,!soundSyncing,let access=tokens["access"],!access.isEmpty else{return}
        soundSyncing=true
        defer{soundSyncing=false}
        do {
            let json=try await api.request("GET","/notifications/config",bearer:access)
            guard tokens["access"]==access,let store=json["storeId"] as? String,let configs=json["configs"] as? [[String:Any]] else{return}
            for config in configs {
                guard tokens["access"]==access else{return}
                guard config["soundMode"] as? String == "custom",let url=config["soundUrl"] as? String,let revision=config["soundRevision"] as? String else{continue}
                await DEZGRESoundCache.shared.prepare(store:store,url:url,revision:revision)
            }
        }catch{logger.info("Notification sound sync unavailable; will retry")}
    }
    private func clearSession(message:String = "") {
        Vault.clear(); tokens=[:]; pushRegisteredFor=""; hasSession=false; pushRequested=false
        panelURL=nil;password="";stores=[];ticket="";self.message=message
        webData.removeData(ofTypes:WKWebsiteDataStore.allWebsiteDataTypes(),modifiedSince:.distantPast){}
    }
    func webLoginReached() {
        guard panelURL != nil else {return}
        clearSession()
    }
    func retry() async {
        guard !busy else { return }; busy = true; message = ""; hasSession = !tokens.isEmpty
        defer { busy = false }
        do { try await openPanel() }
        catch let error as APIError where error.status == 401 || error.status == 403 {
            clearSession(message: error.code == "REFRESH_DEVICE_MISMATCH" ? "Tu instalación cambió. Inicia sesión nuevamente." : error.message)
        } catch { message = error.localizedDescription }
    }
    func logout() async {
        guard !busy else { return }; busy = true; message = ""
        defer { busy = false }
        do {
            if let access = tokens["access"], !access.isEmpty {
                _ = try await api.request("POST", "/auth/logout", bearer:access, body:["deviceId":deviceId])
            }
            clearSession()
        } catch { message = error.localizedDescription }
    }
    func backToLogin() { stores = []; ticket = ""; password = ""; message = "" }
}

private struct Panel: UIViewRepresentable {
    let url: URL
    let dataStore: WKWebsiteDataStore
    let onError: (String) -> Void
    let onLogin: () -> Void
    let onColor: (UIColor) -> Void
    let topInset: CGFloat
    let bottomInset: CGFloat
    func makeCoordinator() -> Coordinator { Coordinator(initialHost:url.host ?? "", onError:onError, onLogin:onLogin,onColor:onColor) }
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = dataStore
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsAirPlayForMediaPlayback = true
        config.allowsPictureInPictureMediaPlayback = true
        config.applicationNameForUserAgent = "DEZGRE-Mobile/iOS"
        config.userContentController.add(context.coordinator,name:"dezgrePanelColor")
        let themeScript = """
        (() => {
          const root = document.documentElement;
          if (!root) return;
          const style = document.createElement('style');
          style.id = 'dezgre-ios-edge-to-edge';
          style.textContent = `
            html { --dz-ios-top: 0px; /* UIKit reserves status-bar height, not CSS. */
                   --dz-ios-bottom: max(env(safe-area-inset-bottom, 0px), var(--dz-native-bottom, 0px)); }
            html:has(.panelShell) body { margin:0 !important; }
            @media (max-width:900px) {
              .panelShell { padding-top:calc(var(--panel-mobile-topbar-height,58px) + var(--dz-ios-top)) !important;
                min-height:100dvh !important; box-sizing:border-box !important; }
              .panelShell > .panelMobileTopbar { box-sizing:border-box !important;
                padding-top:calc(8px + var(--dz-ios-top)) !important;
                height:calc(var(--panel-mobile-topbar-height,58px) + var(--dz-ios-top)) !important;
                min-height:calc(var(--panel-mobile-topbar-height,58px) + var(--dz-ios-top)) !important; }
              .panelShell > .panelContent { box-sizing:border-box !important; padding-bottom:calc(var(--dz-ios-bottom) + var(--dz-content-bottom, 52px)) !important; }
              .panelShell > .panelSidebar { padding-top:calc(24px + var(--dz-ios-top)) !important; padding-bottom:calc(18px + var(--dz-ios-bottom)) !important; }
            }
            @media (min-width:901px) {
              .panelShell { padding-top:var(--dz-ios-top) !important; min-height:100dvh !important; box-sizing:border-box !important; }
              .panelShell > .panelSidebar { padding-top:calc(20px + var(--dz-ios-top)) !important; }
              .panelShell > .panelContent { padding-bottom:calc(76px + var(--dz-ios-bottom)) !important; }
            }
            html:not(:has(.panelShell)) body { padding-top:var(--dz-ios-top); padding-bottom:var(--dz-ios-bottom); }
          `;
          let previous = '';
          let scheduled = false;
          function update() {
            scheduled=false;
            const shell=document.querySelector('.panelShell');
            const top=document.querySelector('.panelMobileTopbar');
            const content=document.querySelector('.panelContent');
            if (!style.isConnected && document.head) {
              if (content) {
                const saved = getComputedStyle(content).paddingBottom;
                if (saved) root.style.setProperty('--dz-content-bottom',saved);
              }
              document.head.appendChild(style);
            }
            const meta=document.querySelector('meta[name="viewport"]');
            if (meta && !/viewport-fit\\s*=\\s*cover/i.test(meta.content)) meta.content=meta.content.replace(/,?\\s*viewport-fit\\s*=\\s*[^,]+/i,'')+', viewport-fit=cover';
            const node=top || shell || document.body;
            if (!node) return;
            let c=getComputedStyle(node).backgroundColor;
            if (!c || c==='transparent' || c==='rgba(0, 0, 0, 0)') c=getComputedStyle(shell || document.body).backgroundColor;
            if (c && c!==previous) {previous=c; window.webkit.messageHandlers.dezgrePanelColor.postMessage(c);}
          }
          function queue() { if (!scheduled) {scheduled=true;requestAnimationFrame(update);} }
          window.addEventListener('pageshow',queue);
          window.addEventListener('resize',queue);
          new MutationObserver(queue).observe(root,{attributes:true,childList:true,subtree:true,attributeFilter:['class','style','data-theme','data-panel-theme']});
          update();
        })();
        """
        config.userContentController.addUserScript(WKUserScript(source:themeScript,injectionTime:.atDocumentEnd,forMainFrameOnly:true))
        let web = WKWebView(frame:.zero, configuration:config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true
        web.scrollView.bounces = false
        web.underPageBackgroundColor = .systemBackground
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.customUserAgent = nil
        web.load(URLRequest(url:url))
        return web
    }
    func updateUIView(_ web: WKWebView, context: Context) {
        context.coordinator.updateInsets(web,top:topInset,bottom:bottomInset)
    }
    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName:"dezgrePanelColor")
        web.navigationDelegate=nil
        web.uiDelegate=nil
        web.stopLoading()
    }
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        let initialHost: String
        let onError: (String) -> Void
        let onLogin: () -> Void
        let onColor:(UIColor)->Void
        private var safeTop:CGFloat=0
        private var safeBottom:CGFloat=0
        func updateInsets(_ web:WKWebView,top:CGFloat,bottom:CGFloat) {
            safeTop=top; safeBottom=bottom
            injectInsets(web)
        }
        private func injectInsets(_ web:WKWebView) {
            let js="document.documentElement?.style.setProperty('--dz-native-top','" + String(Double(safeTop)) + "px');document.documentElement?.style.setProperty('--dz-native-bottom','" + String(Double(safeBottom)) + "px');"
            web.evaluateJavaScript(js,completionHandler:nil)
        }
        init(initialHost:String, onError:@escaping (String)->Void, onLogin:@escaping ()->Void,onColor:@escaping (UIColor)->Void) {
            self.initialHost=initialHost; self.onError=onError; self.onLogin=onLogin; self.onColor=onColor
        }
        func userContentController(_ userContentController:WKUserContentController,didReceive message:WKScriptMessage) {
            guard message.name == "dezgrePanelColor", message.frameInfo.isMainFrame,
                let url=message.frameInfo.request.url, allowed(url), let value=message.body as? String,
                value.hasPrefix("rgb") else { return }
            let numbers=value.split(whereSeparator:{ !$0.isNumber && $0 != "." }).compactMap{Double($0)}
            guard numbers.count >= 3, numbers.prefix(3).allSatisfy({ $0 >= 0 && $0 <= 255 }),
                numbers.count == 3 || (numbers.count == 4 && numbers[3] >= 0.5) else { return }
            let color=UIColor(red:CGFloat(numbers[0]/255),green:CGFloat(numbers[1]/255),blue:CGFloat(numbers[2]/255),alpha:1)
            onColor(color)
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let url = webView.url, allowed(url) else { return }
            injectInsets(webView)
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
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        Group {
            if let url=model.panelURL {
                ZStack(alignment:.bottom) {
                    GeometryReader { geometry in
                        Panel(url:url,dataStore:model.webData,onError:{ model.message=$0 },onLogin:{ model.webLoginReached() },onColor:{ color in
                            var r:CGFloat=0,g:CGFloat=0,b:CGFloat=0,a:CGFloat=0
                            if color.getRed(&r,green:&g,blue:&b,alpha:&a) {
                                model.panelDark = (0.299*r+0.587*g+0.114*b) < 0.5
                            }
                            model.panelColor=Color(uiColor:color)
                        },topInset:0,bottomInset:geometry.safeAreaInsets.bottom)
                        .id(url).ignoresSafeArea(.container,edges:.bottom)
                    }
                    if !model.message.isEmpty {
                        VStack(spacing:8) {
                            Text(model.message).font(.footnote)
                            Button("Reintentar") { Task { await model.retry() } }
                        }.padding().background(.regularMaterial,in:RoundedRectangle(cornerRadius:12)).padding()
                    }
                }.frame(maxWidth:.infinity,maxHeight:.infinity)
                    .background(model.panelColor.ignoresSafeArea())
                    .preferredColorScheme(model.panelDark ? .dark : .light)
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
        .task(id:scenePhase) {
            guard scenePhase == .active else{return}
            while !Task.isCancelled {
                await model.syncSounds()
                try? await Task.sleep(nanoseconds:60_000_000_000)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for:PushDelegate.tokenChanged)) { _ in Task { await model.syncPush() } }
        .onReceive(NotificationCenter.default.publisher(for:PushDelegate.failed)) { _ in
            if let issue=PushDelegate.currentIssue { model.reportPushIssue(issue) }
        }
        .alert("Notificaciones de iOS",isPresented:Binding(get:{model.pushIssue != nil},set:{if !$0 {model.pushIssue=nil}})) {
            Button("Aceptar",role:.cancel) {model.pushIssue=nil}
        } message: {
            Text(model.pushIssue ?? "")
        }
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
    static let failed = Notification.Name("DEZGREAPNSFailed")
    static var currentIssue: String?
    private let coordinator = DEZGRENotificationCoordinator()
    private let logger = Logger(subsystem:"com.dezgre.mobile",category:"apns")
    func application(_ application:UIApplication,didFinishLaunchingWithOptions launchOptions:[UIApplication.LaunchOptionsKey:Any]? = nil)->Bool {
        coordinator.configure()
        return true
    }
    static func request() {
        UNUserNotificationCenter.current().requestAuthorization(options:[.alert,.sound,.badge]) { granted,error in
            DispatchQueue.main.async {
                if granted {
                    UIApplication.shared.registerForRemoteNotifications()
                    DispatchQueue.main.asyncAfter(deadline:.now()+15) {
                        guard Self.currentToken == nil, Self.currentIssue == nil else { return }
                        Self.currentIssue = "El permiso está concedido, pero Apple todavía no entregó un token APNs. Verifica la firma y la conexión. No se confirmó el registro push."
                        NotificationCenter.default.post(name:Self.failed,object:nil)
                    }
                }
                else if let error=error {
                    Self.currentIssue="No se pudo solicitar el permiso de notificaciones: " + String((error as NSError).code)
                    NotificationCenter.default.post(name:Self.failed,object:nil)
                }
            }
        }
    }
    func application(_ application:UIApplication,didRegisterForRemoteNotificationsWithDeviceToken deviceToken:Data) {
        _ = coordinator.acceptAPNSToken(deviceToken)
        Self.currentIssue = nil
        Self.currentToken = deviceToken.map { String(format:"%02x",$0) }.joined()
        NotificationCenter.default.post(name:Self.tokenChanged,object:nil)
        logger.info("Received APNs token")
    }
    func application(_ application:UIApplication,didFailToRegisterForRemoteNotificationsWithError error:Error) {
        logger.error("APNs device registration failed: \(error.localizedDescription, privacy: .public)")
        let code=(error as NSError).code
        Self.currentIssue = "Apple no registró este iPhone para notificaciones push (" + String(code) + "). " + error.localizedDescription
        NotificationCenter.default.post(name:Self.failed,object:nil)
    }
}
@main struct DEZGREApp: App {
    @UIApplicationDelegateAdaptor(PushDelegate.self) var delegate
    var body: some Scene { WindowGroup { Root() } }
}
