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

## Tag-Driven Release Pipeline (`.github/workflows/release.yml`)

Pushing a SemVer tag matching `v*` (for example `v0.1.0`) triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml):

1. **`release-gate` (`ubuntu-latest`):**
   - Validates that the tag matches `v<major>.<minor>.<patch>` (with optional SemVer pre-release/build metadata) via [`.github/scripts/derive-release-version.sh`](../.github/scripts/derive-release-version.sh) and strips the leading `v` to derive `RELEASE_VERSION`.
   - Runs `./gradlew :maradona:desktopTest :maradona-material3:desktopTest :maradona-jewel:desktopTest :sample-desktop:desktopTest`.
2. **`publish` (`macos-latest`, depends on `release-gate`):**
   - Runs on `macos-latest` (with Temurin JDK 25, Xcode, and the Android SDK) so all four KMP targets (`desktop`, `android`, `iosArm64`, `iosSimulatorArm64`) are compiled and uploaded together in a single atomic Sonatype Central Portal deployment.
   - Runs `./gradlew verifyMavenLocalPublication -PVERSION_NAME="$RELEASE_VERSION"` first to verify all 12 KMP coordinate directories, POM metadata, Gradle Module Metadata, `META-INF/LICENSE` + `META-INF/NOTICE`, and the absence of Jewel dependencies in `:maradona` and `:maradona-material3`.
   - Publishes signed artifacts to Sonatype Central Portal in manual-promotion mode (`automaticRelease = false`) via `./gradlew :maradona:publishToMavenCentral :maradona-material3:publishToMavenCentral :maradona-jewel:publishToMavenCentral -PVERSION_NAME="$RELEASE_VERSION"`.
   - Creates a draft GitHub release (`--draft`) listing the Maven Central coordinates and linking to [`CHANGELOG.md`](../CHANGELOG.md).

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
