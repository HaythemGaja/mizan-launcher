# Mizan Launcher 3.22 — verification status

Revision date: November 19, 2026.

## Execution limits

No APK is included. The Gradle wrapper could not download Gradle 8.7 because
network access to services.gradle.org was unavailable. No Kotlin compiler or
Android SDK was available locally. Android compilation, lint, the 28 new JUnit
tests, the companion protocol check and the historical simulation have NOT run.
No emulator or physical-TV visual/navigation testing was performed.

Attempts:

- `sh ./gradlew --version`: blocked, `java.net.SocketException: Network is unreachable`.
- `sh sim/run_sim.sh`: exits 127 with a clear Kotlin-compiler-unavailable message.
- Shell syntax validation passed for `sim/run_sim.sh` and both POSIX Gradle wrappers.
- All 12 XML documents parsed successfully.
- All 17 declared activity/service/receiver classes resolve to existing source files.
- Patch whitespace check passed with CRLF-aware handling for Windows wrappers.
- The packaged ZIP passed an archive integrity check (performed during packaging).
- Windows wrappers were added for both projects; execution on Windows is unverified.

## Added regression coverage (pending execution)

17 launcher JUnit tests cover search ranking/stability, accents and separate
words, Turkish locale casing, JSON number/escape/control/depth handling, exact
HTTP routes, UTF-8 byte framing, truncation, duplicate lengths, transfer encoding,
body/header limits, image sample calculations, pairing-code character validation
and app-lock toggle decisions. Five companion tests cover malformed replies,
trailing data, oversized/deep responses and escaped characters.

The old simulation's source checks were updated for version 3.22/code 48,
shared bounded image decoding and size-aware poster delivery. Its runner now
propagates JVM failures through its logging step.

## Checks required before installing on a daily-use TV

1. Run the Gradle build, unit tests and lint for both projects (commands in README).
2. Navigate Clean, TV and Watch homes using only the D-pad at 720p, 1080p and 4K.
3. Open Apps, Search and My Library; return Home after launching or uninstalling apps.
4. Test keyboard/voice search, locked apps with remote enforcement on/off, and Guest settings.
5. Save custom artwork, restart the launcher, and confirm it remains available offline.
6. Load posters, navigate away during downloads, and test a large/panoramic image.
7. Pair the phone remote, change themes, test wrong-code lockout, and disable it.
8. Verify hero text contrast, the live clock, and focus outlines on bright wallpapers.
9. Confirm library writes and an update signed with the original key preserve data.

This is an improved source revision, not a claim that every issue has been fixed.

## Settings changes in 3.22

Six more JUnit tests (not executed) cover feature aliases, empty/no-result
searches, locale/accent handling, complete backup envelopes, invalid field types,
invalid profile IDs, unknown versions, UTF-8 input and oversized provider input.
23 launcher tests and 5 phone companion tests are present in total.

Static checks passed: all 12 navigation category IDs exactly match the search
index, arrow-key switch mutation is absent, import envelope validation precedes
preference mutation, all 12 XML files parse, and shell wrappers pass syntax checks.
These are source checks, not proof of Android compilation or runtime behavior.

The full Gradle test/lint/assemble command was attempted for 3.22 and failed
before compilation with `java.net.SocketException: Network is unreachable`
while downloading Gradle 8.7. No APK is included.

Additional device checks still needed:

- Search wallpaper, backup, PIN and pairing; open each result using only a remote.
- Hold Up/Down through the category list and confirm focus stays in that list.
- Enter details, change a switch with OK, and press Left to return without
  changing it again. Repeat with TalkBack or the TV's accessibility reader.
- Recreate Settings and verify page/control restoration and lock enforcement.
- Use a narrow window and a 720p TV; confirm both last rows remain reachable.
- Import a valid version 1 backup, a wrong-type backup and a file above 4 MiB.
  Confirm rejected documents do not change settings. Check failed export paths.
- Test changing themes, Guest/profile settings, IPTV refresh completion and
  returning from Android system settings while retaining usable focus.

Backup limitations: this remains a settings-only backup. It does not include
IPTV catalogues, downloaded media, the arrow pattern or all companion data.
Validation precedes writes, but filesystem failure during a valid restore is
not a transactional rollback. The failure message therefore does not promise
that no data changed. Keep a separate known-good backup before restoring.
