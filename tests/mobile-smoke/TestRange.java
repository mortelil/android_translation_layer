// SPDX-License-Identifier: GPL-3.0-only
import android.media.MediaCodecInfo;
import android.util.Range;
public class TestRange {
	static void check(boolean value) {
		if (!value)
			throw new AssertionError();
	}
	static void invalid(Runnable action) {
		try {
			action.run();
			throw new AssertionError("expected invalid interval");
		} catch (IllegalArgumentException expected) {
		}
	}
	public static void main(String[] args) {
		Range<Integer> r = Range.create(2, 8);
		check(r.contains(2) && r.contains(8) && !r.contains(9));
		check(r.contains(new Range<>(3, 7)) && !r.contains(new Range<>(1, 3)));
		check(r.clamp(-1) == 2 && r.clamp(20) == 8 && r.clamp(4) == 4);
		check(r.intersect(5, 12).equals(new Range<>(5, 8)));
		check(r.intersect(8, 12).equals(new Range<>(8, 8)));
		invalid(new Runnable() { public void run() { r.intersect(9, 12); } });
		invalid(new Runnable() { public void run() { new Range<Integer>(5, 4); } });
		invalid(new Runnable() { public void run() { r.extend(10, 9); } });
		check(r.extend(0).equals(new Range<>(0, 8)));
		check(r.extend(4, 15).equals(new Range<>(2, 15)));
		check(r.equals(new Range<>(2, 8)) && r.hashCode() == new Range<>(2, 8).hashCode());
		check(new Range<>(0.0, 1.0 / 30).contains(1.0 / 60));
		MediaCodecInfo.VideoCapabilities caps = new MediaCodecInfo.VideoCapabilities();
		check(caps.getAchievableFrameRatesFor(1920, 1080) == null);
		invalid(new Runnable() { public void run() { caps.getAchievableFrameRatesFor(0,1080); } });
		System.out.println("PASS: Range boundaries, intersections, extensions, ExoPlayer interval; unavailable codec measurements");
	}
}
