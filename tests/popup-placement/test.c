/* SPDX-License-Identifier: GPL-3.0-only */
#include <assert.h>
#include <stdio.h>
#include "../../src/api-impl-jni/widgets/PopupPlacement.h"

static void check(int g, int absolute, int attached, int overlap,
        int xoff, int yoff, int expected_x, int expected_y)
{
    AtlPopupPoint p = atl_popup_position(260, 300, 40, 30, 400, 600,
            120, 80, xoff, yoff, g, absolute, attached, overlap);
    assert(p.x == expected_x && p.y == expected_y);
}

int main(void)
{
    check(3, 0, 1, 0, 0, 0, 260, 330); /* LEFT */
    check(5, 0, 1, 0, 0, 0, 180, 330); /* RIGHT */
    check(5, 0, 1, 0, 7, -5, 187, 325);
    check(3, 0, 1, 1, 0, 0, 260, 300); /* overlap */
    check(3, 0, 1, 0, 200, 0, 280, 330); /* decor clips */
    check(3, 0, 0, 0, 200, 0, 460, 330); /* screen instead */
    check(3 | 48, 1, 1, 0, 10, 20, 10, 20);
    check(5 | 80, 1, 1, 0, 10, 20, 270, 500);
    check(1 | 16, 1, 1, 0, 10, 20, 150, 280);
    check(0, 1, 1, 0, 10, 20, 10, 20);
    AtlPopupPoint p = atl_popup_position(260, 570, 40, 30, 400, 600,
            120, 80, 0, 0, 5, 0, 1, 0);
    assert(p.x == 180 && p.y == 490); /* dropdown flips above */
    p = atl_popup_position(0, 10, 40, 30, 100, 60,
            120, 80, -5, 0, 3, 0, 1, 0);
    assert(p.x == 0 && p.y == 0); /* oversized popup stays on frame */
    puts("PASS: 12 popup placement cases");
}
