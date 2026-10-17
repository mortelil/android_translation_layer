package android.animation;

public class Keyframe {
	private float fraction;
	private Object value;

	public static Keyframe ofFloat(float fraction) {
		Keyframe kf = new Keyframe();
		kf.setFraction(fraction);
		return kf;
	}

	public static Keyframe ofFloat(float fraction, float value) {
		Keyframe kf = ofFloat(fraction);
		kf.setValue(value);
		return kf;
	}

	// AOSP Keyframe.ofInt(float) / ofInt(float, int): AOSP returns IntKeyframe
	// instances carrying an Integer value (android/animation/Keyframe.java:82-105).
	// ATL models every keyframe with the single Object value slot above, so the
	// boxed Integer keeps getValue()/hasValue() equivalent for callers.
	public static Keyframe ofInt(float fraction) {
		Keyframe kf = new Keyframe();
		kf.setFraction(fraction);
		return kf;
	}

	public static Keyframe ofInt(float fraction, int value) {
		Keyframe kf = ofInt(fraction);
		kf.setValue(Integer.valueOf(value));
		return kf;
	}

	public float getFraction() {
		return fraction;
	}

	public void setFraction(float fraction) {
		this.fraction = fraction;
	}

	public boolean hasValue() {
		return value != null;
	}

	public Object getValue() {
		return value;
	}

	public void setValue(Object value) {
		this.value = value;
	}
}
