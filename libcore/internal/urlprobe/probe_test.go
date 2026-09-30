// @author 雾晚
package urlprobe

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestSingleRequestIncludesSetup(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		time.Sleep(40 * time.Millisecond)
		w.WriteHeader(204)
	}))
	defer server.Close()
	ms, err := Measure(server.Client(), server.URL, time.Second)
	if err != nil || ms < 40 || calls.Load() != 1 {
		t.Fatalf("ms=%d calls=%d err=%v", ms, calls.Load(), err)
	}
}

func TestTimeoutDoesNotRetry(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		<-r.Context().Done()
	}))
	defer server.Close()
	start := time.Now()
	_, err := Measure(server.Client(), server.URL, 80*time.Millisecond)
	if err == nil || time.Since(start) > time.Second || calls.Load() != 1 {
		t.Fatalf("calls=%d err=%v", calls.Load(), err)
	}
}

func TestHTTPFailureAndUntrustedTLS(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(503)
	}))
	defer server.Close()
	if _, err := Measure(server.Client(), server.URL, time.Second); err == nil {
		t.Fatal("accepted HTTP 503")
	}
	if _, err := Measure(http.DefaultClient, server.URL, time.Second); err == nil {
		t.Fatal("accepted untrusted TLS")
	}
}
