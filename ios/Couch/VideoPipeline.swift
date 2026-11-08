import AVFoundation
import CouchKit
import CoreMedia

/// Feeds the TV's H.264 access units to an `AVSampleBufferDisplayLayer`. Main-actor only.
@MainActor
final class VideoPipeline {
    private var format: CMVideoFormatDescription?
    private weak var layer: AVSampleBufferDisplayLayer?
    private var needsKeyframe = true
    private(set) var hasPicture = false
    var onFirstPicture: (() -> Void)?

    func attach(_ layer: AVSampleBufferDisplayLayer) {
        self.layer = layer
        layer.videoGravity = .resizeAspect
    }

    func detach(_ layer: AVSampleBufferDisplayLayer) {
        if self.layer === layer { self.layer = nil }
    }

    /// New SPS/PPS: rebuild the format and start clean at the next keyframe.
    func configure(sps: Data, pps: Data) {
        format = try? H264.formatDescription(sps: sps, pps: pps)
        layer?.sampleBufferRenderer.flush(removingDisplayedImage: false, completionHandler: nil)
        needsKeyframe = true
    }

    func enqueue(avcc: Data, keyframe: Bool) {
        guard let format, let layer else { return }
        if needsKeyframe && !keyframe { return }
        let renderer = layer.sampleBufferRenderer
        if renderer.status == .failed { renderer.flush(); needsKeyframe = true; if !keyframe { return } }
        guard let sample = try? H264.sampleBuffer(avcc: avcc, keyframe: keyframe, format: format) else { return }
        needsKeyframe = false
        renderer.enqueue(sample)
        if !hasPicture { hasPicture = true; onFirstPicture?() }
    }

    /// Stream stopped: drop the picture and the format.
    func reset() {
        layer?.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        format = nil
        needsKeyframe = true
        hasPicture = false
    }
}
