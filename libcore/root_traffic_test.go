// @author 雾晚
package libcore

import (
	"github.com/sagernet/sing-box/option"
	"math"
	"reflect"
	"testing"
)

func TestRootTrafficIncludesApplicationRuleOutbounds(t *testing.T) {
	proxy, direct := rootTrafficTags([]option.Outbound{
		{Type: "selector", Tag: "proxy"}, {Type: "socks", Tag: "fixture-ph"},
		{Type: "direct", Tag: "direct"}, {Type: "direct", Tag: "bypass"},
		{Type: "socks", Tag: "fixture-ph"}, {Type: "direct"},
	}, []option.Endpoint{{Type: "wireguard", Tag: "fixture-wg"}})
	if !reflect.DeepEqual(proxy, []string{"proxy", "fixture-ph", "fixture-wg"}) || !reflect.DeepEqual(direct, []string{"direct", "bypass"}) {
		t.Fatalf("incorrect traffic categories: %v %v", proxy, direct)
	}
	seen := map[string]int{}
	rate := rootTrafficRate(proxy, "downlink", 2, func(tag, direction string) int64 {
		if direction != "downlink" {
			t.Fatal("changed direction")
		}
		seen[tag]++
		if tag == "fixture-ph" {
			return 1200
		}
		return 0
	})
	if rate != 600 || len(seen) != 3 || seen["fixture-ph"] != 1 {
		t.Fatalf("rule traffic lost/double counted: %d %v", rate, seen)
	}
}
func TestRootTrafficRateBoundaries(t *testing.T) {
	if rootTrafficRate([]string{"x"}, "uplink", 0, func(string, string) int64 { t.Fatal("queried before ready"); return 0 }) != 0 {
		t.Fatal("invalid rate")
	}
	if rootTrafficRate([]string{"x", "y"}, "uplink", 1, func(string, string) int64 { return math.MaxInt64 }) != math.MaxInt64 {
		t.Fatal("overflow")
	}
	if rootTrafficRate([]string{"x"}, "uplink", 1, func(string, string) int64 { return -1 }) != 0 {
		t.Fatal("negative rate")
	}
}
