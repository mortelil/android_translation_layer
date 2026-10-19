# Search history provider contracts

Build in atl_dev: `sh tests/search-history/build.sh build-mobile OUTPUT`.
Sign the APK and launch org.atl.searchhistory/.Probe twice, in the same private
profile. The same APK is suitable for an Android emulator and ATL.
First launch: SEARCH_HISTORY_PASS write checks=9.
Second launch: SEARCH_HISTORY_PASS restart checks=2.

Checks: invalid mode, single-line validation, SQLite insert/truncation, asynchronous
saveRecentQuery, quoted search filtering, two-line projections, process-restart
persistence and clearHistory. No real queries or accounts are used.

SearchRecentSuggestions, SearchRecentSuggestionsProvider and SearchManager
constants are from AOSP frameworks/base android-14.0.0_r1, commit
299fe6f5d6fc6f1af7c3411dcf4e5efdf7217368, with Apache-2.0 headers preserved.
Removed unsupported hidden-API annotations. Added openFile rejection because
ATL's ContentProvider declares that method abstract (Android's default rejects
files). No general SearchManager UI or searchable-activity integration is added.
