package com.simpleec.job.channel.action;

import com.simpleec.job.channel.model.Resource;

public interface ActionService {
    String getAction();
    void setting(Resource resource);
    void getPlatformTokens();
    void verifyNeedData();
    void doAction();
}
