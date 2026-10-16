/*
 * Standalone DNS-SD browse+resolve probe for the ATL android.net.nsd tests.
 * Not part of ATL: it answers "can this container query the running avahi
 * daemon at all?" independently of the ATL runtime (the container has
 * libavahi-client but no avahi-utils, so there is no `avahi-browse`).
 *
 * build (inside the toolbox container):
 *   cc -o nsd_browse nsd_browse.c $(pkg-config --cflags --libs avahi-client)
 * run:
 *   ./nsd_browse <service_type> <timeout_s>
 * exit 0 and prints BROWSE_FOUND <name> <host> <address> <port> <txtlist>
 * when a service was resolved, 1 otherwise.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <avahi-client/client.h>
#include <avahi-client/lookup.h>
#include <avahi-common/error.h>
#include <avahi-common/strlst.h>
#include <avahi-common/simple-watch.h>
#include <avahi-common/address.h>

static AvahiSimplePoll *poll;
static AvahiClient *client;
static AvahiServiceBrowser *browser;
static int found = 0;

static void resolve_cb(AvahiServiceResolver *r, AvahiIfIndex iface,
		AvahiProtocol proto, AvahiResolverEvent event, const char *name,
		const char *type, const char *domain, const char *host_name,
		const AvahiAddress *a, uint16_t port, AvahiStringList *txt,
		AvahiLookupResultFlags flags, void *userdata)
{
	(void)iface; (void)proto; (void)domain; (void)flags; (void)userdata;
	if (event == AVAHI_RESOLVER_FOUND) {
		char addr[AVAHI_ADDRESS_STR_MAX] = "?";
		char txtbuf[1024] = "";
		if (a)
			avahi_address_snprint(addr, sizeof(addr), a);
		AvahiStringList *l;
		for (l = txt; l; l = avahi_string_list_get_next(l)) {
			size_t sz = avahi_string_list_get_size(l);
			if (strlen(txtbuf) + sz + 2 < sizeof(txtbuf)) {
				if (txtbuf[0])
					strcat(txtbuf, ",");
				strncat(txtbuf, (const char *)avahi_string_list_get_text(l), sz);
			}
		}
		printf("BROWSE_FOUND %s %s %s %u %s\n", name, host_name ? host_name : "?",
				addr, port, txtbuf);
		fflush(stdout);
		found = 1;
	}
	avahi_service_resolver_free(r);
}

static void browse_cb(AvahiServiceBrowser *b, AvahiIfIndex iface,
		AvahiProtocol proto, AvahiBrowserEvent event, const char *name,
		const char *type, const char *domain, AvahiLookupResultFlags flags,
		void *userdata)
{
	(void)b; (void)flags; (void)userdata;
	if (event == AVAHI_BROWSER_NEW) {
		avahi_service_resolver_new(client, iface, proto, name, type, domain,
				AVAHI_PROTO_UNSPEC, 0, resolve_cb, NULL);
	}
}

static void client_cb(AvahiClient *c, AvahiClientState state, void *userdata)
{
	(void)c; (void)userdata;
	if (state == AVAHI_CLIENT_S_RUNNING && !browser)
		browser = avahi_service_browser_new(c, AVAHI_IF_UNSPEC,
				AVAHI_PROTO_UNSPEC, (const char *)userdata, NULL, 0,
				browse_cb, NULL);
	if (state == AVAHI_CLIENT_FAILURE)
		fprintf(stderr, "BROWSE_ERROR client failure: %s\n",
				avahi_strerror(avahi_client_errno(c)));
}

int main(int argc, char **argv)
{
	const char *type = (argc > 1) ? argv[1] : "_home-assistant._tcp";
	int timeout_s = (argc > 2) ? atoi(argv[2]) : 6;

	poll = avahi_simple_poll_new();
	if (!poll) {
		fprintf(stderr, "BROWSE_ERROR poll_new\n");
		return 2;
	}
	int error = 0;
	client = avahi_client_new(avahi_simple_poll_get(poll), 0, client_cb,
			(void *)type, &error);
	if (!client) {
		fprintf(stderr, "BROWSE_ERROR client_new: %s\n", avahi_strerror(error));
		return 2;
	}

	time_t start = time(NULL);
	while (!found && time(NULL) - start < timeout_s)
		avahi_simple_poll_iterate(poll, 200);

	avahi_client_free(client);
	avahi_simple_poll_free(poll);
	printf("BROWSE_RESULT %s\n", found ? "FOUND" : "TIMEOUT");
	return found ? 0 : 1;
}