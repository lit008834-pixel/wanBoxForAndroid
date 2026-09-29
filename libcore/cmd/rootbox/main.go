// @author 雾晚
package main

import (
	"fmt"
	"libcore"
	"os"
	"strconv"
)

func main() {
	if len(os.Args) != 6 {
		fmt.Fprintln(os.Stderr, "usage: rootbox CONFIG ASSETS PID READY PARENT_PID")
		os.Exit(2)
	}
	parentPID, err := strconv.Atoi(os.Args[5])
	if err != nil || parentPID <= 1 {
		fmt.Fprintln(os.Stderr, "invalid parent PID")
		os.Exit(2)
	}
	if err := libcore.RunRootBox(os.Args[1], os.Args[2], os.Args[3], os.Args[4], parentPID); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
