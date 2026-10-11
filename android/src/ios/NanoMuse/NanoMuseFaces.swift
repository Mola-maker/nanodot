//
//  NanoMuseFaces.swift
//  Nanodot
//
//  The agent's face: the five moods, the dragon that every account starts
//  with, and the look the user drew in the avatar studio (kept on disk and
//  shared through the account's profile). Mirrors Android's AgentAvatar /
//  AvatarStore.
//

import SwiftUI
import UIKit
import AVFoundation
import ImageIO
import UniformTypeIdentifiers
import CryptoKit

enum NanoMuseMood: String, CaseIterable, Codable {
    case idle, working, waiting, happy, error
}

/// Which face the header wears: the bundled dragon, or the pictures the
/// user drew. Pictures live under Application Support/nanomuse/avatar as
/// PNG, one per mood, plus a small meta.json (description, style, when).
@MainActor
final class NanoMuseFaceStore: ObservableObject {
    static let shared = NanoMuseFaceStore()

    struct Meta: Codable, Equatable {
        var description: String = ""
        var style: String = ""
        var createdAt: Double = 0
        var faceId: String = ""
    }

    @Published private(set) var custom: [NanoMuseMood: UIImage] = [:]
    @Published private(set) var meta = Meta()

    var hasCustomFace: Bool { custom[.idle] != nil }

    private static var dragonCache: [NanoMuseMood: UIImage] = [:]

    private init() {
        load()
    }

    // MARK: Paths

    /// The stills' folder; the motion clips sit under `motion/` inside it (NanoMuseAvatarMotion).
    nonisolated static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("nanomuse/avatar", isDirectory: true)
    }

    private static func file(_ mood: NanoMuseMood) -> URL {
        directory.appendingPathComponent("\(mood.rawValue).png")
    }

    private static var metaFile: URL { directory.appendingPathComponent("meta.json") }

    // MARK: Reading

    /// The picture for a mood: the drawn one, the drawn idle still when that
    /// mood was never drawn, else the dragon. Nil only when the bundle lacks
    /// the dragon files as well.
    func image(for mood: NanoMuseMood) -> UIImage? {
        if let own = custom[mood] { return own }
        if let idle = custom[.idle] { return idle }
        return Self.dragon(mood)
    }

    static func dragon(_ mood: NanoMuseMood) -> UIImage? {
        if let cached = dragonCache[mood] { return cached }
        let name = "dragon-\(mood.rawValue)"
        var image: UIImage?
        if let url = Bundle.main.url(forResource: name, withExtension: "webp"),
           let data = try? Data(contentsOf: url) {
            image = UIImage(data: data)
        }
        if image == nil, let named = UIImage(named: name) {
            image = named
        }
        if let image { dragonCache[mood] = image }
        return image
    }

    private func load() {
        var found: [NanoMuseMood: UIImage] = [:]
        for mood in NanoMuseMood.allCases {
            let url = Self.file(mood)
            if let data = try? Data(contentsOf: url), let img = UIImage(data: data) {
                found[mood] = img
            }
        }
        custom = found
        if let data = try? Data(contentsOf: Self.metaFile),
           let m = try? JSONDecoder().decode(Meta.self, from: data) {
            meta = m
        }
    }

    // MARK: Writing

    /// A new face: `stills` must contain at least the idle picture. Replaces
    /// whatever was worn before.
    func adopt(_ stills: [NanoMuseMood: UIImage], description: String, style: String) {
        guard let idle = stills[.idle] else { return }
        // The old face's clips go with it; the new ones follow the poses (NanoMuseAvatarStudio).
        NanoMuseAvatarMotion.shared.clear()
        let fm = FileManager.default
        try? fm.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        for mood in NanoMuseMood.allCases {
            try? fm.removeItem(at: Self.file(mood))
        }
        var kept: [NanoMuseMood: UIImage] = [:]
        for (mood, image) in stills {
            let squared = Self.square(image, side: 512)
            if let png = squared.pngData() {
                try? png.write(to: Self.file(mood), options: .atomic)
                kept[mood] = squared
            }
        }
        custom = kept
        let idleBytes = Self.encodeStill(Self.square(idle, side: 512)) ?? Data()
        meta = Meta(description: description, style: style,
                    createdAt: Date().timeIntervalSince1970,
                    faceId: Self.faceId(of: idleBytes))
        saveMeta()
        NanoMuseProfileSync.shared.faceChanged()
    }

    /// One more pose for a face that is already worn.
    func put(_ mood: NanoMuseMood, image: UIImage) {
        let squared = Self.square(image, side: 512)
        try? FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        if let png = squared.pngData() {
            try? png.write(to: Self.file(mood), options: .atomic)
        }
        custom[mood] = squared
    }

    /// C12: the folder changed under the store — another account's face is in place (or none).
    /// The motion clips are per face and go; NanoMuseAvatarMotion draws new ones when enabled.
    func reload() {
        NanoMuseAvatarMotion.shared.clear()
        custom = [:]
        meta = Meta()
        load()
    }

    /// Back to the dragon.
    func reset(sync: Bool = true) {
        NanoMuseAvatarMotion.shared.clear()
        try? FileManager.default.removeItem(at: Self.directory)
        custom = [:]
        meta = Meta()
        if sync { NanoMuseProfileSync.shared.faceChanged() }
    }

    /// Wear a face that another device drew (pulled from the profile). Clips are per device:
    /// the old ones go, and the caller asks NanoMuseAvatarMotion for new ones when enabled.
    func wear(remote stills: [NanoMuseMood: UIImage], faceId: String, description: String, style: String) {
        guard stills[.idle] != nil else { return }
        NanoMuseAvatarMotion.shared.clear()
        let fm = FileManager.default
        try? fm.removeItem(at: Self.directory)
        try? fm.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        var kept: [NanoMuseMood: UIImage] = [:]
        for (mood, image) in stills {
            if let png = image.pngData() {
                try? png.write(to: Self.file(mood), options: .atomic)
                kept[mood] = image
            }
        }
        custom = kept
        meta = Meta(description: description, style: style,
                    createdAt: Date().timeIntervalSince1970, faceId: faceId)
        saveMeta()
    }

    private func saveMeta() {
        if let data = try? JSONEncoder().encode(meta) {
            try? data.write(to: Self.metaFile, options: .atomic)
        }
    }

    // MARK: Encoding helpers

    /// The still as the relay wants it: WebP when Image I/O can write it,
    /// else a PNG shrunk until it fits the relay's 200 KB per-still limit.
    static func encodeStill(_ image: UIImage) -> Data? {
        if let webp = encodeWebP(image, quality: 0.86), webp.count <= 190 * 1024 { return webp }
        var side: CGFloat = 512
        while side >= 128 {
            let small = square(image, side: side)
            if let png = small.pngData(), png.count <= 190 * 1024 { return png }
            side /= 2
        }
        return nil
    }

    static func encodeWebP(_ image: UIImage, quality: CGFloat) -> Data? {
        guard let cg = image.cgImage else { return nil }
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, UTType.webP.identifier as CFString, 1, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: quality]
        CGImageDestinationAddImage(dest, cg, options as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return data as Data
    }

    static func faceId(of idleBytes: Data) -> String {
        guard !idleBytes.isEmpty else { return "" }
        let digest = Insecure.SHA1.hash(data: idleBytes)
        return digest.map { String(format: "%02x", $0) }.joined().prefix(12).description
    }

    /// Centre-crop to a square and scale to `side` points at 1x.
    static func square(_ image: UIImage, side: CGFloat) -> UIImage {
        let w = image.size.width, h = image.size.height
        guard w > 0, h > 0 else { return image }
        let s = min(w, h)
        let crop = CGRect(x: (w - s) / 2, y: (h - s) / 2, width: s, height: s)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format)
        return renderer.image { _ in
            let scale = side / s
            let origin = CGPoint(x: -crop.minX * scale, y: -crop.minY * scale)
            image.draw(in: CGRect(origin: origin, size: CGSize(width: w * scale, height: h * scale)))
        }
    }
}

/// The face at a given size and mood. When a custom face has a motion clip
/// at `avatar/motion/<mood>.mp4`, it plays muted in a loop, clipped to the disc.
/// Built-in Dot uses the bundled mood stills.
/// Otherwise the still, with the small life the Android header gives it: a
/// slow breath when idle, a quicker bob while working, a tilt while
/// waiting, a pop when happy and a shake on error. Android: AgentAvatar.kt.
struct NanoMuseFaceView: View {
    var mood: NanoMuseMood
    var size: CGFloat
    var showsRing: Bool = true

    @ObservedObject private var faces = NanoMuseFaceStore.shared
    @ObservedObject private var motion = NanoMuseAvatarMotion.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var breath = false
    @State private var popped = false
    @State private var shake: CGFloat = 0

    /// The clip for this mood, when there is one for the face that is worn.
    private var clip: URL? {
        if faces.hasCustomFace { return motion.clips[mood] }
        return NanoMuseAvatarMotion.dragonClip(mood)
    }

    var body: some View {
        ZStack {
            if showsRing {
                Circle().fill(NanoMuseTones.disc)
            }
            if let clip {
                ZStack {
                    // The still underneath covers the first frames while the player warms up.
                    if let img = faces.image(for: mood) {
                        Image(uiImage: img).resizable().scaledToFill()
                    }
                    NanoMuseLoopingClipView(url: clip, version: motion.clipVersion)
                }
                .frame(width: size, height: size)
                .clipShape(Circle())
                .transition(.opacity)
            } else if let img = faces.image(for: mood) {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
                    .transition(.opacity)
                    .id(mood.rawValue + (faces.hasCustomFace ? "c" : "d"))
            } else {
                Image(systemName: "face.smiling")
                    .resizable()
                    .scaledToFit()
                    .padding(size * 0.18)
                    .foregroundStyle(NanoMuseTones.action)
            }
        }
        .frame(width: size, height: size)
        .scaleEffect(scale)
        .rotationEffect(.degrees(tilt))
        .offset(x: shake, y: bob)
        .animation(.easeInOut(duration: 0.35), value: mood)
        .onAppear {
            startBreathing()
            NanoMuseClipPlayers.shared.setSuspended(scenePhase != .active)
        }
        .nmOnChange(of: scenePhase) { phase in
            NanoMuseClipPlayers.shared.setSuspended(phase != .active)
        }
        .nmOnChange(of: mood) { newMood in
            if newMood == .happy {
                popped = true
                withAnimation(.spring(response: 0.35, dampingFraction: 0.45)) { popped = false }
            } else if newMood == .error {
                withAnimation(.interpolatingSpring(stiffness: 600, damping: 6)) { shake = 4 }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                    withAnimation(.interpolatingSpring(stiffness: 600, damping: 10)) { shake = 0 }
                }
            }
        }
        .accessibilityLabel(Text(AppLocalized("Agent face")))
    }

    /// The still's own motion; a clip brings its own and stays at rest.
    private var animatesStill: Bool { clip == nil }

    private var scale: CGFloat {
        if popped { return 1.12 }
        guard animatesStill else { return 1.0 }
        switch mood {
        case .working: return breath ? 1.045 : 0.985
        case .idle, .waiting, .happy, .error: return breath ? 1.02 : 1.0
        }
    }

    private var bob: CGFloat {
        animatesStill && mood == .working ? (breath ? -1.5 : 1.5) : 0
    }

    private var tilt: Double {
        animatesStill && mood == .waiting ? (breath ? 6 : -6) : 0
    }

    private func startBreathing() {
        withAnimation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true)) {
            breath = true
        }
    }
}

// MARK: - Clip playback

/// One muted looping player per clip, shared by every face view that shows it (the header,
/// the agent page, the studio thumbnails all point their layers at the same player). A
/// player goes when its last view does; all of them pause while the app is not in front.
@MainActor
final class NanoMuseClipPlayers {
    static let shared = NanoMuseClipPlayers()

    private struct Entry {
        let player: AVQueuePlayer
        let looper: AVPlayerLooper
        var users: Int
    }

    /// Keyed by file and version, so a redone clip (same path, new bytes) gets a fresh player
    /// while views still on the old one keep theirs until they move over.
    private var entries: [String: Entry] = [:]
    private var suspended = false

    private init() {}

    private static func key(_ url: URL, _ version: Int) -> String { "\(version):\(url.path)" }

    func acquire(_ url: URL, version: Int) -> AVQueuePlayer {
        let key = Self.key(url, version)
        if var entry = entries[key] {
            entry.users += 1
            entries[key] = entry
            if !suspended { entry.player.play() }
            return entry.player
        }
        let player = AVQueuePlayer()
        player.isMuted = true
        player.preventsDisplaySleepDuringVideoPlayback = false
        player.allowsExternalPlayback = false
        let looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
        entries[key] = Entry(player: player, looper: looper, users: 1)
        if !suspended { player.play() }
        return player
    }

    func release(_ url: URL, version: Int) {
        let key = Self.key(url, version)
        guard var entry = entries[key] else { return }
        entry.users -= 1
        if entry.users <= 0 {
            entry.player.pause()
            entries[key] = nil
        } else {
            entries[key] = entry
        }
    }

    /// Background → pause every loop; foreground → resume.
    func setSuspended(_ suspend: Bool) {
        guard suspend != suspended else { return }
        suspended = suspend
        for entry in entries.values {
            if suspend { entry.player.pause() } else { entry.player.play() }
        }
    }
}

/// A `UIView` whose layer is the player layer, so the clip fills the disc without a video player's chrome.
final class NanoMusePlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer? { layer as? AVPlayerLayer }
}

/// The muted looping clip behind a face. `version` changes when the file at `url` was rewritten
/// (a redone clip), so the player is rebuilt instead of showing the old frames.
struct NanoMuseLoopingClipView: UIViewRepresentable {
    var url: URL
    var version: Int

    final class Coordinator {
        var url: URL?
        var version = -1
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> NanoMusePlayerLayerView {
        let view = NanoMusePlayerLayerView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.playerLayer?.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: NanoMusePlayerLayerView, context: Context) {
        let coordinator = context.coordinator
        guard coordinator.url != url || coordinator.version != version else { return }
        if let old = coordinator.url { NanoMuseClipPlayers.shared.release(old, version: coordinator.version) }
        view.playerLayer?.player = NanoMuseClipPlayers.shared.acquire(url, version: version)
        coordinator.url = url
        coordinator.version = version
    }

    static func dismantleUIView(_ view: NanoMusePlayerLayerView, coordinator: Coordinator) {
        if let old = coordinator.url { NanoMuseClipPlayers.shared.release(old, version: coordinator.version) }
        coordinator.url = nil
        view.playerLayer?.player = nil
    }
}

/// Android's MuseTones: a quiet grey palette for the shell.
enum NanoMuseTones {
    private static func tone(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: 1)
        })
    }
    static let surface = tone(0xFFFFFF, 0x172B43)
    static let fill = tone(0xE9EFF1, 0x233239)
    static let bubble = tone(0xE9EFF1, 0x233239)
    static let disc = tone(0xF4F8FF, 0x172B43)
    static let hairline = tone(0x61758F, 0x9DB5D2)
    static let canvas = tone(0xF4F8FF, 0x101D30)
    static let action = tone(0x2D6396, 0xA9D2F5)
    /// The profile's onPrimary color for labels and icons on an action fill.
    static let onAction = tone(0xFFFFFF, 0x101D30)
    static let care = tone(0x3F66A0, 0xC0D9FA)
}

/// Whether the UI runs in Chinese right now (the in-app override wins).
enum NanoMuseLocale {
    /// The BCP-47 tag of the language the screens show right now (`zh-Hans`, `en`, `ja`): the
    /// in-app override wins, else the system's first preferred language.
    static var tag: String {
        AppBundle.current.preferredLocalizations.first
            ?? Locale.preferredLanguages.first ?? "en"
    }

    static var isChinese: Bool {
        tag.lowercased().hasPrefix("zh")
    }
}
