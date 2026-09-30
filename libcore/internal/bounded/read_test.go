// @author 雾晚
package bounded

import (
    "strings"
    "testing"
)

func TestReadBoundary(t *testing.T) {
    if data, err := Read(strings.NewReader("1234"), 4); err != nil || string(data) != "1234" { t.Fatal(data, err) }
    if data, err := Read(strings.NewReader("12345"), 4); err == nil || data != nil { t.Fatal("oversized response accepted") }
}
