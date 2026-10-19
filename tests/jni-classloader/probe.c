/* Only Android pthread imports: the same x86_64 ELF runs on Android and ATL. */
#include <jni.h>
typedef unsigned long pthread_t;
extern int pthread_create(pthread_t *, const void *, void *(*)(void *), void *);
extern int pthread_join(pthread_t, void **);
struct request { JavaVM *vm; jobject loader, expected; const char *error; };
static void *worker(void *data)
{
    struct request *r=data;JNIEnv *env;
    if((*r->vm)->AttachCurrentThread(r->vm,(void **)&env,0)!=JNI_OK){r->error="attach failed";return 0;}
    jclass raw=(*env)->FindClass(env,"org/atl/loaderprobe/Probe$Marker");
    if(raw){r->error="raw FindClass on unattached-to-Java worker saw app class";goto done;}
    if(!(*env)->ExceptionCheck(env)){r->error="raw miss lacked exception";goto done;}
    (*env)->ExceptionClear(env);
    jclass loaderClass=(*env)->GetObjectClass(env,r->loader);
    jmethodID load=(*env)->GetMethodID(env,loaderClass,"loadClass","(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name=(*env)->NewStringUTF(env,"org.atl.loaderprobe.Probe$Marker");
    jobject resolved=(*env)->CallObjectMethod(env,r->loader,load,name);
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);r->error="owner loadClass threw";}
    else if(!(*env)->IsSameObject(env,resolved,r->expected))r->error="owner loadClass returned different identity";
done:
    (*r->vm)->DetachCurrentThread(r->vm);return 0;
}
JNIEXPORT jstring JNICALL Java_org_atl_loaderprobe_Probe_check(JNIEnv *env,jclass cls,jclass expected,jobject loader)
{
    jclass normal=(*env)->FindClass(env,"org/atl/loaderprobe/Probe$Marker");
    if(!normal || !(*env)->IsSameObject(env,normal,expected))return (*env)->NewStringUTF(env,"managed JNI frame lost app loader");
    struct request r={0};(*env)->GetJavaVM(env,&r.vm);
    r.loader=(*env)->NewGlobalRef(env,loader);r.expected=(*env)->NewGlobalRef(env,expected);
    pthread_t t;
    if(pthread_create(&t,0,worker,&r))r.error="pthread_create failed";
    else pthread_join(t,0);
    (*env)->DeleteGlobalRef(env,r.loader);(*env)->DeleteGlobalRef(env,r.expected);
    return r.error ? (*env)->NewStringUTF(env,r.error) : 0;
}
