# How to Release EXIFiler

This document describes the end-to-end release process for EXIFiler on Android and outlines
the future iOS release path.

---

## Android releases (automated)

Releases are built and published automatically by
`.github/workflows/release.yml` whenever a **semver tag** of the form `v*` is pushed
(e.g. `v1.0.0`, `v1.2.3-beta.1`).  A manual-dispatch option also exists for
re-triggering a build against an existing tag.

### What the workflow does

1. Checks out the tagged commit.
2. Builds the **release APK** (`assembleRelease`) and **release AAB** (`bundleRelease`).
3. Signs both artifacts with the keystore stored in GitHub Secrets (see below).
4. Creates (or updates) a **GitHub Release** named `EXIFiler <tag>` with auto-generated
   release notes and the signed APK + AAB attached.

Tags that contain a hyphen (e.g. `v1.0.0-beta.1`) are published as **pre-releases**.

---

## One-time setup — signing secrets

> **Never commit the keystore file or any credential to the repository.**

### 1 — Generate a release keystore (if you don't have one yet)

```bash
keytool -genkey -v \
  -keystore exifiler-release.keystore \
  -alias exifiler \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

Keep the generated `exifiler-release.keystore` file in a **secure, backed-up location**
outside the repository (e.g. a password manager or encrypted vault).
If this keystore is ever lost you will be unable to publish updates to the same app listing.

### 2 — Store the keystore as a base64 secret

**Linux (GNU coreutils):**
```bash
base64 -w 0 exifiler-release.keystore
```

**macOS (BSD base64):**
```bash
base64 -i exifiler-release.keystore | tr -d '\n'
```

**Either platform — Python fallback:**
```bash
python3 -c "import base64, sys; sys.stdout.write(base64.b64encode(open('exifiler-release.keystore','rb').read()).decode())"
```

Copy the single-line output and save it as a **GitHub repository secret** named
`RELEASE_KEYSTORE_BASE64`.

### 3 — Store the remaining secrets

| Secret name              | Value                                      |
|--------------------------|--------------------------------------------|
| `RELEASE_KEYSTORE_BASE64`| Base64-encoded keystore file (step 2)      |
| `RELEASE_KEYSTORE_PASSWORD` | Password chosen during `keytool -genkey` |
| `RELEASE_KEY_ALIAS`      | Alias chosen during `keytool -genkey` (e.g. `exifiler`) |
| `RELEASE_KEY_PASSWORD`   | Key password chosen during `keytool -genkey` |

Add secrets at:
**GitHub → Repository → Settings → Secrets and variables → Actions → New repository secret**

---

## Publishing a release

1. Update `versionCode` and `versionName` in `androidApp/build.gradle.kts`.
2. Commit and push to `main`.
3. Create and push a semver tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
4. The **Release** workflow triggers automatically.  Monitor progress at
   `https://github.com/LookAtWhatAiCanDo/EXIFiler/actions`.
5. Once the workflow completes, a GitHub Release is created at
   `https://github.com/LookAtWhatAiCanDo/EXIFiler/releases` with the signed APK and AAB
   attached.

### Manual trigger

If you need to re-build against an existing tag without pushing again:

1. Go to **Actions → Release → Run workflow**.
2. Enter the existing tag name (e.g. `v1.0.0`).
3. Click **Run workflow**.

---

## Rotating the keystore / secrets

1. Generate a new keystore with `keytool` (as above).
2. Base64-encode the new keystore file.
3. Update all four secrets in GitHub.
4. Keep the old keystore in your secure vault in case you need to re-sign older builds.

> **Note:** If the app is already published on Google Play, you **must** use the same
> signing key (or use Play App Signing key management) — changing the key will prevent
> users from updating.

---

## Local release build (without secrets)

When the signing environment variables are absent the Gradle build skips the signing
config, producing an **unsigned** release APK/AAB.  This is suitable for local testing:

```bash
./gradlew :androidApp:assembleRelease
# output: androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

---

## Future — iOS release process (Phase 2+)

The following is a checklist for when KMP iOS development begins:

- [ ] Enroll in Apple Developer Program.
- [ ] Generate an **iOS Distribution Certificate** and a **Provisioning Profile** for the
      app's bundle ID.
- [ ] Store certificate (`p12`) + password and provisioning profile as GitHub Secrets (or
      use a secure CI secret management solution such as `fastlane match`).
- [ ] Add a new GitHub Actions workflow `release-ios.yml` running on `macos-latest` that:
  - Runs `./gradlew :shared:compileKotlinIosArm64` (KMP shared framework).
  - Builds and archives the Xcode project (`xcodebuild archive`).
  - Exports the `.ipa` using the distribution provisioning profile.
  - Uploads the `.ipa` to the GitHub Release as an additional asset.
  - Optionally uploads to TestFlight via `xcrun altool` or `fastlane pilot`.
- [ ] Review certificate expiry rotation process and document it here.

---

## References

- [GitHub Docs — Managing Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
- [Android — Sign your app](https://developer.android.com/studio/publish/app-signing)
- [Securely storing Android keystores in CI](https://developer.android.com/studio/publish/app-signing#github-actions)
- [softprops/action-gh-release](https://github.com/softprops/action-gh-release)
