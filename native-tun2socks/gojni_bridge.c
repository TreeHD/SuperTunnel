#include <jni.h>
#include <stdlib.h>
#include <pthread.h>
#include <android/log.h>
#include <sys/socket.h>

#define LOG_TAG "GoSocketBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static int socket_mark(int fd) {
    int mark = -1;
    socklen_t length = sizeof(mark);
    return getsockopt(fd, SOL_SOCKET, SO_MARK, &mark, &length) == 0 ? mark : -1;
}

extern char *GoSshStart(char *raw);
extern void GoSshStop(void);
extern int GoSshIsAlive(void);
extern void GoSshFree(char *value);

static JavaVM *java_vm;
static pthread_mutex_t vpn_lock = PTHREAD_MUTEX_INITIALIZER;
static jobject vpn_service;
static jmethodID protect_method;
static jobject bound_network;
static jmethodID bind_socket_method;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    java_vm = vm;
    return JNI_VERSION_1_6;
}

/* Called by the Go net.Dialer before connect(), on a Go-owned thread. */
__attribute__((visibility("default"))) int GoProtectSocket(int fd) {
    if (!java_vm) return 0;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    }
    pthread_mutex_lock(&vpn_lock);
    jobject service = vpn_service;
    jmethodID protect = protect_method;
    jboolean result = (service && protect)
        ? (*env)->CallBooleanMethod(env, service, protect, (jint)fd)
        : JNI_FALSE;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = JNI_FALSE;
    }
    pthread_mutex_unlock(&vpn_lock);
    if (attached) (*java_vm)->DetachCurrentThread(java_vm);
    LOGD("protect fd=%d result=%d mark=%d", fd, result == JNI_TRUE, socket_mark(fd));
    return result == JNI_TRUE ? 1 : 0;
}

/* Keep Go-created sockets on the selected physical Network. VPN protect alone
 * excludes the app VPN but does not reliably select the desired underlying
 * network on Android 17. */
__attribute__((visibility("default"))) int GoBindSocketToNetwork(int fd) {
    if (!java_vm) return 0;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    }
    pthread_mutex_lock(&vpn_lock);
    jobject network = bound_network;
    jmethodID bind_socket = bind_socket_method;
    jboolean result = JNI_FALSE;
    if (network && bind_socket) {
        jclass fd_class = (*env)->FindClass(env, "java/io/FileDescriptor");
        jmethodID fd_ctor = fd_class ? (*env)->GetMethodID(env, fd_class, "<init>", "()V") : NULL;
        jfieldID descriptor = fd_class ? (*env)->GetFieldID(env, fd_class, "descriptor", "I") : NULL;
        if (fd_ctor && descriptor) {
            jobject file_descriptor = (*env)->NewObject(env, fd_class, fd_ctor);
            if (file_descriptor) {
                (*env)->SetIntField(env, file_descriptor, descriptor, (jint)fd);
                (*env)->CallVoidMethod(env, network, bind_socket, file_descriptor);
                result = (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                (*env)->DeleteLocalRef(env, file_descriptor);
            }
        }
        if (fd_class) (*env)->DeleteLocalRef(env, fd_class);
    }
    pthread_mutex_unlock(&vpn_lock);
    if (attached) (*java_vm)->DetachCurrentThread(java_vm);
    LOGD("bindNetwork fd=%d result=%d mark=%d", fd, result == JNI_TRUE, socket_mark(fd));
    return result == JNI_TRUE ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_me_treexhd_supertunnel_transport_ssh_NativeGoSuperTunnel_bindVpn(
        JNIEnv *env, jclass clazz, jobject service) {
    (void)clazz;
    pthread_mutex_lock(&vpn_lock);
    if (vpn_service) {
        (*env)->DeleteGlobalRef(env, vpn_service);
        vpn_service = NULL;
        protect_method = NULL;
    }
    if (service) {
        jclass klass = (*env)->GetObjectClass(env, service);
        protect_method = (*env)->GetMethodID(env, klass, "protect", "(I)Z");
        if (protect_method) vpn_service = (*env)->NewGlobalRef(env, service);
        (*env)->DeleteLocalRef(env, klass);
    }
    pthread_mutex_unlock(&vpn_lock);
}

JNIEXPORT void JNICALL
Java_me_treexhd_supertunnel_transport_ssh_NativeGoSuperTunnel_bindNetwork(
        JNIEnv *env, jclass clazz, jobject network) {
    (void)clazz;
    pthread_mutex_lock(&vpn_lock);
    if (bound_network) {
        (*env)->DeleteGlobalRef(env, bound_network);
        bound_network = NULL;
        bind_socket_method = NULL;
    }
    if (network) {
        jclass klass = (*env)->GetObjectClass(env, network);
        bind_socket_method = (*env)->GetMethodID(
            env, klass, "bindSocket", "(Ljava/io/FileDescriptor;)V");
        if (bind_socket_method) bound_network = (*env)->NewGlobalRef(env, network);
        (*env)->DeleteLocalRef(env, klass);
    }
    pthread_mutex_unlock(&vpn_lock);
}

JNIEXPORT jstring JNICALL
Java_me_treexhd_supertunnel_transport_ssh_NativeGoSuperTunnel_start(
        JNIEnv *env, jclass clazz, jstring config) {
    (void)clazz;
    const char *source = (*env)->GetStringUTFChars(env, config, NULL);
    if (!source) return NULL;
    char *reply = GoSshStart((char *)source);
    (*env)->ReleaseStringUTFChars(env, config, source);
    if (!reply) return NULL;
    jstring result = (*env)->NewStringUTF(env, reply);
    GoSshFree(reply);
    return result;
}

JNIEXPORT void JNICALL
Java_me_treexhd_supertunnel_transport_ssh_NativeGoSuperTunnel_stop(
        JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    GoSshStop();
}

JNIEXPORT jboolean JNICALL
Java_me_treexhd_supertunnel_transport_ssh_NativeGoSuperTunnel_isAlive(
        JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return GoSshIsAlive() ? JNI_TRUE : JNI_FALSE;
}
