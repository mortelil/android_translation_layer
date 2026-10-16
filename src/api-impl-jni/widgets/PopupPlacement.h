/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ATL_POPUP_PLACEMENT_H
#define ATL_POPUP_PLACEMENT_H

/* Android absolute gravity bits; START/END are resolved by Java first.
 * Coordinates and dimensions are in the same GTK logical coordinate space. */
typedef struct { int x, y; } AtlPopupPoint;

static inline AtlPopupPoint atl_popup_position(int ax, int ay, int aw, int ah,
        int fw, int fh, int pw, int ph, int xoff, int yoff,
        int gravity, int absolute, int attached, int overlap)
{
    int horizontal = gravity & 7;
    int vertical = gravity & 112;
    AtlPopupPoint p;
    if (absolute) {
        p.x = horizontal == 5 ? fw - pw - xoff :
              horizontal == 1 ? (fw - pw) / 2 + xoff : xoff;
        p.y = vertical == 80 ? fh - ph - yoff :
              vertical == 16 ? (fh - ph) / 2 + yoff : yoff;
    } else {
        /* Drop-down gravity aligns edges; Android does not centre a dropdown
         * for CENTER_HORIZONTAL. It only special-cases RIGHT. */
        p.x = ax + xoff - (horizontal == 5 ? pw - aw : 0);
        p.y = ay + (overlap ? 0 : ah) + yoff;
        if (attached && p.y + ph > fh && ay - ph + yoff >= 0)
            p.y = ay - ph + yoff;
    }
    if (attached) {
        int xmax = fw > pw ? fw - pw : 0;
        int ymax = fh > ph ? fh - ph : 0;
        if (p.x > xmax) p.x = xmax;
        if (p.y > ymax) p.y = ymax;
        if (p.x < 0) p.x = 0;
        if (p.y < 0) p.y = 0;
    }
    return p;
}
#endif
