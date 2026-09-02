package com.easysys.engine.service;

import com.easysys.channel.ChannelAdapter;
import com.easysys.engine.EngineException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通道路由：按 channel() 标识注册全部 ChannelAdapter Bean，ACTION 执行按标识分发。
 * 同名通道出现多个适配器时后者覆盖前者（真实供应商接入时保持一行 bean 声明即可）。
 */
@Service
public class ChannelRouter {

    private final Map<String, ChannelAdapter> adapters;

    public ChannelRouter(List<ChannelAdapter> adapters) {
        Map<String, ChannelAdapter> byChannel = new HashMap<>();
        for (ChannelAdapter adapter : adapters) {
            byChannel.put(adapter.channel(), adapter);
        }
        this.adapters = byChannel;
    }

    /** 取通道适配器，未注册则抛错（节点 FAILED，执行 FAILED）。 */
    public ChannelAdapter require(String channel) {
        ChannelAdapter adapter = adapters.get(channel);
        if (adapter == null) {
            throw new EngineException("未注册的通道适配器: " + channel);
        }
        return adapter;
    }
}