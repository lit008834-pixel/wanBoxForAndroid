// @author 雾晚
package libcore

import "github.com/sagernet/sing-box/adapter"

// CurrentOutboundTag observes the selected leaf without dialing or advancing a
// load balancer. An empty value means that no actual member is known yet.
func (b *BoxInstance) CurrentOutboundTag() string {
	if b == nil || b.Box == nil {
		return ""
	}
	return observedOutboundTag(b.Outbound().Default(), b.Outbound().Outbound)
}

// @author 雾晚
func observedOutboundTag(outbound adapter.Outbound, lookup func(string) (adapter.Outbound, bool)) string {
	seen := make(map[string]bool)
	for depth := 0; outbound != nil && depth < 32; depth++ {
		tag := outbound.Tag()
		if seen[tag] {
			return ""
		}
		seen[tag] = true
		var next string
		if observer, ok := outbound.(interface{ LastSelectedTag() string }); ok {
			next = observer.LastSelectedTag()
		} else if selector, ok := outbound.(interface{ Selected() adapter.Outbound }); ok {
			selected := selector.Selected()
			if selected == nil {
				return ""
			}
			next = selected.Tag()
		} else {
			return tag
		}
		if next == "" {
			return ""
		}
		var exists bool
		outbound, exists = lookup(next)
		if !exists {
			return ""
		}
	}
	return ""
}
