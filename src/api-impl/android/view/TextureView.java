package android.view;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;

/**
 * TextureView backed by the platform's real {@link SurfaceTexture} queue.
 *
 * ATL has no GPU compositor bridge: views are rendered through GTK/GSK into an
 * sRGB buffer, so frames written into a SurfaceTexture are not blitted onto the
 * view. This class therefore implements the documented lifecycle and listener
 * contract and always reports the SurfaceTexture the view really owns; it never
 * fabricates a placeholder texture and it never claims on-screen composition.
 */
public class TextureView extends View {

	public interface SurfaceTextureListener {
		void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height);

		void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height);

		boolean onSurfaceTextureDestroyed(SurfaceTexture surface);

		void onSurfaceTextureUpdated(SurfaceTexture surface);
	}

	private SurfaceTextureListener surfaceTextureListener;
	private SurfaceTexture surfaceTexture;
	private boolean available;
	private boolean opaque = true;
	private Matrix transform;

	public TextureView(Context context) {
		super(context);
	}

	public TextureView(Context context, AttributeSet attributeSet) {
		super(context, attributeSet);
	}

	public TextureView(Context context, AttributeSet attributeSet, int defStyleAttr) {
		super(context, attributeSet, defStyleAttr);
	}

	public TextureView(Context context, AttributeSet attributeSet, int defStyleAttr, int defStyleRes) {
		super(context, attributeSet, defStyleAttr, defStyleRes);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		// A texture the previous owner released cannot be reused.
		if (surfaceTexture == null || surfaceTexture.isReleased())
			surfaceTexture = new SurfaceTexture(false);
		int width = getWidth() > 0 ? getWidth() : 1;
		int height = getHeight() > 0 ? getHeight() : 1;
		surfaceTexture.setDefaultBufferSize(width, height);
		available = true;
		if (surfaceTextureListener != null)
			surfaceTextureListener.onSurfaceTextureAvailable(surfaceTexture, width, height);
	}

	@Override
	protected void onDetachedFromWindow() {
		SurfaceTexture old = surfaceTexture;
		try {
			boolean release = true;
			try {
				if (available && old != null && surfaceTextureListener != null)
					release = surfaceTextureListener.onSurfaceTextureDestroyed(old);
			} finally {
				// false transfers ownership to the listener; never reuse that surface.
				if (release && old != null) old.release();
			}
		} finally {
			surfaceTexture = null;
			available = false;
			super.onDetachedFromWindow();
		}
	}

	@Override
	protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
		super.onSizeChanged(width, height, oldWidth, oldHeight);
		if (available && surfaceTexture != null && !surfaceTexture.isReleased()) {
			surfaceTexture.setDefaultBufferSize(width > 0 ? width : 1, height > 0 ? height : 1);
			if (surfaceTextureListener != null)
				surfaceTextureListener.onSurfaceTextureSizeChanged(surfaceTexture, width, height);
		}
	}

	public void setSurfaceTextureListener(SurfaceTextureListener surfaceTextureListener) {
		this.surfaceTextureListener = surfaceTextureListener;
	}

	public SurfaceTextureListener getSurfaceTextureListener() {
		return surfaceTextureListener;
	}

	/**
	 * The SurfaceTexture associated with this view, or {@code null} when the view
	 * has no texture yet. Never a dummy: callers may consume and release it.
	 */
	public SurfaceTexture getSurfaceTexture() {
		return surfaceTexture;
	}

	public boolean isAvailable() {
		return available && surfaceTexture != null && !surfaceTexture.isReleased();
	}

	public void setOpaque(boolean opaque) {
		this.opaque = opaque;
	}

	public boolean isOpaque() {
		return opaque;
	}

	public void setTransform(Matrix transform) {
		this.transform = transform;
	}

	public Matrix getTransform(Matrix transform) {
		if (transform == null) {
			transform = new Matrix();
		}
		if (this.transform != null) {
			transform.set(this.transform);
		} else {
			transform.reset();
		}
		return transform;
	}

	public void setSurfaceTexture(SurfaceTexture surfaceTexture) {
		if (surfaceTexture == null) throw new NullPointerException("surfaceTexture");
		if (surfaceTexture == this.surfaceTexture || surfaceTexture.isReleased())
			throw new IllegalArgumentException("SurfaceTexture must be live and different");
		if (this.surfaceTexture != null) this.surfaceTexture.release();
		this.surfaceTexture = surfaceTexture;
	}
}
