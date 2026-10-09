// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package libcore

import "testing"

// TestDomesticResolveConfig checks the official pinned schema, without opening a TUN or dialing. @author 雾晚
func TestDomesticResolveConfig(t *testing.T) {
	instance, err := checkConfig(`{
  "log":{"disabled":true},
  "dns":{"servers":[{"type":"local","tag":"resolver"}],"final":"resolver"},
  "outbounds":[{"type":"direct","tag":"bypass"}],
  "route":{"default_domain_resolver":"resolver","rules":[
   {"domain_suffix":["cn.fixture.invalid"],"outbound":"bypass"},
   {"action":"resolve","timeout":"3s"},
   {"ip_cidr":["192.0.2.0/24","2001:db8:1::/48"],"outbound":"bypass"}
  ],"final":"bypass"}
 }`)
	if err != nil {
		t.Fatal(err)
	}
	if err = instance.Close(); err != nil {
		t.Fatal(err)
	}
}
