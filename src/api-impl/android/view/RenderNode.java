package android.view;

import android.atl.RecordingCanvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RenderNode {

	private long render_node = 0;
	private long transformed_node = 0;
	private List<RenderNode> children = new ArrayList<RenderNode>();
	private long[] childGenerations = new long[0];
	private long[] childNodes = new long[0];
	private RecordingCanvas displayList, recording;
	private long generation;
	private boolean resolving;

	private float width = 0.0f;
	private float height = 0.0f;
	private float scaleX = 1.0f;
	private float scaleY = 1.0f;
	private float translationX = 0.0f;
	private float translationY = 0.0f;
	private float elevation = 0.0f;
	private float rotation = 0.0f;
	private float rotationX = 0.0f;
	private float rotationY = 0.0f;
	private float cameraDistance = 0.0f;
	private float pivotX = 0.0f;
	private float pivotY = 0.0f;
	private boolean clipToBounds = true;
	private boolean clipToOutline = false;
	private float alpha = 1.0f;
	private Outline outline = null;

	/* Outline is mutable - callers (e.g. Compose's OutlineResolver) keep one instance and
	 * setRoundRect()/setPath() into it - so setOutline()'s identity comparison alone would leave
	 * the clip of an already built transformed_node stale. These remember the outline geometry
	 * that the cached node was clipped with. */
	private final Rect outlineRect = new Rect();
	private int outlineMode = Outline.MODE_EMPTY;
	private float outlineRadius = Float.NEGATIVE_INFINITY;
	private long outlinePath = 0;

	private native long nativeTransform(long node, float scaleX, float scaleY, float translationX, float translationY, float rotation, float pivotX, float pivotY);
	private native long nativeClipOutline(long node, int mode, float left, float top, float right, float bottom, float radius, int fillType, long path);

	private native long nativeOpacity(long node, float alpha);
	private native void nativeUnref(long node);
	private native long nativeCreateEmpyNode();

	public static RenderNode create(String name, View view) {
		return new RenderNode();
	}

	public float getScaleX() {
		return scaleX;
	}

	public boolean setScaleX(float scaleX) {
		boolean changed = this.scaleX != scaleX;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.scaleX = scaleX;
		return changed;
	}

	public float getScaleY() {
		return scaleY;
	}

	public boolean setScaleY(float scaleY) {
		boolean changed = this.scaleY != scaleY;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.scaleY = scaleY;
		return changed;
	}

	public float getTranslationX() {
		return translationX;
	}

	public boolean setTranslationX(float translationX) {
		boolean changed = this.translationX != translationX;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.translationX = translationX;
		return changed;
	}

	public float getTranslationY() {
		return translationY;
	}

	public boolean setTranslationY(float translationY) {
		boolean changed = this.translationY != translationY;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.translationY = translationY;
		return changed;
	}

	public float getElevation() {
		return elevation;
	}

	public boolean setElevation(float elevation) {
		boolean changed = this.elevation != elevation;
		this.elevation = elevation;
		return changed;
	}

	public float getRotation() {
		return rotation;
	}

	public boolean setRotation(float rotation) {
		boolean changed = this.rotation != rotation;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.rotation = rotation;
		return changed;
	}

	public float getRotationX() {
		return rotationX;
	}

	public boolean setRotationX(float rotationX) {
		boolean changed = this.rotationX != rotationX;
		this.rotationX = rotationX;
		return changed;
	}

	public float getRotationY() {
		return rotationY;
	}

	public boolean setRotationY(float rotationY) {
		boolean changed = this.rotationY != rotationY;
		this.rotationY = rotationY;
		return changed;
	}

	public float getCameraDistance() {
		return cameraDistance;
	}

	public boolean setCameraDistance(float cameraDistance) {
		boolean changed = this.cameraDistance != cameraDistance;
		this.cameraDistance = cameraDistance;
		return changed;
	}

	public float getPivotX() {
		return pivotX;
	}

	public boolean setPivotX(float pivotX) {
		boolean changed = this.pivotX != pivotX;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.pivotX = pivotX;
		return changed;
	}

	public float getPivotY() {
		return pivotY;
	}

	public boolean setPivotY(float pivotY) {
		boolean changed = this.pivotY != pivotY;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.pivotY = pivotY;
		return changed;
	}

	public boolean getClipToOutline() {
		return clipToOutline;
	}

	public boolean setClipToOutline(boolean clipToOutline) {
		boolean changed = this.clipToOutline != clipToOutline;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.clipToOutline = clipToOutline;
		return changed;
	}

	public boolean setClipToBounds(boolean clipToBounds) {
		boolean changed = this.clipToBounds != clipToBounds;
		this.clipToBounds = clipToBounds;
		return changed;
	}

	public float getAlpha() {
		return alpha;
	}

	public boolean setAlpha(float alpha) {
		boolean changed = this.alpha != alpha;
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.alpha = alpha;
		return changed;
	}

	public boolean isValid() {
		return displayList != null;
	}

	public boolean setLeftTopRightBottom(int left, int top, int right, int bottom) {
		float old_width = width;
		float old_height = height;
		width = right - left;
		height = bottom - top;
		return old_width != width || old_height != height || setTranslationX(left) || setTranslationY(top);
	}

	public boolean offsetLeftAndRight(int offset) {
		return setTranslationX(translationX + offset);
	}

	public boolean offsetTopAndBottom(int offset) {
		return setTranslationY(translationY + offset);
	}

	public void discardDisplayList() {
		nativeUnref(render_node);
		nativeUnref(transformed_node);
		render_node = 0;
		transformed_node = 0;
		children.clear();
		childGenerations = new long[0];
		childNodes = new long[0];
		if (displayList != null) { displayList.release(); displayList = null; }
		if (recording != null) { recording.release(); recording = null; }
	}

	public void destroyDisplayListData() {
		discardDisplayList();
	}

	public boolean setLayerType(int layerType) {
		return false;
	}

	public boolean hasOverlappingRendering() {
		return false;
	}

	public boolean setHasOverlappingRendering(boolean hasOverlappingRendering) {
		return false;
	}

	public boolean setLayerPaint(Paint paint) {
		return false;
	}

	public boolean setOutline(Outline outline) {
		boolean changed = !Objects.equals(this.outline, outline);
		if (changed && transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
		this.outline = outline;
		return changed;
	}

	public long getGskNode() {
		if (resolving) throw new IllegalStateException("Cyclic RenderNode display list");
		resolving = true;
		try {
			boolean changed = render_node == 0;
			for (int i = 0; i < children.size(); i++) {
				RenderNode child = children.get(i);
				childNodes[i] = child.getGskNode();
				changed |= childGenerations[i] != child.generation;
			}
			if (changed) {
				long replacement = displayList != null ? displayList.replay(childNodes) : 0;
				if (replacement == 0) replacement = nativeCreateEmpyNode();
				nativeUnref(render_node);
				render_node = replacement;
				for (int i = 0; i < children.size(); i++) childGenerations[i] = children.get(i).generation;
			}
			if (transformed_node == 0 || changed || outlineClipChanged()) {
				long clipped_node = outlineClipNode();
				long replacement = nativeTransform(clipped_node, scaleX, scaleY, translationX, translationY, rotation, pivotX, pivotY);
				if (clipped_node != render_node) nativeUnref(clipped_node);
				if (alpha != 1.0f) replacement = nativeOpacity(replacement, alpha);
				nativeUnref(transformed_node);
				transformed_node = replacement;
				rememberOutline();
				generation++;
			}
			return transformed_node;
		} finally {
			resolving = false;
		}
	}

	/* The node render_node clipped to this layer's outline, or render_node itself when there is
	 * nothing to clip with. Mirrors AOSP's clipOutline() shape selection: a round-rect outline
	 * clips to a rounded rectangle (a zero radius is a plain rectangle), a path outline clips to
	 * its path. The result is a new node that references render_node; callers own both.
	 * AOSP: frameworks/base/libs/hwui/pipeline/skia/RenderNodeDrawable.cpp clipOutline(),
	 * android-17.0.0_r1 (commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d). */
	private long outlineClipNode() {
		if (!clipToOutline || outline == null || outline.isEmpty())
			return render_node;
		int mode = outline.mMode;
		long path = 0;
		int fillType = 0;
		if (mode == Outline.MODE_PATH) {
			if (outline.mPath == null)
				return render_node;
			path = outline.mPath.getGskPath();
			fillType = outline.mPath.getFillType().ordinal();
		} else if (mode != Outline.MODE_ROUND_RECT) {
			return render_node;
		}
		Rect rect = outline.mRect;
		long clipped_node = nativeClipOutline(render_node, mode, rect.left, rect.top, rect.right, rect.bottom,
				outline.getRadius(), fillType, path);
		return clipped_node != 0 ? clipped_node : render_node;
	}

	private boolean outlineClipChanged() {
		if (outline == null)
			return false;
		long path = outline.mPath != null ? outline.mPath.getGskPath() : 0;
		return outlineMode != outline.mMode || outlineRadius != outline.mRadius
				|| outlinePath != path || !outlineRect.equals(outline.mRect);
	}

	private void rememberOutline() {
		outlineMode = outline != null ? outline.mMode : Outline.MODE_EMPTY;
		outlineRadius = outline != null ? outline.mRadius : Float.NEGATIVE_INFINITY;
		outlinePath = outline != null && outline.mPath != null ? outline.mPath.getGskPath() : 0;
		if (outline != null)
			outlineRect.set(outline.mRect);
		else
			outlineRect.setEmpty();
	}

	public DisplayListCanvas start(int width, int height) {
		if (recording != null) throw new IllegalStateException("RenderNode already recording");
		this.width = width;
		this.height = height;
		recording = new RecordingCanvas();
		return recording;
	}

	public void end(DisplayListCanvas canvas) {
		if (canvas != recording || recording == null)
			throw new IllegalArgumentException("Canvas does not belong to this recording");
		recording.seal();
		if (displayList != null) displayList.release();
		displayList = recording;
		recording = null;
		children = displayList.getChildren();
		childGenerations = new long[children.size()];
		childNodes = new long[children.size()];
		nativeUnref(render_node); render_node = 0;
		nativeUnref(transformed_node); transformed_node = 0;
	}

	@Override protected void finalize() throws Throwable {
		try { discardDisplayList(); } finally { super.finalize(); }
	}

	public void getMatrix(Matrix matrix) {
		matrix.reset();
		matrix.setTranslate(translationX, translationY);
		matrix.preRotate(rotation, pivotX, pivotY);
		matrix.preScale(scaleX, scaleY, pivotX, pivotY);
	}
}
