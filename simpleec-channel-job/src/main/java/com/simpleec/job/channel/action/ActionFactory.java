package com.simpleec.job.channel.action;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ActionFactory {

    private final Map<String, ActionService> services = new HashMap<>();

    public ActionFactory(List<ActionService> actionServices) {
        for (ActionService service : actionServices) {
            services.put(service.getAction(), service);
        }
    }

    public ActionService getService(String topic, String action) {
        return services.get(action);
    }
}
