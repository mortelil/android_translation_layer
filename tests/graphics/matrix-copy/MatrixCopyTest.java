// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.graphics;

import android.graphics.Matrix;
import junit.framework.TestCase;

/** Regression tests for the Matrix(Matrix) constructor's public contract. */
public class MatrixCopyTest extends TestCase {
	private static void assertValues(float[] expected, Matrix matrix) {
		float[] actual = new float[9];
		matrix.getValues(actual);
		for (int i = 0; i < actual.length; i++)
			assertEquals("matrix element " + i, expected[i], actual[i], 0.0f);
	}

	public void testDefaultAndNullConstructorsAreIdentity() {
		assertTrue(new Matrix().isIdentity());
		assertTrue(new Matrix((Matrix)null).isIdentity());
	}

	public void testIdentityCopy() {
		Matrix source = new Matrix();
		Matrix copy = new Matrix(source);
		assertNotSame(source, copy);
		assertTrue(copy.isIdentity());
	}

	public void testAffineCopyPreservesAllValues() {
		float[] values = {2, 3, 17, 5, -7, 23, 0, 0, 1};
		Matrix source = new Matrix();
		source.setValues(values);
		assertValues(values, new Matrix(source));
		assertValues(values, source);
	}

	public void testPerspectiveCopyPreservesAllValues() {
		float[] values = {2, 3, 17, 5, 7, 23, 0.125f, -0.25f, 2};
		Matrix source = new Matrix();
		source.setValues(values);
		assertValues(values, new Matrix(source));
	}

	public void testSourceAndCopyCanBeMutatedIndependently() {
		float[] values = {2, 3, 17, 5, -7, 23, 0, 0, 1};
		Matrix source = new Matrix();
		source.setValues(values);
		Matrix copy = new Matrix(source);
		source.reset();
		assertTrue(source.isIdentity());
		assertValues(values, copy);
		source.setValues(values);
		copy.reset();
		assertTrue(copy.isIdentity());
		assertValues(values, source);
	}

	public void testCopiedTransformMapsTheSamePoint() {
		Matrix source = new Matrix();
		source.setValues(new float[] {2, 0, 17, 0, 3, 23, 0, 0, 1});
		float[] point = {4, 5};
		new Matrix(source).mapPoints(point);
		assertEquals(25.0f, point[0], 0.0f);
		assertEquals(38.0f, point[1], 0.0f);
	}
}
