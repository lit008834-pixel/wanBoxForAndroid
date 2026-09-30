// @author 雾晚
package urlprobe

import (
	"context"
	"fmt"
	"net/http"
	"time"
)

// Measure includes DNS, dialing, TLS and response headers in one bounded GET.
// It never hides setup latency with a second warmed request or another endpoint.
func Measure(client *http.Client, link string, timeout time.Duration) (int32, error) {
	return MeasureContext(context.Background(), client, link, timeout)
}

// MeasureContext also observes service shutdown and group cancellation.
func MeasureContext(parent context.Context, client *http.Client, link string, timeout time.Duration) (int32, error) {
	if timeout <= 0 {
		return 0, fmt.Errorf("invalid probe timeout")
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	elapsed := time.Since(start).Milliseconds()
	if elapsed < 1 {
		elapsed = 1
	}
	return int32(elapsed), nil
}
