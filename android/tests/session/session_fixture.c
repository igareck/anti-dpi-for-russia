#define _DEFAULT_SOURCE
#include <jni.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>

/* Host-only Android Os adapter. Calls the real kernel session and FD APIs. */
static void throw_errno(JNIEnv *env, const char *function) {
    int number=errno;
    jclass error=(*env)->FindClass(env,"android/system/ErrnoException");
    jmethodID ctor=(*env)->GetMethodID(env,error,"<init>","(Ljava/lang/String;I)V");
    jstring name=(*env)->NewStringUTF(env,function);
    jobject value=(*env)->NewObject(env,error,ctor,name,number);
    (*env)->Throw(env,(jthrowable)value);
}

JNIEXPORT jint JNICALL Java_android_system_Os_setsid(JNIEnv *env,jclass type) {
    (void)type;
    pid_t result=setsid();
    if(result<0)throw_errno(env,"setsid");
    return result;
}
JNIEXPORT jint JNICALL Java_android_system_Os_openNull(JNIEnv *env,jclass type) {
    (void)type;
    int fd=open("/dev/null",O_RDWR|O_CLOEXEC);
    if(fd<0)throw_errno(env,"open");
    return fd;
}
JNIEXPORT void JNICALL Java_android_system_Os_duplicate(JNIEnv *env,jclass type,jint from,jint to) {
    (void)type;
    if(dup2(from,to)<0)throw_errno(env,"dup2");
}
JNIEXPORT void JNICALL Java_android_system_Os_closeFd(JNIEnv *env,jclass type,jint fd) {
    (void)type;
    if(close(fd)<0)throw_errno(env,"close");
}
