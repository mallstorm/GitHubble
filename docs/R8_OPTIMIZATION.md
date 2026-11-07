# Release size optimization with Xray/VLESS preserved

Baseline: main e792cd757e87c7917fcab71098794d37989fa7c1, RC4.2.27. Measurements compare unsigned ARM64 Release APKs built locally with the same source version, SDK 36, JDK 21 and Gradle 9.5.1. Signing changes APK size slightly.

| Payload | Baseline | R8 + resources + native debug stripping |
|---|---:|---:|
| APK bytes | 60,008,865 | 37,803,619 |
| DEX bytes | 9,244,656 | 1,807,948 |
| Xray native bytes | 50,146,448 | 35,541,544 |
| Resource payload bytes | 525,190 | 368,336 |
| Reduction | — | 37.00% |

R8 alone does not optimize Go machine code. The previous build logged `Unable to strip ... libgojni.so` because no NDK was available. Pinning/installing NDK 28.2.13676358 enables standard Android native stripping. No protocol/module was removed and no native library was recompiled or replaced.

## Preservation checks

- The pinned AAR is still io.github.toolshubofficial:libxray:26.6.27, ARM64 distribution.
- Explicit go.** / libXray.** keep rules mirror the AAR consumer rules and preserve reflection/JNI names. No blanket app keep rule, dontobfuscate or blanket dontwarn is used.
- Room 2.6.1's consumer rules preserve database class names but not their no-argument constructors in R8 full mode. A constructor-only rule preserves reflective database creation. The minified Android smoke caught and reproduced a WorkManager startup crash without this rule; the final size above includes the fix.
- `tools/verify-xray-payload.py` compares every PT_LOAD segment and its flags, address, size and alignment against the original AAR. Only ELF section-table header metadata is normalized because stripping rewrites metadata for non-loaded debug sections. All loaded code/data match; 16 KiB alignment is preserved.
- `tools/apk-size-report.py` provides an APK breakdown and the same native loaded-image comparison. The native file SHA changes after debug removal, as expected; executable/loaded content does not.
- R8 mapping is saved as R8_MAPPING.txt for main releases. Native symbols are saved separately, filtered to the ABIs actually shipped, as NATIVE_DEBUG_SYMBOLS.zip. The full original library and symbols remain in the immutable pinned AAR too.

## Android minified Release smoke

The dedicated workflow runs an actual R8/resource-shrunk Release, with the same production rules, on Android 13 x86_64. The CI-only GITHUBBLE_R8_SMOKE flag changes only the test runner/source set and signing certificate; it requires the x86_64 override and cannot produce a debug-signed ARM64 distribution. Emulator APKs are not published as user installable releases.

The smoke runner deliberately has no direct app-class references and adds no test-only app keep rules. It launches MainActivity, opens Settings and Chat, resolves the real gomobile API by class/method name, starts native Xray, and transfers bytes through SOCKS → VLESS outbound → local VLESS inbound → loopback echo. No external server, personal credentials or subscription is required. It always stops the test core.

`./gradlew -PGITHUBBLE_CI_EMULATOR_ABI=x86_64 -PGITHUBBLE_R8_SMOKE=true assembleRelease assembleReleaseAndroidTest`

Normal production build: `./gradlew clean test assembleRelease lintRelease` (without smoke properties). Debug remains unminified for development. Production signing remains main-only, with the persistent project key.

A local native roundtrip is not a live-user test of every external TLS/REALITY configuration, subscription provider or physical ARM64 phone. Those are additional acceptance checks. Existing unit regressions still run through the normal Android checks workflow.
