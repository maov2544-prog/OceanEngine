
package com.example.oceanengine.test.qianchuan;
import com.example.oceanengine.client.QianchuanTokenClient;

public class QianchuanTokenTest {

    public static void main(String[] args) {
        try {
            String token =
                    QianchuanTokenClient.getAccessToken();

            System.out.println("千川 PC Token 获取成功");
            System.out.println(
                    "Token 长度：" + token.length()
            );

        } catch (Exception e) {
            System.err.println(
                    "获取千川 Token 失败：" + e.getMessage()
            );
        }
    }
}
