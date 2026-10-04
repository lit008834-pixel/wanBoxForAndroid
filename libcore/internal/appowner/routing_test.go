// @author 雾晚
package appowner

import (
	"context"
	"os"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route/rule"
	"github.com/sagernet/sing/service"
)

// Tests the exact pinned core matcher with the Android serializer fixture. @author 雾晚
func TestAndroidIdentityRule(t *testing.T) {
	data, err := os.ReadFile("../../../app/src/test/resources/app-route-identity.json")
	if err != nil {
		t.Fatal(err)
	}
	ctx := service.ContextWithDefaultRegistry(context.Background())
	var options option.Rule
	if err := options.UnmarshalJSONContext(ctx, data); err != nil {
		t.Fatal(err)
	}
	matcher, err := rule.NewRule(ctx, log.NewNOPFactory().NewLogger("test"), options, true)
	if err != nil {
		t.Fatal(err)
	}
	action, ok := matcher.Action().(*rule.RuleActionRoute)
	if !ok || action.Outbound != "fixture-node" {
		t.Fatal("outbound was changed")
	}
	cases := []struct {
		name     string
		uid      int32
		packages []string
		want     bool
	}{
		{"root UID without package cache", 10123, nil, true},
		{"secondary user package", 1010123, []string{"example.chat"}, true},
		{"shared UID all packages", 10123, []string{"example.other", "example.chat"}, true},
		{"unknown identity", -1, nil, false},
		{"unselected application", 10999, []string{"example.other"}, false},
	}
	for _, tc := range cases {
		for _, network := range []string{"tcp", "udp"} {
			t.Run(tc.name+"/"+network, func(t *testing.T) {
				metadata := adapter.InboundContext{Network: network, ProcessInfo: &adapter.ConnectionOwner{UserId: tc.uid, PackageNames: tc.packages}}
				if got := matcher.Match(&metadata); got != tc.want {
					t.Fatalf("match=%v want=%v", got, tc.want)
				}
			})
		}
	}
	// Reproduce the old AND rule: a known UID alone could not select its outbound.
	var old option.Rule
	if err := old.UnmarshalJSONContext(ctx, []byte(`{"package_name":["example.chat"],"user_id":[10123],"outbound":"fixture-node"}`)); err != nil {
		t.Fatal(err)
	}
	previous, err := rule.NewRule(ctx, log.NewNOPFactory().NewLogger("test"), old, true)
	if err != nil {
		t.Fatal(err)
	}
	metadata := adapter.InboundContext{ProcessInfo: &adapter.ConnectionOwner{UserId: 10123}}
	if previous.Match(&metadata) {
		t.Fatal("old failure was not reproduced")
	}
}
