# Security policy

- Secrets must be stored encrypted with a Keystore-backed key; profiles only retain secret IDs.
- SSH host keys use trust-on-first-use and fail on a changed key.
- TLS uses system verification by default; insecure TLS is an explicit per-profile advanced option.
- Upstream physical sockets must call `VpnService.protect()` before `connect()` and bind to the current underlying network when available.
- Logs redact credentials, proxy authorization, private keys and raw payloads in release builds.
