// @author 雾晚
package urltest

import (
	"context"
	"libcore/internal/urlprobe"
	"net"
	"net/http"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

const DefaultFallbackURL = "https://www.gstatic.com/generate_204"

// ProbeOutbound uses the same complete request timing as manual and Root probes.
func ProbeOutbound(ctx context.Context, detour adapter.Outbound, link string, timeout time.Duration) (uint16, error) {
	if detour == nil {
		return 0, E.New("nil detour")
	}
	if link == "" {
		link = DefaultFallbackURL
	}
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	transport := &http.Transport{
		ForceAttemptHTTP2: true,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return detour.DialContext(ctx, network, M.ParseSocksaddr(addr))
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	delay, err := urlprobe.MeasureContext(ctx, client, link, timeout)
	if err != nil {
		return 0, err
	}
	if delay > 65535 {
		delay = 65535
	}
	return uint16(delay), nil
}
