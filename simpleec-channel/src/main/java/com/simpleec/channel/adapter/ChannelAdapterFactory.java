package com.simpleec.channel.adapter;

import com.simpleec.common.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ChannelAdapterFactory {

    private final Map<ChannelType, ChannelAdapter> adapters = new EnumMap<>(ChannelType.class);

    public ChannelAdapterFactory(List<ChannelAdapter> adapterList) {
        for (ChannelAdapter adapter : adapterList) {
            adapters.put(adapter.getChannelType(), adapter);
        }
    }

    public ChannelAdapter getAdapter(ChannelType channelType) {
        ChannelAdapter adapter = adapters.get(channelType);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for channel: " + channelType);
        }
        return adapter;
    }

    public boolean hasAdapter(ChannelType channelType) {
        return adapters.containsKey(channelType);
    }
}
