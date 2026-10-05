// @author 雾晚
package libcore

import "net/netip"

// Report configured host addresses, not whole subnets, to the core's local-source check.
// @author 雾晚
func tunInterfaceAddresses(ipv4, ipv6 []netip.Prefix) []netip.Addr {
	addresses := make([]netip.Addr, 0, len(ipv4)+len(ipv6))
	for _, prefixes := range [][]netip.Prefix{ipv4, ipv6} {
		for _, prefix := range prefixes {
			if prefix.IsValid() {
				addresses = append(addresses, prefix.Addr())
			}
		}
	}
	return addresses
}
