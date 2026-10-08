// SPDX-License-Identifier: GPL-3.0-only
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <gtk/gtk.h>
#include <jni.h>
#include <stdint.h>

static void fail(JNIEnv *env, const char *message)
{
	jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
	(*env)->ThrowNew(env, cls, message);
	(*env)->DeleteLocalRef(env, cls);
}
static gboolean check_context(JNIEnv *env, jlong context)
{
	if (!context || (EGLContext)(intptr_t)context != eglGetCurrentContext()) {
		fail(env, "SurfaceTexture requires its owning EGL context");
		return FALSE;
	}
	return TRUE;
}
JNIEXPORT jlong JNICALL Java_android_graphics_SurfaceTexture_nativeCurrentContext(JNIEnv *env, jclass cls)
{
	return (intptr_t)eglGetCurrentContext();
}
JNIEXPORT jlong JNICALL Java_android_graphics_SurfaceTexture_nativeAttach(JNIEnv *env, jclass cls, jint texture)
{
	EGLContext context = eglGetCurrentContext();
	if (!check_context(env, (intptr_t)context))
		return 0;
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
	if (glGetError() != GL_NO_ERROR) {
		fail(env, "Cannot bind SurfaceTexture external texture");
		return 0;
	}
	return (intptr_t)context;
}
JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeDetach(JNIEnv *env, jclass cls, jlong context, jint texture)
{
	if (!check_context(env, context))
		return;
	GLuint name = texture;
	glDeleteTextures(1, &name);
}
JNIEXPORT void JNICALL Java_android_graphics_SurfaceTexture_nativeUpdate(JNIEnv *env, jclass cls, jlong context, jint texture, jbyteArray rgba, jint width, jint height)
{
	if (!check_context(env, context))
		return;
	PFNGLEGLIMAGETARGETTEXTURE2DOESPROC image_target = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
	if (!image_target) {
		fail(env, "GL_OES_EGL_image is unavailable");
		return;
	}
	if (width <= 0 || height <= 0 || (int64_t)width * height * 4 != (*env)->GetArrayLength(env, rgba)) {
		fail(env, "Invalid SurfaceTexture image dimensions");
		return;
	}
	jbyte *pixels = (*env)->GetByteArrayElements(env, rgba, NULL);
	if (!pixels)
		return;
	GLint previous_texture, unpack_alignment;
	glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous_texture);
	glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpack_alignment);
	GLuint source;
	glGenTextures(1, &source);
	glBindTexture(GL_TEXTURE_2D, source);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
	(*env)->ReleaseByteArrayElements(env, rgba, pixels, JNI_ABORT);
	glPixelStorei(GL_UNPACK_ALIGNMENT, unpack_alignment);
	EGLDisplay display = eglGetCurrentDisplay();
	const EGLAttrib attrs[] = {EGL_IMAGE_PRESERVED, EGL_TRUE, EGL_NONE};
	EGLImage image = eglCreateImage(display, eglGetCurrentContext(), EGL_GL_TEXTURE_2D, (EGLClientBuffer)(uintptr_t)source, attrs);
	if (image != EGL_NO_IMAGE) {
		glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
		image_target(GL_TEXTURE_EXTERNAL_OES, image);
		eglDestroyImage(display, image);
	} else {
		fail(env, "Cannot create EGLImage for SurfaceTexture frame");
	}
	glBindTexture(GL_TEXTURE_2D, previous_texture);
	glDeleteTextures(1, &source);
	if (glGetError() != GL_NO_ERROR && !(*env)->ExceptionCheck(env))
		fail(env, "Failed to upload SurfaceTexture frame");
}
JNIEXPORT jlong JNICALL Java_android_view_Surface_nativeResetCanvas(JNIEnv *env, jclass cls, jlong old)
{
	if (old)
		g_object_unref((gpointer)(intptr_t)old);
	return (intptr_t)gtk_snapshot_new();
}
JNIEXPORT jbyteArray JNICALL Java_android_view_Surface_nativePostCanvas(JNIEnv *env, jclass cls, jlong snapshot, jint width, jint height)
{
	GskRenderNode *node = gtk_snapshot_free_to_node((GtkSnapshot *)(intptr_t)snapshot);
	if (width <= 0 || height <= 0 || (int64_t)width * height * 4 > INT32_MAX) {
		if (node)
			gsk_render_node_unref(node);
		fail(env, "Invalid Surface canvas size");
		return NULL;
	}
	jsize size = width * height * 4;
	jbyteArray result = (*env)->NewByteArray(env, size);
	if (!result) {
		if (node)
			gsk_render_node_unref(node);
		return NULL;
	}
	if (node) {
		GskRenderer *renderer = gsk_cairo_renderer_new();
		GError *error = NULL;
		if (!gsk_renderer_realize(renderer, NULL, &error)) {
			fail(env, error->message);
			g_error_free(error);
			gsk_render_node_unref(node);
			g_object_unref(renderer);
			return NULL;
		}
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, width, height);
		GdkTexture *image = gsk_renderer_render_texture(renderer, node, &bounds);
		GdkTextureDownloader *download = gdk_texture_downloader_new(image);
		gdk_texture_downloader_set_format(download, GDK_MEMORY_R8G8B8A8_PREMULTIPLIED);
		jbyte *pixels = (*env)->GetByteArrayElements(env, result, NULL);
		if (pixels) {
			gdk_texture_downloader_download_into(download, (guchar *)pixels, width * 4);
			(*env)->ReleaseByteArrayElements(env, result, pixels, 0);
		}
		gdk_texture_downloader_free(download);
		g_object_unref(image);
		gsk_render_node_unref(node);
		gsk_renderer_unrealize(renderer);
		g_object_unref(renderer);
	}
	return result;
}
