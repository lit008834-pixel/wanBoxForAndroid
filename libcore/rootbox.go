// @author 雾晚
package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"sync/atomic"
	"syscall"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

// RunRootBox starts the same configured core without Android's VpnService platform
// interface. The caller must run this process as UID 0 and terminate it on stop.
func RunRootBox(configPath, assetsPath, pidPath, readyPath, stopPath string, parentPID int) error {
	if os.Geteuid() != 0 {
		return fmt.Errorf("Root TUN requires UID 0")
	}
	if err := os.Chdir(filepath.Dir(configPath)); err != nil {
		return err
	}
	if err := os.WriteFile(pidPath, []byte(fmt.Sprint(os.Getpid())), 0644); err != nil {
		return err
	}
	defer os.Remove(pidPath)
	externalAssetsPath = filepath.Clean(assetsPath) + string(os.PathSeparator)
	resourcePaths = append(resourcePaths, externalAssetsPath)
	if pem, err := os.ReadFile(filepath.Join(assetsPath, "ca.pem")); err == nil {
		updateRootCACerts(pem)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	// @author 雾晚: reuse the existing watchdog; no new polling thread or API port.
	var telemetry atomic.Pointer[BoxInstance]
	watchdogDone := make(chan struct{})
	defer func() { cancel(); <-watchdogDone }()
	go func() {
		defer close(watchdogDone)
		lastSample := time.Now()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if _, err := os.Stat(stopPath); err == nil {
					cancel()
					return
				}
				if core := telemetry.Load(); core != nil {
					elapsed := time.Since(lastSample).Seconds()
					lastSample = time.Now()
					if elapsed > 0 {
						payload, _ := json.Marshal(struct {
							Tag      string `json:"tag"`
							Tx       int64  `json:"tx"`
							Rx       int64  `json:"rx"`
							DirectTx int64  `json:"directTx"`
							DirectRx int64  `json:"directRx"`
						}{
							core.CurrentOutboundTag(), int64(float64(core.QueryStats("proxy", "uplink")) / elapsed), int64(float64(core.QueryStats("proxy", "downlink")) / elapsed), int64(float64(core.QueryStats("bypass", "uplink")) / elapsed), int64(float64(core.QueryStats("bypass", "downlink")) / elapsed),
						})
						fmt.Println("WANBOX_STATS:" + string(payload))
					}
				}
				if telemetry.Load() == nil {
					lastSample = time.Now()
				}
				if syscall.Kill(parentPID, 0) == syscall.ESRCH {
					cancel()
					return
				}
			}
		}
	}()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	config, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, config); err != nil {
		return fmt.Errorf("decode root configuration: %w", err)
	}
	if options.Route != nil {
		if err = prepareLocalGeoRuleSets(options.Route.RuleSet); err != nil {
			return err
		}
		if err = prepareRemoteRuleSets(options.Route.RuleSet); err != nil {
			return err
		}
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		return err
	}
	// @author 雾晚: join the reader before closing stats/router resources.
	defer func() { cancel(); <-watchdogDone; instance.Close() }()
	stats := &BoxInstance{Box: instance}
	stats.SetV2rayStats("proxy\nbypass")
	if err = instance.Start(); err != nil {
		return err
	}
	if err = os.WriteFile(readyPath, []byte("ready"), 0644); err != nil {
		return err
	}
	defer os.Remove(readyPath)
	telemetry.Store(stats)
	<-ctx.Done()
	return nil
}
