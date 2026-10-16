//go:build parity

package parity

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"
)

// daemonStartupTimeout bounds the wait for the daemon's handshake line and
// for its socket to accept connections. A cold JVM start on a CI runner is
// well inside this.
const daemonStartupTimeout = 60 * time.Second

// maxGRPCMsgSize is the per-message limit for the daemon connection. The
// bridge streams whole documents inline, so the default 4 MB cap is too low.
const maxGRPCMsgSize = 256 << 20

// Handshake is the JSON envelope a plugin daemon prints as its first stdout
// line (plugin protocol v1, Mode C). The daemon keeps stdout open afterwards
// and writes only logs to it.
type Handshake struct {
	// Socket is the absolute path to the Unix socket the daemon bound to.
	Socket string `json:"socket"`
	// Version is the daemon's own version string.
	Version string `json:"version"`
}

// DaemonClient is a running okapi-bridge daemon and the gRPC connection to it.
type DaemonClient struct {
	// Conn is the gRPC client connection to the daemon's Unix socket.
	Conn *grpc.ClientConn
	// Socket is the path the daemon announced in its handshake.
	Socket string
	// Version is the version string from the handshake.
	Version string

	cmd       *exec.Cmd
	drainDone chan struct{}
}

// PID returns the daemon's process id.
func (c *DaemonClient) PID() int {
	if c.cmd == nil || c.cmd.Process == nil {
		return 0
	}
	return c.cmd.Process.Pid
}

// Close terminates the daemon: SIGTERM, a grace period, then SIGKILL.
func (c *DaemonClient) Close() {
	if c.Conn != nil {
		_ = c.Conn.Close()
	}
	if c.cmd == nil || c.cmd.Process == nil {
		return
	}
	_ = c.cmd.Process.Signal(os.Interrupt)
	done := make(chan struct{})
	go func() {
		<-c.drainDone
		_ = c.cmd.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		_ = c.cmd.Process.Kill()
		<-done
	}
	if c.Socket != "" {
		_ = os.Remove(c.Socket)
	}
}

// daemonState owns the singleton daemon shared by every parity test in one
// binary invocation.
type daemonState struct {
	once     sync.Once
	client   *DaemonClient
	initErr  error
	shutdown sync.Once
}

var bridgeDaemon = &daemonState{}

// AcquireBridgeDaemon returns the shared okapi-bridge daemon client. The
// daemon is spawned from the sandbox the first time this is called and reused
// by every later caller in the same test binary.
func AcquireBridgeDaemon(t *testing.T) *DaemonClient {
	t.Helper()
	s := RequireSandbox(t)

	bridgeDaemon.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), daemonStartupTimeout)
		defer cancel()
		client, err := StartDaemon(ctx, s)
		if err != nil {
			bridgeDaemon.initErr = err
			return
		}
		bridgeDaemon.client = client
		if testing.Verbose() {
			fmt.Fprintf(os.Stderr, "[parity-daemon] spawned okapi-bridge at %s (pid %d)\n", client.Socket, client.PID())
		}
	})
	if bridgeDaemon.initErr != nil {
		t.Fatalf("acquire bridge daemon: %v", bridgeDaemon.initErr)
	}
	return bridgeDaemon.client
}

// ShutdownBridgeDaemon stops the shared daemon. Each test package's TestMain
// calls it after m.Run so the JVM is released between `go test` invocations.
func ShutdownBridgeDaemon() {
	bridgeDaemon.shutdown.Do(func() {
		if bridgeDaemon.client != nil {
			bridgeDaemon.client.Close()
		}
	})
}

// StartDaemon launches the okapi-bridge launcher installed in the sandbox with
// the `daemon` argument, reads its handshake, and dials the announced socket.
// The subprocess is started without a context so the daemon outlives the
// startup deadline; Close tears it down.
func StartDaemon(ctx context.Context, s *Sandbox) (*DaemonClient, error) {
	if _, err := os.Stat(s.OkapiBridgeBinary); err != nil {
		return nil, fmt.Errorf("okapi-bridge launcher missing at %s: %w", s.OkapiBridgeBinary, err)
	}
	cmd := exec.Command(s.OkapiBridgeBinary, "daemon") //nolint:noctx // long-lived daemon subprocess
	cmd.Env = append(os.Environ(),
		"KAPI_PLUGIN_DIR="+s.OkapiBridgeDir,
		"KAPI_PLUGIN_NAME=okapi-bridge",
	)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("daemon stdout pipe: %w", err)
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start %s: %w", s.OkapiBridgeBinary, err)
	}

	type handshakeResult struct {
		hs  Handshake
		err error
	}
	br := bufio.NewReader(stdout)
	hsCh := make(chan handshakeResult, 1)
	go func() {
		line, err := br.ReadString('\n')
		if err != nil && line == "" {
			hsCh <- handshakeResult{err: fmt.Errorf("read handshake: %w", err)}
			return
		}
		var hs Handshake
		if err := json.Unmarshal([]byte(strings.TrimSpace(line)), &hs); err != nil {
			hsCh <- handshakeResult{err: fmt.Errorf("parse handshake %q: %w", strings.TrimSpace(line), err)}
			return
		}
		hsCh <- handshakeResult{hs: hs}
	}()

	kill := func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}
	var hs Handshake
	select {
	case <-ctx.Done():
		kill()
		return nil, fmt.Errorf("okapi-bridge daemon: handshake timeout after %s", daemonStartupTimeout)
	case res := <-hsCh:
		if res.err != nil {
			kill()
			return nil, fmt.Errorf("okapi-bridge daemon: %w", res.err)
		}
		hs = res.hs
	}
	if hs.Socket == "" {
		kill()
		return nil, errors.New("okapi-bridge daemon: handshake missing socket")
	}

	// Everything the daemon prints after the handshake is a log line.
	drainDone := make(chan struct{})
	go func() {
		defer close(drainDone)
		for {
			line, err := br.ReadString('\n')
			if line != "" {
				fmt.Fprintf(os.Stderr, "[okapi-bridge] %s", line)
			}
			if err != nil {
				return
			}
		}
	}()

	conn, err := dialUnixSocket(ctx, hs.Socket)
	if err != nil {
		kill()
		return nil, fmt.Errorf("okapi-bridge daemon: dial %s: %w", hs.Socket, err)
	}
	return &DaemonClient{
		Conn:      conn,
		Socket:    hs.Socket,
		Version:   hs.Version,
		cmd:       cmd,
		drainDone: drainDone,
	}, nil
}

// dialUnixSocket opens a gRPC connection over a Unix socket and waits until
// it is ready, so a daemon that announced a socket it does not serve fails
// here rather than on the first RPC.
func dialUnixSocket(ctx context.Context, socket string) (*grpc.ClientConn, error) {
	conn, err := grpc.NewClient("unix://"+socket,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(maxGRPCMsgSize),
			grpc.MaxCallSendMsgSize(maxGRPCMsgSize),
		),
	)
	if err != nil {
		return nil, err
	}
	conn.Connect()
	for {
		st := conn.GetState()
		switch st {
		case connectivity.Ready:
			return conn, nil
		case connectivity.TransientFailure, connectivity.Shutdown:
			_ = conn.Close()
			return nil, fmt.Errorf("connection entered %s", st)
		}
		if !conn.WaitForStateChange(ctx, st) {
			_ = conn.Close()
			return nil, ctx.Err()
		}
	}
}
