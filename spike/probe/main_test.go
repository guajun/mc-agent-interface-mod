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
		"eof":                io.EOF,
		"unexpected eof":     io.ErrUnexpectedEOF,
		"closed":             net.ErrClosed,
		"wrapped eof":        fmt.Errorf("read hello: %w", io.EOF),
		"windows reset text": errors.New("wsarecv: An existing connection was forcibly closed by the remote host."),
		"posix reset text":   errors.New("read tcp: connection reset by peer"),
		"broken pipe text":   errors.New("write tcp: broken pipe"),
		"connection aborted": errors.New("read tcp: connection aborted"),
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
	}
}

func TestIsDisconnectRejectsTimeoutsAndProtocolErrors(t *testing.T) {
	cases := map[string]error{
		"timeout error":     timeoutError{},
		"wrapped timeout":   fmt.Errorf("read hello: %w", timeoutError{}),
		"os deadline":       fmt.Errorf("read: %w", os.ErrDeadlineExceeded),
		"json error":        errors.New("reply is not JSON: \"oops\""),
		"wrong packet type": errors.New("expected pong, got \"state\""),
		"write refused":     errors.New("write tcp: cannot assign requested address"),
		"plain failure":     errors.New("server did not close its side"),
		"nil":               nil,
	}
	for name, err := range cases {
		if isDisconnect(err) {
			t.Errorf("isDisconnect(%s) = true, want false", name)
		}
	}
}
