# Visible AlertDialog message

Build inside Alpine with `sh tests/alert-message/build.sh BUILD_DIR OUTPUT_DIR`
and sign the resulting APK with a local test key. Launch the probe, verify that
`BEFORE` becomes `AFTER: visible message updated`, and click **Verify**.
Require `ALERT_MESSAGE PASS` and files/results.json. The callback checks that
android.R.id.message is the same updated TextView and has a screen allocation.
This tests a real widget update/click, not merely a non-null lookup.

Repair of the Sketchbook OMP lane f81a24b: that candidate supplied a detached
message TextView while a separate GtkLabel displayed the dialog text. Here
the message stays inside the Java Window decor and nativeConstruct mounts that decor; its text, visibility and
movement-method state are shared with the object returned to the app.
Existing item-list presentation is retained. A null button listener dismisses
normally, as in Android.

Scope: AlertDialog/Window message lookup and updates. Native buttons and item
lists still are not in the Java decor hierarchy; theme resolution, rich-link
interaction and arbitrary custom content need separate work. This
does not claim full AlertController or Android dialog-layout compatibility.
