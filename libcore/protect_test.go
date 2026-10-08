//go:build linux || android

// @author 雾晚
package libcore

import (
	"io"
	"net"
	"os"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// @author 雾晚: real SCM_RIGHTS duplicates, not mocked descriptor ownership.
func protectExchange(t *testing.T, descriptors []int, callback func(int), success bool) {
	t.Helper()
	pair, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	connections := make([]*net.UnixConn, 0, 2)
	for _, fd := range pair {
		file := os.NewFile(uintptr(fd), "protect-test")
		conn, err := net.FileConn(file)
		file.Close()
		if err != nil {
			t.Fatal(err)
		}
		connections = append(connections, conn.(*net.UnixConn))
	}
	defer connections[0].Close()
	defer connections[1].Close()
	for _, conn := range connections {
		conn.SetDeadline(time.Now().Add(5 * time.Second))
	}
	done := make(chan struct{})
	go func() { defer close(done); handleProtectConn(connections[1], callback) }()
	if _, _, err := connections[0].WriteMsgUnix([]byte{1}, unix.UnixRights(descriptors...), nil); err != nil {
		t.Fatal(err)
	}
	ack := make([]byte, 1)
	n, readErr := connections[0].Read(ack)
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("protect handler did not finish")
	}
	if success {
		if readErr != nil || n != 1 || ack[0] != 1 {
			t.Fatalf("missing protect acknowledgement: n=%d err=%v", n, readErr)
		}
	} else if n != 0 || readErr != io.EOF {
		t.Fatalf("invalid request was acknowledged: n=%d err=%v", n, readErr)
	}
}

func TestProtectClosesReceivedCopyAndKeepsSender(t *testing.T) {
	for _, panicCallback := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "callback-panic"}[panicCallback], func(t *testing.T) {
			file, err := os.Open(os.DevNull)
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			received := -1
			protectExchange(t, []int{int(file.Fd())}, func(fd int) {
				received = fd
				if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
					t.Errorf("callback received invalid fd: %v", err)
				}
				if panicCallback {
					panic("synthetic callback failure")
				}
			}, !panicCallback)
			if received < 0 {
				t.Fatal("callback was not called")
			}
			if _, err := unix.FcntlInt(uintptr(received), unix.F_GETFD, 0); err != unix.EBADF {
				unix.Close(received) // clean up when demonstrating the old implementation's leak
				t.Fatalf("received SCM_RIGHTS copy leaked: %v", err)
			}
			if _, err := unix.FcntlInt(file.Fd(), unix.F_GETFD, 0); err != nil {
				t.Fatalf("sender's original was closed: %v", err)
			}
		})
	}
}

func TestProtectRepeatedAndInvalidRequestsDoNotLeak(t *testing.T) {
	file, err := os.Open(os.DevNull)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	count := func() int {
		entries, err := os.ReadDir("/proc/self/fd")
		if err != nil {
			t.Fatal(err)
		}
		return len(entries)
	}
	// Warm up Go's network poller before counting descriptors.
	protectExchange(t, []int{int(file.Fd())}, func(int) {}, true)
	before := count()
	for index := 0; index < 64; index++ {
		protectExchange(t, []int{int(file.Fd())}, func(int) {}, true)
	}
	for _, number := range []int{0, 2, 32} {
		fds := make([]int, number)
		for index := range fds {
			fds[index] = int(file.Fd())
		}
		protectExchange(t, fds, func(int) { t.Error("invalid descriptor count reached callback") }, false)
	}
	if after := count(); after != before {
		t.Fatalf("descriptor count grew: before=%d after=%d", before, after)
	}
}
