# capacitor-kotlin-multi-platform – example app

Calls the base plugin's self-test (`CapacitorKmp.getKmpInfo()` / `echo()`). Every result is produced by
the shared Kotlin `CoreInfoModule`, running on Android, iOS and (as Kotlin/JS) on the Web.

```bash
(cd .. && npm install && npm run build)   # build the plugin (Kotlin + TypeScript)
npm install
npm run build && npx cap sync
npx cap run android                         # or: npx cap run ios  |  npm start
```
