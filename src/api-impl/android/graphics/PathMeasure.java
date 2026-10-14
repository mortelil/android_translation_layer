/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

/** Measures a {@link Path}: the length of the CURRENT contour, and the position and
 *  tangent at a given distance along that contour.
 *
 *  <p>Android's contract (which this port implements) is per-contour:
 *  {@link #getLength()} and {@link #getPosTan} only describe the contour the
 *  measure currently points at (initially the first one), and
 *  {@link #nextContour()} advances to the next contour, returning false once none
 *  is left. Past the last contour the measure is empty: the length is 0,
 *  getPosTan() returns false. Contours without length (a lone moveTo, or segments
 *  that all collapse into a single point) are skipped, like Skia's
 *  SkContourMeasureIter. The backend is GTK's GskPathMeasure (GTK &gt;= 4.14), but
 *  the native side rebuilds each contour into its own GskPath so GskPathMeasure
 *  ever only sees one contour.
 *
 *  <p>{@code forceClosed} (constructor and {@link #setPath}) measures an open
 *  contour as if it ended where it started: the closing chord is appended. Skia
 *  appends the same closing segment for a contour that is closed explicitly or is
 *  force-closed, so an open triangle legs 3/4 measures 7, and 12 (7 + the 5-long
 *  closing edge) with forceClosed - exactly as on Android.
 *
 *  <p>Lifetime: setPath() keeps a strong reference to the Java {@link Path} (as
 *  AOSP does) and the native side additionally holds its own reference on that
 *  path's GskPath, so the measured geometry stays valid even when the Java Path is
 *  dropped and garbage-collected, or reset() and reused. The measure is therefore
 *  a SNAPSHOT of the path as it was at setPath() time; modifying a Path afterwards
 *  does not change an existing measure (Android leaves that case undefined).
 *
 *  <p>Still unimplemented in this port: {@link #getSegment} always returns false
 *  (see its note) and {@link #getMatrix}/{@link #isClosed} are not provided at all.
 *
 *  Semantics adapted from AOSP's android.graphics.PathMeasure and Skia's
 *  SkContourMeasure (SkPathMeasure::setPath/getPosTan/nextContour, and
 *  buildSegments' per-contour closing segment), android-17.0.0_r1. */
public class PathMeasure {
	/** Native measure state (per-contour GskPaths + GskPathMeasures); 0 after finalize. */
	private long native_instance;
	/** Strong reference that keeps the measured Path (and hence its GskPath) alive. */
	private Path path;

	public PathMeasure() {
		native_instance = native_create();
	}

	public PathMeasure(Path path, boolean forceClosed) {
		native_instance = native_create();
		setPath(path, forceClosed);
	}

	/** Assign a new path (or null for none) to measure. See the class comment for
	 *  the lifetime and forceClosed rules. */
	public void setPath(Path path, boolean forceClosed) {
		this.path = path;
		native_setPath(native_instance, path == null ? 0 : path.getGskPath(), forceClosed);
	}

	/** Length of the current contour, or 0 if the measure is empty (no path, or past
	 *  the last contour). */
	public float getLength() {
		return native_instance == 0 ? 0 : native_getLength(native_instance);
	}

	/** Pins {@code distance} to 0 <= distance <= getLength() and reports the position
	 *  at that distance in {@code pos} (x == [0], y == [1]) and/or the unit tangent in
	 *  {@code tan}. Either array may be null to skip that half of the result.
	 *
	 *  @return false when there is no current contour (no path, empty path, or past
	 *  the last contour) or the distance is NaN; the output arrays are then
	 *  unchanged. As on Android (SkContourMeasure::getPosTan), an out-of-range
	 *  distance is pinned to the nearest end of the contour and still returns true.
	 *  @throws ArrayIndexOutOfBoundsException when pos or tan is non-null but
	 *  shorter than 2, as on Android. */
	public boolean getPosTan(float distance, float[] pos, float[] tan) {
		if ((pos != null && pos.length < 2) || (tan != null && tan.length < 2))
			throw new ArrayIndexOutOfBoundsException();
		if (native_instance == 0)
			return false;
		return native_getPosTan(native_instance, distance, pos, tan);
	}

	/** <b>Not implemented</b> in ATL: always returns false, so no segment is ever
	 *  appended to {@code dst}. On Android this appends the sub-contour between
	 *  {@code start} and {@code end} (pinned to 0..getLength(), false when
	 *  start >= end), splitting curves at those distances; that curve-splitting is
	 *  not available here, and returning true without filling dst would be worse
	 *  than the explicit false. The native limit is documented in the class
	 *  comment. */
	public boolean getSegment(float start, float end, Path dst, boolean startWithMoveTo) {
		return false;
	}

	/** Move to the next contour of the path. Returns true if the new contour has
	 *  length, or false when the measure is past the last contour (the measure is
	 *  then empty: getLength() returns 0 and getPosTan() returns false). */
	public boolean nextContour() {
		return native_instance != 0 && native_nextContour(native_instance);
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			native_destroy(native_instance);
			native_instance = 0;
			path = null;
		} finally {
			super.finalize();
		}
	}

	private static native long native_create();
	private static native void native_setPath(long handle, long gsk_path_ptr, boolean forceClosed);
	private static native float native_getLength(long handle);
	private static native boolean native_getPosTan(long handle, float distance, float[] pos, float[] tan);
	private static native boolean native_nextContour(long handle);
	private static native void native_destroy(long handle);
}