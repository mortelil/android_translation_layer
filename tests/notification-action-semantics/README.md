# Notification action semantic metadata

Build in Alpine: `sh tests/notification-action-semantics/build.sh build-mobile OUT`.
Run OUT/probe.apk with a private ATL profile, or sign/install it on Android.
Expected: `NOTIFICATION_ACTION_PASS checks=8` (tag NotificationActionProbe).

The identical APK passes on Android and ATL x86_64. Checks cover default NONE,
fluent setters, stored values, independent snapshots on builder reuse, Android's
lack of runtime integer-range validation, and both builder constructors.

Contract reference: AOSP frameworks/base
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
core/java/android/app/Notification.java, Action/Action.Builder.
This is metadata support only. It does not implement desktop semantic-action
policies, remote inputs, action parceling, or full notification integration.
