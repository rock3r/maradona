# Publishing & Release Guide

Maradona's three library modules (`:maradona`, `:maradona-material3`, and `:maradona-jewel`) publish to Sonatype Central Portal via [`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin), following the same tag-driven workflow and Central Portal validation tooling as Spectre.

The sample modules (`:sample-shared`, `:sample-desktop`, `:sample-android`, and `iosApp`) do not apply the publish plugin and are never published to Maven Central.

## Published Coordinates

| Gradle Module | Multiplatform Root Coordinate | Published Target Artifacts |
| --- | --- | --- |
| **`:maradona`** | `dev.sebastiano.maradona:maradona:<version>` | `maradona`, `maradona-desktop` (`.jar`, JDK 25+), `maradona-android` (`.aar`, `minSdk 24`, JDK 25+), `maradona-iosarm64` (`.klib`), `maradona-iossimulatorarm64` (`.klib`) |
| **`:maradona-material3`** | `dev.sebastiano.maradona:maradona-material3:<version>` | `maradona-material3`, `maradona-material3-desktop` (`.jar`, JDK 25+), `maradona-material3-android` (`.aar`, `minSdk 24`, JDK 25+), `maradona-material3-iosarm64` (`.klib`), `maradona-material3-iossimulatorarm64` (`.klib`) |
| **`:maradona-jewel`** | `dev.sebastiano.maradona:maradona-jewel:<version>` | `maradona-jewel`, `maradona-jewel-desktop` (`.jar`, Desktop JVM / JDK 25+ only) |

Shared coordinates and POM metadata (`GROUP`, `VERSION_NAME`, `POM_URL`, `POM_LICENSE_*`, `POM_SCM_*`, `POM_DEVELOPER_*`) live in the root [`gradle.properties`](../gradle.properties). Per-module `POM_ARTIFACT_ID`, `POM_NAME`, and `POM_DESCRIPTION` live in each library module's own `gradle.properties`. Every published JAR, `-sources.jar`, and `-javadoc.jar` bundles [`LICENSE`](../LICENSE) and [`NOTICE`](../NOTICE) under `META-INF/`.

## GitHub Release → Maven Central Pipeline (`.github/workflows/release.yml`)

Publishing a GitHub Release with a SemVer tag matching `v*` (for example `v0.1.0`) triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml) (`on: release: types: [published]`). The workflow also supports `workflow_dispatch` with `dry_run: true` (default) so the full Linux + macOS 12-component release build and verification pipeline can be tested in GitHub Actions without publishing to Maven Central or requiring secrets:

1. **`release-gate` (`ubuntu-latest`):**
   - Validates that the release tag matches `v<major>.<minor>.<patch>` (with optional SemVer pre-release/build metadata) via [`.github/scripts/derive-release-version.sh`](../.github/scripts/derive-release-version.sh) and strips the leading `v` to derive `RELEASE_VERSION`.
   - Runs `./gradlew verifyApiDesign :maradona:desktopTest :maradona-material3:desktopTest :maradona-jewel:desktopTest :sample-desktop:desktopTest :maradona:testAndroidHostTest :maradona-material3:testAndroidHostTest :sample-android:assembleDebug`.
2. **`publish` (`macos-latest`, depends on `release-gate`):**
   - Runs on `macos-latest` (with Temurin JDK 25, Xcode, and the Android SDK) so all four KMP targets (`desktop`, `android`, `iosArm64`, `iosSimulatorArm64`) are compiled, tested, and packaged together in a single atomic Sonatype Central Portal deployment.
   - Runs `./gradlew :maradona:iosSimulatorArm64Test :maradona-material3:iosSimulatorArm64Test :sample-shared:iosSimulatorArm64Test` on the iOS Simulator.
   - Runs `./gradlew verifyMavenLocalPublication -PVERSION_NAME="$RELEASE_VERSION"` (which also executes `verifyApiDesign` and `checkKotlinAbi` across all three published modules) to verify all 12 KMP coordinate directories, POM metadata, Gradle Module Metadata, `META-INF/LICENSE` + `META-INF/NOTICE`, Java 25 classfile major version `69`, `android:minSdkVersion="24"`, and the absence of Jewel dependencies in `:maradona` and `:maradona-material3`.
   - Uploads the verified 12-component Maven repository layout as a workflow artifact (`maradona-maven-bundle-<version>`).
   - When `dry_run` is `false` (all `release: published` runs, or `workflow_dispatch` with `dry_run: false`), signs with in-memory PGP and publishes to Sonatype Central Portal via `./gradlew :maradona:publishToMavenCentral :maradona-material3:publishToMavenCentral :maradona-jewel:publishToMavenCentral -PVERSION_NAME="$RELEASE_VERSION" -PcentralAutomaticRelease="$AUTOMATIC_RELEASE"`.

> **Note on `-javadoc.jar`:** Each KMP coordinate currently publishes `-sources.jar` (containing full source + KDoc for IDE quick-documentation) alongside an empty `-javadoc.jar` (`JavadocJar.Empty()`) to satisfy Sonatype Central Portal's required artifact set, matching Spectre's setup. `-javadoc.jar` is a Central Portal packaging placeholder, not generated Dokka HTML reference documentation.

## Binary Compatibility (`abiValidation()`) & API Design Lint (`:verifyApiDesign`)

All three published modules (`:maradona`, `:maradona-material3`, and `:maradona-jewel`) enable the Kotlin Gradle Plugin's built-in `abiValidation()` extension and commit reviewed ABI baselines under `<module>/api/`:

- **`:maradona`**: `maradona/api/desktop/maradona.api`, `maradona/api/android/maradona.api`, `maradona/api/maradona.klib.api`
- **`:maradona-material3`**: `maradona-material3/api/desktop/maradona-material3.api`, `maradona-material3/api/android/maradona-material3.api`, `maradona-material3/api/maradona-material3.klib.api`
- **`:maradona-jewel`**: `maradona-jewel/api/maradona-jewel.api`

In addition to `checkKotlinAbi`, the root `:verifyApiDesign` task enforces:

1. **Desktop/Android JVM ABI parity:** `api/desktop/<module>.api` and `api/android/<module>.api` must be byte-for-byte identical for `:maradona` and `:maradona-material3`.
2. **No internal implementation leakage:** Internal Dagre ranking/routing helpers (`dev.maradona.layout.dagre`), viewport-culling helpers (`ViewportCullingSupport`), and internal SVG formatting structures (`FormattedTextSpan`, `*LayoutForExport`) must never leak into public ABI dumps.
3. **Module & package boundary hygiene:** `:maradona` and `:maradona-material3` must not expose `org.jetbrains.jewel`, `java.awt`, or `javax.swing` types; `:maradona` must not expose `androidx.compose.material3` types; all public classes must reside in their module's declared package namespace.
4. **KDoc coverage on public entry points:** `MaradonaDiagram`, `MaradonaTheme`, `LocalMaradonaTheme`, `MaradonaParser`, `MaradonaDiagramModel`, `MaradonaSvgExporter`, and all Material 3 / Jewel theme connector functions must carry KDoc comments in source.

```sh
./gradlew checkKotlinAbi verifyApiDesign # Verify binary compatibility & API design hygiene
./gradlew updateKotlinAbi                # Intentionally update ABI baseline dumps after reviewed API additions
```

## Required GitHub Actions Secrets

Configure these four secrets in **Settings → Secrets and variables → Actions** on `rock3r/maradona` (using the same Sonatype Central Portal account and PGP key as `rock3r/spectre` for namespace `dev.sebastiano`):

| Secret Name | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal user token password |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored PGP private key (`gpg --armor --export-secret-key <key-id>`) |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Passphrase for `SIGNING_IN_MEMORY_KEY` |

## Local Verification (`:verifyMavenLocalPublication`)

`publishToMavenLocal` and `:verifyMavenLocalPublication` work locally without credentials or PGP keys (signing only activates when `signingInMemoryKey` is provided):

```sh
./gradlew verifyMavenLocalPublication -PVERSION_NAME=0.1.0
```

## Central Portal Validation & Promotion (`scripts/central_portal_check.py`)

After the tag workflow uploads a staged deployment to Sonatype Central Portal, validate and promote it using [`scripts/central_portal_check.py`](../scripts/central_portal_check.py) (which reads credentials from `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` or the 1Password item `"Maradona Maven Central Portal"` / `"Spectre Maven Central Portal"`):

```sh
# 1. Check deployment state
python3 scripts/central_portal_check.py status --deployment-id <deployment-id>

# 2. Download and verify all 12 KMP components, .asc signatures, checksums, and JAR contents
python3 scripts/central_portal_check.py validate --deployment-id <deployment-id> --version 0.1.0

# 3. Promote the validated deployment to Maven Central
python3 scripts/central_portal_check.py publish --deployment-id <deployment-id> --version 0.1.0

# 4. Undraft the GitHub release once Central reports PUBLISHED
gh release edit v0.1.0 --draft=false
```
