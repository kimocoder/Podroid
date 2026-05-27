#!/bin/sh
# Host test (no root): builds the helper natively and checks it normalizes a
# fake upper using the user.overlay.* namespace (settable without CAP_SYS_ADMIN).
set -e
cd "$(dirname "$0")"
command -v setfattr >/dev/null || { echo "SKIP: need attr/setfattr"; exit 0; }

cc -O2 -Wall -Wextra -Wno-unused-parameter -D_GNU_SOURCE -o /tmp/pon podroid-overlay-normalize.c

T=$(mktemp -d)
mkdir -p "$T/upper/sub" "$T/work/index/deep"
echo realdata > "$T/upper/realfile"
echo ""       > "$T/upper/metacopyfile"
setfattr -n user.overlay.metacopy -v y "$T/upper/metacopyfile"
setfattr -n user.overlay.redirect -v /old "$T/upper/sub"

PODROID_NORMALIZE_NS=user.overlay. /tmp/pon "$T/upper" "$T/work"

fail=0
[ -f "$T/upper/realfile" ]      || { echo "FAIL: realfile removed"; fail=1; }
[ ! -e "$T/upper/metacopyfile" ]|| { echo "FAIL: metacopyfile kept"; fail=1; }
[ -d "$T/upper/sub" ]           || { echo "FAIL: sub removed"; fail=1; }
getfattr -n user.overlay.redirect "$T/upper/sub" 2>/dev/null | grep -q redirect \
    && { echo "FAIL: redirect xattr kept"; fail=1; } || true
[ ! -e "$T/work/index" ]        || { echo "FAIL: index dir kept"; fail=1; }
rm -rf "$T"
[ "$fail" = 0 ] && echo "PASS" || exit 1
