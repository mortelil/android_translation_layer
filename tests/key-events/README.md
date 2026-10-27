# Virtual keyboard synthesis

`KeyCharacterMap.getEvents` supports the base/shift US keyboard subset used for
letters, digits, punctuation and whitespace. Unknown characters return null so
IME callers can commit text directly; null input is an error. It does not claim
host keyboard-layout discovery, dead-key synthesis or complete Android KCM support.

Reference (mapping semantics, not copied implementation): AOSP
`platform/frameworks/base`, tag `android-16.0.0_r1`,
`data/keyboards/Generic.kcm`. Existing AOSP file attribution remains intact.

Inside Toolbox with the candidate mobile environment:

```
sh tests/key-events/build.sh "$ATL_BUILD_DIR" /tmp/key-apk
sh tests/key-events/run.sh /tmp/key-apk/probe.apk /tmp/key-run
```

Checks empty/null/unmappable inputs, balanced key transitions, device/source,
Unicode reconstruction, shift punctuation and shift/caps interaction.

Also checks KeyEvent parcel roundtrips, Editable.getSpans declaring-interface
identity, and TextKeyListener insertion/deletion/clearing with modifier state.
MetaKeyKeyListener is copied unchanged from AOSP frameworks/base commit
`1aed5ea2dcf7cb0bf35635f378b2326461ba0df0` (android-4.4_r1),
`core/java/android/text/method/MetaKeyKeyListener.java`, Apache-2.0; its header is
preserved. TextKeyListener delegates editing to ATL's existing full-keyboard
QwertyKeyListener. Automatic capitalization, correction and multi-tap phone
keyboards are outside this patch.
