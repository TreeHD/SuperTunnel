package me.treexhd.supertunnel.domain.state

enum class TunnelStage { IDLE, VALIDATING, REQUESTING_VPN_PERMISSION, STARTING_SERVICE, CONNECTING_TCP, PROXY_HANDSHAKE, TLS_HANDSHAKE, PAYLOAD_HANDSHAKE, WEBSOCKET_HANDSHAKE, SSH_KEY_EXCHANGE, VERIFYING_HOST_KEY, SSH_AUTHENTICATING, STARTING_SOCKS, ESTABLISHING_TUN, STARTING_TUN2SOCKS, CONNECTED, RECONNECTING, STOPPING, ERROR }
data class TunnelFailure(val code: String, val stage: TunnelStage, val userMessage: String, val technicalMessage: String = "", val recoverable: Boolean = false)
data class TunnelState(val stage: TunnelStage = TunnelStage.IDLE, val profileName: String? = null, val startedAt: Long? = null, val failure: TunnelFailure? = null)
data class TunnelTraffic(val uploadedBytes: Long = 0L, val downloadedBytes: Long = 0L)
