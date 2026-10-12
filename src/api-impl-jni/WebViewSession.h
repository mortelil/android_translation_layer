// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <webkit/webkit.h>
/* GTK main thread only; NULL means private storage could not be created. */
WebKitNetworkSession *atl_webview_session(void);
