// @author 雾晚
package loadbalance

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/interrupt"
	M "github.com/sagernet/sing/common/metadata"
)

type dialFixture struct {
	adapter.Outbound
	failure error
}

func (f dialFixture) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	if f.failure != nil {
		return nil, f.failure
	}
	local, remote := net.Pipe()
	remote.Close()
	time.Sleep(3 * time.Millisecond)
	return local, nil
}

func (f dialFixture) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	if f.failure != nil {
		return nil, f.failure
	}
	time.Sleep(3 * time.Millisecond)
	return net.ListenPacket("udp", "127.0.0.1:0")
}

func TestApplicationDialsPreserveProbeLatency(t *testing.T) {
	for _, udp := range []bool{false, true} {
		name := "tcp"
		if udp {
			name = "udp"
		}
		t.Run(name, func(t *testing.T) {
			stats := &nodeStats{}
			stats.recordSuccess(250)
			stats.recordFailure()
			lb := &LoadBalance{
				tags: []string{"node"}, strategy: "leastPing",
				stats: []*nodeStats{stats}, outbounds: []adapter.Outbound{dialFixture{}},
				interruptGroup: interrupt.NewGroup(),
			}
			for i := 0; i < 20; i++ {
				if udp {
					conn, err := lb.ListenPacket(context.Background(), M.Socksaddr{})
					if err != nil {
						t.Fatal(err)
					}
					conn.Close()
				} else {
					conn, err := lb.DialContext(context.Background(), "tcp", M.Socksaddr{})
					if err != nil {
						t.Fatal(err)
					}
					conn.Close()
				}
			}
			if stats.latencyEmaMs.Load() != 250 {
				t.Fatalf("application dials polluted probe EMA: %d", stats.latencyEmaMs.Load())
			}
			if stats.consecutiveFails.Load() != 0 || stats.successDials.Load() != 21 || stats.totalDials.Load() != 22 {
				t.Fatal("dial success/failure accounting changed")
			}
			stats.recordSuccess(100)
			if stats.latencyEmaMs.Load() != 220 {
				t.Fatal("health probe no longer updates EMA")
			}
			lb.outbounds[0] = dialFixture{failure: errors.New("network unavailable")}
			var err error
			if udp {
				_, err = lb.ListenPacket(context.Background(), M.Socksaddr{})
			} else {
				_, err = lb.DialContext(context.Background(), "tcp", M.Socksaddr{})
			}
			if err == nil || stats.consecutiveFails.Load() != 1 || stats.latencyEmaMs.Load() != 220 {
				t.Fatal("network failure not propagated or latency overwritten")
			}
		})
	}
}

func TestLeastPingOrderSurvivesApplicationTraffic(t *testing.T) {
	slow, fast := &nodeStats{}, &nodeStats{}
	slow.recordSuccess(250)
	fast.recordSuccess(50)
	for i := 0; i < 100; i++ {
		slow.recordDialSuccess()
	}
	lb := &LoadBalance{tags: []string{"slow", "fast"}, strategy: "leastPing", stats: []*nodeStats{slow, fast}, outbounds: make([]adapter.Outbound, 2)}
	if lb.candidateIndices(context.Background(), M.Socksaddr{})[0] != 1 {
		t.Fatal("application traffic changed leastPing ordering")
	}
}
