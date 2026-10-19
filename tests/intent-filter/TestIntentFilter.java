// SPDX-License-Identifier: GPL-3.0-only
import android.content.IntentFilter;
import android.net.Uri;
import android.os.PatternMatcher;
import java.util.*;

public final class TestIntentFilter {
    static int checks;
    static long digest = 1;
    static void eq(Object a, Object b, String why) {
        checks++;
        if (a == null ? b != null : !a.equals(b))
            throw new AssertionError(why + ": expected " + a + ", got " + b);
    }
    static int match(IntentFilter f, String type, String uri) {
        Uri u = uri == null ? null : Uri.parse(uri);
        return f.match("view", type, u == null ? null : u.getScheme(), u, null, "test");
    }
    public static void main(String[] args) throws Exception {
        IntentFilter f = new IntentFilter("view");
        f.addAction("view"); eq(1, f.countActions(), "duplicate action");
        eq(-3, f.match("wrong",null,null,null,null,"test"), "wrong action");
        eq(0x108000, match(f,null,null), "empty data");
        eq(-2, match(f,null,"https://example.test/"), "unrequested data");
        eq(-4, f.match("view",null,null,null,Collections.singleton("cat"),"test"), "category");
        f.addCategory("cat"); f.addCategory("cat");
        eq(1, f.countCategories(), "duplicate category");
        f.setPriority(9); eq(9,f.getPriority(),"priority");
        IntentFilter copy = new IntentFilter(f); copy.addCategory("other");
        eq(1,f.countCategories(),"independent copy");
        eq(9,copy.getPriority(),"copied priority");
        eq(null,f.typesIterator(),"unset type iterator");
        eq(null,f.pathsIterator(),"unset path iterator");
        eq(null,f.schemeSpecificPartsIterator(),"unset SSP iterator");
        for (String bad : new String[]{"*", "text", "/plain", "text/"}) {
            try { f.addDataType(bad); throw new AssertionError("accepted " + bad); }
            catch (IntentFilter.MalformedMimeTypeException expected) { checks++; }
        }
        IntentFilter all = new IntentFilter("view","*/*");
        eq(0x608000,match(all,"image/png","content://example.test/"),"wildcard MIME");
        eq(-1,match(all,null,null),"wildcard still needs a type");
        IntentFilter ssp = new IntentFilter("view");
        ssp.addDataScheme("mailto");
        ssp.addDataSchemeSpecificPart("user@",PatternMatcher.PATTERN_PREFIX);
        eq(1,ssp.countDataSchemeSpecificParts(),"SSP retained");
        eq(0x588000,match(ssp,null,"mailto:user@example.test"),"SSP positive");
        eq(-2,match(ssp,null,"mailto:other@example.test"),"SSP negative");
        ssp.addDataAuthority("example.test",null);
        eq(0x308000,match(ssp,null,"mailto://example.test/path"),"authority fallback after SSP mismatch");
        IntentFilter port = new IntentFilter("view"); port.addDataScheme("https");
        port.addDataAuthority("*.example.test","443"); port.addDataPath("/ok",PatternMatcher.PATTERN_PREFIX);
        eq(0x508000,match(port,null,"https://A.example.test:443/ok/1"),"host port path");
        eq(-2,match(port,null,"https://A.example.test/ok"),"port required");
        eq(-2,match(port,null,"https://A.example.test:443/no"),"path required");
        eq(-2,port.getDataAuthority(0).match(Uri.parse("https://wrong:443/ok")),"authority negative code");
        try { new IntentFilter.AuthorityEntry("example.test",""); throw new AssertionError("empty port"); }
        catch (NumberFormatException expected) { checks++; }
        // Differential matrix: identical dex executes against Android and ATL.
        // Includes misleading paths without authorities, SSP fallback, MIME and
        // scheme case sensitivity, wildcard host suffixes, ports and category direction.
        List<IntentFilter> filters = new ArrayList<>();
        for (int n=0;n<16;n++) {
            IntentFilter v = new IntentFilter("view"); v.addCategory("cat");
            if (n==1) v.addDataType("image/png");
            if (n==2) v.addDataType("image/*");
            if (n==3) v.addDataType("*/*");
            if (n>=4) v.addDataScheme(n==14 ? "mailto" : "https");
            if (n==5 || n==10) v.addDataType("image/*");
            if (n>=6 && n<=12) v.addDataAuthority(n==7 ? "*.example.test" : "example.test", n==8 ? "443" : null);
            if (n==9 || n==10 || n==13) v.addDataPath("/ok",PatternMatcher.PATTERN_PREFIX);
            if (n==11) v.addDataPath("/ok.*",PatternMatcher.PATTERN_SIMPLE_GLOB);
            if (n==12) v.addDataPath("/ok",PatternMatcher.PATTERN_LITERAL);
            if (n>=14) v.addDataSchemeSpecificPart(n==14 ? "user@" : "//example.test/ok", PatternMatcher.PATTERN_PREFIX);
            if (n==15) v.addDataAuthority("fallback.test", null);
            filters.add(v);
        }
        String[] types={null,"image/png","image/jpeg","text/plain","image/*","*/*","IMAGE/png"};
        String[] uris={null,"content://example.test/ok","file:/ok","https://example.test/ok",
            "https://EXAMPLE.test/ok/1","https://example.test:443/no","https://a.example.test:443/ok",
            "https://fallback.test/ok","HTTPS://example.test/ok","mailto:user@example.test",
            "mailto:other@example.test","/ok","https:"};
        Set<String>[] cats = new Set[]{null,Collections.singleton("cat"),Collections.singleton("other")};
        for (IntentFilter v:filters) for(String type:types) for(String uri:uris)
            for(String action:new String[]{null,"view","wrong"}) for(Set<String> c:cats) {
                Uri u=uri==null?null:Uri.parse(uri);
                int result=v.match(action,type,u==null?null:u.getScheme(),u,c,"test");
                digest=digest*31+result;
                if (args.length>0 && args[0].equals("rows")) System.out.println(result);
                checks++;
            }
        System.out.println("FILTER_PASS checks="+checks+" digest="+digest);
        Runtime.getRuntime().halt(0);
    }
}
