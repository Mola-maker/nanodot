//
//  NanoMuseNetworkView.swift
//  Nanodot
//
//  Settings → Network: the HTTP proxy for own providers and the ChatGPT plan (NanoMuseProxy)
//  — one switch, host, port, an optional user name and password, saved as typed, and a Test
//  row that fetches https://chatgpt.com/ through the proxy as entered and says what came back.
//  The footer names what goes through it and what never does. Android: ui/net/NetworkScreen.kt.
//

import SwiftUI

struct NanoMuseNetworkView: View {
    @State private var config = NanoMuseProxy.config()
    @State private var portText = ""
    @State private var testing = false
    @State private var result: String?
    @State private var resultOK = false
    @State private var hosts: [String] = []

    private static let testURL = "https://chatgpt.com/"

    var body: some View {
        Form {
            Section {
                Toggle(AppLocalized("Use a proxy"), isOn: $config.enabled)
                HStack {
                    TextField(AppLocalized("Host"), text: $config.host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField(AppLocalized("Port"), text: $portText)
                        .keyboardType(.numberPad)
                        .frame(width: 72)
                        .multilineTextAlignment(.trailing)
                }
                TextField(AppLocalized("User name (optional)"), text: $config.user)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField(AppLocalized("Password (optional)"), text: $config.password)
            } header: {
                Text(AppLocalized("HTTP proxy for own providers"))
            } footer: {
                Text(AppLocalized("Only the requests to the providers you added with your own key, and to the ChatGPT plan, go through it. nanoMuse Cloud, your computers and the local network never do. Kept on this phone only."))
            }

            Section {
                Button {
                    test()
                } label: {
                    HStack {
                        Text(AppLocalized("Test"))
                        Spacer()
                        if testing {
                            ProgressView()
                        } else {
                            Text(String(format: AppLocalized("Fetches %@ through it"), "chatgpt.com"))
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(testing)
                if let result {
                    Text(result)
                        .font(.footnote)
                        .foregroundStyle(resultOK ? Color.secondary : Color.red)
                }
            } footer: {
                if !hosts.isEmpty {
                    Text(String(format: AppLocalized("Goes through the proxy: %@"), hosts.joined(separator: ", ")))
                }
            }
        }
        .navigationTitle(AppLocalized("Network"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            portText = config.port > 0 ? String(config.port) : ""
            NanoMuseProxy.refreshHosts()
            hosts = NanoMuseProxy.routedHosts()
        }
        .nmOnChange(of: portText) { t in
            let digits = String(t.filter(\.isNumber).prefix(5))
            if digits != t { portText = digits }
            config.port = Int(digits) ?? 0
        }
        .nmOnChange(of: config) { c in
            NanoMuseProxy.save(c)
        }
    }

    private func test() {
        guard !testing else { return }
        let c = config
        guard !c.host.trimmingCharacters(in: .whitespaces).isEmpty, (1...65535).contains(c.port) else {
            result = AppLocalized("Enter a host and a port between 1 and 65535.")
            resultOK = false
            return
        }
        testing = true
        result = nil
        Task { @MainActor in
            let p = await NanoMuseProxy.probe(c, url: Self.testURL)
            resultOK = p.ok
            if p.ok {
                result = String(format: AppLocalized("Reached %@ (HTTP %d) in %d ms."), p.host, p.status, p.millis)
            } else {
                let why: String
                switch p.failure?.kind {
                case .regionBlocked: why = AppLocalized("OpenAI does not serve this region")
                case nil: why = String(format: AppLocalized("HTTP %d"), p.status)
                default: why = p.failure?.detail.isEmpty == false ? p.failure!.detail : String(format: AppLocalized("HTTP %d"), p.status)
                }
                result = String(format: AppLocalized("Could not reach %@ through the proxy: %@"), p.host, why)
            }
            testing = false
        }
    }
}
