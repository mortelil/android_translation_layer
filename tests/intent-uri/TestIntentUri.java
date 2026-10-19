// SPDX-License-Identifier: GPL-3.0-only
import android.content.Intent;
import android.content.ComponentName;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;

public final class TestIntentUri {
    static int checks;
    static void eq(Object want, Object got, String label) {
        checks++;
        if (want == null ? got != null : !want.equals(got))
            throw new AssertionError(label + ": expected " + want + ", got " + got);
    }
    static Intent parse(String s) throws Exception { return Intent.parseUri(s, 0); }
    static void malformed(String s) throws Exception {
        try { parse(s); throw new AssertionError("accepted malformed URI: " + s); }
        catch (java.net.URISyntaxException expected) { checks++; }
    }
    static Intent parcel(Intent i) {
        Parcel p = Parcel.obtain();
        try {
            i.writeToParcel(p, 0); p.setDataPosition(0);
            Intent result = Intent.CREATOR.createFromParcel(p);
            eq(p.dataSize(), p.dataPosition(), "parcel consumed exactly");
            return result;
        } finally { p.recycle(); }
    }
    static void fields(Intent i) {
        eq("test.action", i.getAction(), "action");
        eq("https://example.test/a", i.getDataString(), "data");
        eq("text/plain", i.getType(), "type retained with data");
        eq("sample.pkg", i.getPackage(), "package");
        eq(new ComponentName("sample.pkg", "sample.pkg.Main"), i.getComponent(), "component");
        eq(true, i.hasCategory("cat.one"), "category one");
        eq(true, i.hasCategory("cat.two"), "category two");
        eq("instance", i.getIdentifier(), "identifier");
        eq(7, i.getExtendedFlags(), "extended flags");
        eq(0x90000000, i.getFlags(), "unsigned flags and grants removed");
        eq(new Rect(1,2,30,40), i.getSourceBounds(), "bounds");
        eq("a+b;é", i.getStringExtra("encoded key"), "encoded extra and literal plus");
    }
    public static void main(String[] args) throws Exception {
        Intent web = Intent.parseUri("https://example.test/#Intent;action=bad;end", Intent.URI_INTENT_SCHEME);
        eq(Intent.ACTION_VIEW, web.getAction(), "web action");
        eq("https://example.test/#Intent;action=bad;end", web.getDataString(), "web fragment not intent");
        eq("https://example.test/#section", parse("https://example.test/#section").getDataString(), "plain fragment");
        eq(Intent.ACTION_VIEW, parse("https://example.test/").getAction(), "simple action");
        Intent rich = parse("intent://example.test/a#Intent;scheme=https;action=test.action;"
            + "category=cat.one;category=cat.two;type=text/plain;package=sample.pkg;"
            + "component=sample.pkg/.Main;identifier=instance;extendedLaunchFlags=7;launchFlags=0x900000c3;"
            + "sourceBounds=1%202%2030%2040;S.encoded%20key=a+b%3B%C3%A9;end");
        fields(rich); fields(new Intent(rich)); fields(parcel(rich));
        Intent copy = new Intent(rich);
        copy.putExtra("encoded key", "changed"); copy.getSourceBounds().left = 99;
        eq("a+b;é", rich.getStringExtra("encoded key"), "copy bundle isolated");
        eq(1, rich.getSourceBounds().left, "copy bounds isolated");
        Rect external = new Rect(4,5,6,7); copy.setSourceBounds(external); external.left = 90;
        eq(4, copy.getSourceBounds().left, "set bounds copies");
        copy.setSourceBounds(null); eq(null, copy.getSourceBounds(), "clear bounds");
        Intent unsafe = Intent.parseUri("intent:#Intent;launchFlags=0xc3;end", Intent.URI_ALLOW_UNSAFE);
        eq(0xc3, unsafe.getFlags(), "explicit unsafe opt-in");
        Bundle b = parse("intent:#Intent;B.bool=true;b.byte=-3;c.char=Z;d.double=1.25;f.float=2.5;"
            + "i.int=-9;l.long=9223372036854775807;s.short=300;end").getExtras();
        eq(true, b.getBoolean("bool"), "boolean extra"); eq((byte)-3, b.getByte("byte"), "byte extra");
        eq('Z', b.getChar("char"), "char extra"); eq(1.25, b.getDouble("double"), "double extra");
        eq(2.5f, b.getFloat("float"), "float extra"); eq(-9, b.getInt("int"), "integer extra");
        eq(Long.MAX_VALUE, b.getLong("long"), "long extra"); eq((short)300, b.getShort("short"), "short extra");
        Intent sel = parse("intent:#Intent;action=base;SEL;action=selected;scheme=https;S.k=v;end");
        eq("base", sel.getAction(), "base action"); eq("selected", sel.getSelector().getAction(), "selector action");
        eq("https:", sel.getSelector().getDataString(), "selector scheme");
        eq("v", parcel(sel).getSelector().getStringExtra("k"), "selector parcel");
        Intent selCopy = new Intent(sel); selCopy.getSelector().setAction("changed");
        eq("selected", sel.getSelector().getAction(), "selector copy isolated");
        eq(null, parse("intent:#Intent;package=p;SEL;action=ignored;end").getSelector(), "package wins over selector");
        try { sel.setSelector(sel); throw new AssertionError("self selector"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { sel.setPackage("p"); throw new AssertionError("package with selector"); }
        catch (IllegalArgumentException expected) { checks++; }
        Intent pkg = parse("android-app://sample.pkg");
        eq("sample.pkg", pkg.getPackage(), "android-app package"); eq(Intent.ACTION_MAIN, pkg.getAction(), "package main");
        eq(null, pkg.getData(), "package-only data");
        Intent app = parse("android-app://sample.pkg/https/example.test/path?q=x");
        eq("https://example.test/path?q=x", app.getDataString(), "android-app URL");
        eq(Intent.ACTION_VIEW, app.getAction(), "android-app view");
        Intent old = parse("https://example.test/#action(old.action)categories(one!two)type(text/plain)launchFlags(0xc3)extras(Sk=hello%20world!in=7)");
        eq("old.action", old.getAction(), "legacy action"); eq(true, old.hasCategory("two"), "legacy category");
        eq("text/plain", old.getType(), "legacy mime"); eq(0, old.getFlags(), "legacy strips grants");
        eq("hello world", old.getStringExtra("k"), "legacy string"); eq(7, old.getIntExtra("n", 0), "legacy int");
        malformed("intent:#Intent;action=x;");
        malformed("intent:#Intent;X.key=value;end");
        malformed("intent:#Intent;c.key=;end");
        malformed("android-app:");
        malformed("https://e/#extras(in=abc)");
        // AOSP's modern parser deliberately exposes NumberFormatException.
        try { parse("intent:#Intent;i.key=abc;end"); throw new AssertionError("invalid numeric extra"); }
        catch (NumberFormatException expected) { checks++; }
        System.out.println("INTENT_URI_PASS " + checks + " assertions");
        Runtime.getRuntime().halt(0);
    }
}
