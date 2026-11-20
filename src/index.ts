import { registerPlugin } from '@capacitor/core';

import type { CapacitorKmpPlugin } from './definitions';
import type { CapacitorKmpWeb } from './web';

// Memoised: Capacitor may call the factory several times when the first plugin calls happen
// concurrently, and every web instance would otherwise host its own Kotlin module (losing events).
let web: Promise<CapacitorKmpWeb> | undefined;

const CapacitorKmp = registerPlugin<CapacitorKmpPlugin>('CapacitorKmp', {
  web: () => (web ??= import('./web').then((m) => new m.CapacitorKmpWeb())),
});

export * from './definitions';
// Helpers for plugins built on capacitor-kotlin-multi-platform.
export { KmpWebPlugin } from './kmp-web';
export type { KmpJsBridge, KmpBridgeLoader } from './kmp-web';
export { CapacitorKmp };
