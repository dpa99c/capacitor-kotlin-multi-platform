import { CapacitorException, WebPlugin } from '@capacitor/core';
import type { ExceptionCode } from '@capacitor/core';

/**
 * The JavaScript view of a Kotlin `JsKmpBridge` (see `kmp/core/src/jsMain`). Every Kotlin/JS module
 * built with capacitor-kotlin-multi-platform exports a factory returning one of these.
 */
export interface KmpJsBridge {
  readonly methodNames: string[];
  call(method: string, argsJson: string): Promise<string>;
  setEventListener(listener: ((event: string, payloadJson: string) => void) | null | undefined): void;
  close(): void;
}

/** Loads (usually lazily, via dynamic `import()`) the Kotlin/JS bridge backing a web plugin. */
export type KmpBridgeLoader = () => KmpJsBridge | Promise<KmpJsBridge>;

/**
 * Base class for the web implementation of a KMP-backed Capacitor plugin.
 *
 * ```ts
 * export class MyPluginWeb extends KmpWebPlugin implements MyPlugin {
 *   constructor() {
 *     super(() => import('./generated/kmp/my-plugin-shared.mjs').then((m) => m.createMyPluginBridge()));
 *   }
 *   greet(options: { name: string }): Promise<Greeting> {
 *     return this.invoke('greet', options);
 *   }
 * }
 * ```
 *
 * Calls are forwarded to the shared Kotlin module as JSON; Kotlin errors (`KmpException`) are
 * re-thrown as `CapacitorException`s carrying the Kotlin error code, and events emitted by the module
 * are delivered to `addListener()` listeners.
 */
export class KmpWebPlugin extends WebPlugin {
  private bridge?: Promise<KmpJsBridge>;

  constructor(private readonly loadBridge: KmpBridgeLoader) {
    super();
  }

  /** Returns the (lazily created) Kotlin bridge. */
  protected kmp(): Promise<KmpJsBridge> {
    if (!this.bridge) {
      this.bridge = Promise.resolve(this.loadBridge()).then((bridge) => {
        bridge.setEventListener((event, payloadJson) => this.notifyListeners(event, JSON.parse(payloadJson)));
        return bridge;
      });
      // Allow a retry if loading fails.
      this.bridge.catch(() => (this.bridge = undefined));
    }
    return this.bridge;
  }

  /** Invokes the shared Kotlin method `method` with `options` and returns its (JSON) result. */
  protected async invoke<T>(method: string, options: object = {}): Promise<T> {
    const bridge = await this.kmp();
    try {
      return JSON.parse(await bridge.call(method, JSON.stringify(options ?? {}))) as T;
    } catch (error) {
      throw toCapacitorException(error);
    }
  }
}

function toCapacitorException(error: unknown): CapacitorException {
  if (error instanceof CapacitorException) return error;
  const { message, code } = (error ?? {}) as { message?: string; code?: string };
  return new CapacitorException(message ?? String(error), code as ExceptionCode | undefined);
}
