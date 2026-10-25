import java.io.File;
import java.nio.file.Files;
import java.util.*;

import lk.manus.iptv.data.FavGuard;

/** T33: snapshots (newest 10, duplicates skipped, names kept) and the 200-event history. Run: java -cp build/test FavGuardTest */
public class FavGuardTest {
    static int fails = 0, checks = 0;

    static void eq(String what, Object e, Object a) {
        checks++;
        if (!Objects.equals(e, a)) { fails++; System.out.println("FAIL " + what + ": expected [" + e + "] got [" + a + "]"); }
    }

    public static void main(String[] args) throws Exception {
        File d = Files.createTempDirectory("favguard").toFile();
        FavGuard.init(d);
        Map<String, String> n = new HashMap<>();
        n.put("u1", "One"); n.put("u2", "Two\tTab"); n.put("u3", "Three"); n.put("u4", "Four");
        FavGuard.rememberNames(n);
        eq("names", true, FavGuard.names().containsKey("u2"));
        FavGuard.snapshot(new ArrayList<>(Arrays.asList("u1", "u2")), FavGuard.names());
        FavGuard.snapshot(new ArrayList<>(Arrays.asList("u1", "u2")), FavGuard.names()); // identical: skipped
        eq("dedupe", 1, FavGuard.snaps().size());
        eq("urls", Arrays.asList("u1", "u2"), FavGuard.snaps().get(0).urls);
        eq("names kept, tab cleaned", Arrays.asList("One", "Two Tab"), FavGuard.snaps().get(0).names);
        FavGuard.snapshot(new ArrayList<String>(), FavGuard.names());
        eq("empty list is a snapshot", 0, FavGuard.snaps().get(0).urls.size());
        for (int i = 0; i < 15; i++) FavGuard.snapshot(new ArrayList<>(Collections.nCopies(1 + i % 3, "u3").subList(0, 1)), FavGuard.names());
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 12; i++) { l.add("x" + i); FavGuard.snapshot(l, FavGuard.names()); l = new ArrayList<>(l); }
        eq("max 10", 10, FavGuard.snaps().size());
        eq("newest first", 12, FavGuard.snaps().get(0).urls.size());
        for (int i = 0; i < 230; i++) FavGuard.log(i % 2 == 0, "Chan " + i);
        List<FavGuard.Event> h = FavGuard.history();
        eq("history 200", 200, h.size());
        eq("history newest first", "Chan 229", h.get(0).name);
        eq("history flag", false, h.get(0).added);
        FavGuard.init(null);
        FavGuard.log(true, "no folder"); // no-op, no crash
        eq("no-op", 200, h.size());
        System.out.println(checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
