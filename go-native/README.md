# Native Go SSH core

This module builds the app-owned `libgojni` implementation.  It deliberately
uses open-source Go packages and contains no code or binary from any upstream app.

The data path is: raw Payload/HTTP 101 -> native Go SSH -> native Go SOCKS ->
Hev tun2socks.  Kotlin owns only lifecycle, profile storage and the VPN UI.
