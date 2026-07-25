# Architecture

`TunnelVpnService` owns connection state and all socket/TUN/native lifetimes. Compose is an observer only.

The upstream path is intentionally composable: protected TCP dial → optional HTTP CONNECT → optional payload → optional TLS/WebSocket → SSH transport. The SOCKS listener must bind only to `127.0.0.1`; a native tun2socks bridge receives the TUN fd only after SSH authentication succeeds.

This repository is clean-room work. It does not reuse SuperTunnel code, identifiers, assets, formats, or keys.
