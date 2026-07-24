# M3 Release Plumbing + v0.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the debug-only test app into a signed, branded, legally-compliant product that a stranger can download from GitHub Releases and play — tagged `v0.1.0`.

**Architecture:** The app (`mgba-android/app`, applicationId `com.trebuchetdynamics.mgba`) currently ships as a debug/benchmark build signed with Android's debug key, labelled "mGBA Custom", with a placeholder vector icon and no licence notices. M3 adds: product identity (name/icon/strings), an in-app notices screen carrying mGBA's MPL-2.0 obligations, a release signing config that reads credentials from the environment and never from git, and a CI job that builds, signs, and attaches an APK to a GitHub Release on tag push.

**Tech Stack:** Android Gradle (JDK 17, SDK 35, NDK `22.1.7171670`, CMake `3.18.1`), `keytool`/`apksigner`/`aapt2` from build-tools 35.0.0, GitHub Actions, `gh` CLI.

## Decisions already made (do not relitigate)

| Decision | Value | Source |
|---|---|---|
| Product name | **Garnacha Boy** | User, 2026-07-14, with the Nintendo trademark risk explicitly presented and accepted |
| applicationId | **`com.trebuchetdynamics.garnacha`** — changed from `.mgba`, then **permanent forever** | User, 2026-07-14. Done now because it is free before the first public release and impossible after: on Play the ID can never change, and on any channel a changed ID orphans every existing install and its save data. The only casualty is the throwaway test install on the dev device. |
| Release signing | Keystore generated locally; base64 + passwords held in GitHub Secrets; CI signs on tag push | User, 2026-07-14 |
| Distribution (this milestone) | GitHub Releases only. Play and F-Droid are M6 | Roadmap spec |

## Global Constraints

- **No secret ever enters git.** Not the keystore, not a password, not a base64 blob. `git status` must be clean of them at every step; `.gitignore` must cover `*.jks`, `*.keystore`, `keystore.properties`.
- The build must still work for a contributor with **no signing credentials** — release signing is skipped, not failed, when the env vars are absent.
- mGBA stays an unmodified pinned submodule (`26b7884bc25a5933960f3cdcd98bac1ae14d42e2`, `0.10.5`); never edit `vendor/mgba`.
- The APK must package mGBA's MPL-2.0 licence, and the app must surface it to the user. This is a licence obligation, not a nicety.
- No game or BIOS content is ever committed or bundled.
- Zero Android lint errors (`lintDebug`).
- Every commit message ends with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Work on branch `mvp`.

## File Structure

| File | Responsibility |
|---|---|
| `docs/adr/0002-product-name.md` (create) | Records the name and the accepted trademark risk |
| `mgba-android/app/src/main/res/values/strings.xml` (create) | All user-visible strings; kills the hardcoded manifest label |
| `mgba-android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (create) | Adaptive icon |
| `mgba-android/app/src/main/res/drawable/ic_launcher_foreground.xml` (create) | Icon foreground |
| `mgba-android/app/src/main/res/values/ic_launcher_background.xml` (create) | Icon background colour |
| `mgba-android/app/src/main/assets/NOTICES.md` (create) | The notices text shown in-app and packaged in the APK |
| `.../emulator/app/NoticesActivity.java` (create) | Renders the notices; reachable from the app |
| `.../emulator/app/Notices.java` (create) | Pure loader/formatter — JVM-unit-testable |
| `.../app/src/test/.../NoticesTest.java` (create) | Tests for the above |
| `mgba-android/app/build.gradle` (modify) | `release` build type + signing config from env |
| `mgba-android/app/src/main/AndroidManifest.xml` (modify) | Label from strings, adaptive icon, register NoticesActivity |
| `.gitignore` (modify) | Keystore patterns |
| `CHANGELOG.md` (create) | Keep-a-Changelog format |
| `.github/workflows/release.yml` (create) | Tag → build → sign → GitHub Release |
| `RELEASING.md` (create) | How to cut a release; how to rotate the key |

---

### Task 1: ADR for the product name and its accepted risk

**Files:**
- Create: `docs/adr/0002-product-name.md`

**Interfaces:**
- Produces: the canonical product name string `Garnacha Boy`, used verbatim by every later task.

- [ ] **Step 1: Write the ADR**

Create `docs/adr/0002-product-name.md`:

```markdown
# ADR 0002: Product name "Garnacha Boy"

- Status: Accepted
- Date: 2026-07-14

## Decision

The product is named **Garnacha Boy**, with the permanent applicationId
`com.trebuchetdynamics.garnacha` (changed from `com.trebuchetdynamics.mgba`
before any public release, while doing so is still free).

## Why

The name follows the Pizza Boy convention — a concrete, playful food noun that
says nothing about emulation and is therefore memorable. "Garnacha" is
distinctive, unclaimed in this category, and local to where the product is
built.

## The accepted risk

The "Boy" suffix evokes Nintendo's **Game Boy** trademark. On Google Play a
trademark complaint is cheap to file and acted on quickly, and Nintendo has
recently pursued emulator projects aggressively (Yuzu and Citra shut down in
2024; Dolphin's Steam release was blocked). Pizza Boy itself survives as a
sideloaded APK and is no longer on the Play Store.

This risk was presented explicitly and accepted by the product owner on
2026-07-14. The exposure is understood to be:

- **Google Play:** a listing takedown is plausible. This is the channel at risk.
- **GitHub Releases and F-Droid:** effectively unaffected; these are not
  trademark-complaint-driven channels.

Mitigations in place:

- The app is never described as being, or endorsed by, mGBA or Nintendo.
- Store copy will credit mGBA as the emulation core under MPL-2.0.
- No Nintendo trademark, logo, or product likeness is used in branding.

## Reversal

Dropping the suffix to plain "Garnacha" removes the exposure and requires
changing only `strings.xml`, store metadata, and this ADR — the applicationId,
signing key, and save data are unaffected, because the display name and the
applicationId are independent. **This remains cheap to reverse right up until
the Play listing is submitted (M6); it is expensive afterwards.**

The applicationId itself is *not* reversible after v0.1.0 ships: changing it
orphans every install and its save data, and Play never permits it at all.
```

- [ ] **Step 2: Commit**

```sh
git add docs/adr/0002-product-name.md
git commit -m "docs: ADR 0002 — name the product Garnacha Boy

Records the name and the explicitly accepted Game Boy trademark risk on the
Play channel, plus the cheap reversal path before M6.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Product identity — name, strings, adaptive icon

**Files:**
- Create: `mgba-android/app/src/main/res/values/strings.xml`
- Create: `mgba-android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `mgba-android/app/src/main/res/values/ic_launcher_background.xml`
- Create: `mgba-android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `mgba-android/app/src/main/res/mipmap/ic_launcher.xml` (API 24–25 fallback)
- Modify: `mgba-android/app/src/main/AndroidManifest.xml`
- Modify: `mgba-android/app/build.gradle` (applicationId)
- Delete: `mgba-android/app/src/main/res/drawable/ic_launcher.xml` (replaced)

**Interfaces:**
- Consumes: the name from Task 1.
- Produces: applicationId `com.trebuchetdynamics.garnacha`; `@string/app_name` = `Garnacha Boy`; `@mipmap/ic_launcher` adaptive icon. Task 7 verifies all three via `aapt2 dump badging`.

Note: the Java **namespace** stays `com.trebuchetdynamics.emulator.app` — only
the applicationId changes. No Java package or import moves.

- [ ] **Step 1: Create strings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Garnacha Boy</string>
    <string name="notices_title">Open source notices</string>
    <string name="notices_button">Notices</string>
    <string name="status_no_rom">Tap to load a GBA ROM</string>
</resources>
```

- [ ] **Step 2: Create the icon foreground**

`res/drawable/ic_launcher_foreground.xml` — a simple, legible mark (a stylised
cartridge). Adaptive-icon foregrounds are 108x108dp with the outer 18dp a safe
margin, so keep art inside the central 72x72dp:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- cartridge body -->
    <path
        android:fillColor="#F5C542"
        android:pathData="M36,26h36a4,4 0 0 1 4,4v48a4,4 0 0 1 -4,4h-36a4,4 0 0 1 -4,-4v-48a4,4 0 0 1 4,-4z" />
    <!-- label -->
    <path
        android:fillColor="#2B2118"
        android:pathData="M41,34h26v20h-26z" />
    <!-- contacts -->
    <path
        android:fillColor="#2B2118"
        android:pathData="M44,64h20v4h-20z M44,71h20v4h-20z" />
</vector>
```

- [ ] **Step 3: Create the icon background colour**

`res/values/ic_launcher_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1E2228</color>
</resources>
```

- [ ] **Step 4: Create the adaptive icon**

`res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 5: Add the API 24–25 icon fallback**

`minSdk` is 24, but `mipmap-anydpi-v26` only applies from API 26, so API 24–25
needs an `ic_launcher` that resolves without the adaptive-icon element.

`res/mipmap/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@color/ic_launcher_background" />
    <item android:drawable="@drawable/ic_launcher_foreground" />
</layer-list>
```
API 26+ resolves `@mipmap/ic_launcher` to the `-v26` adaptive icon; API 24–25
falls back to this layer-list. Both resolve, so no device shows a blank icon.

- [ ] **Step 6: Point the manifest at them and drop the old icon**

In `AndroidManifest.xml`, change the `<application>` attributes:
```xml
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="@string/app_name"
```
Then delete the old drawable:
```sh
git rm mgba-android/app/src/main/res/drawable/ic_launcher.xml
```

- [ ] **Step 7: Set the permanent applicationId**

In `mgba-android/app/build.gradle`, change the one line:
```groovy
        applicationId 'com.trebuchetdynamics.garnacha'
```
This is irreversible after the first public release: on Play the ID can never be
changed, and on any channel changing it orphans every install and its saves. It
is free to do now and only now.

- [ ] **Step 8: Build and verify identity is really in the APK**

```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging \
  mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk | grep -E "application-label|application-icon|^package:"
```
Expected: `application-label:'Garnacha Boy'`, an `application-icon` entry, and
`package: name='com.trebuchetdynamics.garnacha'`. Lint: 0 errors.

- [ ] **Step 9: Install and eyeball it on the device**

The old test app has a different applicationId, so this installs *alongside* it.
Remove the old one to avoid confusing later steps:
```sh
adb uninstall com.trebuchetdynamics.mgba || true
adb install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
adb shell monkey -p com.trebuchetdynamics.garnacha -c android.intent.category.LAUNCHER 1
```
Confirm the launcher shows "Garnacha Boy" with the new icon (not the old
placeholder, not a white square). Capture a screenshot for the receipt.

- [ ] **Step 10: Commit**

```sh
git add mgba-android/app/src/main/res mgba-android/app/src/main/AndroidManifest.xml mgba-android/app/build.gradle
git commit -m "feat(app): brand the app as Garnacha Boy and fix the applicationId

Replaces the 'mGBA Custom' placeholder label and flat vector icon, and sets the
permanent applicationId com.trebuchetdynamics.garnacha while it is still free to
change — after the first public release it never is. Strings are externalised so
the display name stays changeable per ADR 0002's reversal path.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: In-app open-source notices (an MPL-2.0 obligation)

MPL-2.0 requires that recipients of the binary are informed of the licence and
their rights, and told where the source is. Packaging `LICENSE-mGBA` in the AAR
(already done) is not sufficient for an app a user installs — the app must
surface it.

**Files:**
- Create: `mgba-android/app/src/main/assets/NOTICES.md`
- Create: `.../emulator/app/Notices.java`
- Create: `.../emulator/app/NoticesActivity.java`
- Create: `mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/NoticesTest.java`
- Modify: `mgba-android/app/src/main/AndroidManifest.xml`
- Modify: `.../emulator/app/EmulatorView.java` (add a NOTICES tap target next to LOAD)

**Interfaces:**
- Consumes: `ControlLayout` (from the landscape work) for placing the new chip.
- Produces: `Notices.load(InputStream) : String` (pure, unit-tested);
  `NoticesActivity` shown by an `ACTION_VIEW`-style internal intent.

- [ ] **Step 1: Write the notices content**

`mgba-android/app/src/main/assets/NOTICES.md`:

```markdown
# Garnacha Boy — open source notices

Garnacha Boy is free software. It is not affiliated with, endorsed by, or
sponsored by Nintendo or the mGBA project.

## mGBA

Game Boy Advance emulation is provided by mGBA, used unmodified.

- Version: 0.10.5 (commit 26b7884bc25a5933960f3cdcd98bac1ae14d42e2)
- Copyright: Jeffrey Pfau and mGBA contributors
- Licence: Mozilla Public License 2.0
- Source: https://github.com/mgba-emu/mgba

Under the MPL-2.0 you are entitled to the source code of mGBA and of any
modifications to MPL-covered files. Garnacha Boy makes no modifications to
mGBA's source. The full licence text is included with this application and is
available at https://mozilla.org/MPL/2.0/.

## Garnacha Boy

- Source: https://github.com/TrebuchetDynamics/garnacha-boy-android
- Licence: MIT

No game ROMs or BIOS files are included with this application. Supply only
content you are legally entitled to use.
```

- [ ] **Step 2: Write the failing test**

`mgba-android/app/src/test/java/com/trebuchetdynamics/emulator/app/NoticesTest.java`:
```java
package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class NoticesTest {
    @Test
    public void readsTheNoticesStreamAsText() throws IOException {
        InputStream in = new ByteArrayInputStream(
                "# Title\n\nmGBA, MPL-2.0".getBytes(StandardCharsets.UTF_8));
        String text = Notices.load(in);
        assertTrue(text.contains("mGBA"));
        assertTrue(text.contains("MPL-2.0"));
    }

    @Test
    public void rejectsAnEmptyNoticesStream() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        // An empty notices file means the licence obligation is silently unmet;
        // fail loudly rather than shipping a blank screen.
        assertThrows(IOException.class, () -> Notices.load(in));
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*NoticesTest'
```
Expected: FAIL — `Notices` does not exist.

- [ ] **Step 4: Implement Notices**

`.../emulator/app/Notices.java`:
```java
package com.trebuchetdynamics.emulator.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads the packaged open-source notices. Pure java.io so it is unit-testable. */
final class Notices {
    private Notices() {
    }

    static String load(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        if (out.size() == 0) {
            throw new IOException("Notices are empty — the licence obligation is unmet");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

```sh
mgba-android/gradlew -p mgba-android :app:testDebugUnitTest --tests '*NoticesTest'
```
Expected: PASS (2 tests).

- [ ] **Step 6: Add the NoticesActivity**

`.../emulator/app/NoticesActivity.java`:
```java
package com.trebuchetdynamics.emulator.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

public final class NoticesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.notices_title);

        TextView text = new TextView(this);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setTextColor(Color.WHITE);
        text.setTextIsSelectable(true);
        text.setGravity(Gravity.START);
        try (InputStream in = getAssets().open("NOTICES.md")) {
            text.setText(Notices.load(in));
        } catch (IOException e) {
            text.setText("Notices could not be loaded: " + e.getMessage());
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(14, 16, 20));
        scroll.addView(text);
        setContentView(scroll);
    }
}
```

- [ ] **Step 7: Register it and make it reachable**

In `AndroidManifest.xml`, inside `<application>`:
```xml
        <activity
            android:name=".NoticesActivity"
            android:exported="false"
            android:label="@string/notices_title" />
```

In `EmulatorView`, add a "NOTICES" chip beside the existing LOAD chip and a
`Runnable requestNotices` callback alongside `requestRom` (mirror the existing
`isLoadHit`/`requestRom` wiring exactly — do NOT reintroduce hand-computed
geometry; put the chip's rect in `ControlLayout` next to `loadLeft/Top/Right/Bottom`
as `noticesLeft/Top/Right/Bottom`, with an `isNoticesHit(x, y)`, and add a
`ControlLayoutTest` case asserting the two chips do not overlap each other or
the game rect in both orientations).

In `MainActivity`, pass a callback that starts the activity:
```java
        startActivity(new android.content.Intent(this, NoticesActivity.class));
```

- [ ] **Step 8: Build, test, and verify on device**

```sh
mgba-android/gradlew -p mgba-android lintDebug :app:assembleBenchmark :app:testDebugUnitTest
adb install -r mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk
```
On the device: tap NOTICES; confirm the mGBA MPL-2.0 attribution and source URL
render and scroll. Screenshot it — that screenshot is the compliance evidence.

Also confirm the licence file is actually inside the APK:
```sh
unzip -l mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk | grep -iE "NOTICES|LICENSE"
```
Expected: `assets/NOTICES.md` present, plus the existing `META-INF/LICENSE-mGBA`.

- [ ] **Step 9: Commit**

```sh
git add mgba-android/app/src/main mgba-android/app/src/test
git commit -m "feat(app): show open-source notices in-app

MPL-2.0 requires binary recipients be told of the licence and where to get the
source; packaging the licence in the AAR does not discharge that for an app a
user installs. Adds a notices screen carrying mGBA's attribution, licence, and
source URL, and an explicit statement that we are not affiliated with Nintendo
or the mGBA project.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Release signing that never touches git

**Files:**
- Modify: `mgba-android/app/build.gradle`
- Modify: `.gitignore`
- Create: `RELEASING.md`

**Interfaces:**
- Produces: a `release` build type signed when `GARNACHA_KEYSTORE_*` env vars are
  present, and cleanly unsigned when they are not. Task 6's CI job supplies them.

- [ ] **Step 1: Harden .gitignore FIRST (before a key exists to leak)**

Append to `.gitignore`:
```gitignore
# Signing material — never commit
*.jks
*.keystore
keystore.properties
*.p12
```

Commit this alone, before generating any key:
```sh
git add .gitignore
git commit -m "chore: never track signing material

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 2: Generate the release keystore (local only, NEVER committed)**

```sh
mkdir -p ~/.android-keys
keytool -genkeypair -v \
  -keystore ~/.android-keys/garnacha-release.jks \
  -alias garnacha \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS \
  -dname "CN=Trebuchet Dynamics, OU=Garnacha Boy, O=Trebuchet Dynamics, C=MX"
```
Choose a strong store password and key password when prompted; record them in a
password manager. **A lost key cannot be recovered and Play will never accept a
differently-signed update — this file is the single most irreplaceable artifact
of the project.** Back it up somewhere durable and offline.

Verify it is not visible to git:
```sh
git status --short   # must be clean
git check-ignore -v ~/.android-keys/garnacha-release.jks 2>/dev/null || echo "outside the repo — good"
```

- [ ] **Step 3: Wire signing into build.gradle**

In `mgba-android/app/build.gradle`, add above `buildTypes`:
```groovy
    signingConfigs {
        release {
            // Supplied by CI secrets or a local env; absent for contributors,
            // who then get an unsigned release build rather than a hard failure.
            def storePath = System.getenv('GARNACHA_KEYSTORE_PATH')
            if (storePath) {
                storeFile file(storePath)
                storePassword System.getenv('GARNACHA_KEYSTORE_PASSWORD')
                keyAlias System.getenv('GARNACHA_KEY_ALIAS')
                keyPassword System.getenv('GARNACHA_KEY_PASSWORD')
            }
        }
    }
```
and in `buildTypes`, add a `release` type:
```groovy
        release {
            minifyEnabled false
            signingConfig System.getenv('GARNACHA_KEYSTORE_PATH')
                    ? signingConfigs.release
                    : null
        }
```
Leave the existing `benchmark` type exactly as it is — it stays debug-signed for
measurement and must not start depending on the release key.

- [ ] **Step 4: Verify BOTH paths**

Without credentials (the contributor path):
```sh
unset GARNACHA_KEYSTORE_PATH
mgba-android/gradlew -p mgba-android :app:assembleRelease
ls mgba-android/app/build/outputs/apk/release/
```
Expected: BUILD SUCCESSFUL, an `app-release-unsigned.apk` — not a failure.

With credentials (the release path):
```sh
export GARNACHA_KEYSTORE_PATH=~/.android-keys/garnacha-release.jks
export GARNACHA_KEYSTORE_PASSWORD='...' GARNACHA_KEY_ALIAS=garnacha GARNACHA_KEY_PASSWORD='...'
mgba-android/gradlew -p mgba-android clean :app:assembleRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  mgba-android/app/build/outputs/apk/release/app-release.apk
```
Expected: `Verifies`, and a certificate whose DN is the one from Step 2. Record
the **SHA-256 certificate digest** — publish it in `RELEASING.md` so users can
verify future APKs are genuinely yours.

- [ ] **Step 5: Write RELEASING.md**

Create `RELEASING.md` documenting: the env vars, how to cut a release (Task 7),
the certificate SHA-256 digest from Step 4, the fact that the key is
irreplaceable and where the backup lives (do not write the location of the
secret itself — just that a backup exists and who holds it), and how to rotate
if it is ever compromised (a rotation means a new applicationId on Play; on
GitHub it means users must uninstall and reinstall).

- [ ] **Step 6: Confirm no secret leaked, then commit**

```sh
git status --short          # no .jks, no properties file
git diff --cached | grep -iE "password|BEGIN (RSA|PRIVATE)" && echo "SECRET IN DIFF — STOP" || echo "clean"
git add mgba-android/app/build.gradle RELEASING.md
git commit -m "feat(release): sign release builds from env-supplied credentials

The keystore and its passwords come from the environment (CI secrets locally),
never from the repository. A contributor without credentials still gets a
successful unsigned release build instead of a broken one.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Versioning and changelog

**Files:**
- Create: `CHANGELOG.md`
- Modify: `mgba-android/app/build.gradle`

**Interfaces:**
- Produces: `versionName` derived from the git tag when building in CI; a
  changelog whose top section becomes the GitHub Release body in Task 6.

- [ ] **Step 1: Derive the version from the tag**

In `mgba-android/app/build.gradle`, replace the hardcoded version lines:
```groovy
        // A tag push (refs/tags/v0.1.0) sets GARNACHA_VERSION_NAME in CI.
        // Local builds fall back to a dev marker so they can never be mistaken
        // for a release build.
        versionCode Integer.parseInt(System.getenv('GARNACHA_VERSION_CODE') ?: '1')
        versionName System.getenv('GARNACHA_VERSION_NAME') ?: '0.1.0-dev'
```

- [ ] **Step 2: Write the changelog**

`CHANGELOG.md`, in Keep-a-Changelog format:
```markdown
# Changelog

All notable changes to Garnacha Boy are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0] — 2026-07-14

The first public build. A working Game Boy Advance emulator: import a ROM, play
it, and your cartridge saves persist.

### Added
- GBA emulation via mGBA 0.10.5, used unmodified, with 48 kHz audio.
- ROM import through Android's document picker into private app storage,
  including transparent import of zip-packaged ROMs.
- On-screen touch controls with distinct portrait and landscape layouts.
- Cartridge save persistence.
- Open-source notices screen carrying mGBA's MPL-2.0 attribution.

### Known limitations
- No ROM library — one ROM at a time. (Coming in 0.2.)
- No settings, control remapping, or save-state UI. (Coming in 0.3.)
- Bluetooth controllers are untested.
- Battery life is unmeasured.
- Tested on one device (Snapdragon 8 Gen 3). Low-end hardware is unverified.

No game ROMs or BIOS files are included. Supply only content you are legally
entitled to use.
```

- [ ] **Step 3: Verify the version wiring**

```sh
GARNACHA_VERSION_NAME=0.1.0 GARNACHA_VERSION_CODE=1 \
  mgba-android/gradlew -p mgba-android :app:assembleBenchmark
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging \
  mgba-android/app/build/outputs/apk/benchmark/app-benchmark.apk | head -1
```
Expected: `versionName='0.1.0'`. Then without the env var, expect
`versionName='0.1.0-dev'` — a local build must be distinguishable from a release.

- [ ] **Step 4: Commit**

```sh
git add CHANGELOG.md mgba-android/app/build.gradle
git commit -m "feat(release): derive the version from the tag; add a changelog

Local builds report 0.1.0-dev so they can never be mistaken for a release.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: CI — tag push builds, signs, and publishes a GitHub Release

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: the signing config (Task 4) and version wiring (Task 5).
- Produces: a GitHub Release with a signed APK attached, on every `v*` tag.

- [ ] **Step 1: Add the repository secrets**

Base64 the keystore and load it plus the passwords into GitHub Secrets:
```sh
base64 -w0 ~/.android-keys/garnacha-release.jks > /tmp/ks.b64
gh secret set GARNACHA_KEYSTORE_BASE64 < /tmp/ks.b64
shred -u /tmp/ks.b64          # do not leave the key lying around
gh secret set GARNACHA_KEYSTORE_PASSWORD   # paste when prompted
gh secret set GARNACHA_KEY_ALIAS           # garnacha
gh secret set GARNACHA_KEY_PASSWORD        # paste when prompted
gh secret list
```
Expected: the four secrets listed. They are write-only from here on.

- [ ] **Step 2: Write the release workflow**

`.github/workflows/release.yml`:
```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          submodules: recursive

      - name: Set up Ninja
        uses: seanmiddleditch/gha-setup-ninja@master

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install native toolchain
        run: sdkmanager "cmake;3.18.1" "ndk;22.1.7171670"

      - name: Restore the signing key
        env:
          KEYSTORE_BASE64: ${{ secrets.GARNACHA_KEYSTORE_BASE64 }}
        run: |
          echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"
          test -s "$RUNNER_TEMP/release.jks"

      - name: Build the signed release APK
        env:
          GARNACHA_KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          GARNACHA_KEYSTORE_PASSWORD: ${{ secrets.GARNACHA_KEYSTORE_PASSWORD }}
          GARNACHA_KEY_ALIAS: ${{ secrets.GARNACHA_KEY_ALIAS }}
          GARNACHA_KEY_PASSWORD: ${{ secrets.GARNACHA_KEY_PASSWORD }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          GARNACHA_VERSION_NAME="$VERSION" \
          GARNACHA_VERSION_CODE="${GITHUB_RUN_NUMBER}" \
          mgba-android/gradlew -p mgba-android \
            lintDebug :app:testDebugUnitTest :app:assembleRelease

      - name: Verify the APK is signed with the expected key
        run: |
          APK=mgba-android/app/build/outputs/apk/release/app-release.apk
          test -f "$APK" || { echo "release APK is missing — was it signed?"; exit 1; }
          "$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs "$APK"
          mv "$APK" "garnacha-boy-${GITHUB_REF_NAME}.apk"

      - name: Shred the key
        if: always()
        run: shred -u "$RUNNER_TEMP/release.jks" || true

      - name: Publish the release
        uses: softprops/action-gh-release@v2
        with:
          files: garnacha-boy-*.apk
          body_path: CHANGELOG.md
          draft: true
          prerelease: true
```
The release is created as a **draft** so a bad build never auto-publishes;
Task 7 reviews and publishes it by hand. `prerelease: true` marks 0.x honestly.

- [ ] **Step 3: Commit and push (the workflow does not run — no tag yet)**

```sh
git add .github/workflows/release.yml
git commit -m "ci: build, sign, and publish a release APK on tag push

The keystore is restored from a secret, used, and shredded; the job fails if the
APK is not signed with it. Releases land as drafts so a bad build cannot
auto-publish.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push origin mvp
```

---

### Task 7: Cut v0.1.0 and prove a stranger can play it

**Files:**
- Modify: `docs/validation/` — add `release-v0.1.0-2026-07-14.md`

**Interfaces:**
- Consumes: everything above.
- Produces: **the M3 exit criterion** — a signed APK on GitHub Releases that
  installs and plays.

- [ ] **Step 1: Tag and push**

```sh
git tag -a v0.1.0 -m "Garnacha Boy 0.1.0 — first public build"
git push origin v0.1.0
gh run watch --exit-status
```
Expected: the Release workflow succeeds. If signing fails, the APK-missing check
in Step 2 of the workflow fails the job loudly rather than publishing an
unsigned build.

- [ ] **Step 2: Verify the artifact as an outsider would**

```sh
gh release view v0.1.0
mkdir -p /tmp/outsider && cd /tmp/outsider
gh release download v0.1.0 --pattern '*.apk'
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs garnacha-boy-v0.1.0.apk
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging garnacha-boy-v0.1.0.apk | head -3
```
Expected: `Verifies`; certificate SHA-256 matches the digest recorded in
`RELEASING.md`; `versionName='0.1.0'`; `application-label:'Garnacha Boy'`.

- [ ] **Step 3: Install the DOWNLOADED artifact on the device and play**

Not the local build — the actual published file, as a user would get it:
```sh
adb uninstall com.trebuchetdynamics.garnacha || true
adb install /tmp/outsider/garnacha-boy-v0.1.0.apk
adb shell monkey -p com.trebuchetdynamics.garnacha -c android.intent.category.LAUNCHER 1
```
Then, on the device: import a ROM (zip or raw), confirm it renders and plays,
open NOTICES and confirm the mGBA MPL-2.0 attribution shows, and confirm the
launcher icon and name are right.

Note the uninstall wipes private storage, so this also proves **first-run
behaviour on a clean install** — which is exactly what a stranger gets, and is
the one path never yet tested.

```sh
adb logcat -d | grep -cE "FATAL|ANR in com.trebuchetdynamics.garnacha"
```
Expected: 0.

- [ ] **Step 4: Publish the draft release**

Only after Step 3 passes:
```sh
gh release edit v0.1.0 --draft=false
gh release view v0.1.0 --json url --jq .url
```

- [ ] **Step 5: Write the receipt**

`docs/validation/release-v0.1.0-2026-07-14.md`: the release URL, the APK's
certificate SHA-256, `aapt2 badging` output, confirmation that a clean-install of
the *downloaded* artifact imported a ROM and played, the notices screenshot, and
the known limitations carried from the changelog. State plainly what is still
untested (Bluetooth controllers, battery, low-end devices).

- [ ] **Step 6: Commit**

```sh
git add docs/validation/release-v0.1.0-2026-07-14.md
git commit -m "docs: record the v0.1.0 release and its clean-install verification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push origin mvp
```

---

## Self-review notes

- **Spec coverage:** M3's five spec bullets map to Tasks 2 (name/icon), 4
  (keystore/signing), 5 (versioning/changelog), 6 (CI release on tag), 3 (in-app
  licences). The exit criterion ("a stranger can download a signed APK and
  play") is Task 7, tested against the *downloaded* artifact on a *clean install*
  rather than a local build — the distinction matters, because every test so far
  has run against an already-configured install.
- **Deliberately out of scope:** Play Console, F-Droid, store assets, privacy
  policy, accessibility (all M6); ROM library (M4); settings (M5). Two open
  release gates from M1 — Bluetooth controllers and battery life — are carried
  into the changelog as known limitations rather than silently dropped.
- **Riskiest step:** Task 4, Step 2. The release keystore is irreplaceable; if it
  is lost, Play will never accept a signed update to this applicationId again.
  The plan makes `.gitignore` hardening precede key generation so there is no
  window in which a key exists and could be committed.
