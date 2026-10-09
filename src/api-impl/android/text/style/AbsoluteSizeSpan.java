package android.text.style;

public class AbsoluteSizeSpan extends MetricAffectingSpan {
	private final int size;
	private final boolean dip;
	public AbsoluteSizeSpan() { this(0); }
	public AbsoluteSizeSpan(int size) { this(size, false); }
	public AbsoluteSizeSpan(int size, boolean dip) { this.size = size; this.dip = dip; }
	public int getSize() { return size; }
	public boolean getDip() { return dip; }
	public void updateDrawState(android.text.TextPaint paint) { updateMeasureState(paint); }
	@Override public void updateMeasureState(android.text.TextPaint paint) {
		paint.setTextSize(size * (dip ? paint.density : 1));
	}
}
