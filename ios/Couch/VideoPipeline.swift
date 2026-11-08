import AVFoundation
import CouchKit
import CoreMedia

private struct Unchecked<T>: @unchecked Sendable { let value: T }

/// Feeds the TV's H.264 access units to an `AVSampleBufferDisplayLayer`'s renderer from its own serial
/// queue, straight from the session's read loop; only UI state hops to the main actor.
///
/// Latency rules: every sample is DisplayImmediately; if a newer frame is already waiting, a frame that
/// has sat in the queue longer than `staleAfter` is still decoded (so later frames keep their references)
/// but marked DoNotDisplay, so the screen jumps to the newest instead of replaying a backlog.
final class VideoPipeline: VideoSink, @unchecked Sendable {
    private let queue = DispatchQueue(label: "cloud.samlo.couch.video", qos: .userInteractive)
    private let staleAfter: CFTimeInterval = 0.15

    // Confined to `queue`.
    private var format: CMVideoFormatDescription?
    private weak var layer: AVSampleBufferDisplayLayer?
    private var needsKeyframe = true
    private var hasPicture = false
    private let waitingLock = NSLock()
    private var waitingCount = 0
    private func addWaiting(_ n: Int) -> Int { waitingLock.lock(); defer { waitingLock.unlock() }; waitingCount += n; return waitingCount }

    private let onFirstPicture: @Sendable () -> Void
    private let onLayerChange: @Sendable () -> Void

    init(onFirstPicture: @escaping @Sendable () -> Void, onLayerChange: @escaping @Sendable () -> Void) {
        self.onFirstPicture = onFirstPicture
        self.onLayerChange = onLayerChange
    }

    // MARK: layer ownership (called from the main actor)

    func attach(_ layer: AVSampleBufferDisplayLayer) {
        let box = Unchecked(value: layer)
        queue.async { [self] in
            let layer = box.value
            guard self.layer !== layer else { return }
            self.layer = layer
            needsKeyframe = true
            layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
            if format != nil { onLayerChange() }
        }
    }

    func detach(_ layer: AVSampleBufferDisplayLayer) {
        let box = Unchecked(value: layer)
        queue.async { [self] in
            if self.layer === box.value { self.layer = nil; needsKeyframe = true }
        }
    }

    /// Stream stopped: drop the picture and the format.
    func reset() {
        queue.async { [self] in
            layer?.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
            format = nil
            needsKeyframe = true
            hasPicture = false
        }
    }

    // MARK: VideoSink (called from the session's read loop)

    func videoConfig(sps: Data, pps: Data) {
        queue.async { [self] in
            format = try? H264.formatDescription(sps: sps, pps: pps)
            layer?.sampleBufferRenderer.flush(removingDisplayedImage: false, completionHandler: nil)
            needsKeyframe = true
        }
    }

    func videoUnit(avcc: Data, keyframe: Bool, release: @escaping @Sendable () -> Void) {
        let arrived = CACurrentMediaTime()
        _ = addWaiting(1)
        queue.async { [self] in
            defer { release() }
            let waiting = addWaiting(-1)
            guard let format, let layer else { return }
            if needsKeyframe && !keyframe { return }
            let renderer = layer.sampleBufferRenderer
            if renderer.status == .failed { renderer.flush(); needsKeyframe = true; if !keyframe { return } }
            guard let sample = try? H264.sampleBuffer(avcc: avcc, keyframe: keyframe, format: format) else { return }
            needsKeyframe = false
            if !keyframe && waiting > 0 && CACurrentMediaTime() - arrived > staleAfter {
                H264.markDoNotDisplay(sample)
            }
            renderer.enqueue(sample)
            if !hasPicture { hasPicture = true; onFirstPicture() }
        }
    }
}
