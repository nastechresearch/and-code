# Release guide

## Unsigned CI artifacts

GitHub Actions builds `app-release-unsigned.apk`. These are for smoke testing only.

## Signed release APK / AAB (local)

1. Create a keystore (once). The repository includes a generator that creates a
   private, Git-ignored keystore and credentials file without printing secrets:

```bash
./scripts/generate_release_keys.sh
```

2. Source the generated `.release-keys/credentials.env` locally, or copy its
   values to `~/.gradle/gradle.properties` (do not commit):

```properties
AND_CODE_STORE_FILE=/absolute/path/and-code-release.jks
AND_CODE_STORE_PASSWORD=...
AND_CODE_KEY_ALIAS=andcode-release
AND_CODE_KEY_PASSWORD=...
```

3. Build the signed artifact:

```bash
./gradlew assembleGithubRelease
# or
./gradlew bundleGithubRelease
```

4. Verify:

```bash
apksigner verify --print-certs app/build/outputs/apk/github/release/app-github-release.apk
```

## Versioning

- `versionName` / `versionCode` live in `app/build.gradle.kts`
- Tag releases as `vX.Y.Z` matching `versionName`
- Update `CHANGELOG.md` (or GitHub Release notes) with user-facing changes

## Pre-release checklist

- [ ] `./gradlew testGithubDebugUnitTest lintGithubDebug assembleGithubRelease`
- [ ] Manual smoke: local install, chat, permission approve/reject, remote connect
- [ ] `THIRD_PARTY_NOTICES.md` still accurate
- [ ] No secrets in git history

## F-Droid-compatible binary repository

The repository also has a `Publish F-Droid repository` workflow. It publishes
the signed APKs from GitHub Releases as a self-hosted F-Droid binary repository on
GitHub Pages. This is not an application submission to the official F-Droid
repository and does not require an F-Droiddata review.

Before enabling the workflow, create a dedicated repository signing keystore
and add these GitHub Actions secrets:

- `F_DROID_REPO_KEYSTORE_BASE64`: base64-encoded repository keystore
- `F_DROID_REPO_KEYSTORE_PASSWORD`: keystore password
- `F_DROID_REPO_KEY_ALIAS`: repository key alias
- `F_DROID_REPO_KEY_PASSWORD`: repository key password

The separate key and all four values can be generated locally with
`./scripts/generate_fdroid_keys.sh`. It writes a Git-ignored keystore and a
mode-600 credentials file under `.release-keys/`; copy the values into the
matching repository secrets without committing either file. The APK release
key and F-Droid repository key must remain different keys.

For example, create the keystore locally with:

```bash
keytool -genkeypair -v \
  -keystore fdroid-repo.keystore \
  -alias and-code-fdroid \
  -keyalg RSA -keysize 4096 -validity 10000
base64 fdroid-repo.keystore | tr -d '\n'
```

Put the final command's output in `F_DROID_REPO_KEYSTORE_BASE64`. The other
three values must match the keystore when it is created. Do not commit the
keystore or its passwords.

The repository key is separate from the APK signing key. Back it up securely;
changing it makes existing clients treat the repository as a new repository.

Enable GitHub Pages with `GitHub Actions` as the source. After a published
release, users can add:

```text
https://nastechresearch.github.io/and-code/fdroid/repo/
```

The workflow retains the latest 100 non-draft, non-prerelease GitHub releases.

## Official F-Droid catalog (build-from-source)

The self-hosted repository above only republishes the GitHub-signed APK; it does
not put AndCode in the official F-Droid catalog (browsable by category, e.g.
"AI Chat"). That requires F-Droid's own build server to compile the app from
source, which does not accept Firebase/Google Play services.

The `app` module has a `distribution` flavor dimension for this:

- `github` — current behavior, includes Firebase Analytics/Crashlytics.
- `fdroid` — no Firebase code at all (`app/src/fdroid/.../diagnostics/`
  provides no-op `AnalyticsReporter`/`CrashReporter` in place of
  `app/src/github/.../diagnostics/`, which keeps the Firebase-backed ones).

Build it locally with:

```bash
./gradlew -Pandcode.fdroidBuild=true :app:assembleFdroidRelease
```

The `-Pandcode.fdroidBuild=true` property additionally skips applying the
`com.google.gms.google-services` / `com.google.firebase.crashlytics` Gradle
plugins outright (they process `google-services.json` project-wide regardless
of flavor, so leaving them applied would still embed inert Google project
identifiers in the fdroid build).

Submitting to the official catalog means opening a merge request against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata). This has been done:
[!48005](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/48005). Current
metadata, after review feedback from F-Droid maintainers:

```yaml
Categories:
  - AI Chat
License: MIT
AuthorName: Yu-ga
RepoType: git
Repo: https://github.com/nastechresearch/and-code
SourceCode: https://github.com/nastechresearch/and-code
IssueTracker: https://github.com/nastechresearch/and-code/issues
Changelog: https://github.com/nastechresearch/and-code/releases

AntiFeatures:
  NonFreeNet: optional GitHub OAuth sign-in, not required for core functionality
  TetheredNet: the on-demand Vosk wake-word speech model is only ever fetched from alphacephei.com

Builds:
  - versionName: "1.2.20"
    versionCode: 59
    commit: 4184ea3154dce9ad25fec56aa9ef9bb215c77036
    subdir: app
    gradle:
      - fdroid
    gradleprops:
      - andcode.fdroidBuild=true
    # The google-services/firebase-crashlytics Gradle plugins are declared with
    # `apply false` in both build.gradle.kts files (needed by the "github" flavor;
    # never applied for this fdroid build, see `andcode.fdroidBuild` above), but the
    # source scanner still flags the bare "apply false" declaration lines. Strip
    # just those lines instead of scanignoring the whole files.
    prebuild:
      - sed -i '/id("com.google.gms.google-services").*apply false/d' ../build.gradle.kts
      - sed -i '/id("com.google.firebase.crashlytics").*apply false/d' ../build.gradle.kts
      - sed -i '/id("com.google.gms.google-services").*apply false/d' build.gradle.kts
      - sed -i '/id("com.google.firebase.crashlytics").*apply false/d' build.gradle.kts

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: "1.2.20"
CurrentVersionCode: 59
```

This recipe was dry-run locally: after a fresh checkout at the pinned commit
with the two `prebuild` sed lines applied to each file by hand,
`./gradlew -Pandcode.fdroidBuild=true :app:assembleFdroidRelease` still built
successfully end-to-end. `AntiFeatures` is a map with a reason per entry
(the initial submission used a plain list), and `commit:` is the resolved
full SHA rather than the tag name — both per review feedback.

Review also asked whether the Vosk model download is opt-in and discloses
that it bypasses F-Droid's build/source checks. Checked the app source: the
download was already gated behind an explicit "Download" button in Settings
(never auto-triggered by the wake-word toggle), so declining was already no
harder than accepting. The missing piece — an in-app disclosure of the
bypass — shipped in v1.2.20 (PR #309: visible text next to the Download
button, plus a regression test in `LegalDisclosureComplianceTest`), so the
merge request's `Builds:` entry above now points at v1.2.20. The remaining
step is editing MR !48005 itself to this entry.

The fork's own CI (`soccer.hy620/fdroiddata`) is separately blocked: GitLab's
GraphQL API reports every pipeline run failing with
`"The pipeline failed due to the user not being verified."` — an
account-level verification gate on shared-runner minutes, unrelated to the
recipe itself. That needs clearing on the GitLab account before the fork's
own pipeline can go green (does not block F-Droid's own review process).
