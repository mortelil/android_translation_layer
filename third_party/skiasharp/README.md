# SkiaSharp C API declarations

`include/c/sk_path.h` and `sk_types.h` are from mono/skia commit
`7dbfc07dd33181f84e0958afb7ee805c6c769f0b` (tag v3.119.4).
https://github.com/mono/skia/tree/7dbfc07dd33181f84e0958afb7ee805c6c769f0b/include/c

Only sk_path.h's local include path was changed. Copyright notices and upstream
BSD license are retained. No Skia implementation is vendored: ATL links the
native `libSkiaSharp` library (Alpine: `apk add skiasharp`). No .NET runtime is
needed. PathOps preserves quadratic, cubic and rational conic curves and both
inverse/non-inverse fill rules, unlike the discarded polygon approximation.
