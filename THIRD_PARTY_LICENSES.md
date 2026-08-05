# Third-Party Licenses

This file tracks third-party binaries and assets that are checked into this repository or shipped in release artifacts.

It is not a complete list of all transitive Gradle dependencies. For those, refer to the dependency metadata resolved during the build.

## Bundled components

### Vosk Android bindings

- Usage: offline wake word and speech recognition support
- Source in this repo: `com.alphacephei:vosk-android:0.3.75`
- Upstream project: https://github.com/alphacep/vosk-api
- Upstream license: Apache-2.0
- Notes in this repo:
  - app code dependency declaration: `app/build.gradle.kts`
  - bundled model notice: `app/src/main/assets/model/README`

### Vosk English mobile model

- Usage: default offline English recognition model
- Source in this repo: `app/src/main/assets/model/`
- Bundled notice file: `app/src/main/assets/model/README`
- Notes:
  - The checked-in model directory includes the upstream README notice.
  - If you replace or redistribute a different Vosk model, verify that model's own license terms from the upstream distribution.

### VOICEVOX CORE Android AAR

- Usage: VOICEVOX support in the `full` flavor
- Source in this repo: `app/libs/voicevoxcore-android-0.16.4.aar`
- Provenance metadata: `app/libs/java_packages.zip`
- Upstream project: https://github.com/VOICEVOX/voicevox_core
- Upstream license for version `0.16.4`: MIT
- Notes:
  - VOICEVOX states that versions earlier than `0.16` used a different license. Keep the version pinned unless you re-review the license.

### ONNX Runtime Android library

- Usage: ONNX runtime support for VOICEVOX-related binaries
- Source in this repo: `app/libs/onnxruntime-android.aar`
- Upstream project: https://github.com/microsoft/onnxruntime
- Upstream license: MIT

### Custom VOICEVOX ONNX Runtime binary

- Usage: runtime required by the bundled VOICEVOX full flavor
- Source in this repo: `app/src/full/jniLibs/arm64-v8a/libvoicevox_onnxruntime.so`
- Upstream project: https://github.com/VOICEVOX/onnxruntime-builder
- Upstream release: `voicevox_onnxruntime-1.23.2` (`voicevox_onnxruntime-android-arm64-1.23.2.tgz`)
- Notes:
  - This binary is distributed together with the VOICEVOX full flavor assets.
  - Upstream terms allow redistribution inside an application and require visible credit that VOICEVOX is used.
  - Review upstream ONNX Runtime and VOICEVOX release materials again before upgrading or replacing it.

### Open JTalk dictionary

- Usage: Japanese text processing assets for VOICEVOX
- Source in this repo: `app/src/full/assets/open_jtalk_dic_utf_8-1.11/`
- Bundled license file: `app/src/full/assets/open_jtalk_dic_utf_8-1.11/COPYING`
- Notes:
  - Keep `COPYING` with the dictionary assets when redistributing.
