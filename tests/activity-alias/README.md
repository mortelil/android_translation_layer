# Activity alias dispatch

Compile the two Java files against the Android SDK API (Java 8), dex them, and
package with this manifest. Run in a private app-data directory.
Require `ACTIVITY_ALIAS PASS` and exit 0.

An implicit intent resolves an alias to its real activity class. An explicit
alias intent with CLEAR_TOP then reuses that class and delivers onNewIntent
without trying to load the nonexistent alias class. The original alias
component remains present in the delivered intent.
