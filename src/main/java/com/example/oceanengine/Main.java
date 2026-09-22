
package com.example.oceanengine;

import com.bytedance.ads.ApiClient;

public class Main {

    public static void main(String[] args) {

        String token = System.getenv(
            "OCEANENGINE_ACCESS_TOKEN"
        );

        if (token == null || token.isBlank()) {
            System.out.println(
                "未设置 OCEANENGINE_ACCESS_TOKEN"
            );
            return;
        }

        ApiClient apiClient = new ApiClient();

        apiClient.setBasePath(
            "https://api.oceanengine.com"
        );

        apiClient.addDefaultHeader(
            "Access-Token",
            token
        );

        System.out.println("SDK Client 初始化成功！");
    }
}
