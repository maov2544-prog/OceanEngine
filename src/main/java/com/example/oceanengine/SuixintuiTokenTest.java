
package com.example.oceanengine;

public class SuixintuiTokenTest {

    public static void main(String[] args) {
        try {
            String token =
                    SuixintuiTokenClient.getAccessToken();

            System.out.println("随心推 Token 获取成功");
            System.out.println(
                    "Token 长度：" + token.length()
            );

        } catch (Exception e) {
            System.err.println(
                    "获取 Token 失败：" + e.getMessage()
            );
        }
    }
}
