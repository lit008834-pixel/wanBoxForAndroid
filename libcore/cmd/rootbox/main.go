// @author 雾晚
package main

import (
	"fmt"
	"libcore"
	"os"
	"strconv"
)

func main() {
	if len(os.Args) == 4 && os.Args[1] == "--check" {
		if err := libcore.CheckRootConfig(os.Args[2], os.Args[3]); err != nil {
			fmt.Fprintln(os.Stderr, "core_config_invalid")
			os.Exit(1)
		}
		return
	}
	if len(os.Args) != 7 {
		fmt.Fprintln(os.Stderr, "usage: rootbox CONFIG ASSETS PID READY STOP SUPERVISOR_PID")
		os.Exit(2)
	}
	supervisorPID, err := strconv.Atoi(os.Args[6])
	if err != nil || supervisorPID <= 1 {
		fmt.Fprintln(os.Stderr, "invalid supervisor PID")
		os.Exit(2)
	}
	if err := libcore.RunRootBox(os.Args[1], os.Args[2], os.Args[3], os.Args[4], os.Args[5], supervisorPID); err != nil {
		fmt.Fprintln(os.Stderr, "core_runtime_failed")
		os.Exit(1)
	}
}
