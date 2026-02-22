# iOS Debugging

This guide covers debugging Forge on a physical iOS device.  For simulator
debugging see the Xcode documentation.  For building and deploying first, see
[iOS Builds](iOS-Builds.md).

## Prerequisites

- Forge built and installed on a physical device (see [iOS Builds](iOS-Builds.md)).
- Xcode installed on macOS.
- **Developer Mode** enabled on the device:
  **Settings → Privacy & Security → Developer Mode** (iOS 16+).

## 1. Reading on-device logs

### Via `idevicesyslog` (libimobiledevice)

`idevicesyslog` streams the device system log to stdout.  Install via Homebrew:

```bash
brew install libimobiledevice
```

Connect the device and filter for Forge output:

```bash
idevicesyslog | grep -E 'forge|Forge|GDX|robovm'
```

Leave this running in a separate terminal before launching the app.

### Via Console.app

1. Open `/Applications/Utilities/Console.app`.
2. Select your device in the left sidebar.
3. Click **Start streaming**.
4. In the search bar, type `forge` to filter messages.

### Via Xcode Organizer (crash reports)

After a crash, Xcode automatically syncs crash reports from trusted devices:

1. In Xcode, open **Window → Organizer**.
2. Click **Crashes** in the toolbar.
3. Select your device and find the most recent `Forge` crash.
4. The symbolicated stack trace shows the Java method names translated by MobiVM.

## 2. Attaching the Xcode debugger

MobiVM-compiled apps expose a standard LLDB server that Xcode can attach to.

1. Open **any** Xcode project (or create a blank one — you only need the debugger).
2. In Xcode, choose **Debug → Attach to Process by PID or Name…**.
3. Enter `forge.ios.Main` (the executable name from `robovm.properties`).
4. Launch Forge on the device; Xcode attaches automatically.
5. Use the **Debug Navigator** and console for inspection.

> **Tip:** Breakpoints set on Objective-C/Swift symbols (e.g. UIKit entry
> points) work; Java-level breakpoints require the robovm JDWP bridge (see
> section 4 below).

## 3. Verbose Maven build output

To see detailed MobiVM AOT compilation logs during the build add `-X` or set
the `robovm.debug` system property:

```bash
mvn -U -B clean -P ios-device install -Drobovm.debug=true 2>&1 | tee /tmp/ios-build.log
```

Search the log for `ERROR` or `WARN` lines to find which class triggered a
compilation failure.

## 4. Remote Java debugging with JDWP (advanced)

MobiVM supports JDWP-over-TCP so you can attach IntelliJ IDEA (or any Java
debugger) to a running app on a physical device.

### Step 1 — Enable debug mode in `robovm.xml`

Add the `<debug>` stanza inside the `<config>` root of
`forge-gui-ios/robovm.xml`:

```xml
<debug>
  <jdwpPort>5005</jdwpPort>
  <jdwpSuspend>false</jdwpSuspend>
</debug>
```

`jdwpSuspend=false` lets the app start immediately; set it to `true` if you
need to break before `main()`.

### Step 2 — Rebuild and deploy

```bash
mvn -U -B clean -P ios-device install
./forge-gui-ios/scripts/ios-device-build.sh --deploy
```

### Step 3 — Forward the JDWP port over USB

Use `iproxy` (part of `libimobiledevice`) to forward the device TCP port to
localhost:

```bash
iproxy 5005 5005 &
```

### Step 4 — Attach IntelliJ IDEA

1. In IntelliJ, open **Run → Edit Configurations…** and add a **Remote JVM
   Debug** configuration.
2. Set **Host** to `localhost` and **Port** to `5005`.
3. Launch the configuration — IntelliJ connects to the running app.
4. Set breakpoints in the `forge-gui-mobile` or other Java source modules as
   normal.

## 5. Common failures and fixes

| Symptom | Likely cause | Fix |
|---|---|---|
| App crashes immediately with no log | Developer Mode not enabled | **Settings → Privacy & Security → Developer Mode** |
| `dyld: Library not loaded` | Missing native framework | Check `<frameworks>` in `robovm.xml`; ensure Xcode SDK is up to date |
| Blank/black screen on launch | libGDX OpenGL init failure | Check device supports OpenGL ES 3.0; consult `idevicesyslog` for GL errors |
| `SIGABRT` in `robovm-rt` | Java record `equals`/`hashCode`/`toString` called | Expected — see *Known Limitation: Java Records* in [iOS Builds](iOS-Builds.md) |
| `Could not find class …` | Class stripped by MobiVM linker | Add a `<forceLinkClasses>` entry for the class pattern in `robovm.xml` |
| App installs but won't open | Provisioning profile / UDID mismatch | Re-create the profile with the correct device UDID |
| JDWP connection refused | Port not forwarded or wrong port | Confirm `iproxy` is running and `robovm.xml` port matches |

## 6. Useful tools summary

| Tool | Install | Purpose |
|---|---|---|
| `idevicesyslog` | `brew install libimobiledevice` | Stream on-device system log |
| `ideviceinfo` | `brew install libimobiledevice` | Query device properties / UDID |
| `iproxy` | `brew install libimobiledevice` | TCP port forwarding over USB |
| `ios-deploy` | `brew install ios-deploy` | Install and launch IPA from CLI |
| Console.app | bundled with macOS | GUI log viewer |
| Xcode Organizer | bundled with Xcode | Symbolicated crash reports |
