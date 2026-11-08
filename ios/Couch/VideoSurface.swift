import AVFoundation
import SwiftUI

final class VideoLayerView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer? { layer as? AVSampleBufferDisplayLayer }
}

/// The TV picture. The pipeline draws straight into the layer; SwiftUI only places it.
struct VideoSurface: UIViewRepresentable {
    let pipeline: VideoPipeline

    func makeUIView(context: Context) -> VideoLayerView {
        let view = VideoLayerView()
        view.backgroundColor = .black
        if let layer = view.displayLayer { pipeline.attach(layer) }
        return view
    }

    func updateUIView(_ uiView: VideoLayerView, context: Context) {
        if let layer = uiView.displayLayer { pipeline.attach(layer) }
    }

    static func dismantleUIView(_ uiView: VideoLayerView, coordinator: ()) {
        // The pipeline holds the layer weakly; nothing to tear down.
    }
}
