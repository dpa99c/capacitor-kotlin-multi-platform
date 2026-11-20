import Foundation
import Capacitor
import CapacitorKmpCore
#if canImport(CapacitorKmpSupport)
import CapacitorKmpSupport // Swift Package Manager (CocoaPods compiles it into this module)
#endif

/// The base plugin's own Capacitor plugin (`CapacitorKmp` in JavaScript).
///
/// All logic lives in the shared Kotlin `CoreInfoModule` (kmp/core/src/commonMain), compiled into
/// `CapacitorKmpCore.xcframework`; this class only declares the methods and forwards them.
@objc(CapacitorKmpPlugin)
public class CapacitorKmpPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CapacitorKmpPlugin"
    public let jsName = "CapacitorKmp"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getKmpInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "echo", returnType: CAPPluginReturnPromise)
    ]

    private lazy var kmp = KmpBridge(module: CoreInfoModule())

    override public func load() {
        KmpCapacitor.connectEvents(of: self) { listener in kmp.setEventListener(listener: listener) }
    }

    deinit {
        kmp.close()
    }

    @objc func getKmpInfo(_ call: CAPPluginCall) { forward(call) }

    @objc func echo(_ call: CAPPluginCall) { forward(call) }

    private func forward(_ call: CAPPluginCall) {
        KmpCapacitor.forward(call) { method, args, onSuccess, onError in
            kmp.call(method: method, argsJson: args, onSuccess: onSuccess, onError: onError)
        }
    }
}
