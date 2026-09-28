package main

import (
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"testing"
)

type timeoutError struct{}

func (timeoutError) Error() string   { return "i/o timeout" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }

func TestIsDisconnectAcceptsPeerTermination(t *testing.T) {
	cases := map[string]error{
		"eof":            io.EOF,
		"unexpected eof": io.ErrUnexpectedEOF,
		"closed":         net.ErrClosed,
		"wrapped eof":    fmt.Errorf("read hello: %w", io.EOF),
		// Raw socket errors do not always map to a Go errno, so a net.OpError
		// whose underlying system error says reset/abort is still accepted.
		"wrapped system reset": &net.OpError{Op: "read", Net: "tcp",
			Err: errors.New("wsarecv: An existing connection was forcibly closed by the remote host.")},
		"wrapped system pipe": &net.OpError{Op: "write", Net: "tcp",
			Err: errors.New("write: broken pipe")},
	}
	for name, err := range cases {
		if !isDisconnect(err) {
			t.Errorf("isDisconnect(%s) = false, want true", name)
		}
	}
	for _, err := range resetErrors() {
		if !isDisconnect(err) {
			t.Errorf("isDisconnect(%v) = false, want true", err)
		}
		if !isDisconnect(&net.OpError{Op: "read", Net: "tcp", Err: err}) {
			t.Errorf("isDisconnect(wrapped %v) = false, want true", err)
		}
	}
}

func TestIsDisconnectRejectsProtocolErrorsEvenIfTheyQuoteResetText(t *testing.T) {
	cases := map[string]error{
		"json error quoting reset": errors.New("reply is not JSON: \"connection reset\""),
		"type error quoting abort": errors.New("expected state, got \"connection aborted\""),
		"wrapped protocol reset":   fmt.Errorf("STATE during hold: %w", errors.New("connection reset by peer")),
		"marker description":       errors.New("server did not close its side"),
		"write refused":            errors.New("write tcp: cannot assign requested address"),
		"wrapped write refused":    fmt.Errorf("MARK: %w", errors.New("write tcp: cannot assign requested address")),
	}
	for name, err := range cases {
		if isDisconnect(err) {
			t.Errorf("isDisconnect(%s) = true, want false", name)
		}
	}
}

func TestIsDisconnectRejectsTimeouts(t *testing.T) {
	cases := map[string]error{
		"timeout error":   timeoutError{},
		"wrapped timeout": fmt.Errorf("read hello: %w", timeoutError{}),
		"os deadline":     fmt.Errorf("read: %w", os.ErrDeadlineExceeded),
		"op timeout":      &net.OpError{Op: "read", Net: "tcp", Err: timeoutError{}},
		"nil":             nil,
	}
	for name, err := range cases {
		if isDisconnect(err) {
			t.Errorf("isDisconnect(%s) = true, want false", name)
		}
	}
}
