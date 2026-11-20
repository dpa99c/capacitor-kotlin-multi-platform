import Foundation
import Capacitor

/// Swift glue between Capacitor and a Kotlin Multiplatform `KmpBridge`.
///
/// The helpers are deliberately independent of any Kotlin framework: each KMP-backed plugin ships its
/// own XCFramework (with its own copy of `KmpBridge`), so the bridge is passed in as closures.
///
/// ```swift
/// private lazy var kmp = KmpBridge(module: MyModule())
///
/// override public func load() {
///     KmpCapacitor.connectEvents(of: self) { listener in kmp.setEventListener(listener: listener) }
/// }
///
/// @objc func greet(_ call: CAPPluginCall) {
///     KmpCapacitor.forward(call) { method, args, onSuccess, onError in
///         kmp.call(method: method, argsJson: args, onSuccess: onSuccess, onError: onError)
///     }
/// }
/// ```
public enum KmpCapacitor {
    /// Signature of `KmpBridge.call(method:argsJson:onSuccess:onError:)`.
    public typealias Invoke = (
        _ method: String,
        _ argsJson: String,
        _ onSuccess: @escaping (String) -> Void,
        _ onError: @escaping (String, String) -> Void
    ) -> Void

    // swiftlint:disable function_default_parameter_at_end
    /// Forwards `call` to the shared module method of the same name (or `method`): the call options are
    /// passed as JSON, the JSON result resolves the call and errors reject it with their code.
    public static func forward(_ call: CAPPluginCall, method: String? = nil, to invoke: Invoke) {
        invoke(
            method ?? call.methodName,
            jsonString(from: call),
            { resultJson in call.resolve(jsObject(fromJson: resultJson)) },
            { code, message in call.reject(message, code) }
        )
    }
    // swiftlint:enable function_default_parameter_at_end

    /// Delivers events emitted by the shared module to the plugin's JavaScript listeners.
    /// `setListener` receives the closure to install with `KmpBridge.setEventListener(listener:)`.
    public static func connectEvents(of plugin: CAPPlugin, setListener: (@escaping (String, String) -> Void) -> Void) {
        setListener { [weak plugin] event, payloadJson in
            plugin?.notifyListeners(event, data: jsObject(fromJson: payloadJson))
        }
    }

    /// Serializes the options of `call` to a JSON object string.
    public static func jsonString(from call: CAPPluginCall) -> String {
        let options = call.options ?? [:]
        guard JSONSerialization.isValidJSONObject(options),
              let data = try? JSONSerialization.data(withJSONObject: options),
              let json = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return json
    }

    /// Parses a JSON object string into a Capacitor `JSObject`.
    public static func jsObject(fromJson json: String) -> JSObject {
        guard let data = json.data(using: .utf8),
              let dictionary = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) as? [String: Any] else {
            return [:]
        }
        return JSTypes.coerceDictionaryToJSObject(dictionary) ?? [:]
    }
}
