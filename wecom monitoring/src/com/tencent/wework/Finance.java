package com.tencent.wework;

/* SDK 返回数据
typedef struct Slice_t {
    char* buf;
    int len;
} Slice_t;

typedef struct MediaData {
    char* outindexbuf;
    int out_len;
    char* data;
    int data_len;
    int is_finish;
} MediaData_t;
*/

public class Finance {
    public native static long NewSdk();

    /**
     * 初始化函数
     * Return 值为 0 表示该 API 调用成功。
     *
     * @param sdk NewSdk 返回的 sdk 指针
     * @param corpid 调用企业的企业 ID，可以在企业微信管理端「我的企业」查看
     * @param secret 会话内容存档 Secret，可以在企业微信管理端「管理工具 - 会话内容存档」查看
     * @return 0 表示成功，非 0 表示失败
     */
    public native static int Init(long sdk, String corpid, String secret);

    /**
     * 拉取聊天记录函数。
     *
     * @param sdk NewSdk 返回的 sdk 指针
     * @param seq 从指定的 seq 开始拉取消息，返回消息从 seq + 1 开始
     * @param limit 一次拉取的消息条数，最大值 1000
     * @param proxy 代理地址，例如 socks5://10.0.0.1:8081 或 http://10.0.0.1:8081
     * @param passwd 代理账号密码，例如 user_name:passwd_123
     * @param timeout 超时时间，单位：秒
     * @param chatData 返回本次拉取消息的 Slice 指针
     * @return 0 表示成功，非 0 表示失败
     */
    public native static int GetChatData(long sdk, long seq, long limit, String proxy, String passwd, long timeout, long chatData);

    /**
     * 拉取媒体消息函数。
     *
     * @param sdk NewSdk 返回的 sdk 指针
     * @param indexbuf 媒体分片索引，首次传空字符串，后续传上次返回的 outindexbuf
     * @param sdkField 解密后的媒体消息里包含的 sdkfileid
     * @param proxy 代理地址
     * @param passwd 代理账号密码
     * @param timeout 超时时间，单位：秒
     * @param mediaData 返回本次拉取媒体数据的 MediaData 指针
     * @return 0 表示成功，非 0 表示失败
     */
    public native static int GetMediaData(long sdk, String indexbuf, String sdkField, String proxy, String passwd, long timeout, long mediaData);

    /**
     * 解密会话内容。
     *
     * @param sdk NewSdk 返回的 sdk 指针
     * @param encryptKey 使用会话存档私钥解密 encrypt_random_key 后得到的 key
     * @param encryptMsg GetChatData 返回的 encrypt_chat_msg
     * @param msg 返回解密明文的 Slice 指针
     * @return 0 表示成功，非 0 表示失败
     */
    public native static int DecryptData(long sdk, String encryptKey, String encryptMsg, long msg);

    public native static void DestroySdk(long sdk);

    public native static long NewSlice();

    public native static void FreeSlice(long slice);

    public native static String GetContentFromSlice(long slice);

    public native static int GetSliceLen(long slice);

    public native static long NewMediaData();

    public native static void FreeMediaData(long mediaData);

    public native static String GetOutIndexBuf(long mediaData);

    public native static byte[] GetData(long mediaData);

    public native static int GetIndexLen(long mediaData);

    public native static int GetDataLen(long mediaData);

    public native static int IsMediaDataFinish(long mediaData);

    static {
        System.loadLibrary("WeWorkFinanceSdk");
    }
}
