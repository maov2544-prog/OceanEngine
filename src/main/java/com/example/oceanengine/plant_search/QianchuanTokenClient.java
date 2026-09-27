package com.example.oceanengine.plant_search;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 千川 Access-Token 获取客户端
 *
 * 支持两种模式：
 * 1. APP Access Token（应用级）: /open_api/oauth2/app_access_token/
 * 2. 用户 Access Token（授权级）: /open_api/oauth2/access_token/
 *
 * 内置 Token 缓存和自动刷新机制。
 */
public class QianchuanTokenClient {

    private static final Logger log = LoggerFactory.getLogger(QianchuanTokenClient.class);
    private static final String APP_TOKEN_PATH = "/open_api/oauth2/app_access_token/";
    private static final String USER_TOKEN_PATH = "/open_api/oauth2/access_token/";
    private static final String REFRESH_TOKEN_PATH = "/open_api/oauth2/refresh_token/";

    private final Long appId;
    private final String secret;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String directAccessToken;
    private final boolean useServiceToken;

    /** Token 缓存 */
    private volatile String cachedAccessToken;
    /** Token 过期时间戳（毫秒），提前5分钟刷新 */
    private volatile long expireTimestamp;
    /** Refresh Token（用户授权模式） */
    private volatile String refresh_token;

    /**
     * APP级Token模式（只需 app_id + secret）
     */
    public QianchuanTokenClient(Long appId, String secret) {
        this.appId = appId;
        this.secret = secret;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.directAccessToken = null;
        this.useServiceToken = false;
    }

    /**
     * 直接使用环境变量中的 Access-Token，不需要 appId 和 secret。
     */
    public QianchuanTokenClient() {
        this.appId = null;
        this.secret = null;
        this.httpClient = null;
        this.objectMapper = null;
        this.directAccessToken = System.getenv("OCEANENGINE_ACCESS_TOKEN");
        this.useServiceToken = true;
    }

    /**
     * 获取 Access-Token（带缓存和自动刷新）
     */
    public String getAccessToken() {
        if (useServiceToken) {
            try {
                return com.example.oceanengine.client.QianchuanTokenClient.getAccessToken();
            } catch (Exception e) {
                throw new RuntimeException("通过自建服务获取千川 Token 失败", e);
            }
        }

        if (directAccessToken != null && !directAccessToken.isBlank()) {
            return directAccessToken;
        }

        if (appId == null || secret == null || appId <= 0 || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 OCEANENGINE_ACCESS_TOKEN，或未提供 appId 和 secret");
        }

        // Token 未过期则直接返回缓存
        if (cachedAccessToken != null && System.currentTimeMillis() < expireTimestamp) {
            return cachedAccessToken;
        }

        synchronized (this) {
            // 双重检查
            if (cachedAccessToken != null && System.currentTimeMillis() < expireTimestamp) {
                return cachedAccessToken;
            }

            String token = refreshToken();
            if (token == null) {
                throw new RuntimeException("获取千川 Access-Token 失败");
            }
            return token;
        }
    }

    /**
     * 获取/刷新 Token
     * 优先用 refresh_token 刷新，否则用 app_id + secret 重新获取
     */
    private String refreshToken() {
        try {
            if (refresh_token != null) {
                return doRefreshToken();
            } else {
                return doGetAppToken();
            }
        } catch (Exception e) {
            log.error("获取千川 Access-Token 异常", e);
            return null;
        }
    }

    /**
     * APP级Token：POST /open_api/oauth2/app_access_token/
     */
    private String doGetAppToken() throws IOException {
        String url = "https://open.oceanengine.com" + APP_TOKEN_PATH;

        String body = objectMapper.writeValueAsString(Map.of(
                "app_id", appId,
                "secret", secret
        ));

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse("application/json"), body))
                .addHeader("Content-Type", "application/json")
                .build();

        Response response = httpClient.newCall(request).execute();
        String respBody = response.body() != null ? response.body().string() : "";

        Map<String, Object> respMap = objectMapper.readValue(respBody, Map.class);
        Integer code = (Integer) respMap.get("code");

        if (code == null || code != 0) {
            log.error("获取APP Access-Token失败, code={}, message={}", code, respMap.get("message"));
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) respMap.get("data");
        String token = (String) data.get("access_token");
        Integer expiresIn = (Integer) data.get("expires_in");

        cacheToken(token, expiresIn);
        log.info("获取APP Access-Token成功, expiresIn={}s", expiresIn);
        return token;
    }

    /**
     * 用户Token：POST /open_api/oauth2/access_token/
     *
     * @param authCode 授权回调获取的 auth_code
     */
    public String getUserToken(String authCode) throws IOException {
        String url = "https://open.oceanengine.com" + USER_TOKEN_PATH;

        String body = objectMapper.writeValueAsString(Map.of(
                "app_id", appId,
                "secret", secret,
                "auth_code", authCode
        ));

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse("application/json"), body))
                .addHeader("Content-Type", "application/json")
                .build();

        Response response = httpClient.newCall(request).execute();
        String respBody = response.body() != null ? response.body().string() : "";

        Map<String, Object> respMap = objectMapper.readValue(respBody, Map.class);
        Integer code = (Integer) respMap.get("code");

        if (code == null || code != 0) {
            log.error("获取用户Access-Token失败, code={}, message={}", code, respMap.get("message"));
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) respMap.get("data");
        String token = (String) data.get("access_token");
        Integer expiresIn = (Integer) data.get("expires_in");
        this.refresh_token = (String) data.get("refresh_token");

        cacheToken(token, expiresIn);
        log.info("获取用户Access-Token成功, expiresIn={}s", expiresIn);
        return token;
    }

    /**
     * 用 refresh_token 刷新 Access-Token
     */
    private String doRefreshToken() throws IOException {
        String url = "https://open.oceanengine.com" + REFRESH_TOKEN_PATH;

        String body = objectMapper.writeValueAsString(Map.of(
                "app_id", appId,
                "secret", secret,
                "grant_type", "refresh_token",
                "refresh_token", refresh_token
        ));

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse("application/json"), body))
                .addHeader("Content-Type", "application/json")
                .build();

        Response response = httpClient.newCall(request).execute();
        String respBody = response.body() != null ? response.body().string() : "";

        Map<String, Object> respMap = objectMapper.readValue(respBody, Map.class);
        Integer code = (Integer) respMap.get("code");

        if (code == null || code != 0) {
            log.error("刷新Access-Token失败, code={}, message={}", code, respMap.get("message"));
            // 刷新失败，清除 refresh_token，下次走 app_token 流程
            this.refresh_token = null;
            return doGetAppToken();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) respMap.get("data");
        String token = (String) data.get("access_token");
        Integer expiresIn = (Integer) data.get("expires_in");
        this.refresh_token = (String) data.get("refresh_token");

        cacheToken(token, expiresIn);
        log.info("刷新Access-Token成功, expiresIn={}s", expiresIn);
        return token;
    }

    /**
     * 缓存 Token，提前5分钟过期
     */
    private void cacheToken(String token, Integer expiresIn) {
        this.cachedAccessToken = token;
        // 提前5分钟刷新
        long advanceMs = 5 * 60 * 1000L;
        this.expireTimestamp = System.currentTimeMillis() + (expiresIn != null ? expiresIn : 86400) * 1000L - advanceMs;
    }
}
