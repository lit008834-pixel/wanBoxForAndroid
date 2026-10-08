#!/system/bin/sh
# @author 雾晚
MODDIR=${0%/*}
[ "$#" -eq 0 ] || exit 2
[ -x "$MODDIR/bin/wanboxctl" ] || exit 1
exec "$MODDIR/bin/wanboxctl" __internal boot
