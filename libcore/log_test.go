// @author 雾晚
package libcore

import (
	"bytes"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"
	"testing"
)

// Synthetic data only; verify the writer contract and disk bound. @author 雾晚
func TestFileLogWriterBound(t *testing.T) {
	for _, size := range []int{1, 64, 512} {
		t.Run(strconv.Itoa(size), func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "test.log")
			flags := os.O_CREATE | os.O_RDWR
			// Windows append-only handles cannot truncate. Linux/Android use the production flags.
			if runtime.GOOS != "windows" {
				flags = os.O_CREATE | os.O_APPEND | os.O_WRONLY
			}
			file, err := os.OpenFile(path, flags, 0600)
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			writer := fileLogWriter{file: file, maxSize: int64(size)}
			payload := append(bytes.Repeat([]byte("x"), 4096), []byte("tail")...)
			n, err := writer.Write(payload)
			if err != nil || n != len(payload) {
				t.Fatalf("consumed=%d, error=%v", n, err)
			}
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(data, payload[len(payload)-size:]) {
				t.Fatalf("oversized log retained %d bytes, limit %d", len(data), size)
			}
			var workers sync.WaitGroup
			for i := 0; i < 8; i++ {
				workers.Add(1)
				go func() {
					defer workers.Done()
					for j := 0; j < 50; j++ {
						if _, e := writer.Write([]byte("sample\n")); e != nil {
							t.Error(e)
							return
						}
					}
				}()
			}
			workers.Wait()
			info, err := file.Stat()
			if err != nil || info.Size() > int64(size) {
				t.Fatalf("bound violated: %v %v", info, err)
			}
			writer.Truncate()
			info, err = file.Stat()
			if err != nil || info.Size() != 0 {
				t.Fatal("clear failed", err)
			}
		})
	}
}

func TestFileLogWriterDisabledAndClosed(t *testing.T) {
	file, err := os.OpenFile(filepath.Join(t.TempDir(), "test.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	writer := fileLogWriter{file: file, maxSize: 64, disabled: true}
	if n, e := writer.Write([]byte("ignored")); n != 7 || e != nil {
		t.Fatal(n, e)
	}
	info, _ := file.Stat()
	if info.Size() != 0 {
		t.Fatal("disabled writer wrote data")
	}
	file.Close()
	writer.disabled = false
	if _, e := writer.Write([]byte("failure")); e == nil {
		t.Fatal("closed file reported success")
	}
}
