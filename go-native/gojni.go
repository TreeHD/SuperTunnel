package main

/*
#include <stdlib.h>
extern int GoProtectSocket(int fd) __attribute__((weak));
extern int GoBindSocketToNetwork(int fd) __attribute__((weak));
static int CallGoProtectSocket(int fd) {
    return GoProtectSocket ? GoProtectSocket(fd) : 0;
}
static int CallGoBindSocketToNetwork(int fd) {
    return GoBindSocketToNetwork ? GoBindSocketToNetwork(fd) : 0;
}
*/
import "C"

import (
	"bufio"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/crypto/ssh"
)

// Config is deliberately small: Kotlin renders NPV-style tokens before the
// native call, while this process owns every byte after the TCP connect.
type Config struct {
	GatewayHost         string        `json:"gatewayHost"`
	GatewayPort         int           `json:"gatewayPort"`
	Payload             string        `json:"payload"`
	PayloadParts        []PayloadPart `json:"payloadParts"`
	WaitForResponse     bool          `json:"waitForResponse"`
	ContinueOnAnyStatus bool          `json:"continueOnAnyStatus"`
	RawUpgradeMode      bool          `json:"rawUpgradeMode"`
	UseTLS              bool          `json:"useTls"`
	SNI                 string        `json:"sni"`
	SSHHost             string        `json:"sshHost"`
	SSHPort             int           `json:"sshPort"`
	Username            string        `json:"username"`
	Password            string        `json:"password"`
	PrivateKey          string        `json:"privateKey"`
	ProxyHost           string        `json:"proxyHost"`
	ProxyPort           int           `json:"proxyPort"`
	ProxyUser           string        `json:"proxyUser"`
	ProxyPass           string        `json:"proxyPass"`
	WebSocketPath       string        `json:"webSocketPath"`
	LocalSocksPort      int           `json:"localSocksPort"`
	KeepAliveSeconds    int           `json:"keepAliveSeconds"`
	BindInterface       string        `json:"bindInterface"`
}

// PayloadPart preserves NPV-style write boundaries. Data is Base64 because a
// payload may intentionally contain bytes which are not valid UTF-8.
type PayloadPart struct {
	Data    string `json:"data"`
	DelayMs int64  `json:"delayMs"`
}

type response struct {
	Port           int    `json:"port,omitempty"`
	Error          string `json:"error,omitempty"`
	ClientPayload  string `json:"clientPayload,omitempty"`
	ServerResponse string `json:"serverResponse,omitempty"`
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

// websocketConn presents RFC6455 binary frames as a byte stream to SSH. The
// raw-upgrade gateways used by NPV bypass this entirely; standard WebSocket
// gateways use it when RawUpgradeMode is disabled.
type websocketConn struct {
	net.Conn
	r       *bufio.Reader
	data    []byte
	pos     int
	writeMu sync.Mutex
}

func (c *websocketConn) Read(out []byte) (int, error) {
	for c.pos >= len(c.data) {
		first, err := c.r.ReadByte()
		if err != nil {
			return 0, err
		}
		second, err := c.r.ReadByte()
		if err != nil {
			return 0, err
		}
		opcode := first & 0x0f
		size := int64(second & 0x7f)
		if size == 126 {
			a, e := c.r.ReadByte()
			if e != nil {
				return 0, e
			}
			b, e := c.r.ReadByte()
			if e != nil {
				return 0, e
			}
			size = int64(a)<<8 | int64(b)
		}
		if size == 127 {
			size = 0
			for i := 0; i < 8; i++ {
				b, e := c.r.ReadByte()
				if e != nil {
					return 0, e
				}
				size = size<<8 | int64(b)
			}
		}
		if size < 0 || size > 16*1024*1024 {
			return 0, errors.New("websocket frame too large")
		}
		var mask [4]byte
		if second&0x80 != 0 {
			if _, err := io.ReadFull(c.r, mask[:]); err != nil {
				return 0, err
			}
		}
		frame := make([]byte, int(size))
		if _, err := io.ReadFull(c.r, frame); err != nil {
			return 0, err
		}
		if second&0x80 != 0 {
			for i := range frame {
				frame[i] ^= mask[i&3]
			}
		}
		switch opcode {
		case 0, 2:
			c.data, c.pos = frame, 0
		case 8:
			return 0, io.EOF
		case 9:
			_ = c.writeFrame(10, frame, true)
		}
	}
	n := copy(out, c.data[c.pos:])
	c.pos += n
	return n, nil
}
func (c *websocketConn) writeFrame(opcode byte, data []byte, final bool) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	first := opcode
	if final {
		first |= 0x80
	}
	header := []byte{first}
	n := len(data)
	if n < 126 {
		header = append(header, byte(0x80|n))
	} else if n <= 65535 {
		header = append(header, 0x80|126, byte(n>>8), byte(n))
	} else {
		header = append(header, 0x80|127)
		for i := 7; i >= 0; i-- {
			header = append(header, byte(uint64(n)>>(uint(i)*8)))
		}
	}
	var mask [4]byte
	if _, err := rand.Read(mask[:]); err != nil {
		return err
	}
	header = append(header, mask[:]...)
	masked := make([]byte, n)
	for i := range data {
		masked[i] = data[i] ^ mask[i&3]
	}
	if _, err := c.Conn.Write(header); err != nil {
		return err
	}
	_, err := c.Conn.Write(masked)
	return err
}
func (c *websocketConn) Write(data []byte) (int, error) {
	for offset := 0; offset < len(data); {
		size := len(data) - offset
		if size > 64*1024 {
			size = 64 * 1024
		}
		opcode := byte(2)
		if offset > 0 {
			opcode = 0
		}
		if err := c.writeFrame(opcode, data[offset:offset+size], offset+size == len(data)); err != nil {
			return offset, err
		}
		offset += size
	}
	return len(data), nil
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.r.Read(p) }

type tunnel struct {
	ssh      *ssh.Client
	listener net.Listener
	done     chan struct{}
	once     sync.Once
	stateMu  sync.RWMutex
	alive    bool
}

func (t *tunnel) stop() {
	t.once.Do(func() {
		close(t.done)
		t.stateMu.Lock()
		t.alive = false
		t.stateMu.Unlock()
		_ = t.listener.Close()
		_ = t.ssh.Close()
	})
}

func (t *tunnel) isAlive() bool {
	t.stateMu.RLock()
	defer t.stateMu.RUnlock()
	return t.alive
}

func (t *tunnel) watchTransport(keepAlive time.Duration) {
	// Wait returns whenever the SSH/WS transport closes, including a remote
	// reset. Closing the listener makes stale SOCKS requests fail quickly while
	// Kotlin rebuilds this transport at the same loopback address.
	done := make(chan struct{})
	go func() { _ = t.ssh.Wait(); close(done) }()
	if keepAlive <= 0 {
		<-done
	} else {
		ticker := time.NewTicker(keepAlive)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				goto closed
			case <-t.done:
				return
			case <-ticker.C:
				// This is an SSH transport keepalive, not user traffic. It does
				// not require a server reply; a write error is enough to mark a
				// reset transport unavailable without accumulating goroutines.
				if _, _, err := t.ssh.SendRequest("keepalive@openssh.com", false, nil); err != nil {
					goto closed
				}
			}
		}
	}
closed:
	t.stateMu.Lock()
	t.alive = false
	t.stateMu.Unlock()
	_ = t.listener.Close()
}

var current struct {
	sync.Mutex
	t *tunnel
}

func readHeader(r *bufio.Reader) (string, error) {
	var b strings.Builder
	for b.Len() < 64*1024 {
		line, err := r.ReadString('\n')
		if err != nil {
			return "", err
		}
		b.WriteString(line)
		if strings.HasSuffix(b.String(), "\r\n\r\n") {
			return b.String(), nil
		}
	}
	return "", errors.New("payload response header too large")
}

func logSafeHeader(value string) string {
	value = strings.ReplaceAll(value, "\r", "\\r")
	value = strings.ReplaceAll(value, "\n", "\\n")
	// Authentication material must not be copied from a profile into logs.
	for _, key := range []string{"Authorization:", "Proxy-Authorization:", "Cookie:"} {
		if index := strings.Index(strings.ToLower(value), strings.ToLower(key)); index >= 0 {
			end := strings.Index(value[index:], "\\r\\n")
			if end < 0 {
				end = len(value) - index
			}
			value = value[:index] + key + " <redacted>" + value[index+end:]
		}
	}
	if len(value) > 4096 {
		return value[:4096] + "…"
	}
	return value
}

func payloadConn(c Config) (net.Conn, string, string, error) {
	clientPayload, serverResponse := "", ""
	if c.GatewayHost == "" || c.GatewayPort < 1 || c.GatewayPort > 65535 {
		return nil, clientPayload, serverResponse, errors.New("invalid gateway")
	}
	dialHost, dialPort := c.GatewayHost, c.GatewayPort
	if c.ProxyHost != "" && c.ProxyPort > 0 {
		dialHost, dialPort = c.ProxyHost, c.ProxyPort
	}
	dialer := net.Dialer{Timeout: 30 * time.Second, KeepAlive: time.Duration(c.KeepAliveSeconds) * time.Second}
	// A TUN is established before this dial. Protecting the newly created fd
	// keeps the physical SSH/WebSocket connection outside that TUN.
	previousControl := dialer.Control
	dialer.Control = func(network, address string, rawConn syscall.RawConn) error {
		if previousControl != nil {
			if err := previousControl(network, address, rawConn); err != nil {
				return err
			}
		}
		var bound, protected bool
		if err := rawConn.Control(func(fd uintptr) {
			protected = C.CallGoProtectSocket(C.int(fd)) != 0
			// protect() changes the Android socket mark. Bind the explicit
			// physical Network afterwards, otherwise protect can erase its netId.
			bound = C.CallGoBindSocketToNetwork(C.int(fd)) != 0
		}); err != nil {
			return err
		}
		if !bound {
			return errors.New("Network.bindSocket(fd) failed")
		}
		if !protected {
			return errors.New("VpnService.protect(fd) failed")
		}
		return nil
	}
	raw, err := dialer.Dial("tcp", net.JoinHostPort(dialHost, fmt.Sprint(dialPort)))
	if err != nil {
		return nil, clientPayload, serverResponse, err
	}
	var conn net.Conn = raw
	if c.ProxyHost != "" && c.ProxyPort > 0 {
		auth := ""
		if c.ProxyUser != "" {
			auth = "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(c.ProxyUser+":"+c.ProxyPass)) + "\r\n"
		}
		request := fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\n%s\r\n", net.JoinHostPort(c.GatewayHost, fmt.Sprint(c.GatewayPort)), net.JoinHostPort(c.GatewayHost, fmt.Sprint(c.GatewayPort)), auth)
		if _, err := io.WriteString(conn, request); err != nil {
			_ = conn.Close()
			return nil, clientPayload, serverResponse, err
		}
		proxyReader := bufio.NewReaderSize(conn, 32*1024)
		header, err := readHeader(proxyReader)
		if err != nil || !strings.Contains(strings.SplitN(header, "\r\n", 2)[0], " 200 ") {
			_ = conn.Close()
			if err != nil {
				return nil, clientPayload, serverResponse, err
			}
			return nil, clientPayload, serverResponse, errors.New("HTTP proxy CONNECT rejected")
		}
		conn = &bufferedConn{Conn: conn, r: proxyReader}
	}
	if c.UseTLS {
		sni := c.SNI
		if sni == "" {
			sni = c.GatewayHost
		}
		tlsConn := tls.Client(conn, &tls.Config{ServerName: sni, InsecureSkipVerify: true, MinVersion: tls.VersionTLS12})
		if err := tlsConn.Handshake(); err != nil {
			_ = conn.Close()
			return nil, clientPayload, serverResponse, err
		}
		conn = tlsConn
	}
	if len(c.PayloadParts) == 0 && c.Payload == "" {
		path := c.WebSocketPath
		if path == "" {
			path = "/"
		}
		keyBytes := make([]byte, 16)
		_, _ = rand.Read(keyBytes)
		c.Payload = fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: %s\r\n\r\n", path, c.GatewayHost, base64.StdEncoding.EncodeToString(keyBytes))
		c.PayloadParts = []PayloadPart{{Data: base64.StdEncoding.EncodeToString([]byte(c.Payload))}}
	}
	var logPayload strings.Builder
	for index, part := range c.PayloadParts {
		if part.DelayMs < 0 || part.DelayMs > 5000 {
			_ = conn.Close()
			return nil, clientPayload, serverResponse, errors.New("invalid payload split delay")
		}
		if index > 0 && part.DelayMs > 0 {
			time.Sleep(time.Duration(part.DelayMs) * time.Millisecond)
		}
		bytes, err := base64.StdEncoding.DecodeString(part.Data)
		if err != nil {
			_ = conn.Close()
			return nil, clientPayload, serverResponse, errors.New("invalid payload part")
		}
		if len(bytes) > 65536 {
			_ = conn.Close()
			return nil, clientPayload, serverResponse, errors.New("payload part too large")
		}
		logPayload.Write(bytes)
		if _, err := conn.Write(bytes); err != nil {
			_ = conn.Close()
			return nil, clientPayload, serverResponse, err
		}
	}
	clientPayload = logSafeHeader(logPayload.String())
	if !c.WaitForResponse {
		return conn, clientPayload, "Server response: not requested", nil
	}
	reader := bufio.NewReaderSize(conn, 128*1024)
	header, err := readHeader(reader)
	if err != nil {
		_ = conn.Close()
		return nil, clientPayload, serverResponse, err
	}
	serverResponse = logSafeHeader(header)
	if !c.ContinueOnAnyStatus && !strings.Contains(strings.SplitN(header, "\r\n", 2)[0], " 101 ") {
		_ = conn.Close()
		return nil, clientPayload, serverResponse, fmt.Errorf("payload rejected: %s", strings.TrimSpace(strings.SplitN(header, "\r\n", 2)[0]))
	}
	buffered := &bufferedConn{Conn: conn, r: reader}
	// WSTunnel/NPV gateways commonly answer 101 but deliberately omit the
	// RFC6455 Sec-WebSocket-Accept header: after the HTTP Upgrade they expose a
	// raw SSH byte stream. Treat that response shape as raw even when a legacy
	// profile did not have its Raw Upgrade checkbox enabled.
	isRFC6455 := strings.Contains(strings.ToLower(header), "sec-websocket-accept:")
	if c.RawUpgradeMode || !isRFC6455 {
		return buffered, clientPayload, serverResponse, nil
	}
	if !strings.Contains(strings.SplitN(header, "\r\n", 2)[0], " 101 ") {
		_ = conn.Close()
		return nil, clientPayload, serverResponse, errors.New("WebSocket framing requires HTTP 101")
	}
	return &websocketConn{Conn: buffered, r: reader}, clientPayload, serverResponse, nil
}

func start(config Config) (*tunnel, int, string, string, error) {
	conn, clientPayload, serverResponse, err := payloadConn(config)
	if err != nil {
		return nil, 0, clientPayload, serverResponse, err
	}
	auth := []ssh.AuthMethod{ssh.Password(config.Password)}
	if config.PrivateKey != "" {
		signer, err := ssh.ParsePrivateKey([]byte(config.PrivateKey))
		if err != nil {
			_ = conn.Close()
			return nil, 0, clientPayload, serverResponse, err
		}
		auth = []ssh.AuthMethod{ssh.PublicKeys(signer)}
	}
	sshConfig := &ssh.ClientConfig{
		User:            config.Username,
		Auth:            auth,
		HostKeyCallback: ssh.InsecureIgnoreHostKey(), // host keys remain managed by the app profile layer during migration
		Timeout:         30 * time.Second,
	}
	host := config.SSHHost
	if host == "" {
		host = "127.0.0.1"
	}
	port := config.SSHPort
	if port == 0 {
		port = 22
	}
	cc, channels, requests, err := ssh.NewClientConn(conn, net.JoinHostPort(host, fmt.Sprint(port)), sshConfig)
	if err != nil {
		_ = conn.Close()
		return nil, 0, clientPayload, serverResponse, err
	}
	client := ssh.NewClient(cc, channels, requests)
	listenPort := config.LocalSocksPort
	if listenPort < 0 || listenPort > 65535 {
		_ = client.Close()
		return nil, 0, clientPayload, serverResponse, errors.New("invalid local SOCKS port")
	}
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", fmt.Sprint(listenPort)))
	if err != nil {
		_ = client.Close()
		return nil, 0, clientPayload, serverResponse, err
	}
	keepAlive := time.Duration(config.KeepAliveSeconds) * time.Second
	if keepAlive <= 0 {
		keepAlive = 30 * time.Second
	}
	t := &tunnel{ssh: client, listener: listener, done: make(chan struct{}), alive: true}
	go t.acceptLoop()
	go t.watchTransport(keepAlive)
	return t, listener.Addr().(*net.TCPAddr).Port, clientPayload, serverResponse, nil
}

func (t *tunnel) acceptLoop() {
	for {
		conn, err := t.listener.Accept()
		if err != nil {
			return
		}
		go t.handleSocks(conn)
	}
}

func (t *tunnel) handleSocks(conn net.Conn) {
	defer conn.Close()
	r := bufio.NewReader(conn)
	version, err := r.ReadByte()
	if err != nil || version != 5 {
		return
	}
	n, err := r.ReadByte()
	if err != nil || n == 0 {
		return
	}
	if _, err = io.CopyN(io.Discard, r, int64(n)); err != nil {
		return
	}
	if _, err = conn.Write([]byte{5, 0}); err != nil {
		return
	}
	head := make([]byte, 4)
	if _, err = io.ReadFull(r, head); err != nil || head[1] != 1 {
		return
	}
	var host string
	switch head[3] {
	case 1:
		ip := make([]byte, 4)
		if _, err = io.ReadFull(r, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	case 3:
		l, e := r.ReadByte()
		if e != nil {
			return
		}
		name := make([]byte, l)
		if _, err = io.ReadFull(r, name); err != nil {
			return
		}
		host = string(name)
	case 4:
		ip := make([]byte, 16)
		if _, err = io.ReadFull(r, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	default:
		return
	}
	p := make([]byte, 2)
	if _, err = io.ReadFull(r, p); err != nil {
		return
	}
	remote, err := t.ssh.Dial("tcp", net.JoinHostPort(host, fmt.Sprint(int(p[0])<<8|int(p[1]))))
	if err != nil {
		_, _ = conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	if _, err = conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	done := make(chan struct{})
	go func() { _, _ = io.Copy(remote, r); _ = remote.Close(); close(done) }()
	_, _ = io.Copy(conn, remote)
	<-done
}

//export GoSshStart
func GoSshStart(raw *C.char) *C.char {
	var config Config
	if err := json.Unmarshal([]byte(C.GoString(raw)), &config); err != nil {
		b, _ := json.Marshal(response{Error: err.Error()})
		return C.CString(string(b))
	}
	current.Lock()
	if current.t != nil {
		current.t.stop()
		current.t = nil
	}
	t, port, clientPayload, serverResponse, err := start(config)
	if err == nil {
		current.t = t
	}
	current.Unlock()
	if err != nil {
		b, _ := json.Marshal(response{Error: err.Error(), ClientPayload: clientPayload, ServerResponse: serverResponse})
		return C.CString(string(b))
	}
	b, _ := json.Marshal(response{Port: port, ClientPayload: clientPayload, ServerResponse: serverResponse})
	return C.CString(string(b))
}

//export GoSshStop
func GoSshStop() {
	current.Lock()
	if current.t != nil {
		current.t.stop()
		current.t = nil
	}
	current.Unlock()
}

//export GoSshIsAlive
func GoSshIsAlive() C.int {
	current.Lock()
	t := current.t
	current.Unlock()
	if t != nil && t.isAlive() {
		return 1
	}
	return 0
}

//export GoSshFree
func GoSshFree(value *C.char) { C.free(unsafe.Pointer(value)) }

func main() {}
