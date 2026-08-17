# Build the bridge APK on GitHub

No Android Studio, no terminal. GitHub compiles it for you in about 3 minutes.

You already have a GitHub account, since that is what CloudPebble signed you in with.

---

## Step 1: Make a repo

1. Go to [github.com/new](https://github.com/new)
2. Name it `audiocues-pebble-bridge`
3. Set it **Private** (nothing here needs to be public)
4. Leave every checkbox unticked. Do not add a README
5. Click **Create repository**

---

## Step 2: Upload the code

On the empty repo page, click **uploading an existing file**.

Then drag these three items from the `github-repo` folder into the browser:

- the `app` folder
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`

Wait for all the file names to finish listing, then click **Commit changes**.

**Do not worry about the `.github` folder.** macOS hides it and it usually will not drag. Step 3 recreates it inside GitHub.

---

## Step 3: Add the build recipe

1. Click the **Actions** tab
2. Click **set up a workflow yourself**
3. Select everything in the editor and delete it
4. Paste the whole block below
5. Click **Commit changes**

```yaml
name: Build APK

on:
  push:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Check out the code
        uses: actions/checkout@v5

      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools platforms;android-36 build-tools;36.0.0'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.14.3'

      - name: Build debug APK
        run: gradle assembleDebug --no-daemon --stacktrace

      - name: Upload the APK
        uses: actions/upload-artifact@v4
        with:
          name: audiocues-bridge-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
```

YAML is picky about indentation, so paste, do not retype.

---

## Step 4: Collect the APK

The build starts on its own the moment you commit.

1. **Actions** tab, click the run at the top
2. Wait for the green check, roughly 3 minutes on the first run
3. Scroll to **Artifacts** at the bottom
4. Download **audiocues-bridge-apk**, which arrives as a zip containing `app-debug.apk`

**If it goes red instead:** click the failed step to expand the log, copy the last 30 or so lines, and send them to me. First-run failures are almost always a version mismatch, and they are quick to patch.

---

## Step 5: Put it on the phone

1. Email or AirDrop `app-debug.apk` to your Nothing Phone, or upload it to Drive and download it there
2. Tap it. Android will ask you to allow installs from that app, which is expected for anything outside the Play Store
3. Allow, then install

Now open **Audio Cues Pebble Bridge** on the phone and follow Part 3 in the main README: turn on accessibility, open Audio Cues in Run mode, then run the three tests.

---

## Notes

- The build produces a **debug** APK, whose package is `com.clowncare.audiocuesbridge.debug`. That is deliberate: it is already listed in the watchapp's companion app list, so the Pebble app will route messages to it.
- Debug APKs are signed with a throwaway key. Fine for your own phone, not for the Play Store.
- Any future push to the repo rebuilds automatically. To rebuild without changing anything, use **Actions > Build APK > Run workflow**.
