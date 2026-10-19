# GTK Enter event regression

GTK activation used to construct KeyEvent with action 3 (IME_ACTION_SEARCH).
That is an editor action, not a valid key action. Applications that check for
ACTION_DOWN ignored it. This test requires a real Enter press, not a direct
listener call: keyCode 66, key action 0, editor action IME_NULL (0).

Build with `sh tests/editor-enter/build.sh build-mobile OUT`, then sign the APK.
Set ATL_PREFIX, ATL_CORE_JAR and ATL_BUILD_DIR for the candidate runtime and run
`dbus-run-session -- xvfb-run -a sh tests/editor-enter/run.sh APK OUT/run`
inside Alpine. Uses an isolated test profile. Requires xdotool.

Android comparison: install the same signed APK, start
`org.atl.editorenter/.Probe`, then `adb shell input keyevent 66`.
The EditorEnter log tag must report EDITOR_ENTER_PASS. Both Android emulator
API 37 and ATL passed on 2026-10-19.

Reference: AOSP frameworks/base android-14.0.0_r1, TextView.java,
doKeyDown and getActionIdForEnterEvent (commit
299fe6f5d6fc6f1af7c3411dcf4e5efdf7217368).
This narrow fix does not implement IME actions, key-up dispatch, listener
replacement/lifetime management, or all Android text editing behavior.
