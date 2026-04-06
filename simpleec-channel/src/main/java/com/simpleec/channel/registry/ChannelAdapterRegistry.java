package com.simpleec.channel.registry;

import com.simpleec.channel.adapter.ChannelAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry for all platform ChannelAdapters.
 * Resolves adapter by platformCode. Used by all Channel Job handlers.
 *
 * When adding a new platform:
 *  1. Create an adapter class implementing ChannelAdapter
 *  2. Register it as a @Bean in ChannelAdapterConfig
 *  3. No changes needed here — Spring auto-collects all ChannelAdapter beans
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> registry;

    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.registry = adapters.stream()
            .collect(Collectors.toMap(
                a -> a.getPlatformCode().toLowerCase(),
                Function.identity()
            ));
    }

    public ChannelAdapter getAdapter(String platformCode) {
        ChannelAdapter adapter = registry.get(platformCode.toLowerCase());
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for platform: " + platformCode);
        }
        return adapter;
    }

    public boolean hasAdapter(String platformCode) {
        return registry.containsKey(platformCode.toLowerCase());
    }
}
