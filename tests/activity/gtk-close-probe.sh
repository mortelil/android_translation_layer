#!/bin/sh
# Lane-C diagnostic runner for gtk-close-probe.c (A04 S3D2 close-path triage).
#
# Answers one question with no ATL code in the loop: under this container's
# Xvfb (with/without a WM), does an external WM_DELETE_WINDOW arrive at a GTK4
# toplevel as a `close-request` signal, and does g_application_run() then return?
#
#   sh gtk-close-probe.sh [OUTDIR]              # no window manager
#   ATL_PROBE_WM=openbox sh gtk-close-probe.sh  # production-like session
#
# The inner driver runs *inside* the xvfb-run session so DISPLAY/XAUTHORITY come
# from the session itself (reading them out of a live process's environ is
# fragile in this shared container). Lane-private paths only.
set -u
OUT=${1:-/home/morten/ATL/agent-work/LIFECYCLE-FOUNDATION/activity/a04-strict/gtk-env}
LANE=${ATL_LANE_DIR:-/home/morten/ATL/lifecycle-foundation/activity}
RESOURCES=${ATL_RESOURCES:-/home/morten/.omp/profiles/atl-lifecycle/agent/resources.py}
mkdir -p "$OUT"
rm -f "$OUT/summary.txt" "$OUT/probe-stdout.log" "$OUT/inner-probe.log"

# Independent close mechanism: xdotool windowclose sends WM_DELETE_WINDOW when
# the window advertises the protocol, so this probe shares no code with the
# lane's atl-x11-close helper.
cat > "$OUT/inner.sh" <<'EOS'
#!/bin/sh
# Runs inside xvfb-run: DISPLAY/XAUTHORITY are already exported here.
OUT=$1
"$OUT/gtk-close-probe" > "$OUT/inner-probe.log" 2>&1 &
P=$!
i=0
while [ "$i" -lt 60 ]; do
	grep -q GTK_CLOSE_PRESENTED "$OUT/inner-probe.log" 2>/dev/null && break
	kill -0 "$P" 2>/dev/null || break
	i=$((i + 1)); sleep 1
done
echo "inner_presented_after=${i}s"
sleep 2
# xdotool windowclose destroys the window instead of delivering the protocol
# message, so use the lane's own WM-protocol sender.
"$OUT/x11-wm-close" --list --name gtk-close-probe
"$OUT/x11-wm-close" --name gtk-close-probe; echo "inner_close_rc=$?"
t=0
while [ "$t" -lt 15 ]; do
	kill -0 "$P" 2>/dev/null || break
	t=$((t + 1)); sleep 1
done
if kill -0 "$P" 2>/dev/null; then
	echo "inner_probe_alive_after=${t}s"
	kill -9 "$P" 2>/dev/null
else
	wait "$P"; echo "inner_probe_exited_after=${t}s status=$?"
fi
echo "inner_close_request=$(grep -c GTK_CLOSE_REQUEST "$OUT/inner-probe.log") inner_app_run_returned=$(grep -c GTK_APP_RUN_RETURNED "$OUT/inner-probe.log")"
EOS
chmod +x "$OUT/inner.sh"

WM_PRE=""
[ -n "${ATL_PROBE_WM:-}" ] && WM_PRE="$ATL_PROBE_WM & sleep 2; "

INNER="cd $LANE/repo; ulimit -c 0; \
cc -O0 -o $OUT/gtk-close-probe tests/activity/gtk-close-probe.c \$(pkg-config --cflags --libs gtk4) || exit 9; \
cc -O0 -o $OUT/x11-wm-close tests/activity/x11-wm-close.c -lX11 || exit 9; \
dbus-run-session -- xvfb-run -a -s '-screen 0 1280x1024x24' sh -c '${WM_PRE}sh $OUT/inner.sh $OUT'"

python3 "$RESOURCES" --team activity --kind atl --timeout 240 -- \
	toolbox run --container atl_dev sh -lc "$INNER" > "$OUT/probe-stdout.log" 2>&1
{
	echo "wm=${ATL_PROBE_WM:-none}"
	grep -E '^inner_' "$OUT/probe-stdout.log" || echo "no inner output (see probe-stdout.log)"
} | tee "$OUT/summary.txt"