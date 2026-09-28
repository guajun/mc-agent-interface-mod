//go:build !windows

package main

import "syscall"

func platformResetErrors() []error {
	return []error{
		syscall.ECONNRESET,
		syscall.ECONNABORTED,
		syscall.EPIPE,
		syscall.ENOTCONN,
	}
}
