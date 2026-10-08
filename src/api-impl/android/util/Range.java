// SPDX-License-Identifier: GPL-3.0-only
package android.util;

import java.util.Objects;

/** Immutable inclusive interval of comparable values. */
public final class Range<T extends Comparable<? super T>> {
	private final T lower;
	private final T upper;

	public Range(T lower, T upper) {
		this.lower = Objects.requireNonNull(lower, "lower");
		this.upper = Objects.requireNonNull(upper, "upper");
		if (lower.compareTo(upper) > 0)
			throw new IllegalArgumentException("lower must not exceed upper");
	}

	public static <T extends Comparable<? super T>> Range<T> create(T lower, T upper) {
		return new Range<T>(lower, upper);
	}
	public T getLower() { return lower; }
	public T getUpper() { return upper; }
	public boolean contains(T value) {
		Objects.requireNonNull(value, "value");
		return lower.compareTo(value) <= 0 && upper.compareTo(value) >= 0;
	}
	public boolean contains(Range<T> range) {
		Objects.requireNonNull(range, "range");
		return contains(range.lower) && contains(range.upper);
	}
	public T clamp(T value) {
		Objects.requireNonNull(value, "value");
		return value.compareTo(lower) < 0 ? lower : value.compareTo(upper) > 0 ? upper
		                                                                       : value;
	}
	public Range<T> intersect(Range<T> range) {
		Objects.requireNonNull(range, "range");
		return intersect(range.lower, range.upper);
	}
	public Range<T> intersect(T lower, T upper) {
		Range<T> other = new Range<T>(lower, upper);
		if (other.contains(this))
			return this;
		return new Range<T>(this.lower.compareTo(lower) >= 0 ? this.lower : lower,
		                    this.upper.compareTo(upper) <= 0 ? this.upper : upper);
	}
	public Range<T> extend(Range<T> range) {
		Objects.requireNonNull(range, "range");
		return extend(range.lower, range.upper);
	}
	public Range<T> extend(T value) { return extend(value, value); }
	public Range<T> extend(T lower, T upper) {
		Range<T> other = new Range<T>(lower, upper);
		if (contains(other))
			return this;
		return new Range<T>(this.lower.compareTo(lower) <= 0 ? this.lower : lower,
		                    this.upper.compareTo(upper) >= 0 ? this.upper : upper);
	}
	@Override
	public boolean equals(Object other) {
		if (!(other instanceof Range))
			return false;
		Range<?> range = (Range<?>)other;
		return lower.equals(range.lower) && upper.equals(range.upper);
	}
	@Override
	public int hashCode() {
		int hash = 31 + lower.hashCode();
		return 31 * hash + upper.hashCode();
	}
	@Override
	public String toString() { return "[" + lower + ", " + upper + "]"; }
}
