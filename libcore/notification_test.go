// @author 雾晚
package libcore

import (
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

type leafFixture struct {
	adapter.Outbound
	name string
}

func (f leafFixture) Tag() string { return f.name }

type observedFixture struct {
	adapter.Outbound
	leaf string
}

func (f observedFixture) LastSelectedTag() string { return f.leaf }

type selectorFixture struct {
	adapter.Outbound
	selected adapter.Outbound
}

func (f selectorFixture) Selected() adapter.Outbound { return f.selected }

func TestNotificationObservation(t *testing.T) {
	leaf := leafFixture{name: "node"}
	nested := observedFixture{leafFixture{name: "auto"}, "node"}
	selector := selectorFixture{leafFixture{name: "proxy"}, nested}
	all := map[string]adapter.Outbound{"node": &leaf, "auto": nested}
	lookup := func(tag string) (adapter.Outbound, bool) { v, ok := all[tag]; return v, ok }
	if observedOutboundTag(selector, lookup) != "node" {
		t.Fatal("nested selection not resolved")
	}
	for _, tag := range []string{"", "missing", "auto"} {
		if observedOutboundTag(observedFixture{nested.Outbound, tag}, lookup) != "" {
			t.Fatal("unknown/cyclic selection guessed")
		}
	}
	if observedOutboundTag(selectorFixture{selector.Outbound, nil}, lookup) != "" {
		t.Fatal("unready selector guessed")
	}
	if observedOutboundTag(nil, lookup) != "" {
		t.Fatal("nil core guessed")
	}
}
