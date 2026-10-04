// @author 雾晚
package appowner

import "strings"

// Names decodes the shared-UID String bridge without discarding secondary packages.
// @author 雾晚
func Names(encoded string) []string {
	var names []string
	seen := make(map[string]bool)
	for _, name := range strings.Split(encoded, "\n") {
		name = strings.TrimSpace(name)
		if name != "" && !seen[name] {
			seen[name] = true
			names = append(names, name)
		}
	}
	return names
}
