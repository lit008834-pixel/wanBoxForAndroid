//go:build linux

// @author 雾晚
package libcore

import (
	"errors"
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

type identityPlatform struct {
	BoxPlatformInterface
	uid          int32
	names        string
	packageError error
	packageCalls int
}

func (p *identityPlatform) FindConnectionOwner(int32, string, int32, string, int32) (int32, error) {
	return p.uid, nil
}
func (p *identityPlatform) PackageNameByUid(int32) (string, error) {
	p.packageCalls++
	return p.names, p.packageError
}

// A failed package lookup must not discard a valid owner UID. @author 雾晚
func TestPlatformOwnerKeepsUidAndEveryPackage(t *testing.T) {
	oldInterface, oldProcfs := intfBox, useProcfs
	defer func() { intfBox = oldInterface; useProcfs = oldProcfs }()
	useProcfs = false
	for _, tc := range []struct {
		name   string
		uid    int32
		names  string
		err    error
		count  int
		failed bool
	}{
		{"shared", 10123, "example.one\nexample.two", nil, 2, false},
		{"package unavailable", 10123, "", errors.New("unavailable"), 0, false},
		{"invalid UID", -1, "example.one", nil, 0, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			platform := &identityPlatform{uid: tc.uid, names: tc.names, packageError: tc.err}
			intfBox = platform
			wrapper := &boxPlatformInterfaceWrapper{}
			owner, err := wrapper.FindConnectionOwner(&adapter.FindConnectionOwnerRequest{IpProtocol: 6, SourceAddress: "127.0.0.1", DestinationAddress: "127.0.0.1"})
			if tc.failed {
				if err == nil || platform.packageCalls != 0 {
					t.Fatal("invalid UID was accepted")
				}
				return
			}
			if err != nil || owner == nil || owner.UserId != tc.uid || len(owner.PackageNames) != tc.count {
				t.Fatalf("owner=%v err=%v", owner, err)
			}
		})
	}
}
