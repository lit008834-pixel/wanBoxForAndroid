// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package appowner

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route/rule"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

// Execute the pinned official matcher against the Android serializer fixture. @author 雾晚
func TestDomesticSuffixAndIPMatching(t *testing.T) {
	data, err := os.ReadFile("../../../app/src/test/resources/domestic-rule-match.json")
	if err != nil {
		t.Fatal(err)
	}
	var entries []json.RawMessage
	if err = json.Unmarshal(data, &entries); err != nil {
		t.Fatal(err)
	}
	ctx := service.ContextWithDefaultRegistry(context.Background())
	cases := [][]struct {
		destination string
		matches     bool
	}{
		{{"cn.fixture.invalid", true}, {"sub.cn.fixture.invalid", true},
			{"evilcn.fixture.invalid", false}, {"cn.fixture.invalid.evil", false}, {"192.0.2.1", false}},
		{{"192.0.2.1", true}, {"192.0.2.255", true}, {"192.0.3.1", false},
			{"2001:db8:1::1", true}, {"2001:db8:2::1", false}, {"cn.fixture.invalid", false}},
	}
	if len(entries) != len(cases) {
		t.Fatal("fixture rule count changed")
	}
	for index, entry := range entries {
		var options option.Rule
		if err = options.UnmarshalJSONContext(ctx, entry); err != nil {
			t.Fatal(err)
		}
		matcher, err := rule.NewRule(ctx, log.NewNOPFactory().NewLogger("test"), options, true)
		if err != nil {
			t.Fatal(err)
		}
		action, ok := matcher.Action().(*rule.RuleActionRoute)
		if !ok || action.Outbound != "bypass" {
			t.Fatal("direct outbound changed")
		}
		for _, tc := range cases[index] {
			for _, network := range []string{"tcp", "udp"} {
				metadata := adapter.InboundContext{Network: network, Destination: M.ParseSocksaddr(net.JoinHostPort(tc.destination, "443"))}
				if got := matcher.Match(&metadata); got != tc.matches {
					t.Fatalf("rule=%d network=%s destination=%s match=%v want=%v", index, network, tc.destination, got, tc.matches)
				}
			}
		}
	}
}
