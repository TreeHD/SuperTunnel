# SuperTunnel

SuperTunnel 是一個以 Android VPN Service 為核心的 arm64 網路隧道工具。它把受保護的 TCP、HTTP CONNECT、Payload、TLS、WebSocket、SSH、SOCKS5 與原生 tun2socks 組合成可重連的 tunnel pipeline，並提供 Slipstream（QUIC over DNS）模式。

![SuperTunnel logo](app/src/main/res/drawable/ic_supertunnel_logo.png)

## 專案資訊

- 顯示名稱：`SuperTunnel`
- Application ID：`me.treexhd.supertunnel`
- Android：最低 API 26，目標 API 37
- APK ABI：只編譯 `arm64-v8a`
- 介面語言：只保留 English (`en`，Android 的 `en_US` fallback)
- 原生元件：Go SSH/SOCKS 核心、Hev tun2socks、Slipstream client

## 取得原始碼

專案依賴兩個 recursive submodule，請使用：

```bash
git clone --recurse-submodules <repository-url>
cd SuperTunnel
```

若已經 clone 但 submodule 尚未初始化：

```bash
git submodule update --init --recursive
```

## 建置需求

- JDK 17
- Android SDK Platform 37
- Android NDK `28.2.13676358`
- Rust stable 與 target `aarch64-linux-android`
- Go（用於編譯 Go JNI 核心）
- 可使用 `cargo`、`cmake` 與 Android SDK build tools

Android SDK 路徑可透過 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 設定；本機的 `local.properties` 不會提交到 Git。

## 本機編譯 APK

先建立 Android 版 OpenSSL 與 Slipstream arm64 binary。CI 使用 vendored OpenSSL，避免把 runner 的 x86_64 `libssl` 誤用於 Android 交叉編譯：

```bash
rustup target add aarch64-linux-android
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export ANDROID_ABI=arm64-v8a
export ANDROID_PLATFORM=android-26
export NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export TOOL_BIN="${TMPDIR:-/tmp}/supertunnel-android-tools"
mkdir -p "$TOOL_BIN"
ln -sf "$NDK_BIN/aarch64-linux-android26-clang" "$TOOL_BIN/aarch64-linux-android-clang"
ln -sf "$NDK_BIN/aarch64-linux-android26-clang++" "$TOOL_BIN/aarch64-linux-android-clang++"
ln -sf "$NDK_BIN/llvm-ar" "$TOOL_BIN/aarch64-linux-android-ar"
ln -sf "$NDK_BIN/llvm-ranlib" "$TOOL_BIN/aarch64-linux-android-ranlib"
export PATH="$TOOL_BIN:$NDK_BIN:$PATH"
export CC="$NDK_BIN/aarch64-linux-android26-clang"
export CXX="$NDK_BIN/aarch64-linux-android26-clang++"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"

cargo build --manifest-path ci/android-openssl-bootstrap/Cargo.toml \
  --release --target aarch64-linux-android
export OPENSSL_ROOT_DIR="$(find ci/android-openssl-bootstrap/target/aarch64-linux-android/release/build \
  -type d -path '*/openssl-build/install' -print -quit)"
export OPENSSL_INCLUDE_DIR="$OPENSSL_ROOT_DIR/include"
export OPENSSL_LIB_DIR="$OPENSSL_ROOT_DIR/lib"
export OPENSSL_SSL_LIBRARY="$OPENSSL_ROOT_DIR/lib/libssl.a"
export OPENSSL_CRYPTO_LIBRARY="$OPENSSL_ROOT_DIR/lib/libcrypto.a"
export OPENSSL_STATIC=1

cd third_party/slipstream-rust
cargo build --release -p slipstream-client --target aarch64-linux-android \
  --features openssl-vendored,picoquic-minimal-build
cd ../..
```

套用 Android VPN data-plane 的 lwIP 效能調整並編譯：

```bash
git apply patches/lwip-performance.patch
./gradlew --no-daemon :app:assembleDebug
```

輸出檔案：

```text
app/build/outputs/apk/debug/app-debug.apk
```

`patches/lwip-performance.patch` 只需在乾淨工作樹套用一次。若要還原：

```bash
git apply --reverse patches/lwip-performance.patch
```

## GitHub Actions

`.github/workflows/build-apk.yml` 會在推送 `v*` 格式的版本 tag（例如
`v0.1.0`）時自動：

1. checkout recursive submodules；
2. 套用 lwIP 效能 patch；
3. 安裝 Android SDK、NDK、JDK 17 與 Rust target；
4. 編譯 Slipstream arm64；
5. 執行 `:app:assembleDebug`；
6. 建立同名 GitHub Release，並附上可直接下載的
   `SuperTunnel-<tag>-arm64-debug.apk`。

建立版本發佈時，先提交變更並建立、推送 tag：

```bash
git tag v0.1.0
git push origin v0.1.0
```

因此不需要把 APK、NDK、Cargo target 或 Gradle build cache 提交到 Git。

## 架構概覽

- `TunnelVpnService`：管理 VPN interface、前景服務、連線生命週期與重連。
- `TunnelOrchestrator`：建立、監控並關閉 transport pipeline。
- `transport/`：TCP、HTTP CONNECT、Payload、TLS、WebSocket、SSH、Slipstream 等通道層。
- `native-tun2socks/`：Android JNI wrapper 與 Go native bridge。
- `go-native/`：原生 SSH/SOCKS 核心。
- `third_party/`：以 submodule 管理的 Hev tunnel 與 Slipstream 原始碼，以及必要的 SSH channel patch。
- `patches/`：不直接修改第三方 submodule 的可重套用效能修補。

連線成功並完成 SSH 認證後才會建立 TUN forwarding；上游 socket 會先呼叫 `VpnService.protect()`，避免 VPN 自我遞迴。SOCKS listener 預設只繫結 loopback。

## 測試

```bash
./gradlew --no-daemon :app:testDebugUnitTest
```

完整 Android 驗證請參考 [docs/TESTING.md](docs/TESTING.md)。架構細節見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，安全規則見 [docs/SECURITY.md](docs/SECURITY.md)。

## 安全注意事項

- 不要把 SSH 密碼、私鑰、Payload 中的 token 或伺服器設定提交到 Git。
- Profile secret 由 Android Keystore 保護，log 應避免輸出認證資料與完整 Payload。
- TLS 憑證驗證預設開啟；只有在明確的進階設定中才允許不安全模式。
- 測試環境帳密只放在本機或 CI secret，不要寫入 README、workflow 或 sample config。

第三方授權資訊請查看 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
