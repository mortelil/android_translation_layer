/* Lane-C diagnostic tool (2026-10-15): send WM_DELETE_WINDOW the way a window
 * manager does, from outside the client.
 *
 * A04's S3D2 scenario needs the app's stack torn down when the toplevel is
 * closed externally. `xdotool windowclose` is NOT equivalent (this container's
 * build destroys the window instead of delivering the protocol message), so the
 * lane needs a self-contained, source-available sender:
 *
 *   x11-wm-close [--name SUBSTR] [--list]
 *
 * It walks the root's children (and one level deeper, for reparenting WMs),
 * keeps viewable windows that advertise WM_DELETE_WINDOW, optionally filters on
 * WM_CLASS/WM_NAME/_NET_WM_NAME, and posts the ClientMessage to the largest
 * match.
 */
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static char *get_text_prop(Display *dpy, Window w, Atom prop)
{
	Atom type;
	int format;
	unsigned long n, left;
	unsigned char *data = NULL;
	char *out = NULL;

	if (XGetWindowProperty(dpy, w, prop, 0, 1024, False, AnyPropertyType, &type, &format, &n, &left,
	                       &data) == Success &&
	    data != NULL) {
		if (format == 8) {
			out = strndup((char *)data, n);
		} else if (format == 32 && n > 0) {
			out = strdup((char *)(data)); /* _NET_WM_NAME is UTF8_STRING */
		}
		XFree(data);
	}
	return out;
}

static int has_wm_delete(Display *dpy, Window w)
{
	Atom *protocols = NULL;
	int count = 0, found = 0;
	Atom wm_delete = XInternAtom(dpy, "WM_DELETE_WINDOW", False);

	if (XGetWMProtocols(dpy, w, &protocols, &count)) {
		for (int i = 0; i < count; i++)
			if (protocols[i] == wm_delete)
				found = 1;
		XFree(protocols);
	}
	return found;
}

static int matches(Display *dpy, Window w, const char *substr)
{
	int ok = 0;
	Atom wm_class = XInternAtom(dpy, "WM_CLASS", False);
	Atom wm_name = XInternAtom(dpy, "_NET_WM_NAME", False);
	Atom wm_wname = XInternAtom(dpy, "WM_NAME", False);
	char *vals[3];

	if (substr == NULL)
		return 1;
	vals[0] = get_text_prop(dpy, w, wm_class);
	vals[1] = get_text_prop(dpy, w, wm_name);
	vals[2] = get_text_prop(dpy, w, wm_wname);
	for (int i = 0; i < 3; i++)
		if (vals[i] != NULL && strstr(vals[i], substr) != NULL)
			ok = 1;
	for (int i = 0; i < 3; i++)
		free(vals[i]);
	return ok;
}

static void examine(Display *dpy, Window w, const char *substr, int list, Window *best,
                    unsigned long *best_area)
{
	XWindowAttributes attrs;

	if (!XGetWindowAttributes(dpy, w, &attrs) || attrs.map_state != IsViewable)
		return;
	if (!has_wm_delete(dpy, w) || !matches(dpy, w, substr))
		return;
	unsigned long area = (unsigned long)attrs.width * (unsigned long)attrs.height;
	char *name = get_text_prop(dpy, w, XInternAtom(dpy, "_NET_WM_NAME", False));
	if (name == NULL)
		name = get_text_prop(dpy, w, XInternAtom(dpy, "WM_NAME", False));
	if (list)
		printf("candidate 0x%lx %dx%d area=%lu name=%s\n", w, attrs.width, attrs.height, area,
		       name != NULL ? name : "(none)");
	free(name);
	if (area > *best_area) {
		*best_area = area;
		*best = w;
	}
}

int main(int argc, char **argv)
{
	const char *substr = NULL;
	int list = 0, opt;

	for (opt = 1; opt < argc; opt++) {
		if (!strcmp(argv[opt], "--name") && opt + 1 < argc)
			substr = argv[++opt];
		else if (!strcmp(argv[opt], "--list"))
			list = 1;
		else {
			fprintf(stderr, "usage: x11-wm-close [--name SUBSTR] [--list]\n");
			return 2;
		}
	}

	Display *dpy = XOpenDisplay(NULL);
	if (dpy == NULL) {
		fprintf(stderr, "x11-wm-close: cannot open display\n");
		return 3;
	}
	Window root = DefaultRootWindow(dpy), parent, *children = NULL, best = None;
	unsigned int nchildren = 0;
	unsigned long best_area = 0;

	if (!XQueryTree(dpy, root, &root, &parent, &children, &nchildren)) {
		fprintf(stderr, "x11-wm-close: XQueryTree failed\n");
		return 3;
	}
	for (unsigned int i = 0; i < nchildren; i++) {
		Window gparent, *grandchildren = NULL;
		unsigned int ngrand = 0;

		examine(dpy, children[i], substr, list, &best, &best_area);
		/* Reparenting WMs put the client inside a frame. */
		if (XQueryTree(dpy, children[i], &root, &gparent, &grandchildren, &ngrand)) {
			for (unsigned int j = 0; j < ngrand; j++)
				examine(dpy, grandchildren[j], substr, list, &best, &best_area);
			if (grandchildren != NULL)
				XFree(grandchildren);
		}
	}
	if (children != NULL)
		XFree(children);
	if (best == None) {
		printf("x11-wm-close: no viewable WM_DELETE_WINDOW window");
		if (substr != NULL)
			printf(" matching '%s'", substr);
		printf("\n");
		return 4;
	}

	XEvent ev;
	memset(&ev, 0, sizeof(ev));
	ev.xclient.type = ClientMessage;
	ev.xclient.window = best;
	ev.xclient.message_type = XInternAtom(dpy, "WM_PROTOCOLS", False);
	ev.xclient.format = 32;
	ev.xclient.data.l[0] = XInternAtom(dpy, "WM_DELETE_WINDOW", False);
	ev.xclient.data.l[1] = CurrentTime;
	XSendEvent(dpy, best, False, NoEventMask, &ev);
	XFlush(dpy);
	printf("x11-wm-close: sent WM_DELETE_WINDOW to 0x%lx (area %lu)\n", best, best_area);
	XCloseDisplay(dpy);
	return 0;
}