# EGL without an ATL window

Run inside atl_dev: `sh tests/egl-headless/run.sh OUT`.
Compiles the production EGL adapter, retaining the exercised functions. Tests
real context/config/pbuffer creation and red pixel readback in three modes:
no GTK with surfaceless Mesa; no GTK with X11; initialized GDK with no ATL window.
GTK criticals are fatal. Attribute-list filtering runs under AddressSanitizer,
covering empty/null lists and retained EGL pairs with and without dimensions.
Also run tests/egl-lifecycle and tests/egl-readback.

Origin: OMP e3474b97 (FirefoxR30). Integration also fixes pre-existing byte-count
underallocation in the Wayland pbuffer attribute copy and frees its temporary
list. Real Wayland window creation/lifetime was not tested: the legacy emulated
pbuffer still needs an explicit owner for its wl_surface/wl_egl_window resources.
This test does not claim a functioning Firefox GPU process or ARM support.
