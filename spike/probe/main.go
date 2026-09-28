// Command probe is the issue #7 same-port control probe.
//
// It opens one plain TCP connection to the Minecraft game port, writes the
// spike's control marker, and then speaks the JSON-lines protocol: the hello
// line the mod pushes first, PING, STATE, an optional hold period that keeps
// polling STATE, a final MARK, and a half-close that expects the server to
// close its side. It never performs a Minecraft handshake, so if the spike is
// not armed the connection is rejected by vanilla instead.
//
// Two of these processes must be started independently; nothing is shared
// between them, which is what proves two daemon-shaped clients can coexist on
// the one game port.
//
//	go run ./spike/probe -addr 127.0.0.1:25565 -name A -hold 20s
//	go run ./spike/probe -addr 127.0.0.1:25565 -name B -hold 20s
//
// Machine-readable lines: EVENT {...} during the run, RESULT {...} at the end.
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

const defaultMagic = "MCAGENT-CONTROL/1\n"

type event struct {
	Event   string `json:"event"`
	Name    string `json:"name"`
	Local   string `json:"local,omitempty"`
	Remote  string `json:"remote,omitempty"`
	Millis  int64  `json:"millis"`
	Players *int   `json:"players,omitempty"`
	Control *int   `json:"control_sessions,omitempty"`
	Tick    *int64 `json:"tick,omitempty"`
	Note    string `json:"note,omitempty"`
}

func main() {
	addr := flag.String("addr", "127.0.0.1:25565", "game host:port to dial")
	name := flag.String("name", "probe", "probe name used in MARK and output")
	hold := flag.Duration("hold", 0, "keep polling STATE for this long after the initial checks")
	poll := flag.Duration("poll", time.Second, "STATE poll interval while holding")
	timeout := flag.Duration("timeout", 5*time.Second, "per-operation dial/read/write timeout")
	magic := flag.String("magic", defaultMagic, "control marker")
	transport := flag.String("transport", "same-port-spike", "expected hello transport")
	disconnectOK := flag.Bool("disconnect-ok", false,
		"treat a mid-hold disconnect as a recorded event instead of a failure (world-close test)")
	eventWait := flag.Duration("event-wait", 2*time.Second,
		"how long to wait for a pushed event after MARK before closing")
	flag.Parse()

	result := map[string]any{
		"name": *name,
		"addr": *addr,
	}
	// Pushed event lines are skipped by request() but recorded, so the driver
	// can verify that the same-port session really receives EventSink events
	// (the hello advertises events:chat/events:game).
	events := []string{}
	fail := func(err error) {
		result["ok"] = false
		result["error"] = err.Error()
		emit("RESULT " + mustJSON(result))
		fmt.Fprintf(os.Stderr, "ERROR %s: %v\n", *name, err)
		os.Exit(1)
	}

	conn, err := net.DialTimeout("tcp", *addr, *timeout)
	if err != nil {
		fail(fmt.Errorf("dial %s: %w", *addr, err))
	}
	defer conn.Close()
	local := conn.LocalAddr().String()
	result["local"] = local
	emitEvent(event{Event: "dial", Name: *name, Local: local, Remote: conn.RemoteAddr().String()})

	reader := bufio.NewReaderSize(conn, 1<<20)

	if _, err := conn.Write([]byte(*magic)); err != nil {
		fail(fmt.Errorf("write marker: %w", err))
	}

	hello, rawHello, err := request(conn, reader, "", *timeout, &events)
	if err != nil {
		fail(fmt.Errorf("read hello: %w", err))
	}
	result["hello"] = rawHello
	if kind := stringField(hello, "type"); kind != "hello" {
		fail(fmt.Errorf("expected hello, got %q: %s", kind, rawHello))
	}
	if got := stringField(hello, "transport"); got != *transport {
		fail(fmt.Errorf("expected transport %q, got %q: %s", *transport, got, rawHello))
	}
	if got := stringField(hello, "instance"); got != "server" {
		fail(fmt.Errorf("expected instance server, got %q", got))
	}
	result["hello_port"] = intField(hello, "port")

	// PING
	pong, rawPong, err := request(conn, reader, "PING", *timeout, &events)
	if err != nil {
		fail(fmt.Errorf("PING: %w", err))
	}
	if kind := stringField(pong, "type"); kind != "pong" {
		fail(fmt.Errorf("expected pong, got %q: %s", kind, rawPong))
	}
	result["ping"] = true

	pollState := func() (map[string]any, string, error) {
		state, rawState, err := request(conn, reader, "STATE", *timeout, &events)
		if err != nil {
			return nil, rawState, err
		}
		if kind := stringField(state, "type"); kind != "state" {
			return nil, rawState, fmt.Errorf("expected state, got %q: %s", kind, rawState)
		}
		return state, rawState, nil
	}

	state, rawState, err := pollState()
	if err != nil {
		fail(fmt.Errorf("initial STATE: %w", err))
	}
	result["initial_state"] = rawState
	players := intField(state, "players")
	sessions := controlSessions(state)
	result["initial_players"] = players
	result["initial_control_sessions"] = sessions
	emitEvent(event{Event: "state", Name: *name, Local: local, Players: intPtr(players),
		Control: intPtr(sessions), Tick: int64Ptr(int64Field(state, "tick"))})

	// The EventSink broadcasts from its own writer thread, so an event can
	// arrive shortly after the reply to the request that caused it. MARK and
	// then wait for the pushed line, which also makes the capability claim in
	// the hello observable.
	if _, _, err := request(conn, reader, "MARK control-probe-"+*name+" ready", *timeout, &events); err != nil {
		fail(fmt.Errorf("MARK ready: %w", err))
	}
	drainEvents(conn, reader, *eventWait, &events)

	polls := []map[string]any{}
	maxSessions := sessions
	minPlayers := players
	maxPlayers := players

	// A mid-hold disconnect is only a recorded observation for a genuine peer
	// termination; timeouts and protocol errors stay failures. The statistics
	// gathered before the disconnect are kept either way.
	recordDisconnect := func(err error) {
		result["disconnected"] = true
		result["disconnect_error"] = err.Error()
		result["ok"] = true
		result["poll_count"] = len(polls)
		if len(polls) > 0 {
			result["polls"] = polls
		}
		result["players_min"] = minPlayers
		result["players_max"] = maxPlayers
		result["control_sessions_max"] = maxSessions
		result["events_seen"] = events
		emitEvent(event{Event: "disconnect", Name: *name, Local: local, Note: err.Error()})
		emit("RESULT " + mustJSON(result))
	}

	deadline := time.Now().Add(*hold)
	for time.Now().Before(deadline) {
		if *poll > 0 {
			time.Sleep(*poll)
		}
		state, rawState, err := pollState()
		if err != nil {
			if *disconnectOK && isDisconnect(err) {
				recordDisconnect(err)
				return
			}
			fail(fmt.Errorf("STATE during hold: %w", err))
		}
		players = intField(state, "players")
		sessions = controlSessions(state)
		tick := int64Field(state, "tick")
		if sessions > maxSessions {
			maxSessions = sessions
		}
		if players < minPlayers {
			minPlayers = players
		}
		if players > maxPlayers {
			maxPlayers = players
		}
		polls = append(polls, map[string]any{
			"millis": time.Now().UnixMilli(), "players": players,
			"control_sessions": sessions, "tick": tick, "raw": rawState,
		})
		emitEvent(event{Event: "poll", Name: *name, Local: local, Players: intPtr(players),
			Control: intPtr(sessions), Tick: int64Ptr(tick)})
	}
	if len(polls) > 0 {
		result["polls"] = polls
	}
	result["players_min"] = minPlayers
	result["players_max"] = maxPlayers
	result["control_sessions_max"] = maxSessions
	result["events_seen"] = events

	// Final marker, then half-close and require the server to close its side.
	if _, _, err := request(conn, reader, "MARK control-probe-"+*name+" closing", *timeout, &events); err != nil {
		if *disconnectOK && isDisconnect(err) {
			recordDisconnect(err)
			return
		}
		fail(fmt.Errorf("MARK: %w", err))
	}
	result["mark"] = true
	result["events_seen"] = events

	if tcp, ok := conn.(*net.TCPConn); ok {
		if err := tcp.CloseWrite(); err != nil {
			fail(fmt.Errorf("half close: %w", err))
		}
	} else {
		if err := conn.Close(); err != nil {
			fail(fmt.Errorf("close: %w", err))
		}
	}

	// After the half close the peer may still flush event lines before its
	// FIN; drain until EOF and tolerate them. EOF is the only clean close.
	conn.SetReadDeadline(time.Now().Add(*timeout))
	serverClosed := false
	var readErr error
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			readErr = err
			serverClosed = errors.Is(err, io.EOF)
			break
		}
		var message map[string]any
		if json.Unmarshal([]byte(line), &message) == nil {
			if pushed, _ := message["event"].(bool); pushed {
				events = append(events, stringField(message, "type"))
			}
		}
	}
	result["server_closed"] = serverClosed
	result["events_seen"] = events
	if !serverClosed {
		fail(fmt.Errorf("server did not close its side: %w", readErr))
	}

	result["ok"] = true
	emit("RESULT " + mustJSON(result))
}

// drainEvents reads for window and records pushed event types; a read deadline
// simply ends the wait and leaves the reader usable for later requests.
func drainEvents(conn net.Conn, reader *bufio.Reader, window time.Duration, events *[]string) {
	if window <= 0 {
		return
	}
	conn.SetReadDeadline(time.Now().Add(window))
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return
		}
		var message map[string]any
		if json.Unmarshal([]byte(line), &message) != nil {
			continue
		}
		if pushed, ok := message["event"].(bool); ok && pushed && events != nil {
			*events = append(*events, stringField(message, "type"))
		}
	}
}

// request writes one request line (unless it is the empty marker read) and
// returns the next reply object, skipping pushed event lines. Event type names
// are appended to events when it is not nil.
func request(conn net.Conn, reader *bufio.Reader, line string, timeout time.Duration,
	events *[]string) (map[string]any, string, error) {
	if line != "" {
		conn.SetWriteDeadline(time.Now().Add(timeout))
		if _, err := conn.Write([]byte(line + "\n")); err != nil {
			return nil, "", err
		}
	}
	for {
		conn.SetReadDeadline(time.Now().Add(timeout))
		raw, err := reader.ReadString('\n')
		if err != nil {
			return nil, raw, err
		}
		var message map[string]any
		if err := json.Unmarshal([]byte(raw), &message); err != nil {
			return nil, raw, fmt.Errorf("reply is not JSON: %q", raw)
		}
		if pushed, ok := message["event"].(bool); ok && pushed {
			if events != nil {
				*events = append(*events, stringField(message, "type"))
			}
			continue
		}
		return message, raw, nil
	}
}

func controlSessions(state map[string]any) int {
	spike, ok := state["samePortSpike"].(map[string]any)
	if !ok {
		return -1
	}
	return intField(spike, "sessions")
}

func stringField(object map[string]any, key string) string {
	value, _ := object[key].(string)
	return value
}

func intField(object map[string]any, key string) int {
	value, _ := object[key].(float64)
	return int(value)
}

func int64Field(object map[string]any, key string) int64 {
	value, _ := object[key].(float64)
	return int64(value)
}

func intPtr(value int) *int { return &value }

func int64Ptr(value int64) *int64 { return &value }

func emitEvent(event event) {
	event.Millis = time.Now().UnixMilli()
	emit("EVENT " + mustJSON(event))
}

func mustJSON(value any) string {
	data, err := json.Marshal(value)
	if err != nil {
		return fmt.Sprintf("%q", fmt.Sprintf("%v", value))
	}
	return string(data)
}

func emit(line string) {
	fmt.Println(line)
}
