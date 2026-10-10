// The offline Nanodot mark, matching assets/brand/nanodot-mark.svg.
import SwiftUI

struct NanoMuseMarkShape: Shape {
    func path(in rect: CGRect) -> Path {
        let size = min(rect.width, rect.height)
        let origin = CGPoint(x: rect.midX - size / 2, y: rect.midY - size / 2)
        return Path(ellipseIn: CGRect(x: origin.x + size * 0.13, y: origin.y + size * 0.13, width: size * 0.74, height: size * 0.74))
    }
}

private struct NanoMuseMarkSmile: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.width * 0.42, y: rect.height * 0.61))
        path.addCurve(to: CGPoint(x: rect.width * 0.58, y: rect.height * 0.61), control1: CGPoint(x: rect.width * 0.46, y: rect.height * 0.66), control2: CGPoint(x: rect.width * 0.54, y: rect.height * 0.66))
        return path
    }
}

struct NanoMuseBrandMark: View {
    var size: CGFloat = 72
    private let ink = Color(red: 244.0 / 255, green: 1, blue: 252.0 / 255)

    var body: some View {
        ZStack {
            NanoMuseMarkShape().fill(Color(red: 19.0 / 255, green: 111.0 / 255, blue: 99.0 / 255))
            Circle().fill(ink).frame(width: size * 0.06, height: size * 0.06).offset(x: -size * 0.11, y: -size * 0.04)
            Circle().fill(ink).frame(width: size * 0.06, height: size * 0.06).offset(x: size * 0.11, y: -size * 0.04)
            NanoMuseMarkSmile().stroke(ink, style: StrokeStyle(lineWidth: size * 0.027, lineCap: .round))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
