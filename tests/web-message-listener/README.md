# AndroidX string WebMessage listener probe

Build in Toolbox using `sh build.sh BUILD OUT`. Run `OUT/web-message.apk`
with `ATL_UGLY_ENABLE_WEBVIEW=1` and a private app-data directory.
Require `WEBMESSAGE PASS`, not merely a WebKit window.

The probe covers allowed and denied origins, subframe identity, asynchronous
string replies, Unicode, removal, and replies retained across navigation.
The AndroidX boundary adapter advertises only `WEB_MESSAGE_LISTENER`.
ArrayBuffer messages, transferred ports, and other AndroidX WebView features
are outside this implementation.

Origin and frame identity are read in an isolated WebKit script world. Page
JavaScript passes message text through a DOM event; it cannot choose the
native callback's origin, main-frame flag, or reply document identifier.
Origin rules are validated before installation. Removing a listener or leaving
a document invalidates its native reply channel.
