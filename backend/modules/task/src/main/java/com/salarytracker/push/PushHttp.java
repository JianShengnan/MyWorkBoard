package com.salarytracker.push;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 推送用的 HTTP 客户端：外部服务不可用时最多等 5 + 10 秒，不能把发送线程挂死。 */
final class PushHttp {
    private PushHttp() {
    }

    static RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
