// @author 雾晚
package libcore

import (
	"net/netip"
	"testing"
)

// @author 雾晚
func TestTunInterfaceAddresses(t *testing.T) {
	ipv4 := []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30"), {}}
	ipv6 := []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")}
	addresses := tunInterfaceAddresses(ipv4, ipv6)
	if len(addresses) != 2 || addresses[0].String() != "172.19.0.1" || addresses[1].String() != "fdfe:dcba:9876::1" {
		t.Fatalf("TUN host addresses changed: %v", addresses)
	}
	if len(tunInterfaceAddresses(nil, nil)) != 0 {
		t.Fatal("empty test core acquired TUN addresses")
	}
	addresses[0] = netip.Addr{}
	if tunInterfaceAddresses(ipv4, nil)[0].String() != "172.19.0.1" {
		t.Fatal("address collection mutated input or shared state")
	}
}
