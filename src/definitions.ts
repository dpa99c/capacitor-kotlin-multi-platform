/**
 * The base plugin's own API. It is a self-test of the Kotlin Multiplatform pipeline: every method is
 * implemented once, in shared Kotlin (`kmp/core/src/commonMain/.../CoreInfoModule.kt`), and runs
 * natively on Android, iOS and (compiled to JavaScript) on the Web.
 */
export interface CapacitorKmpPlugin {
  /**
   * Returns information about the shared Kotlin runtime on the current platform.
   *
   * @since 0.1.0
   */
  getKmpInfo(): Promise<KmpInfo>;

  /**
   * Round-trips a value through the shared Kotlin code.
   *
   * @since 0.1.0
   */
  echo(options: EchoOptions): Promise<EchoResult>;
}

export interface KmpInfo {
  /** Platform reported by the Kotlin `expect`/`actual` `KmpPlatform` object. */
  platform: 'android' | 'ios' | 'web';
  /** OS version (Android/iOS) or user agent (Web), read by platform-specific Kotlin code. */
  osVersion: string;
  /** Version of the capacitor-kotlin-multi-platform core library. */
  coreVersion: string;
  /** Kotlin compiler version the shared code was built with. */
  kotlinVersion: string;
  /** Kotlin standard library version available at runtime. */
  kotlinStdlibVersion: string;
}

export interface EchoOptions {
  value: string;
}

export interface EchoResult {
  value: string;
  /** Platform on which the Kotlin code handled the call. */
  platform: 'android' | 'ios' | 'web';
}
