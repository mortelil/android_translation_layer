// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <gsk/gsk.h>
/* Operand paths are borrowed; result is owned, including a valid empty path.
 * Failure returns NULL and sets out_fill_type=-1 without changing operands.
 * op and fill types use Android/Skia enum ordinals. */
GskPath *atl_path_op(GskPath *a, GskPath *b, int op, int fill_a, int fill_b, int *out_fill_type);
