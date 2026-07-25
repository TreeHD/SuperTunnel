#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#include "hev-main.h"

static pthread_t worker;
static pthread_mutex_t lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int running = 0;
static int worker_joinable = 0;
static int tunnel_fd = -1;
static char config[768];

static void *run_tunnel(void *unused) {
    (void)unused;
    __android_log_print(ANDROID_LOG_INFO, "HevTun2Socks", "starting: fd=%d", tunnel_fd);
    int result = hev_socks5_tunnel_main_from_str((const unsigned char *)config,
                                                 (unsigned int)strlen(config), tunnel_fd);
    __android_log_print(ANDROID_LOG_ERROR, "HevTun2Socks", "tunnel loop exited: result=%d", result);
    if (tunnel_fd >= 0) {
        close(tunnel_fd);
        tunnel_fd = -1;
    }
    atomic_store(&running, 0);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_me_treexhd_supertunnel_tun_NativeTun2Socks_start(
        JNIEnv *env, jclass clazz, jint fd, jstring socks, jstring ignored_udpgw, jint mtu) {
    (void)clazz;
    (void)ignored_udpgw;
    pthread_mutex_lock(&lifecycle_lock);
    if (atomic_load(&running)) {
        pthread_mutex_unlock(&lifecycle_lock);
        return JNI_FALSE;
    }
    if (worker_joinable) {
        pthread_join(worker, NULL);
        worker_joinable = 0;
    }

    const char *socks_address = (*env)->GetStringUTFChars(env, socks, NULL);
    if (!socks_address) {
        pthread_mutex_unlock(&lifecycle_lock);
        return JNI_FALSE;
    }
    char host[128] = {0};
    int port = 0;
    int parsed = sscanf(socks_address, "%127[^:]:%d", host, &port);
    (*env)->ReleaseStringUTFChars(env, socks, socks_address);
    if (parsed != 2 || port < 1 || port > 65535) {
        pthread_mutex_unlock(&lifecycle_lock);
        return JNI_FALSE;
    }

    tunnel_fd = dup(fd);
    if (tunnel_fd < 0) {
        pthread_mutex_unlock(&lifecycle_lock);
        return JNI_FALSE;
    }
    int safe_mtu = mtu < 576 ? 576 : (mtu > 9000 ? 9000 : mtu);
    int written = snprintf(config, sizeof(config),
        "tunnel:\n"
        "  mtu: %d\n"
        "  ipv4: 10.77.0.2\n"
        "  icmp: reply\n"
        "socks5:\n"
        "  address: %s\n"
        "  port: %d\n"
        "  udp: tcp\n"
        "misc:\n"
        "  task-stack-size: 131072\n"
        "  tcp-buffer-size: 1048576\n"
        "  max-session-count: 0\n"
        "  connect-timeout: 15000\n"
        "  tcp-read-write-timeout: 300000\n"
        "  log-level: warn\n",
        safe_mtu, host, port);
    if (written < 0 || written >= (int)sizeof(config)) {
        close(tunnel_fd);
        tunnel_fd = -1;
        pthread_mutex_unlock(&lifecycle_lock);
        return JNI_FALSE;
    }
    atomic_store(&running, 1);
    __android_log_print(ANDROID_LOG_INFO, "HevTun2Socks", "creating worker: socks=%s:%d mtu=%d", host, port, safe_mtu);
    if (pthread_create(&worker, NULL, run_tunnel, NULL) != 0) {
        close(tunnel_fd);
        tunnel_fd = -1;
        atomic_store(&running, 0);
        pthread_mutex_unlock(&lifecycle_lock);
        return JNI_FALSE;
    }
    worker_joinable = 1;
    pthread_mutex_unlock(&lifecycle_lock);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_me_treexhd_supertunnel_tun_NativeTun2Socks_stop(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&lifecycle_lock);
    if (atomic_load(&running)) {
        hev_socks5_tunnel_quit();
    }
    if (worker_joinable) {
        pthread_join(worker, NULL);
        worker_joinable = 0;
    }
    atomic_store(&running, 0);
    pthread_mutex_unlock(&lifecycle_lock);
}

JNIEXPORT jboolean JNICALL
Java_me_treexhd_supertunnel_tun_NativeTun2Socks_isRunning(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_load(&running) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_me_treexhd_supertunnel_tun_NativeTun2Socks_stats(JNIEnv *env, jclass clazz) {
    (void)clazz;
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    jlong values[4] = { (jlong)tx_packets, (jlong)tx_bytes, (jlong)rx_packets, (jlong)rx_bytes };
    jlongArray output = (*env)->NewLongArray(env, 4);
    if (output) (*env)->SetLongArrayRegion(env, output, 0, 4, values);
    return output;
}
