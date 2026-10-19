# IntentFilter matching

Inside atl_dev, set ATL_PREFIX, ATL_CORE_JAR and ATL_BUILD_DIR to the candidate,
then run:

    sh tests/intent-filter/run.sh /tmp/filter-probe

The runner records the candidate runtime hashes. The same dex can run against
Android's framework:

    adb push /tmp/filter-probe/test.jar /data/local/tmp/atl-filter-test.jar
    adb shell 'CLASSPATH=/data/local/tmp/atl-filter-test.jar app_process /system/bin TestIntentFilter'

Require successful exits and identical FILTER_PASS lines on both platforms.
The test has 27 explicit contract checks plus 13,104 differential combinations:
16 filters, 7 MIME values, 13 URI values, 3 actions and 3 category sets.
The rolling digest includes each full match result, not only pass/fail.
For debugging a mismatch, run TestIntentFilter rows to print each result in
the same order. Runtime.halt runs only after successful assertions, avoiding
the existing standalone ART teardown issue.

Covered: negative result codes and precedence; action/category direction;
MIME wildcard filters and intents; content/file defaults; scheme case;
case-insensitive and suffix-wildcard hosts; ports; literal, prefix and simple
glob paths; scheme-specific part matching and authority fallback; copy
independence; priority; empty iterators and malformed MIME exception identity.

Source: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/content/IntentFilter.java (match, matchData, matchCategories,
findMimeType, AuthorityEntry and declaration helpers). Apache-2.0 header retained.
Based on the IntentFilter portion of OMP 9209229d; no provider changes imported.
ATL uses eager lists instead of nullable collections and its existing AOSP
PatternMatcher for literal/prefix/simple-glob patterns.

Scope: this patch does not update manifest data parsing or component resolvers.
Advanced glob/suffix patterns, MIME groups, URI-relative filter groups,
IntentFilter parcel/XML serialization and domain verification are outside this
milestone. It is not full IntentFilter API coverage.
