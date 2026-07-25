# Testing

Run `./gradlew lint test assembleDebug` once an Android SDK with platform 37 is configured.

The service must be tested on API 37 with a valid SSH engine and tun2socks library: verify `protect()` prevents recursive routing, no TUN is created before SSH authentication, and stopping closes all listeners and native threads.
