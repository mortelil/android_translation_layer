# Dialog creation and theme contracts

Build in Toolbox: `ANDROID_JAR=/path/to/sdk/android.jar sh tests/dialog-contract/build.sh BUILD OUT`.
Run through the isolated probe runner and require `DIALOG_CONTRACT PASS` and a PASS results.json.

Checks: explicit floating/nonfloating theme isolation, themed context ownership,
nonfloating MATCH_PARENT defaults, create() without show, exactly one onCreate
across repeated create/show, synchronous start/stop lifecycle, posted onShow,
reshow without recreation, and real host allocation larger than a collapsed
progress indicator. Does not claim full PhoneWindow theme/decor compatibility.
