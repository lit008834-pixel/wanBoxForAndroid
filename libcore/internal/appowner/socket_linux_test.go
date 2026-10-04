//go:build linux

// @author 雾晚
package appowner

import (
	"context"
	"net"
	"os"
	"testing"

	"github.com/sagernet/sing-box/common/process"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
)

// Root uses the pinned core's socket_diag searcher rather than the Android bridge. @author 雾晚
func TestRootSocketOwner(t *testing.T) {
	searcher, err := process.NewSearcher(process.Config{Logger: log.NewNOPFactory().NewLogger("test")})
	if err != nil {
		t.Fatal(err)
	}
	defer searcher.Close()
	for _, address := range []string{"127.0.0.1:0", "[::1]:0"} {
		t.Run(address, func(t *testing.T) {
			listener, err := net.Listen("tcp", address)
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			conn, err := net.Dial("tcp", listener.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			owner, err := searcher.FindProcessInfo(context.Background(), "tcp", M.AddrPortFromNet(conn.LocalAddr()), M.AddrPortFromNet(conn.RemoteAddr()))
			if err != nil || owner == nil || owner.UserId != int32(os.Getuid()) {
				t.Fatalf("TCP owner=%v error=%v", owner, err)
			}
			udp, err := net.ListenPacket("udp", address)
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			owner, err = searcher.FindProcessInfo(context.Background(), "udp", M.AddrPortFromNet(udp.LocalAddr()), M.AddrPortFromNet(listener.Addr()))
			if err != nil || owner == nil || owner.UserId != int32(os.Getuid()) {
				t.Fatalf("UDP owner=%v error=%v", owner, err)
			}
		})
	}
}
