import XCTest
import Capacitor
import CapacitorKmpCore
@testable import CapacitorKmpPlugin
@testable import CapacitorKmpSupport

class CapacitorKmpTests: XCTestCase {
    func testKotlinBridgeEchoesFromSwift() {
        let bridge = KmpBridge(module: CoreInfoModule())
        defer { bridge.close() }
        let done = expectation(description: "echo")
        bridge.call(method: "echo", argsJson: "{\"value\":\"Hello\"}", onSuccess: { json in
            let result = KmpCapacitor.jsObject(fromJson: json)
            XCTAssertEqual(result["value"] as? String, "Hello")
            XCTAssertEqual(result["platform"] as? String, "ios")
            done.fulfill()
        }, onError: { code, message in
            XCTFail("\(code): \(message)")
        })
        wait(for: [done], timeout: 5)
    }

    func testUnknownMethodIsRejectedWithCode() {
        let bridge = KmpBridge(module: CoreInfoModule())
        defer { bridge.close() }
        let done = expectation(description: "error")
        bridge.call(method: "nope", argsJson: nil, onSuccess: { _ in
            XCTFail("expected an error")
        }, onError: { code, _ in
            XCTAssertEqual(code, "UNIMPLEMENTED")
            done.fulfill()
        })
        wait(for: [done], timeout: 5)
    }

    func testJsonToJSObject() {
        let object = KmpCapacitor.jsObject(fromJson: "{\"a\":1,\"b\":[true,\"x\"],\"c\":{\"d\":null}}")
        XCTAssertEqual(object["a"] as? Int, 1)
        XCTAssertEqual((object["b"] as? JSArray)?.count, 2)
        XCTAssertNotNil(object["c"] as? JSObject)
        XCTAssertTrue(KmpCapacitor.jsObject(fromJson: "not json").isEmpty)
    }
}
