//go:build windows

package main

import "syscall"

// WinSock error codes. Go's syscall package does not export the WSA* names, so
// the documented numeric values are used; the generic errnos are kept too.
const (
	wsaECONNRESET   = syscall.Errno(10054)
	wsaECONNABORTED = syscall.Errno(10053)
	wsaESHUTDOWN    = syscall.Errno(10058)
	wsaENETRESET    = syscall.Errno(10052)
	wsaENOTCONN     = syscall.Errno(10057)
)

func platformResetErrors() []error {
	return []error{
		wsaECONNRESET,
		wsaECONNABORTED,
		wsaESHUTDOWN,
		wsaENETRESET,
		wsaENOTCONN,
		syscall.ECONNRESET,
		syscall.ECONNABORTED,
		syscall.EPIPE,
		syscall.ENOTCONN,
	}
}
