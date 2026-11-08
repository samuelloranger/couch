import AVFoundation
import SwiftUI

final class VideoLayerView: UIView {
    var onDismantle: (@MainActor (AVSampleBufferDisplayLayer) -> Void)?
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer? { layer as? AVSampleBufferDisplayLayer }
}

/// The TV picture. The pipeline draws straight into the layer; SwiftUI only places it.
struct VideoSurface: UIViewRepresentable {
    let pipeline: VideoPipeline
    /// Only the visible surface owns the pipeline; a covered one waits.
    var active = true

    func makeUIView(context: Context) -> VideoLayerView {
        let view = VideoLayerView()
        view.backgroundColor = .black
        view.displayLayer?.videoGravity = .resizeAspect
        view.onDismantle = { [pipeline] layer in pipeline.detach(layer) }
        if active, let layer = view.displayLayer { pipeline.attach(layer) }
        return view
    }

    func updateUIView(_ uiView: VideoLayerView, context: Context) {
        if active, let layer = uiView.displayLayer { pipeline.attach(layer) }
    }

    static func dismantleUIView(_ uiView: VideoLayerView, coordinator: ()) {
        // dismantle runs on the main actor; the pipeline ignores layers it no longer owns.
        if let layer = uiView.displayLayer { uiView.onDismantle?(layer) }
    }
}
