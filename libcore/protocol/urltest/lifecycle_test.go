// @author 雾晚
package urltest_test

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/outbound"
	history "github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
	"libcore/protocol/loadbalance"
	custom "libcore/protocol/urltest"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// @author 雾晚: exercise real manager dispatch instead of calling legacy Start manually.
type loopbackOutbound struct{ outbound.Adapter }

func (s *loopbackOutbound) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, dst.String())
}
func (s *loopbackOutbound) ListenPacket(ctx context.Context, dst M.Socksaddr) (net.PacketConn, error) {
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
}
func manager(t *testing.T, kind, member, link string) (*outbound.Manager, *adapter.Scope) {
	t.Helper()
	registry := outbound.NewRegistry()
	custom.RegisterURLTest(registry)
	loadbalance.RegisterLoadBalance(registry)
	outbound.Register[struct{}](registry, "loopback", func(ctx context.Context, r adapter.Router, l log.ContextLogger, tag string, o struct{}) (adapter.Outbound, error) {
		return &loopbackOutbound{outbound.NewAdapter("loopback", tag, []string{"tcp", "udp"}, nil)}, nil
	})
	endpoints := endpoint.NewManager(endpoint.NewRegistry())
	m := outbound.NewManager(registry, endpoints, "probe")
	logger := log.NewNOPFactory().Logger()
	ctx := service.ContextWithDefaultRegistry(context.Background())
	ctx = service.ContextWith[adapter.OutboundManager](ctx, m)
	ctx = service.ContextWithPtr(ctx, history.NewHistoryStorage())
	ctx = pause.WithDefaultManager(ctx)
	scope := adapter.NewScope(ctx, logger)
	if err := scope.Start("endpoints", endpoints, adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = scope.Close() })
	if err := m.Create(ctx, nil, logger, "direct", "loopback", &struct{}{}); err != nil {
		t.Fatal(err)
	}
	var options any = &custom.URLTestOptions{Outbounds: []string{member}, URL: link}
	if kind == "loadbalance" {
		options = &loadbalance.LoadBalanceOptions{URLTestOutboundOptions: option.URLTestOutboundOptions{Outbounds: []string{member}, URL: link}, Strategy: "round-robin"}
	}
	if err := m.Create(ctx, nil, logger, "probe", kind, options); err != nil {
		t.Fatal(err)
	}
	return m, scope
}
func TestManagerStartsCustomGroupsAndProbeSurvivesShutdown(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer server.Close()
	for _, kind := range []string{"urltest", "loadbalance"} {
		t.Run(kind, func(t *testing.T) {
			m, scope := manager(t, kind, "direct", server.URL)
			transport := &http.Transport{DialContext: func(ctx context.Context, n, a string) (net.Conn, error) {
				return m.Default().DialContext(ctx, n, M.ParseSocksaddr(a))
			}}
			defer transport.CloseIdleConnections()
			client := &http.Client{Transport: transport, Timeout: time.Second}
			if response, err := client.Get(server.URL); err == nil {
				response.Body.Close()
				t.Fatal("uninitialized group unexpectedly dialed")
			}
			for _, stage := range adapter.ListStartStages {
				if err := scope.Start("outbounds", m, stage); err != nil {
					t.Fatal(err)
				}
			}
			response, err := client.Get(server.URL)
			if err != nil {
				t.Fatal(err)
			}
			response.Body.Close()
			if response.StatusCode != 204 {
				t.Fatal(response.StatusCode)
			}
			transport.CloseIdleConnections()
			if err := scope.Close(); err != nil {
				t.Fatal(err)
			}
			if err := scope.Close(); err != nil {
				t.Fatal(err)
			}
			{
				if response, err = client.Get(server.URL); err == nil {
					response.Body.Close()
					t.Fatal("closed group unexpectedly dialed")
				}
			}
		})
	}
}
func TestManagerRejectsMissingGroupDependency(t *testing.T) {
	for _, kind := range []string{"urltest", "loadbalance"} {
		t.Run(kind, func(t *testing.T) {
			m, scope := manager(t, kind, "missing", "http://127.0.0.1:1")
			if err := scope.Start("outbounds", m, adapter.StartStateInitialize); err != nil {
				t.Fatal(err)
			}
			if err := scope.Start("outbounds", m, adapter.StartStateStart); err == nil {
				t.Fatal("missing member accepted")
			}
			if err := scope.Close(); err != nil {
				t.Fatal(err)
			}
		})
	}
}
