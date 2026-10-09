// SPDX-License-Identifier: GPL-3.0-only
package android.graphics.fonts;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FontVariationAxis {
	private final float value;
	private final String tag;
	private static final Pattern AXIS = Pattern.compile("\\s*(['\"])([ -~]{4})\\1\\s*([-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)\\s*");
	public FontVariationAxis(String tag, float value) {
		if (tag == null || !tag.matches("[ -~]{4}"))
			throw new IllegalArgumentException("Font axis tag must contain four printable ASCII characters");
		if (!Float.isFinite(value)) throw new IllegalArgumentException("Font axis value must be finite");
		this.tag = tag; this.value = value;
	}
	public float getStyleValue() { return value; }
	public String getTag() { return tag; }
	public int getOpenTypeTagValue() { return (tag.charAt(0) << 24) | (tag.charAt(1) << 16) | (tag.charAt(2) << 8) | tag.charAt(3); }
	public static FontVariationAxis[] fromFontVariationSettings(String settings) {
		if (settings == null || settings.trim().isEmpty()) return null;
		ArrayList<FontVariationAxis> axes = new ArrayList<>();
		Matcher matcher = AXIS.matcher(settings);
		int start = 0;
		while (start < settings.length()) {
			matcher.region(start, settings.length());
			if (!matcher.lookingAt()) throw new IllegalArgumentException("Invalid font variation settings");
			axes.add(new FontVariationAxis(matcher.group(2), Float.parseFloat(matcher.group(3))));
			start = matcher.end();
			if (start == settings.length()) break;
			if (settings.charAt(start++) != ',' || start == settings.length())
				throw new IllegalArgumentException("Invalid font variation separator");
		}
		return axes.toArray(new FontVariationAxis[0]);
	}
	public static String toFontVariationSettings(FontVariationAxis[] axes) {
		if (axes == null || axes.length == 0) return "";
		StringBuilder settings = new StringBuilder();
		for (FontVariationAxis axis : axes) {
			if (settings.length() > 0) settings.append(',');
			settings.append(axis.toString());
		}
		return settings.toString();
	}
	@Override public String toString() { return "'" + tag + "' " + Float.toString(value); }
}
