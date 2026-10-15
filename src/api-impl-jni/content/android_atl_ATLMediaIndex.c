/* SPDX-License-Identifier: GPL-3.0-only */
#include <glib.h>
#include <jni.h>
#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include "../defines.h"

/* Parse local audio headers/tags without creating a player or decoding sound.
 * Restrict protocols and demuxers so a scanned playlist cannot fetch URLs. */
JNIEXPORT jobjectArray JNICALL Java_android_atl_ATLMediaIndex_readAudioMetadata(JNIEnv *env, jclass cls, jstring filename)
{
    const char *path = (*env)->GetStringUTFChars(env, filename, NULL);
    if (!path) return NULL;
    AVFormatContext *format = NULL;
    AVDictionary *options = NULL;
    av_dict_set(&options, "protocol_whitelist", "file", 0);
    av_dict_set(&options, "probesize", "1048576", 0);
    av_dict_set(&options, "analyzeduration", "2000000", 0);
    av_dict_set(&options, "format_whitelist", "mp3,flac,wav,ogg,mov,aac", 0);
    int result = avformat_open_input(&format, path, NULL, &options);
    (*env)->ReleaseStringUTFChars(env, filename, path);
    av_dict_free(&options);
    if (result < 0) return NULL;
    jobjectArray values = NULL;
    if (avformat_find_stream_info(format, NULL) < 0) goto done;
    int audio = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (audio < 0) goto done;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) goto done;
    values = (*env)->NewObjectArray(env, 8, string_class, NULL);
    (*env)->DeleteLocalRef(env, string_class);
    if (!values) goto done;
    const char *keys[] = {"title", "artist", "album", "album_artist", "track", "date", "genre"};
    for (int i = 0; i < 7 && !(*env)->ExceptionCheck(env); i++) {
        AVDictionaryEntry *tag = av_dict_get(format->metadata, keys[i], NULL, 0);
        if (!tag) tag = av_dict_get(format->streams[audio]->metadata, keys[i], NULL, 0);
        if (tag) {
            glong length;
            gunichar2 *utf16 = g_utf8_to_utf16(tag->value, -1, NULL, &length, NULL);
            if (utf16) {
                jstring value = (*env)->NewString(env, (jchar *)utf16, length);
                g_free(utf16);
                if (value) {
                    (*env)->SetObjectArrayElement(env, values, i, value);
                    (*env)->DeleteLocalRef(env, value);
                }
            }
        }
    }
    if (format->duration != AV_NOPTS_VALUE && !(*env)->ExceptionCheck(env)) {
        char duration[32];
        g_snprintf(duration, sizeof(duration), "%" G_GINT64_FORMAT, (gint64)(format->duration / 1000));
        jstring value = (*env)->NewStringUTF(env, duration);
        if (value) {
            (*env)->SetObjectArrayElement(env, values, 7, value);
            (*env)->DeleteLocalRef(env, value);
        }
    }
done:
    avformat_close_input(&format);
    return values;
}
