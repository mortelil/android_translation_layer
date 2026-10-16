/*
 * Synthetic DNS-SD service publisher used by the ATL android.net.nsd
 * compatibility tests. Not part of ATL: this is a test fixture that advertises
 * a service on the host's avahi daemon so that an app running under ATL can
 * discover and resolve it.
 *
 * The container has libavahi-client but no avahi-utils, so this replaces
 * `avahi-publish -s`.
 *
 * build (inside the toolbox container):
 *   cc -o nsd_fixture nsd_fixture.c $(pkg-config --cflags --libs avahi-client)
 * run:
 *   ./nsd_fixture [service_type] [instance_name] [port] [key=value ...]
 * prints  FIXTURE_READY <type> <name> <port>  once committed, then serves until
 * SIGINT/SIGTERM, where it withdraws the service and exits 0.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>

#include <avahi-client/client.h>
#include <avahi-client/publish.h>
#include <avahi-common/error.h>
#include <avahi-common/strlst.h>
#include <avahi-common/simple-watch.h>

static AvahiSimplePoll *poll;
static volatile sig_atomic_t quit = 0;

static const char *g_type;
static const char *g_name;
static uint16_t g_port;
static AvahiStringList *g_txt;
static AvahiEntryGroup *g_group;

static void on_signal(int s)
{
	(void)s;
	quit = 1;
}

static void publish(AvahiClient *c)
{
	int err = avahi_entry_group_add_service_strlst(g_group, AVAHI_IF_UNSPEC,
			AVAHI_PROTO_UNSPEC, 0, g_name, g_type, NULL, NULL, g_port, g_txt);
	if (err < 0) {
		fprintf(stderr, "FIXTURE_ERROR add_service: %s\n", avahi_strerror(err));
		quit = 1;
		return;
	}
	err = avahi_entry_group_commit(g_group);
	if (err < 0) {
		fprintf(stderr, "FIXTURE_ERROR commit: %s\n", avahi_strerror(err));
		quit = 1;
		return;
	}
	printf("FIXTURE_READY %s %s %u\n", g_type, g_name, g_port);
	fflush(stdout);
}

static void client_cb(AvahiClient *c, AvahiClientState state, void *userdata)
{
	(void)userdata;
	switch (state) {
	case AVAHI_CLIENT_S_RUNNING:
		if (!g_group) {
			g_group = avahi_entry_group_new(c, NULL, NULL);
			if (!g_group) {
				fprintf(stderr, "FIXTURE_ERROR group_new\n");
				quit = 1;
				return;
			}
			publish(c);
		}
		break;
	case AVAHI_CLIENT_FAILURE:
		fprintf(stderr, "FIXTURE_ERROR client failure: %s\n",
				avahi_strerror(avahi_client_errno(c)));
		quit = 1;
		break;
	default:
		break;
	}
}

int main(int argc, char **argv)
{
	g_type = (argc > 1) ? argv[1] : "_home-assistant._tcp";
	g_name = (argc > 2) ? argv[2] : "ATL-Synthetic-HA";
	g_port = (argc > 3) ? (uint16_t)atoi(argv[3]) : 8123;

	/* Default TXT payload: what a current Home Assistant instance publishes. */
	static const char *defaults[] = {
		"location_name=ATL Synthetic HA",
		"uuid=0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9",
		"version=2026.10.0",
		"base_url=http://127.0.0.1:8123",
		"internal_url=http://127.0.0.1:8123",
		"external_url=http://127.0.0.1:8123",
		"requires_api_password=True",
	};
	int i;
	for (i = 0; i < (int)(sizeof(defaults) / sizeof(defaults[0])); i++)
		g_txt = avahi_string_list_add(g_txt, defaults[i]);
	for (i = 4; i < argc; i++)
		g_txt = avahi_string_list_add(g_txt, argv[i]);

	signal(SIGINT, on_signal);
	signal(SIGTERM, on_signal);

	poll = avahi_simple_poll_new();
	if (!poll) {
		fprintf(stderr, "FIXTURE_ERROR poll_new\n");
		return 2;
	}
	int error = 0;
	/* flags=0: fail loudly when the daemon is unreachable. */
	AvahiClient *client = avahi_client_new(avahi_simple_poll_get(poll), 0,
			client_cb, NULL, &error);
	if (!client) {
		fprintf(stderr, "FIXTURE_ERROR client_new: %s\n", avahi_strerror(error));
		return 2;
	}

	while (!quit)
		avahi_simple_poll_iterate(poll, 200);

	if (g_group)
		avahi_entry_group_free(g_group);
	avahi_client_free(client);
	avahi_simple_poll_free(poll);
	avahi_string_list_free(g_txt);
	printf("FIXTURE_STOPPED %s %s\n", g_type, g_name);
	return 0;
}