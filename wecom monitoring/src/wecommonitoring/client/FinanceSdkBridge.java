package wecommonitoring.client;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class FinanceSdkBridge {
    private static final int MAX_MEDIA_CHUNKS = 10000;

    private final Class<?> financeClass;
    private final Object sdkHandle;
    private final Method newSliceMethod;
    private final Method getChatDataMethod;
    private final Method decryptDataMethod;
    private final Method getContentFromSliceMethod;
    private final Method freeSliceMethod;
    private final Method getMediaDataMethod;
    private final Method newMediaDataMethod;
    private final Method freeMediaDataMethod;
    private final Method getOutIndexBufMethod;
    private final Method getDataMethod;
    private final Method getDataLenMethod;
    private final Method isMediaDataFinishMethod;
    private final String disabledReason;

    private FinanceSdkBridge(Class<?> financeClass, Object sdkHandle, Method newSliceMethod,
                             Method getChatDataMethod,
                             Method decryptDataMethod, Method getContentFromSliceMethod,
                             Method freeSliceMethod, Method getMediaDataMethod,
                             Method newMediaDataMethod, Method freeMediaDataMethod,
                             Method getOutIndexBufMethod, Method getDataMethod,
                             Method getDataLenMethod, Method isMediaDataFinishMethod,
                             String disabledReason) {
        this.financeClass = financeClass;
        this.sdkHandle = sdkHandle;
        this.newSliceMethod = newSliceMethod;
        this.getChatDataMethod = getChatDataMethod;
        this.decryptDataMethod = decryptDataMethod;
        this.getContentFromSliceMethod = getContentFromSliceMethod;
        this.freeSliceMethod = freeSliceMethod;
        this.getMediaDataMethod = getMediaDataMethod;
        this.newMediaDataMethod = newMediaDataMethod;
        this.freeMediaDataMethod = freeMediaDataMethod;
        this.getOutIndexBufMethod = getOutIndexBufMethod;
        this.getDataMethod = getDataMethod;
        this.getDataLenMethod = getDataLenMethod;
        this.isMediaDataFinishMethod = isMediaDataFinishMethod;
        this.disabledReason = disabledReason;
    }

    public static FinanceSdkBridge create(String corpId, String corpSecret) {
        try {
            Class<?> clazz = Class.forName("com.tencent.wework.Finance");
            Method newSdk = findMethod(clazz, "NewSdk", 0);
            Method init = findMethod(clazz, "Init", 3);
            Method newSlice = findMethod(clazz, "NewSlice", 0);
            Method getChatData = findOptionalMethod(clazz, "GetChatData", 7);
            Method decryptData = findMethod(clazz, "DecryptData", 4);
            Method getContentFromSlice = findMethod(clazz, "GetContentFromSlice", 1);
            Method freeSlice = findOptionalMethod(clazz, "FreeSlice", 1);
            Method getMediaData = findOptionalMethod(clazz, "GetMediaData", 7);
            Method newMediaData = findOptionalMethod(clazz, "NewMediaData", 0);
            Method freeMediaData = findOptionalMethod(clazz, "FreeMediaData", 1);
            Method getOutIndexBuf = findOptionalMethod(clazz, "GetOutIndexBuf", 1);
            Method getData = findOptionalMethod(clazz, "GetData", 1);
            Method getDataLen = findOptionalMethod(clazz, "GetDataLen", 1);
            Method isMediaDataFinish = findOptionalMethod(clazz, "IsMediaDataFinish", 1);

            Object sdk = newSdk.invoke(null);
            int ret = invokeInt(init, adapt(sdk, init.getParameterTypes()[0]), corpId, corpSecret);
            if (ret != 0) {
                return unavailable("Finance.Init returned " + ret);
            }
            return new FinanceSdkBridge(clazz, sdk, newSlice, getChatData, decryptData, getContentFromSlice, freeSlice,
                    getMediaData, newMediaData, freeMediaData, getOutIndexBuf, getData, getDataLen, isMediaDataFinish,
                    null);
        } catch (ClassNotFoundException ex) {
            return unavailable("com.tencent.wework.Finance not found");
        } catch (Throwable ex) {
            return unavailable(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    public static FinanceSdkBridge unavailable(String reason) {
        return new FinanceSdkBridge(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, reason);
    }

    public boolean available() {
        return disabledReason == null;
    }

    public String disabledReason() {
        return disabledReason;
    }

    public boolean canPullChatData() {
        return available() && getChatDataMethod != null;
    }

    public boolean canPullMediaData() {
        return available()
                && getMediaDataMethod != null
                && newMediaDataMethod != null
                && getOutIndexBufMethod != null
                && getDataMethod != null
                && isMediaDataFinishMethod != null;
    }

    public String getChatData(long seq, int limit, String proxy, String passwd, int timeoutSeconds) throws Exception {
        if (!available()) {
            throw new IllegalStateException(disabledReason);
        }
        if (getChatDataMethod == null) {
            throw new IllegalStateException("Finance.GetChatData method not found");
        }
        Object slice = null;
        try {
            slice = newSliceMethod.invoke(null);
            Class<?>[] parameterTypes = getChatDataMethod.getParameterTypes();
            int ret = invokeInt(getChatDataMethod,
                    adapt(sdkHandle, parameterTypes[0]),
                    adapt(seq, parameterTypes[1]),
                    adapt(limit, parameterTypes[2]),
                    proxy == null ? "" : proxy,
                    passwd == null ? "" : passwd,
                    adapt(timeoutSeconds, parameterTypes[5]),
                    adapt(slice, parameterTypes[6]));
            if (ret != 0) {
                throw new IllegalStateException("Finance.GetChatData returned " + ret);
            }
            Object content = getContentFromSliceMethod.invoke(null,
                    adapt(slice, getContentFromSliceMethod.getParameterTypes()[0]));
            return content == null ? "" : String.valueOf(content);
        } catch (InvocationTargetException ex) {
            Throwable target = ex.getTargetException();
            if (target instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(target);
        } finally {
            freeSliceQuietly(slice);
        }
    }

    public byte[] getMediaData(String sdkFileId, String proxy, String passwd, int timeoutSeconds) throws Exception {
        if (!available()) {
            throw new IllegalStateException(disabledReason);
        }
        if (!canPullMediaData()) {
            throw new IllegalStateException("Finance.GetMediaData method group not found");
        }
        if (sdkFileId == null || sdkFileId.isBlank()) {
            throw new IllegalArgumentException("sdkFileId is blank");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String indexBuf = "";
        for (int chunkIndex = 0; chunkIndex < MAX_MEDIA_CHUNKS; chunkIndex++) {
            Object mediaData = null;
            try {
                mediaData = newMediaDataMethod.invoke(null);
                Class<?>[] parameterTypes = getMediaDataMethod.getParameterTypes();
                int ret = invokeInt(getMediaDataMethod,
                        adapt(sdkHandle, parameterTypes[0]),
                        indexBuf == null ? "" : indexBuf,
                        sdkFileId,
                        proxy == null ? "" : proxy,
                        passwd == null ? "" : passwd,
                        adapt(timeoutSeconds, parameterTypes[5]),
                        adapt(mediaData, parameterTypes[6]));
                if (ret != 0) {
                    throw new IllegalStateException("Finance.GetMediaData returned " + ret);
                }
                byte[] chunk = readMediaChunk(mediaData);
                if (chunk.length > 0) {
                    output.write(chunk);
                }
                int finished = invokeInt(isMediaDataFinishMethod,
                        adapt(mediaData, isMediaDataFinishMethod.getParameterTypes()[0]));
                if (finished == 1) {
                    return output.toByteArray();
                }
                Object nextIndex = getOutIndexBufMethod.invoke(null,
                        adapt(mediaData, getOutIndexBufMethod.getParameterTypes()[0]));
                indexBuf = nextIndex == null ? "" : String.valueOf(nextIndex);
                if (indexBuf.isBlank()) {
                    throw new IllegalStateException("Finance.GetMediaData did not finish and returned empty outindexbuf");
                }
            } catch (InvocationTargetException ex) {
                Throwable target = ex.getTargetException();
                if (target instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException(target);
            } finally {
                freeMediaDataQuietly(mediaData);
            }
        }
        throw new IllegalStateException("Finance.GetMediaData exceeded max chunk count " + MAX_MEDIA_CHUNKS);
    }

    public String decrypt(String randomKeyOrEncryptedKey, String encryptChatMsg) throws Exception {
        if (!available()) {
            throw new IllegalStateException(disabledReason);
        }
        Object slice = null;
        try {
            slice = newSliceMethod.invoke(null);
            int ret = invokeInt(decryptDataMethod,
                    adapt(sdkHandle, decryptDataMethod.getParameterTypes()[0]),
                    randomKeyOrEncryptedKey,
                    encryptChatMsg,
                    adapt(slice, decryptDataMethod.getParameterTypes()[3]));
            if (ret != 0) {
                throw new IllegalStateException("Finance.DecryptData returned " + ret);
            }
            Object content = getContentFromSliceMethod.invoke(null,
                    adapt(slice, getContentFromSliceMethod.getParameterTypes()[0]));
            return content == null ? "" : String.valueOf(content);
        } catch (InvocationTargetException ex) {
            Throwable target = ex.getTargetException();
            if (target instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(target);
        } finally {
            freeSliceQuietly(slice);
        }
    }

    @SuppressWarnings("unused")
    Class<?> financeClass() {
        return financeClass;
    }

    private static Method findMethod(Class<?> clazz, String name, int paramCount) {
        Method method = findOptionalMethod(clazz, name, paramCount);
        if (method == null) {
            throw new IllegalStateException("Finance method not found: " + name + "/" + paramCount);
        }
        return method;
    }

    private static Method findOptionalMethod(Class<?> clazz, String name, int paramCount) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        return null;
    }

    private static int invokeInt(Method method, Object... args) throws Exception {
        Object ret = method.invoke(null, args);
        if (ret instanceof Number n) {
            return n.intValue();
        }
        if (ret == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(ret));
    }

    private static Object adapt(Object value, Class<?> target) {
        if (value instanceof Number n) {
            if (target == int.class || target == Integer.class) {
                return n.intValue();
            }
            if (target == long.class || target == Long.class) {
                return n.longValue();
            }
        }
        return value;
    }

    private byte[] readMediaChunk(Object mediaData) throws Exception {
        Object raw = getDataMethod.invoke(null, adapt(mediaData, getDataMethod.getParameterTypes()[0]));
        byte[] bytes;
        if (raw == null) {
            bytes = new byte[0];
        } else if (raw instanceof byte[] arr) {
            bytes = arr;
        } else {
            bytes = String.valueOf(raw).getBytes(StandardCharsets.ISO_8859_1);
        }
        if (getDataLenMethod == null) {
            return bytes;
        }
        int dataLen = invokeInt(getDataLenMethod, adapt(mediaData, getDataLenMethod.getParameterTypes()[0]));
        if (dataLen < 0 || dataLen >= bytes.length) {
            return bytes;
        }
        return Arrays.copyOf(bytes, dataLen);
    }

    private void freeSliceQuietly(Object slice) {
        if (slice == null || freeSliceMethod == null) {
            return;
        }
        try {
            freeSliceMethod.invoke(null, adapt(slice, freeSliceMethod.getParameterTypes()[0]));
        } catch (Throwable ignored) {
        }
    }

    private void freeMediaDataQuietly(Object mediaData) {
        if (mediaData == null || freeMediaDataMethod == null) {
            return;
        }
        try {
            freeMediaDataMethod.invoke(null, adapt(mediaData, freeMediaDataMethod.getParameterTypes()[0]));
        } catch (Throwable ignored) {
        }
    }
}
