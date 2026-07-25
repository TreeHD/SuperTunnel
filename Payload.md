我查完後，先講結論：

**SuperTunnel 沒有公開一份完整、正式的 Payload 變數文件。**目前能交叉確認的來源主要是：

1. NPV 官方頻道的更新紀錄，確認原生支援 **split tags、SSH＋Proxy＋Payload、rotate payload**。([Telegram][1])
2. NPV Payload 編輯畫面顯示的提示標籤。
3. HTTP Injector 同系語法文件與開源相容實作，用來確認各標籤實際展開方式。([Evozi Apps][2])

## SuperTunnel 可確認的 Payload 變數

### 連線目標變數

| 變數             | 用途                   | 可能展開結果                  |
| -------------- | -------------------- | ----------------------- |
| `[method]`     | HTTP 方法              | 通常是 `CONNECT`           |
| `[host]`       | SSH 伺服器主機名稱或 IP      | `1.2.3.4`               |
| `[port]`       | SSH 連接埠              | `22`                    |
| `[host_port]`  | SSH 主機與連接埠           | `1.2.3.4:22`            |
| `[proxy_host]` | Remote Proxy 的主機或 IP | `proxy.example.com`     |
| `[proxy_port]` | Remote Proxy 的連接埠    | `8080`                  |
| `[protocol]`   | HTTP 協定版本            | `HTTP/1.0` 或 `HTTP/1.1` |

`[host]`、`[port]`、`[host_port]` 指的是最終 SSH 目的地；`[proxy_host]`、`[proxy_port]` 則是你在 NPV 裡設定的上游 HTTP Proxy。不要混在一起。標準 injector 語法也將 `[host_port]` 定義成目的主機與連接埠，並讓 `[protocol]` 展開成 HTTP 版本字串。([Evozi Apps][2])

### HTTP 換行字元

| 變數             | 實際字元       | 用途                 |
| -------------- | ---------- | ------------------ |
| `[cr]`         | `\r`       | Carriage Return    |
| `[lf]`         | `\n`       | Line Feed          |
| `[crlf]`       | `\r\n`     | 標準 HTTP 換行         |
| `[lfcr]`       | `\n\r`     | 反向換行，少數特殊 Proxy 使用 |
| `[crlf][crlf]` | `\r\n\r\n` | 結束 HTTP Headers    |

HTTP/1.x 正常應使用 `[crlf]`。Headers 結尾必須有兩次，也就是：

```text
[crlf][crlf]
```

這些展開值可由 HTTP Injector 文件及相容開源實作直接確認。([Evozi Apps][2])

### 自動產生內容

| 變數           | 用途                     | 注意事項                                  |
| ------------ | ---------------------- | ------------------------------------- |
| `[ua]`       | 自動填入 User-Agent        | 通常會生成瀏覽器或裝置 UA                        |
| `[auth]`     | Proxy 認證內容             | 沒設定 Proxy 帳密時可能是空字串                   |
| `[netData]`  | 自動產生基本 CONNECT 請求行     | 通常等同 `CONNECT [host_port] [protocol]` |
| `[raw]`      | 自動產生完整原始 CONNECT 資料    | 通常包含結尾空白行                             |
| `[real_raw]` | 真正／未加工的 raw CONNECT 資料 | 與 `[raw]` 的精確差異未公開                    |

相容實作通常把它們展開為：

```text
[netData]
```

等同：

```text
CONNECT [host_port] [protocol]
```

而：

```text
[raw]
```

通常接近：

```text
CONNECT [host_port] HTTP/1.0[crlf][crlf]
```

開源相容實作中，`[raw]` 與 `[real_raw]` 可能產生相同資料，但 **NPV 本身沒有公開兩者差異**，所以不要直接假設它們在所有版本都完全相同。([Evozi Apps][2])

另外，建議照這個大小寫寫：

```text
[netData]
```

不要寫成：

```text
[netdata]
```

即使部分版本可能不分大小寫，也應把 Payload 標籤當成大小寫敏感。

## Payload 分段變數

這些不是文字替換，而是控制 NPV 如何把 Payload 分成數次送出。

| 變數                | 用途                         |
| ----------------- | -------------------------- |
| `[split]`         | 從這裡分成兩個 TCP write，使用一般分段等待 |
| `[instant_split]` | 立即分段送出，通常不額外等待             |
| `[delay_split]`   | 分段後等待較久再送下一段               |
| `[split_delay]`   | `[delay_split]` 的相容別名      |
| `[split=毫秒]`      | 自訂兩段之間的等待時間                |

例如：

```text
GET / HTTP/1.1[crlf]
Host: gg.wp[crlf]
[crlf]
[split=1000]
CONNECT [host_port] [protocol][crlf]
[crlf]
```

意思大致是：

1. 先送出 GET 請求。
2. 等待約 `1000 ms`。
3. 再送出 CONNECT 請求。

NPV 官方曾明確修復「payloads having split tags」以及「SSH＋Proxy＋Payload」相關問題，代表這些 split 標籤由 NPV 自己解析。官方版本紀錄也確認支援 split payload。([Telegram][1])

不過，`[split]`、`[delay_split]` 的**預設等待時間**沒有可靠的 NPV 官方資料。某些相容實作使用 1 秒與 1.5 秒，但那不能直接當成 NPV 的固定數值。([GitHub][3])

## Random 與 Rotate

### 隨機選擇

```text
[random=a.example.com;b.example.com;c.example.com]
```

每次使用時隨機挑選其中一個值。

例如：

```text
Host: [random=gg.wp;www.gg.wp;cdn.gg.wp]
```

### 輪流選擇

```text
[rotate=a.example.com;b.example.com;c.example.com]
```

依序切換，例如：

```text
a.example.com
b.example.com
c.example.com
a.example.com
```

NPV 的版本紀錄明確提到加入 **SSH rotate payloads**；實際社群設定常使用分號 `;` 分隔 rotate/random 候選值。([APK Cafe][4])

常見用法：

```text
Host: [rotate=gg.wp;www.gg.wp;cdn.gg.wp]
```

Remote Proxy 欄位也可能直接填：

```text
[random=1.1.1.1;2.2.2.2;3.3.3.3]
```

## 可用變數總表

目前對 SuperTunnel 最有把握的清單是：

```text
[method]

[host]
[port]
[host_port]

[proxy_host]
[proxy_port]

[protocol]

[ua]
[auth]

[netData]
[raw]
[real_raw]

[cr]
[lf]
[crlf]
[lfcr]

[split]
[instant_split]
[delay_split]
[split_delay]
[split=毫秒]

[random=值1;值2;值3]
[rotate=值1;值2;值3]
```

## 其他 App 有，但不能保證 NPV 支援

網路上的 Payload 經常混用 HTTP Injector、HA Tunnel、TLS Tunnel、KPN Tunnel 等 App 語法。以下標籤在相容工具或社群 Payload 裡看得到，但我沒有找到足夠證據證明目前 NPV 一定支援：

```text
[realData]
[ssh]
[proxy]
[IP]
[PORT]
[immutable]
[crlf*2]
[split_instant]
[ssh_host]
[ssh_port]
```

其中：

* `[realData]` 可能等同 `[netData]`
* `[split_instant]` 可能是 `[instant_split]` 的別名
* `[crlf*2]` 可能等同 `[crlf][crlf]`
* `[ssh]` 在不同 App 裡甚至可能指 SSH 目的地或 Proxy，語意不一致

因此在 NPV 裡最好使用已知標籤，不要直接複製其他 Injector 的 Payload。

## 你要的 `GET gg.wp` Payload

### 只送 GET 請求

```text
GET / HTTP/1.1[crlf]Host: gg.wp[crlf]User-Agent: [ua][crlf]Connection: keep-alive[crlf][crlf]
```

若是標準 HTTP Proxy，請求行通常使用完整 URL：

```text
GET http://gg.wp/ HTTP/1.1[crlf]Host: gg.wp[crlf]User-Agent: [ua][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]
```

### 先 GET，再 CONNECT SSH

```text
GET http://gg.wp/ HTTP/1.1[crlf]Host: gg.wp[crlf]User-Agent: [ua][crlf]Connection: keep-alive[crlf][crlf][delay_split][method] [host_port] [protocol][crlf]Host: [host_port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]
```

比較容易控制時間的版本：

```text
GET http://gg.wp/ HTTP/1.1[crlf]Host: gg.wp[crlf]User-Agent: [ua][crlf]Connection: keep-alive[crlf][crlf][split=500][method] [host_port] [protocol][crlf]Host: [host_port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]
```

這類「先 GET、再 CONNECT」能不能成功，取決於上游 Proxy 是否允許同一條 TCP 連線繼續處理第二個請求。很多正常 HTTP Proxy 會在第一個 GET 後關閉連線或直接拒絕，因此 Payload 語法正確也不代表一定通。請只在自己的伺服器、Proxy 或已取得授權的網路上測試。

[1]: https://t.me/s/npvchannel?before=158 "NapsternetV – Telegram"
[2]: https://apps.evozi.com/httpinjector/ "HTTP Injector"
[3]: https://github.com/abdoxfox/SSH-VPN-WINDOWS/blob/main/inject.py "SSH-VPN-WINDOWS/inject.py at main · abdoxfox/SSH-VPN-WINDOWS · GitHub"
[4]: https://apk.cafe/go/?b=aHR0cHM6Ly9zLTA0LmZpbGVzaW5jbG91ZC5jb20vc3RvcmFnZS8xNi83NzcvODYwLzc3Nzg2MC9hcm02NF92OGEtYXJtZWFiaV92N2EvNmQyZDQzZTNjNTc2Y2QwZDM5NDBhY2FmNzE1NTg1ZjMvTnB2X1R1bm5lbC0xMDQuMC5hcGs%2Fcz1sUXp1d0o5UVlhV3QzZDVzcTNsMlNnJmU9MTc4NDkwNjMxNiZsYW5nPWVuJmFwa19pZD01OTM3MjgmcHJlbWl1bV9zcGVlZD0w&file_id=2937625 "Free download SuperTunnel V2ray/SSH APK for Android"
