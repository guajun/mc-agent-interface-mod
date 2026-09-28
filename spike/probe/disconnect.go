package main

import (
	"errors"
	"io"
	"net"
	"os"
	"strings"
)

// isDisconnect reports whether err is a peer-caused termination of the
// connection: EOF, a reset/abort, a broken pipe or an already closed socket.
//
// It deliberately rejects everything else. A read deadline (timeout), malformed
// JSON, an unexpected packet type or a local write failure against a live
// socket are probe failures, not evidence that the world closed, so
// -disconnect-ok must not turn them into ok=true.
//
// The text fallback exists only for raw platform socket errors that Go does
// not expose as errno constants. It is therefore restricted to a
// *net.OpError's underlying system error, never to our own protocol messages
// that might quote "connection reset" as data.
func isDisconnect(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, net.ErrClosed) {
		return true
	}
	// A timeout is the probe's own deadline firing, never a peer close.
	var timeout interface{ Timeout() bool }
	if errors.As(err, &timeout) && timeout.Timeout() {
		return false
	}
	if errors.Is(err, os.ErrDeadlineExceeded) {
		return false
	}
	for _, candidate := range resetErrors() {
		if errors.Is(err, candidate) {
			return true
		}
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) && opErr.Err != nil {
		text := strings.ToLower(opErr.Err.Error())
		for _, marker := range []string{
			"forcibly closed",
			"connection reset",
			"connection aborted",
			"broken pipe",
			"was aborted",
		} {
			if strings.Contains(text, marker) {
				return true
			}
		}
	}
	return false
}

// resetErrors returns the platform-specific errno values that mean the peer or
// the network tore the connection down.
func resetErrors() []error {
	return platformResetErrors()
}
