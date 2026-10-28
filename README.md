# RecipeCraft

An Android app for generating recipes by chatting with an LLM. See `SPEC.md` for the spec and
`tasks/plan.md` for the plan.

## Build and test

Export a JDK 21 first (this machine: `export JAVA_HOME=$HOME/.jdks/jdk-21.0.13+9`), then from the
repo root:

```bash
./gradlew test assembleDebug lint koverVerify   # the pre-commit gate
./gradlew connectedDebugAndroidTest             # instrumented tests (emulator or device attached)
./gradlew assembleRelease                       # R8-minified release APK
```

## Sign a release

The release keystore never goes into git. Give Gradle its location and passwords in one of two ways:

- **`keystore.properties`** at the repo root (gitignored): copy `keystore.properties.example` and
  fill in `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- **Environment variables** (used when `keystore.properties` is absent, e.g. in CI):
  `RECIPECRAFT_KEYSTORE` (path), `RECIPECRAFT_KEYSTORE_PASSWORD`, `RECIPECRAFT_KEY_ALIAS`,
  `RECIPECRAFT_KEY_PASSWORD`.

With either in place, `./gradlew assembleRelease` writes a signed
`app/build/outputs/apk/release/app-release.apk`. With neither, it still succeeds and writes an
unsigned `app-release-unsigned.apk`, which must be signed (`apksigner`) before it can be installed.
A partial configuration (some values set, others missing) fails the build and names the missing
keys, never their values.
