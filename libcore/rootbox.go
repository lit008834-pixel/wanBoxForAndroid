// @author 雾晚
package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

// RunRootBox is owned by the module supervisor, never by an Android App PID.
// @author 雾晚
func RunRootBox(configPath, assetsPath, pidPath, readyPath, stopPath string, supervisorPID int) error {
	if os.Geteuid() != 0 {
		return fmt.Errorf("Root TUN requires UID 0")
	}
	if err := os.Chdir(filepath.Dir(configPath)); err != nil {
		return err
	}
	SetMemoryProfile(os.Getenv("WANBOX_PERFORMANCE") == "true")
	if supervisorPID <= 1 {
		return fmt.Errorf("invalid supervisor PID")
	}
	if err := os.WriteFile(pidPath, []byte(fmt.Sprint(os.Getpid())), 0600); err != nil {
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
	type rootSampleSource struct {
		box           *BoxInstance
		proxy, direct []string
	}
	var telemetry atomic.Pointer[rootSampleSource]
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
							core.box.CurrentOutboundTag(), rootTrafficRate(core.proxy, "uplink", elapsed, core.box.QueryStats), rootTrafficRate(core.proxy, "downlink", elapsed, core.box.QueryStats), rootTrafficRate(core.direct, "uplink", elapsed, core.box.QueryStats), rootTrafficRate(core.direct, "downlink", elapsed, core.box.QueryStats),
						})
						fmt.Println("WANBOX_STATS:" + string(payload))
					}
				}
				if telemetry.Load() == nil {
					lastSample = time.Now()
				}
				if syscall.Kill(supervisorPID, 0) == syscall.ESRCH {
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
		if err = validateRootGeoResources(options.Route.RuleSet, assetsPath); err != nil {
			return err
		}
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
	proxyTags, directTags := rootTrafficTags(options.Outbounds, options.Endpoints)
	stats.SetV2rayStats(strings.Join(append(append([]string{}, proxyTags...), directTags...), "\n"))
	if err = instance.Start(); err != nil {
		return err
	}
	if err = os.WriteFile(readyPath, []byte("ready"), 0600); err != nil {
		return err
	}
	defer os.Remove(readyPath)
	telemetry.Store(&rootSampleSource{stats, proxyTags, directTags})
	<-ctx.Done()
	return nil
}

// CheckRootConfig constructs and closes the real pinned core without Start:
// validate schema, outbounds, rules and resources without establishing TUN.
// @author 雾晚
func CheckRootConfig(configPath, assetsPath string) error {
	if os.Geteuid() != 0 {
		return fmt.Errorf("root required")
	}
	if err := os.Chdir(filepath.Dir(configPath)); err != nil {
		return err
	}
	externalAssetsPath = filepath.Clean(assetsPath) + string(os.PathSeparator)
	resourcePaths = append(resourcePaths, externalAssetsPath)
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	raw, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, raw); err != nil {
		return err
	}
	if options.Route != nil {
		if err = validateRootGeoResources(options.Route.RuleSet, assetsPath); err != nil {
			return err
		}
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
	return instance.Close()
}

// @author 雾晚: strict resource checks only for standalone module snapshots.
func validateRootGeoResources(rules []option.RuleSet, assetsPath string) error {
	// Module snapshots must not silently turn missing/corrupt geo assets
	// into empty rules. Keep the legacy converter for other App cores.
	for _, rule := range rules {
		if rule.Type != "local" {
			continue
		}
		code, isIP, legacy, ok := parseGeoRuleSetPath(rule.LocalOptions.Path)
		if !ok {
			continue
		}
		name := geositeDat
		if isIP {
			name = geoipDat
		}
		if !legacy {
			if _, e := os.Stat(filepath.Join(assetsPath, name[:len(name)-3]+"-"+code+".srs")); e == nil {
				continue
			}
		}
		var assetErr error
		if isIP {
			_, assetErr = loadGeoIPRules(filepath.Join(assetsPath, name), code)
		} else {
			_, assetErr = loadGeoSiteRules(filepath.Join(assetsPath, name), code)
		}
		if assetErr != nil {
			return fmt.Errorf("invalid module geo resource")
		}
	}

	return nil
}
