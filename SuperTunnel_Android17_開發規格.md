# Android 17 SuperTunnel App 開發規格

> 目標：以 **clean-room** 方式重做一款功能類似 SuperTunnel 的 Android VPN／SuperTunnel App。
>
> 本專案只實作 **SSH、TLS、HTTP Proxy、Payload、Proxy-Payload、WebSocket、UDPGW** 等相關能力，**完全移除 V2Ray、VMess、VLESS、Trojan、Shadowsocks 與其訂閱格式**。

---

## 1. 給開發 Agent 的直接指令

請直接建立可編譯、可安裝、可連線的 Android 專案，不要只做 UI Mockup。

核心要求：

- 使用 Kotlin。
- 使用 Jetpack Compose + Material 3。
- `minSdk = 26`。
- `compileSdk = 37`。
- `targetSdk = 37`。
- 支援 Android 8 至 Android 17，主要驗收環境為 Android 14、15、16、17。
- App 必須透過 `VpnService` 建立真正的裝置層 VPN，不可只設定 Android HTTP Proxy。
- SSH 上游連線必須避開自己的 VPN，所有實體網路 Socket 都要使用 `VpnService.protect()`，並在可行時綁定目前的 underlying `Network`。
- 所有長時間連線由前景 VPN Service 管理，UI 不得直接持有 SSH、Socket、TUN 或 Native Engine 的生命週期。
- 不加入廣告、分析 SDK、追蹤 SDK或不必要的雲端服務。
- 不複製 SuperTunnel 的程式碼、品牌、Logo、套件名稱、加密格式或專有素材；截圖只作為功能與資訊架構參考。
- 不實作或鼓勵繞過電信計費。Payload 功能定位為連接使用者有權使用的 HTTP Proxy、Reverse Proxy、WebSocket Bridge、TLS Tunnel 或自有伺服器。

專案暫定名稱可使用：

- App 顯示名稱：`SuperTunnel`
- Android package：`me.treexhd.supertunnel`，之後再由使用者替換。
- 匯出設定副檔名：`.shtprofile`

---

## 2. 專案目標

### 2.1 必須完成

1. 建立全裝置或指定 App 的 VPN Tunnel。
2. 將 TUN 的 TCP 流量送入本機 SOCKS5，再透過 SSH `direct-tcpip` channel 出口上網。
3. 支援以下連線模式：
   - SSH-Direct
   - SSH-Proxy
   - SSH-Payload
   - SSH-Proxy-Payload
   - SSH-TLS
   - SSH-TLS-Proxy
   - SSH-TLS-Payload
   - SSH-TLS-Proxy-Payload
4. 支援 SSH 密碼與私鑰登入。
5. 支援 SSH host key 驗證與 known-hosts。
6. 支援 HTTP CONNECT Proxy 與 Proxy Basic Authentication。
7. 支援自訂 HTTP Payload、Payload Generator、Split／Delay Split。
8. 支援真正的 WebSocket framing，不可只送 Upgrade Header 後直接傳送裸 SSH bytes。
9. 支援 UDPGW，預設遠端位址 `127.0.0.1:7300`。
10. 支援自訂 DNS、UDPGW Transparent DNS。
11. 支援設定匯入、匯出、剪貼簿匯入、QR Code 匯入與匯出。
12. 支援即時連線 Log、錯誤階段、上下載流量與連線時間。
13. 支援網路切換後自動重連。
14. 支援 Always-on VPN 基本行為。
15. Native library 必須支援 16 KB page size。

### 2.2 第二階段

- Remote SSH profile subscription，不使用任何 V2Ray subscription 格式。
- SSH keyboard-interactive authentication。
- 每個設定獨立的 App allowlist／denylist。
- TLS custom CA、憑證 pinning。
- HTTP Proxy Digest Authentication。
- IPv6 完整轉送。
- Quick Settings Tile。
- Android TV／平板 adaptive UI。
- SSH-DNSTT。

### 2.3 不做

- V2Ray Core。
- VMess、VLESS、Trojan、Shadowsocks。
- Xray、sing-box 或 Clash Core。
- Terminal Emulator。
- SFTP 檔案管理器。
- 手機 Root 功能。
- Wi-Fi Hotspot／USB tethering 流量強制走 VPN。
- iOS。
- 自動建立或販售 SSH 帳號。
- 相容未知且可能加密的 `.npvt` 私有格式。

---

## 3. 從參考畫面保留的 UX

### 3.1 主導覽

建議底部保留四個頁面：

1. **Home**
2. **Configs**
3. **Logs**
4. **More / Settings**

不保留原本以 V2Ray 為主的 `Subs`。若第二階段加入 SSH profile subscription，再新增 `Subscriptions`。

### 3.2 新增設定對話框

按下 `+` 後提供：

- Import profile file
- Import profile from clipboard
- Scan QR Code
- Add SSH config manually
- Cancel

### 3.3 設定編輯頁

設定頁由上到下：

1. Save
2. Protocol
3. Remarks
4. SSH
5. Proxy，依模式顯示
6. TLS，依模式顯示
7. Payload，依模式顯示
8. UDPGW / DNS
9. VPN Routing
10. Advanced

### 3.4 Payload Generator

保留參考畫面的操作概念：

- Payload preset
- Rotate
- Host
- Request Method
- Injection Method
- Front Query
- Back Query
- Online Host
- Reverse Proxy
- User Agent
- WebSocket
- Forward Host
- Keep Alive
- Referer
- Payload Preview
- Save Payload

但 Raw Payload 必須永遠是最後的真實資料來源。所有開關只是協助產生 Raw Payload，不應在背景偷偷再修改一次內容。

---

## 4. 整體資料流

```text
Android Apps
    │
    ▼
Android VpnService / TUN fd
    │
    ▼
Native tun2socks
    ├── TCP ──► localhost SOCKS5
    │                │
    │                ▼
    │          SSH dynamic forwarding
    │                │
    │                ▼
    │          SSH direct-tcpip channel
    │                │
    │                ▼
    │             Internet
    │
    └── UDP ──► UDPGW protocol
                     │
                     ▼
             SSH direct-tcpip channel
                     │
                     ▼
          remote 127.0.0.1:7300 badvpn-udpgw
                     │
                     ▼
                   UDP
```

### 4.1 重要原則

- `VpnService` 只建立 TUN 介面，本身不會替你完成封包轉送。
- TCP 由 tun2socks 轉成 SOCKS5 連線。
- 本機 SOCKS5 伺服器不直接上網，而是對每個 SOCKS CONNECT 建立 SSH `direct-tcpip` channel。
- SSH、Proxy、TLS、Payload 是「建立 SSH Transport」前的不同包裝層。
- UDP 不是 SSH 原生能力，必須使用遠端 UDPGW 或另外的 UDP relay。
- 每一個真正連到 Proxy／TLS／SSH server 的 Socket，都必須先 `protect()`，否則會重新進入 TUN 而形成死循環。

---

## 5. Protocol Preset 定義

不要把八種模式各寫成八套重複程式碼。建立可組合的 Transport Pipeline。

### 5.1 Transport Stage

```text
Physical TCP Dial
    ├── HTTP CONNECT Proxy
    ├── Raw Payload Handshake
    ├── TLS Wrap
    ├── WebSocket Stream Wrap
    └── SSH Handshake
```

### 5.2 預設模式

| 模式 | 預設 Stage 順序 | 必填資料 |
|---|---|---|
| SSH-Direct | TCP to SSH → SSH | SSH Host、Port、帳密 |
| SSH-Proxy | TCP to Proxy → HTTP CONNECT to SSH → SSH | Proxy + SSH |
| SSH-Payload | TCP to Payload Endpoint → Payload → SSH | Payload Endpoint + Payload + SSH Auth |
| SSH-Proxy-Payload | TCP to Proxy → Custom Payload to establish tunnel → SSH | Proxy + Payload + SSH Auth |
| SSH-TLS | TCP to TLS Endpoint → TLS → SSH | TLS Endpoint、SNI、SSH Auth |
| SSH-TLS-Proxy | TCP to Proxy → CONNECT TLS Endpoint → TLS → SSH | Proxy + TLS + SSH Auth |
| SSH-TLS-Payload | TCP to TLS Endpoint → TLS → Payload → SSH | TLS + Payload + SSH Auth |
| SSH-TLS-Proxy-Payload | TCP to Proxy → Payload to reach TLS Endpoint → TLS → SSH | Proxy + Payload + TLS + SSH Auth |

### 5.3 Advanced Pipeline

不同服務商的 Payload 放置順序可能不同，因此在 Advanced 中加入：

- Payload placement：
  - Before TLS
  - After TLS
- Proxy handshake：
  - Built-in HTTP CONNECT
  - Raw Payload is the proxy handshake
- WebSocket placement：
  - After plain HTTP Upgrade
  - After TLS HTTP Upgrade
- SSH logical destination 與實際 dial endpoint 分離。

不得讓 UI 模式名稱限制底層能力。Preset 只是填入一組合理的 pipeline 預設值。

---

## 6. 建議技術棧

### 6.1 Android

- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- Kotlin Coroutines + Flow
- Room
- DataStore
- Kotlin Serialization
- Hilt 或手寫 DI，二選一；不要混用多套 DI。
- WorkManager 只用於匯入、匯出、清理，不用於維持 VPN 連線。

### 6.2 SSH

首選評估：`connectbot/cbssh`。

原因：

- Kotlin。
- 支援 modern SSH algorithms。
- 支援 local、remote 與 dynamic SOCKS5 forwarding。
- Transport 可替換，適合插入 Proxy、TLS、Payload、WebSocket stream。

執行方式：

1. 先建立一個獨立 PoC，確認它能在 Android 上：
   - Password auth
   - Public key auth
   - Host key callback
   - 使用自訂 InputStream／OutputStream 或 custom transport
   - Dynamic SOCKS5
2. 不要直接追蹤 `main`，必須 pin 固定 tag 或 commit。
3. 若沒有穩定 Maven artifact，可將其作為 Git submodule 或 vendor module。
4. 若 PoC 無法通過，再改用 ConnectBot 現行 SSH engine，不要同時維護兩套 SSH library。

### 6.3 tun2socks / UDPGW

MVP 建議 fork BadVPN 的 `tun2socks`，原因是它的 UDPGW 行為與此類 SuperTunnel App 最接近。

但 BadVPN 上游已停止維護，所以必須：

- Fork 到自己的 repository。
- 只編譯 tun2socks 必要元件。
- 移除無關模組。
- 使用 NDK r28 或更新版本。
- 確保所有 `.so` 具備 16 KB ELF alignment。
- 移除任何假設 page size 固定為 4096 的程式碼。
- 加入 AddressSanitizer 測試 build。
- 實作 JNI start、stop、isRunning、lastError。
- Native thread 不可在 Activity 銷毀後繼續持有 Java object。

長期替代方案：重做或採用維護中的 tun2socks，再自行加入 UDPGW compatibility。這不列入第一版。

---

## 7. Android 17 相容要求

### 7.1 必要 Permission

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

`ACCESS_LOCAL_NETWORK` 只在使用者設定的 SSH、Proxy、TLS 或 Payload endpoint 屬於 LAN 位址時才要求 runtime permission，例如：

- `10.0.0.0/8`
- `172.16.0.0/12`
- `192.168.0.0/16`
- Link-local IPv4／IPv6
- `.local` 或經解析後落在 LAN 的 hostname

不要在第一次開啟 App 時無條件要求。

### 7.2 VpnService Manifest

```xml
<service
    android:name=".service.TunnelVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="systemExempted"
    android:exported="true">

    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>

    <meta-data
        android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON"
        android:value="true" />
</service>
```

注意：

- 啟動前先呼叫 `VpnService.prepare()`。
- 使用者同意後才啟動 Service。
- Service 啟動後立即 `startForeground()`。
- 若實際裝置或 Play 審查對 `systemExempted` 有相容問題，可切換成 AOSP ToyVPN 採用的 `specialUse`，並加入 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE=ssh_vpn_tunnel`。此選擇必須在正式發布前於 Android 14–17 與 Play internal testing 驗證。

### 7.3 Android 17 Native Code

- 不可從網路下載 `.so` 後動態載入。
- Native library 打包於 APK／AAB。
- 使用 `System.loadLibrary()`。
- NDK r28+。
- 驗證 16 KB page-size emulator／device。
- CI 執行 APK alignment 檢查。

### 7.4 Large Screen

Android 17 對大型螢幕的 orientation、resizability 與 aspect ratio 行為更嚴格，所以：

- 不鎖死直向。
- Compose UI 必須支援手機、平板、分割畫面。
- 寬螢幕時設定編輯頁改成雙欄：左側 section，右側表單。
- 不以固定 pixel 高度製作對話框。

---

## 8. VpnService 實作

### 8.1 Service 責任

`TunnelVpnService` 負責：

- 前景通知。
- 讀取指定 profile snapshot。
- 建立與關閉 transport pipeline。
- 建立 SSH session。
- 建立 local SOCKS5 dynamic forwarding。
- 建立 TUN。
- 啟停 native tun2socks。
- 監控 underlying network。
- 重連。
- 記錄狀態、流量與錯誤。
- 處理 `onRevoke()`。

Service 不負責：

- Compose UI。
- 表單驗證畫面。
- 檔案 picker UI。
- QR scanner UI。

### 8.2 建議連線順序

```text
1. 驗證 Profile
2. 取得 VpnService permission
3. 啟動 Foreground Service
4. 取得目前 underlying Network
5. 建立被 protect 且 bind 到 underlying Network 的 TCP socket
6. 執行 Proxy / Payload / TLS / WebSocket pipeline
7. SSH key exchange
8. Host key 驗證
9. SSH authentication
10. 啟動 localhost dynamic SOCKS5，例如 127.0.0.1:10808
11. 建立 TUN fd
12. 啟動 tun2socks
13. 狀態切換為 CONNECTED
```

如果步驟 5–10 失敗，不建立 TUN，避免使用者所有流量被黑洞。

重新連線時可保留 TUN，但必須暫停或停止 tun2socks，重建 SSH 與 SOCKS 後再恢復。

### 8.3 VpnService.Builder 預設

```kotlin
Builder()
    .setSession(profile.name)
    .setMtu(profile.vpn.mtu)
    .addAddress("10.77.0.2", 24)
    .addRoute("0.0.0.0", 0)
    .addDnsServer(profile.dns.primary)
```

進一步要求：

- 預設 MTU：`1400`。
- 可調範圍：`1280..1500`。
- Full tunnel 加入 `0.0.0.0/0`。
- Split tunnel 只加入使用者指定 CIDR。
- 支援 `addAllowedApplication()` 與 `addDisallowedApplication()`。
- Allowed 與 Disallowed 不可同時使用。
- IPv6 未完成前，提供：
  - Block IPv6，預設。
  - Bypass IPv6，顯示 leak 警告。
  - Experimental IPv6。

### 8.4 Underlying Network

建立上游 Socket 時：

1. 取得 `ConnectivityManager.activeNetwork`。
2. 建立 socket。
3. 在 `connect()` 前呼叫 `protect(socket)`。
4. 使用 `Network.bindSocket(socket)`。
5. DNS 解析優先使用 `Network.getAllByName()`，避免 DNS request 進入自己的 TUN。

網路由 Wi-Fi 切換到行動網路後，關閉舊 SSH transport 並重連，不嘗試搬移既有 TCP connection。

---

## 9. Transport Pipeline API

建議建立以下抽象：

```kotlin
interface DuplexConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    val remoteDescription: String
}

interface ConnectionDialer {
    suspend fun dial(
        endpoint: Endpoint,
        context: DialContext
    ): DuplexConnection
}

interface ConnectionLayer {
    suspend fun wrap(
        upstream: DuplexConnection,
        context: PipelineContext
    ): DuplexConnection
}
```

實作：

- `ProtectedTcpDialer`
- `HttpConnectLayer`
- `PayloadHandshakeLayer`
- `TlsLayer`
- `WebSocketLayer`
- `SshTransportAdapter`

`PipelineFactory` 根據 profile mode 建立 ordered stage list。

所有 stage 必須：

- 有獨立 timeout。
- 支援 coroutine cancellation。
- 關閉時向下游傳遞 close。
- 回報 stage-specific error。
- 不在 log 中輸出密碼、Authorization、完整私鑰或敏感 payload header。

---

## 10. SSH Engine

### 10.1 功能

- Password auth。
- OpenSSH private key。
- Ed25519、ECDSA、RSA SHA-2。
- Keyboard-interactive，第二階段。
- Host key verification。
- Dynamic SOCKS5 forward。
- Keepalive。
- Reconnect。

### 10.2 Host Key

第一次連線：

- 顯示 fingerprint。
- 顯示 algorithm。
- 使用者可選擇 Trust Once 或 Trust and Save。

已儲存後：

- 相同 fingerprint：繼續。
- Host key 改變：直接中止，不可自動接受。
- UI 顯示 `HOST_KEY_CHANGED` 並提供查看新舊 fingerprint，使用者必須手動移除舊 key。

### 10.3 演算法政策

預設允許：

- Ed25519／ECDSA／RSA SHA-2 host key。
- Curve25519／ECDH／安全 DH group。
- ChaCha20-Poly1305、AES-GCM、AES-CTR。
- HMAC-SHA2。

預設拒絕：

- `ssh-rsa` SHA-1 signature。
- CBC cipher。
- 3DES。
- `diffie-hellman-group1-sha1`。

若加入 Legacy Mode，必須有明顯警告且只對單一 profile 生效。

### 10.4 Dynamic SOCKS

- 只監聽 `127.0.0.1`。
- 預設 port `10808`，若被占用可自動尋找空 port。
- 支援 SOCKS5 CONNECT。
- DNS 若由 SOCKS request domain 提供，應讓 SSH server 端解析。
- 不提供 LAN listener，避免手機成為未授權 Proxy。
- 設定最大同時 channel，例如 256。
- 每個 channel 加入 idle timeout 與 backpressure。

---

## 11. HTTP Proxy

### 11.1 Proxy Config

```kotlin
data class HttpProxyConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val passwordSecretId: String?,
    val connectTimeoutMs: Long,
    val responseTimeoutMs: Long
)
```

### 11.2 Built-in CONNECT

```http
CONNECT ssh.example.com:22 HTTP/1.1
Host: ssh.example.com:22
Proxy-Connection: keep-alive
Connection: keep-alive


```

接受：

- `200`–`299`。

特殊處理：

- `407`：顯示 Proxy authentication required。
- Header 最大 64 KiB。
- 等待 `\r\n\r\n`。
- 禁止無上限讀取。
- Basic auth 使用 UTF-8 credentials 後 Base64，但 log 必須遮蔽。

第二階段再加入 Digest。

---

## 12. TLS

### 12.1 TLS Config

```kotlin
data class TlsConfig(
    val endpointHost: String,
    val endpointPort: Int,
    val sni: String,
    val versions: Set<TlsVersion>,
    val alpn: List<String>,
    val verificationMode: VerificationMode,
    val customCaSecretId: String?,
    val pinnedSpkiSha256: List<String>
)
```

### 12.2 行為

- 預設 TLS 1.3 + TLS 1.2。
- SNI 與 dial host 分離。
- 憑證 hostname 驗證依 SNI 執行，不依 IP。
- 預設使用 system trust store。
- 支援 custom CA。
- 支援 SPKI SHA-256 pinning。
- `Insecure / Trust All` 只可放在 Advanced，顯示永久警告，且匯出 profile 時預設不允許帶出。
- TLS handshake timeout 預設 15 秒。
- 不使用已淘汰 SSLv3、TLS 1.0、TLS 1.1。

若 TLS Endpoint 是 stunnel／HAProxy TCP TLS frontend，TLS 完成後直接將 SSH binary stream 交給 SSH engine。

---

## 13. Payload Engine

### 13.1 Raw Payload

Raw Payload 是 byte sequence template。預設以 US-ASCII 產生 HTTP header。

支援 token：

- `[host]`
- `[port]`
- `[host_port]`
- `[ssh_host]`
- `[ssh_port]`
- `[proxy_host]`
- `[proxy_port]`
- `[tls_host]`
- `[tls_port]`
- `[sni]`
- `[crlf]`
- `[crlf*2]`
- `[lf]`
- `[tab]`
- `[ws_key]`
- `[unix_time]`
- `[random_uuid]`
- `[random_alpha:N]`
- `[random_numeric:N]`
- `[split]`
- `[delay_split:MS]`

禁止 token 遞迴展開，避免 payload expansion bomb。

### 13.2 Payload Example

```text
CONNECT [ssh_host]:[ssh_port] HTTP/1.1[crlf]
Host: [host][crlf]
Connection: keep-alive[crlf]
User-Agent: [user_agent][crlf]
[crlf]
```

### 13.3 Injection Method

- Normal：一次送出。
- Split：依 `[split]` 分段送出，不延遲。
- Delay Split：依 `[delay_split:MS]` 分段並等待。
- Front Query：將額外 query 放在 request target 前段。
- Back Query：將額外 query 放在 request target 後段。

Split 上限：

- 最多 32 段。
- 單次 delay 最大 5000 ms。
- 總 delay 最大 15000 ms。

### 13.4 Payload Response

Payload 送出後：

- 讀取 response header，最大 64 KiB。
- 預設等待 `\r\n\r\n`。
- 預設接受 `101` 或 `200..299`。
- 可設定 `Do not wait for response`，只適合特定 raw tunnel。
- 可設定 `Continue on any HTTP status`，放在 Advanced 並顯示警告。
- 保存前 1 KiB 的已遮蔽 response 到 log。

### 13.5 Payload Generator

Generator UI 欄位：

- Preset：Normal、CONNECT、GET Upgrade、WebSocket。
- Host。
- Path。
- Request Method：GET、CONNECT、POST、HEAD、OPTIONS。
- User Agent。
- Referer。
- Custom Headers，key-value list。
- Keep Alive。
- Online Host → `X-Online-Host`。
- Forward Host → `X-Forwarded-Host`。
- WebSocket。
- Front Query。
- Back Query。
- Split method。
- Live preview。

`Reverse Proxy` 並非標準 HTTP header。實作時不要猜測隱藏行為；將它設計成一個 preset，改變 request-target 與 Host 的組合，且在 Preview 清楚顯示最終輸出。

---

## 14. WebSocket Stream

勾選 WebSocket 時必須真正實作 RFC WebSocket stream adapter。

### 14.1 Handshake

- 產生 16-byte random `Sec-WebSocket-Key`。
- 驗證 HTTP 101。
- 驗證 `Upgrade: websocket`。
- 驗證 `Connection: Upgrade`。
- 驗證 `Sec-WebSocket-Accept`。

### 14.2 Frame

- Client → Server 必須 masking。
- 使用 Binary frame。
- 支援 fragmented frame。
- 支援 Ping/Pong。
- 支援 Close。
- 將 WebSocket frame 還原成連續 byte stream 給 SSH engine。
- 將 SSH output 切成合理 frame，例如 16 KiB。
- 不將每個 SSH byte 建成一個 frame。

若遠端只是「送出 Upgrade 後改成裸 TCP」的非標準 bridge，另外提供 `Raw upgrade mode`，不可與標準 WebSocket 混為一談。

---

## 15. UDPGW 與 DNS

### 15.1 UDPGW Config

```kotlin
data class UdpGwConfig(
    val enabled: Boolean = true,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int = 7300,
    val maxConnections: Int = 256,
    val transparentDns: Boolean = true
)
```

### 15.2 遠端要求

SSH server 必須執行 `badvpn-udpgw`，建議：

- 只監聽 loopback。
- Port 7300。
- 由 systemd 管理。
- 以低權限使用者執行。
- 限制最大連線與資源。

### 15.3 DNS Mode

提供：

1. UDPGW Transparent DNS，預設。
2. Custom DNS through tunnel。
3. System DNS，不建議，顯示可能繞過 tunnel 的警告。

Custom DNS 預設：

- Primary：`1.1.1.1`
- Secondary：`8.8.8.8`

但不要把任何公共 DNS 寫死成唯一選項。

### 15.4 UDPGW 不可用

如果 SSH 成功但 UDPGW 不存在：

- TCP 仍可使用。
- Log 顯示 `UDPGW_UNAVAILABLE`。
- Home 顯示 `TCP only`。
- 不應讓整個 VPN 直接斷線，除非 profile 設定 `Require UDPGW = true`。

---

## 16. 資料模型

```kotlin
@Serializable
data class TunnelProfile(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val mode: TunnelMode,
    val ssh: SshConfig,
    val proxy: HttpProxyConfig? = null,
    val tls: TlsConfig? = null,
    val payload: PayloadConfig? = null,
    val udpgw: UdpGwConfig = UdpGwConfig(),
    val dns: DnsConfig = DnsConfig(),
    val vpn: VpnConfig = VpnConfig(),
    val reconnect: ReconnectConfig = ReconnectConfig(),
    val createdAt: Long,
    val updatedAt: Long
)
```

### 16.1 Secrets

Room 內不直接保存：

- SSH password。
- Proxy password。
- Private key plaintext。
- Export passphrase。

做法：

- 產生 App master key，放入 Android Keystore。
- 使用 AES-GCM 加密 secret。
- Room 只保存 `secretId` 與 ciphertext metadata。
- Log、Crash report、匯出 preview 不可讀出 secret。
- 提供 `Clear all secrets`。

---

## 17. Profile Validation Matrix

| 模式 | SSH | Proxy | TLS | Payload |
|---|---:|---:|---:|---:|
| SSH-Direct | 必須 | 否 | 否 | 否 |
| SSH-Proxy | 必須 | 必須 | 否 | 否 |
| SSH-Payload | 必須 | 否 | 否 | 必須 |
| SSH-Proxy-Payload | 必須 | 必須 | 否 | 必須 |
| SSH-TLS | 必須 | 否 | 必須 | 否 |
| SSH-TLS-Proxy | 必須 | 必須 | 必須 | 否 |
| SSH-TLS-Payload | 必須 | 否 | 必須 | 必須 |
| SSH-TLS-Proxy-Payload | 必須 | 必須 | 必須 | 必須 |

驗證：

- Port 必須 `1..65535`。
- Host 不可空白。
- SNI 必須是合法 hostname，不接受含 scheme 或 path。
- MTU 必須 `1280..1500`。
- CIDR 必須可解析。
- Payload 展開後最大 64 KiB。
- Split 段數與延遲必須在限制內。
- Full tunnel 與 split route 不可形成互相矛盾設定。
- App allowlist 與 denylist 不可同時存在。

---

## 18. 設定匯入與匯出

### 18.1 Plain Profile

```json
{
  "format": "ssh-tunnel-profile",
  "version": 1,
  "encrypted": false,
  "profile": {}
}
```

### 18.2 Encrypted Profile

- Password KDF：PBKDF2-HMAC-SHA256。
- Random salt 至少 16 bytes。
- 足夠高 iteration count，並可隨版本提高。
- Cipher：AES-256-GCM。
- Random nonce 12 bytes。
- AAD 包含 format 與 version。
- 不自行設計 stream cipher 或 XOR。

### 18.3 匯出選項

- Include password：預設關閉。
- Include private key：預設關閉。
- Encrypt export：包含任何 secret 時強制開啟。
- Lock editing：只作 UI 限制，不宣稱為安全 DRM。
- Expiry：可選。

### 18.4 QR Code

- 短設定可直接 encode。
- 太長時使用 gzip + Base64URL。
- 超過 QR 可接受大小時拒絕並改用檔案。
- QR 匯入後仍須顯示完整摘要與安全警告。

---

## 19. UI 詳細需求

### 19.1 Home

顯示：

- Selected profile。
- Connect／Disconnect 大按鈕。
- 狀態。
- 目前 protocol mode。
- Duration。
- Upload／Download bytes 與即時 speed。
- Network type：Wi-Fi、Cellular、Ethernet。
- SSH endpoint。
- UDP 狀態。
- DNS 狀態。
- 最後錯誤。

狀態顏色不可作為唯一資訊，必須有文字與 icon。

### 19.2 Configs

每張設定卡：

- Name。
- Mode。
- SSH host。
- Last connected。
- Favorite。
- More menu：Edit、Duplicate、Export、Delete、Test。

支援：

- 搜尋。
- 按名稱／最近使用排序。
- 長按多選。
- 匯入。

### 19.3 Config Editor

Section：

- General
- SSH
- Proxy
- TLS
- Payload
- UDPGW & DNS
- VPN routing
- Reconnect
- Advanced

依 protocol mode 動態顯示欄位，但使用者切換 mode 時不要立即刪除隱藏資料。

### 19.4 Logs

- Level：Debug、Info、Warn、Error。
- Stage filter。
- Pause autoscroll。
- Copy selected。
- Export redacted log。
- Clear。

Release build 預設不記錄 raw payload 與完整 response。Debug build 才能由使用者主動啟用 protocol trace。

### 19.5 Settings

- Theme：System、Light、Dark。
- Language。
- Default MTU。
- Default DNS。
- Auto reconnect。
- Battery optimization guide。
- Known Hosts manager。
- Stored keys manager。
- Export all profiles。
- Clear logs。
- About／Open-source licenses。

---

## 20. Connection State Machine

```text
IDLE
  → VALIDATING
  → REQUESTING_VPN_PERMISSION
  → STARTING_SERVICE
  → CONNECTING_TCP
  → PROXY_HANDSHAKE       optional
  → TLS_HANDSHAKE         optional
  → PAYLOAD_HANDSHAKE     optional
  → WEBSOCKET_HANDSHAKE   optional
  → SSH_KEY_EXCHANGE
  → VERIFYING_HOST_KEY
  → SSH_AUTHENTICATING
  → STARTING_SOCKS
  → ESTABLISHING_TUN
  → STARTING_TUN2SOCKS
  → CONNECTED
  → RECONNECTING
  → STOPPING
  → IDLE
```

所有狀態由 Service 發布成 `StateFlow<TunnelState>`，UI 只能觀察，不自行推測。

---

## 21. 錯誤碼

至少定義：

- `PROFILE_INVALID`
- `VPN_PERMISSION_DENIED`
- `LOCAL_NETWORK_PERMISSION_DENIED`
- `NO_ACTIVE_NETWORK`
- `DNS_RESOLUTION_FAILED`
- `TCP_CONNECT_TIMEOUT`
- `TCP_CONNECTION_REFUSED`
- `PROXY_AUTH_REQUIRED`
- `PROXY_CONNECT_REJECTED`
- `PROXY_RESPONSE_TOO_LARGE`
- `TLS_HANDSHAKE_FAILED`
- `TLS_CERTIFICATE_INVALID`
- `TLS_HOSTNAME_MISMATCH`
- `TLS_PIN_MISMATCH`
- `PAYLOAD_RENDER_FAILED`
- `PAYLOAD_RESPONSE_TIMEOUT`
- `PAYLOAD_RESPONSE_REJECTED`
- `WEBSOCKET_HANDSHAKE_FAILED`
- `SSH_HOST_KEY_UNKNOWN`
- `SSH_HOST_KEY_CHANGED`
- `SSH_AUTH_FAILED`
- `SSH_ALGORITHM_UNSUPPORTED`
- `SSH_CHANNEL_OPEN_FAILED`
- `SOCKS_BIND_FAILED`
- `TUN_ESTABLISH_FAILED`
- `TUN2SOCKS_START_FAILED`
- `TUN2SOCKS_CRASHED`
- `UDPGW_UNAVAILABLE`
- `NETWORK_LOST`
- `NATIVE_ABI_MISSING`
- `SERVICE_REVOKED`
- `USER_STOPPED`

每個錯誤包含：

- code。
- stage。
- userMessage。
- technicalMessage。
- recoverable。
- cause type，不保存敏感內容。

---

## 22. 重連策略

預設：

```text
1s → 2s → 4s → 8s → 15s → 30s → 30s...
```

- 加入 ±20% jitter。
- 一般模式連續失敗 10 次後停止並通知使用者。
- Always-on 模式持續低頻重試。
- Authentication failed、host key changed、certificate pin mismatch 不自動重試。
- Network lost 等待新的 validated network 後立刻重試。
- 使用者按 Stop 後取消所有 pending retry。

SSH keepalive：

- 預設 30 秒。
- 連續 3 次無回應視為斷線。
- 可設定 10–120 秒。

---

## 23. Notification

連線中 notification：

- Profile name。
- Current state。
- Duration。
- Upload／Download。
- Action：Disconnect。

錯誤 notification：

- 顯示簡短原因。
- Action：Open logs、Retry。

不要提供會從背景任意啟動 Activity 的不必要行為。

---

## 24. Server 端測試環境

建立 `test-lab/`，包含 Docker Compose 或腳本：

1. OpenSSH server。
2. HTTP CONNECT proxy，例如 Squid。
3. TLS TCP frontend，例如 stunnel 或 HAProxy TCP mode。
4. WebSocket-to-TCP bridge。
5. badvpn-udpgw。
6. 測試 HTTP server。
7. 測試 DNS／UDP echo server。

OpenSSH 測試帳號：

- 非 root。
- 允許 TCP forwarding。
- 不允許 X11 forwarding。
- 不需要 shell 功能時限制 PTY。
- 測試 Password 與 public key。

測試環境必須能分別驗證八種 mode，不可只確認 SSH-Direct。

---

## 25. 測試

### 25.1 Unit Test

- Profile validator。
- Pipeline preset mapping。
- Payload token parser。
- CRLF rendering。
- Split planner。
- HTTP response parser。
- Proxy CONNECT parser。
- WebSocket accept validation。
- WebSocket frame encode／decode。
- Known-host matching。
- Reconnect backoff。
- Secret redaction。
- Import migration。

### 25.2 Integration Test

- SSH password auth。
- SSH key auth。
- Host key first-use 與 changed key。
- SSH direct SOCKS。
- HTTP CONNECT → SSH。
- Payload → SSH。
- TLS → SSH。
- Proxy → TLS → SSH。
- TLS → Payload → SSH。
- WebSocket framed SSH。
- UDPGW DNS lookup。
- UDP echo。
- 100 個並發 TCP connections。
- Large download。
- Network switch。
- Screen off 30 分鐘。
- Service process 被系統回收後恢復。

### 25.3 Android Matrix

- Android 12。
- Android 13。
- Android 14。
- Android 15。
- Android 16。
- Android 17。
- 至少一台 16 KB page-size 裝置或 emulator。
- Wi-Fi。
- 4G／5G。
- IPv4-only。
- IPv6-only／NAT64，至少確認錯誤可理解。

### 25.4 Leak Test

確認：

- DNS 不從 VPN 外洩，除非使用者選擇 System DNS bypass。
- IPv6 未支援時不直接繞過。
- SSH upstream socket 不進入自己的 TUN。
- App 停止後 TUN、SOCKS listener、SSH channel、native thread 全部關閉。

---

## 26. 效能目標

- 連線建立一般情況少於 5 秒，不含網路或伺服器延遲。
- Idle CPU 接近 0%，不可 busy loop。
- Idle memory 目標低於 120 MB。
- 不因單一慢速 SOCKS channel 阻塞其他 channel。
- Log ring buffer 預設 5,000 筆。
- Native packet buffer 有上限。
- Upload／Download 統計每秒更新一次，不每個 packet 刷 UI。
- Release build 關閉 verbose packet logging。

---

## 27. 安全要求

- 不接受所有 SSH host key。
- 不預設 Trust All TLS。
- 不在 log 顯示 password、private key、Proxy-Authorization。
- 不在 clipboard 長時間保存 secret。
- 匯出 secret 時必須加密。
- Imported profile 視為不可信資料。
- Payload 長度、header 長度、token 長度、split 數量全部設上限。
- 防止 zip bomb、JSON recursion、oversized QR。
- Deep link import 必須顯示確認頁，不可直接連線。
- Local SOCKS 只 bind loopback。
- Native code 啟用 stack protector、FORTIFY、RELRO、PIE。
- Release build 禁止 downloadable executable code。
- 建立 `THIRD_PARTY_NOTICES.md`，核對所有依賴授權。

---

## 28. 建議專案結構

```text
app/
  src/main/java/.../
    App.kt
    MainActivity.kt

    ui/
      home/
      configs/
      editor/
      payload/
      logs/
      settings/
      common/

    domain/
      model/
      validation/
      state/
      usecase/

    data/
      room/
      datastore/
      secrets/
      importexport/
      knownhosts/

    service/
      TunnelVpnService.kt
      TunnelOrchestrator.kt
      TunnelNotification.kt
      NetworkMonitor.kt
      TrafficStats.kt

    transport/
      core/
      tcp/
      proxy/
      payload/
      tls/
      websocket/
      ssh/

    socks/
      DynamicSocksController.kt

    tun/
      VpnInterfaceFactory.kt
      Tun2SocksController.kt
      NativeTun2Socks.kt

    diagnostics/
      TunnelLogger.kt
      ErrorMapper.kt
      Redactor.kt

native-tun2socks/
  src/main/cpp/
  CMakeLists.txt

test-lab/
  docker-compose.yml
  openssh/
  proxy/
  tls/
  websocket/
  udpgw/

docs/
  ARCHITECTURE.md
  PROFILE_FORMAT.md
  PAYLOAD_FORMAT.md
  TESTING.md
  SECURITY.md
```

---

## 29. Gradle 與 CI

CI 必須執行：

- `./gradlew lint`
- `./gradlew test`
- `./gradlew connectedCheck`，可在 nightly。
- Debug APK build。
- Release bundle build。
- Kotlin formatting。
- Dependency vulnerability scan。
- Native C/C++ build for：
  - arm64-v8a
  - armeabi-v7a，可選
  - x86_64，供 emulator
- 16 KB ELF alignment check。
- APK install test on API 37 emulator。
- Secret scan。

正式版最低必須包含 `arm64-v8a` 與 `x86_64`。是否保留 `armeabi-v7a` 由 APK 大小與需求決定。

---

## 30. 開發階段與驗收條件

### Phase 0：專案骨架

完成：

- Compose navigation。
- Room profile CRUD。
- Secret storage。
- Config editor dynamic fields。
- StateFlow service binding。

驗收：

- 可建立、修改、複製、刪除設定。
- 旋轉螢幕與程序重建不遺失表單。

### Phase 1：SSH-Direct TCP VPN

完成：

- VpnService。
- SSH password auth。
- Host key。
- Dynamic SOCKS5。
- tun2socks TCP。

驗收：

- Chrome、Play Store 或指定測試 App 的 TCP 流量可透過 SSH 出口。
- 手機 SSH upstream socket 不 loop。
- Disconnect 後 VPN icon 消失且所有 thread 關閉。

### Phase 2：UDPGW 與 DNS

完成：

- UDPGW remote 127.0.0.1:7300。
- Transparent DNS。
- Custom DNS。

驗收：

- DNS query 通過 tunnel。
- UDP echo 成功。
- UDPGW 關閉時顯示 TCP-only，而不是無限 loading。

### Phase 3：Proxy 與 Payload

完成：

- SSH-Proxy。
- SSH-Payload。
- SSH-Proxy-Payload。
- Payload generator。
- Split／Delay Split。

驗收：

- 三種模式各有 integration test。
- Proxy 407、Payload 403、timeout 都能精確顯示。

### Phase 4：TLS 與 WebSocket

完成：

- SSH-TLS。
- SSH-TLS-Proxy。
- SSH-TLS-Payload。
- SSH-TLS-Proxy-Payload。
- Standard WebSocket framing。

驗收：

- 憑證錯誤不可被靜默忽略。
- Standard WebSocket server 可傳輸 SSH。
- 網路切換後可重連。

### Phase 5：匯入匯出與發布品質

完成：

- `.shtprofile`。
- Clipboard。
- QR。
- Encrypted export。
- Redacted logs。
- Adaptive UI。
- Android 17 與 16 KB 測試。

驗收：

- Release AAB 可產生。
- 所有 secret 不出現在 log、database plaintext、export default。
- API 37 安裝與連線成功。

### Phase 6：Optional SSH-DNSTT

DNSTT 是另一種底層 transport，不應硬塞進 Payload Engine。

若要做：

```text
DNS transport → reliable stream → SSH handshake → SOCKS → TUN
```

需要：

- 明確選定 DNSTT protocol implementation。
- Client native library。
- Server 端 authoritative DNS delegation。
- MTU、fragmentation、retransmission、latency 測試。
- 獨立安全審查。

未完成前不要在 UI 顯示可選但不能用的 SSH-DNSTT。

---

## 31. Agent 不可採取的捷徑

- 不可只用 Android `ProxyInfo` 假裝全域 VPN。
- 不可只做 SSH port forward，不處理 TUN。
- 不可在 TLS 使用 Trust-All 作為預設。
- 不可自動接受 SSH host key。
- 不可把 SSH password 放 SharedPreferences plaintext。
- 不可只送 WebSocket Upgrade header，之後卻傳裸 SSH。
- 不可忽略 UDP 卻仍顯示完整 VPN 已連線。
- 不可直接使用多年未維護的預編譯 `.so`。
- 不可讓 SOCKS listener 綁 `0.0.0.0`。
- 不可把 V2Ray／Xray／sing-box 當作偷懶的底層實作。
- 不可在每一種 mode 複製一份連線程式碼。
- 不可在 UI thread 做 DNS、Socket、TLS、SSH 或檔案加密。

---

## 32. Definition of Done

專案只有同時符合以下條件才算完成：

- 可在 Android 17 安裝與開啟。
- 可建立 SSH-Direct profile 並讓指定 App 上網。
- 八種 SSH／TLS／Proxy／Payload preset 均可連線至測試環境。
- TCP、DNS、UDPGW 有 integration test。
- WebSocket 是標準 framing implementation。
- 支援 host key 驗證與 TLS certificate 驗證。
- 支援 profile 匯入匯出與 secret encryption。
- 支援前景通知與可靠 disconnect。
- Wi-Fi／行動網路切換能重連。
- 沒有 V2Ray 相關程式碼、依賴、UI、設定格式。
- Native libraries 通過 16 KB page-size 檢查。
- Release build 不洩漏 secrets。
- `README.md` 包含建置、測試、伺服器環境與已知限制。
- `docs/ARCHITECTURE.md` 說明完整資料流與各 stage。

---

## 33. 開發 Agent 的第一批工作項目

請依序執行，不要一開始同時做全部 protocol：

1. 建立 Android 專案與 module structure。
2. 完成 Profile data model、Room、Keystore secret store。
3. 做 SSH library PoC，證明 custom transport + dynamic SOCKS 可行。
4. 建立 `ProtectedTcpDialer`。
5. 完成 SSH-Direct，不先做 Payload。
6. 整合 TUN + tun2socks TCP。
7. 建立 Docker test lab 並加入自動測試。
8. 加入 UDPGW。
9. 把 transport 改造成 stage pipeline。
10. 依序加入 Proxy、Payload、TLS、WebSocket。
11. 最後再做 import/export、QR、adaptive UI 與發布整理。

每完成一個 Phase，更新：

- `README.md`
- `docs/ARCHITECTURE.md`
- `docs/TESTING.md`
- Git tag
- 可安裝 Debug APK

