package com.salarytracker.push;

import java.util.Map;

/** 一个外部通道的发送器；新增通道只需实现本接口并注册为 Bean。 */
public interface PushSender {
    /** FEISHU / BARK / EMAIL */
    String kind();

    /** 必填配置是否齐全；缺配置时不算错误，只是不参与发送 */
    boolean ready(Map<String, String> config);

    void send(Map<String, String> config, PushModels.Message message) throws Exception;

    /** 带事件类型的重载：飞书按事件决定卡片配色，Bark 用来分组 */
    default void send(Map<String, String> config, PushModels.Message message, String event) throws Exception {
        send(config, message);
    }
}
