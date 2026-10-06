#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "mobi.h"

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static void put_bytes(JNIEnv *env, jobjectArray result, int index, const void *data, size_t length) {
    jbyteArray value = (*env)->NewByteArray(env, (jsize) length);
    if (!value) return;
    if (length) (*env)->SetByteArrayRegion(env, value, 0, (jsize) length, (const jbyte *) data);
    (*env)->SetObjectArrayElement(env, result, index, value);
    (*env)->DeleteLocalRef(env, value);
}

JNIEXPORT jobjectArray JNICALL
Java_com_jknl_englishreader_data_MobiImporter_extract(JNIEnv *env, jobject self, jbyteArray input) {
    (void) self;
    jobjectArray result = NULL;
    MOBIData *mobi = NULL;
    MOBIRawml *raw = NULL;
    FILE *file = NULL;
    char *title = NULL, *author = NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (!bytes) return NULL;
    jsize length = (*env)->GetArrayLength(env, input);
    file = fmemopen(bytes, (size_t) length, "rb");
    mobi = mobi_init();
    if (!file || !mobi) { fail(env, "无法分配电子书解析内存"); goto done; }
    if (mobi_load_file(mobi, file) != MOBI_SUCCESS) { fail(env, "MOBI/AZW 文件结构损坏或不受支持"); goto done; }
    if (mobi_is_encrypted(mobi)) { fail(env, "这本 Kindle 书受 DRM 加密保护，无法直接读取"); goto done; }
    if (mobi_get_text_maxsize(mobi) > 64 * 1024 * 1024) { fail(env, "Kindle 正文解压后过大"); goto done; }
    raw = mobi_init_rawml(mobi);
    if (!raw || mobi_parse_rawml_opt(raw, mobi, true, false, true) != MOBI_SUCCESS) {
        fail(env, "无法重建 MOBI/AZW3 正文"); goto done;
    }
    int count = 0;
    size_t total = 0;
    for (MOBIPart *p = raw->markup; p; p = p->next) {
        if (++count > 6000 || p->size > 16 * 1024 * 1024 || (total += p->size) > 64 * 1024 * 1024) {
            fail(env, "Kindle 章节数量或正文过大"); goto done;
        }
    }
    if (!count) { fail(env, "Kindle 文件没有文字正文"); goto done; }
    jclass array_class = (*env)->FindClass(env, "[B");
    result = (*env)->NewObjectArray(env, count * 2 + 4, array_class, NULL);
    if (!result) goto done;
    title = mobi_meta_get_title(mobi);
    author = mobi_meta_get_author(mobi);
    put_bytes(env, result, 0, title, title ? strlen(title) : 0);
    put_bytes(env, result, 1, author, author ? strlen(author) : 0);
    MOBIExthHeader *cover_header = mobi_get_exthrecord_by_tag(mobi, EXTH_COVEROFFSET);
    MOBIPart *cover = cover_header ? mobi_get_resource_by_uid(raw, mobi_decode_exthvalue(cover_header->data, cover_header->size)) : NULL;
    if (cover && cover->size <= 16 * 1024 * 1024)
        put_bytes(env, result, 2, cover->data, cover->size);
    else put_bytes(env, result, 2, NULL, 0);
    MOBIPart *ncx = raw->resources;
    while (ncx && ncx->type != T_NCX) ncx = ncx->next;
    if (ncx && ncx->size <= 16 * 1024 * 1024) put_bytes(env, result, 3, ncx->data, ncx->size);
    else put_bytes(env, result, 3, NULL, 0);
    int index = 4;
    for (MOBIPart *p = raw->markup; p && !(*env)->ExceptionCheck(env); p = p->next) {
        char path[64];
        snprintf(path, sizeof(path), "part%05zu.html", p->uid);
        put_bytes(env, result, index++, path, strlen(path));
        put_bytes(env, result, index++, p->data, p->size);
    }
done:
    free(title); free(author);
    if (raw) mobi_free_rawml(raw);
    if (mobi) mobi_free(mobi);
    if (file) fclose(file);
    (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
    return result;
}
