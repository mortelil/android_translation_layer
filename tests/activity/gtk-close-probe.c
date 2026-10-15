/* Lane-C environment diagnostic (2026-10-15).
 *
 * A04's S3D2 scenario expects the app's activity stack to be torn down when the
 * toplevel is closed from outside. ATL wires that to `close-request` on its
 * GtkWindow (main.c:801 -> app_exit -> activity_close_all), so this 40-line
 * program isolates GTK from ATL: it prints `GTK_CLOSE_PRESENTED` once the window
 * is up, then reports whether an external WM_DELETE_WINDOW turns into a
 * `close-request` signal and whether the GtkApplication then returns from
 * g_application_run().
 *
 * Build + run: tests/activity/gtk-close-probe.sh
 */
#include <gtk/gtk.h>

static gboolean on_close_request(GtkWindow *window, gpointer user_data)
{
	g_print("GTK_CLOSE_REQUEST\n");
	fflush(stdout);
	gtk_window_destroy(window);
	return TRUE; /* handled: GTK's default handler would destroy it too */
}

static void on_activate(GtkApplication *app, gpointer user_data)
{
	GtkWidget *window = gtk_application_window_new(app);

	gtk_window_set_title(GTK_WINDOW(window), "gtk-close-probe");
	gtk_window_set_default_size(GTK_WINDOW(window), 320, 240);
	g_signal_connect(window, "close-request", G_CALLBACK(on_close_request), NULL);
	gtk_window_present(GTK_WINDOW(window));
	g_print("GTK_CLOSE_PRESENTED\n");
	fflush(stdout);
}

int main(int argc, char **argv)
{
	GtkApplication *app = gtk_application_new("org.atl.tests.gtkclose", G_APPLICATION_DEFAULT_FLAGS);
	int status;

	g_signal_connect(app, "activate", G_CALLBACK(on_activate), NULL);
	status = g_application_run(G_APPLICATION(app), argc, argv);
	g_print("GTK_APP_RUN_RETURNED %d\n", status);
	fflush(stdout);
	g_object_unref(app);
	return status;
}
