package com.example.oceanengine.client;

import com.bytedance.ads.ApiClient;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * 千川 SDK {@link ApiClient} 统一配置入口。
 *
 * <p>背景：oceanengine-mapi-java-client 的 ApiClient 构造 OkHttpClient 时
 * 没有设置任何超时，OkHttp 默认 connect/read/write 超时均为 10 秒，
 * 创编、授权号列表等接口偶发超过 10 秒会直接抛
 * {@code java.net.SocketTimeoutException: Read timed out}。
 *
 * <p>本类统一：
 * <ul>
 *     <li>连接超时 15s、读超时 60s、写超时 60s；</li>
 *     <li>GET 请求在读超时等网络 IO 异常时自动重试最多 2 次（带退避），
 *     用于绕开偶发的慢 CDN 节点；POST 等非幂等请求绝不重试，
 *     避免创建出重复计划。</li>
 * </ul>
 */
public final class ApiClients {

    /** 连接超时：15 秒 */
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    /** 读超时：60 秒 */
    public static final int READ_TIMEOUT_MS = 60_000;
    /** 写超时：60 秒 */
    public static final int WRITE_TIMEOUT_MS = 60_000;

    /** GET 请求最大尝试次数（首次 + 2 次重试） */
    private static final int GET_MAX_ATTEMPTS = 3;

    private ApiClients() {
    }

    /**
     * 在 {@code new ApiClient()} 之后立即调用，统一设置超时与 GET 重试。
     *
     * @return 传入的同一个 ApiClient（便于链式书写）
     */
    public static ApiClient configure(ApiClient client) {
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.setReadTimeout(READ_TIMEOUT_MS);
        client.setWriteTimeout(WRITE_TIMEOUT_MS);

        // setReadTimeout 等方法会重建 httpClient；这里在其基础上追加拦截器，
        // 已设置的超时会被保留。
        client.setHttpClient(
                client.getHttpClient()
                        .newBuilder()
                        .addInterceptor(new RetryGetInterceptor())
                        .build()
        );
        return client;
    }

    /**
     * 仅对 GET 请求生效的重试拦截器。
     * 读超时（SocketTimeoutException）时重试，最多 {@value #GET_MAX_ATTEMPTS} 次；
     * 非 GET 请求、非超时异常直接抛出，不做重试。
     */
    static final class RetryGetInterceptor implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            boolean isGet = "GET".equalsIgnoreCase(request.method());
            int maxAttempts = isGet ? GET_MAX_ATTEMPTS : 1;

            IOException lastError = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return chain.proceed(request);
                } catch (SocketTimeoutException e) {
                    lastError = e;
                    if (attempt >= maxAttempts) {
                        throw e;
                    }
                    sleepBeforeRetry(attempt);
                }
            }
            throw lastError;
        }

        private void sleepBeforeRetry(int attempt) {
            long backoffMs = 500L * attempt;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
