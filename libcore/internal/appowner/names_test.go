// @author 雾晚
package appowner

import (
	"reflect"
	"testing"
)

func TestNamesPreserveSharedUIDAndLegacySinglePackage(t *testing.T) {
	for _, tc := range []struct {
		encoded string
		want    []string
	}{
		{"example.one", []string{"example.one"}},
		{"example.one\nexample.two\nexample.one\n", []string{"example.one", "example.two"}},
		{"\n \n", nil},
	} {
		if got := Names(tc.encoded); !reflect.DeepEqual(got, tc.want) {
			t.Fatalf("Names() = %v, want %v", got, tc.want)
		}
	}
}
