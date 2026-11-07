package com.hsf.aichat.ai;

import com.hsf.aichat.entity.ProviderType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Finds the ChatProvider implementation for a ProviderType. Spring injects every @Component ChatProvider. */
@Component
public class ProviderRegistry {

    private final Map<ProviderType, ChatProvider> providers = new EnumMap<>(ProviderType.class);

    public ProviderRegistry(List<ChatProvider> all) {
        for (ChatProvider p : all) {
            providers.put(p.type(), p);
        }
    }

    public ChatProvider get(ProviderType type) {
        ChatProvider p = providers.get(type);
        if (p == null) {
            throw new AiException("No implementation for provider type " + type);
        }
        return p;
    }
}
