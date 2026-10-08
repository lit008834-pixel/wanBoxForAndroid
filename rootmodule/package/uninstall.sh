#!/system/bin/sh
# @author 雾晚
MODDIR=${0%/*}
[ -x "$MODDIR/bin/wanboxctl" ] || exit 1
# Only the module's identified processes are stopped. sing-tun owns teardown;
# never flush iptables, nftables or other modules' routes. Preserve user data.
exec "$MODDIR/bin/wanboxctl" stop
