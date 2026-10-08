// @author 雾晚
package libcore

import (
	"github.com/sagernet/sing-box/option"
	"math"
)

// rootTrafficTags includes rule-selected outbounds, not only the main proxy alias.
// StatsService tracks the routed outbound once, so detour hops are not added twice.
// @author 雾晚
func rootTrafficTags(outbounds []option.Outbound, endpoints []option.Endpoint) (proxy, direct []string) {
	seen := map[string]bool{}
	add := func(tag string, isDirect bool) {
		if tag == "" || seen[tag] {
			return
		}
		seen[tag] = true
		if isDirect {
			direct = append(direct, tag)
		} else {
			proxy = append(proxy, tag)
		}
	}
	for _, outbound := range outbounds {
		add(outbound.Tag, outbound.Type == "direct")
	}
	for _, endpoint := range endpoints {
		add(endpoint.Tag, false)
	}
	return
}

// @author 雾晚: QueryStats resets counters; read every routed tag exactly once per sample.
func rootTrafficRate(tags []string, direction string, elapsed float64, query func(string, string) int64) int64 {
	if elapsed <= 0 {
		return 0
	}
	var bytes int64
	for _, tag := range tags {
		value := query(tag, direction)
		if value <= 0 {
			continue
		}
		if value > math.MaxInt64-bytes {
			bytes = math.MaxInt64
			continue
		}
		bytes += value
	}
	rate := float64(bytes) / elapsed
	if rate >= float64(math.MaxInt64) {
		return math.MaxInt64
	}
	return int64(rate)
}
