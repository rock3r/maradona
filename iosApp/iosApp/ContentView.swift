import UIKit
import SwiftUI
import MaradonaSample

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    /// Oracle captures are compared against the golden pixel for pixel, so
    /// nothing but the diagram may be on screen: no status bar icon ink, no
    /// home indicator.
    private var oracle: Bool {
        ProcessInfo.processInfo.arguments.contains("-oracleChart")
    }

    @ViewBuilder
    var body: some View {
        let content = ComposeView()
            .ignoresSafeArea(.all, edges: .bottom) // Compose has own keyboard handler
            .statusBarHidden(oracle)
        if #available(iOS 16.0, *) {
            content.persistentSystemOverlays(oracle ? .hidden : .automatic)
        } else {
            content
        }
    }
}
