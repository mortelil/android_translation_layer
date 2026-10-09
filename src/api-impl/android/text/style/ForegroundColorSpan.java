package android.text.style;

public class ForegroundColorSpan extends CharacterStyle {
	private final int color;
	public ForegroundColorSpan(int color) { this.color = color; }
	public int getForegroundColor() { return color; }
	public void updateDrawState(android.text.TextPaint paint) { paint.setColor(color); }
}
