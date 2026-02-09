package com.simpleec.job.backend.action;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;

public interface BackendActionService {
    String getAction();
    void setting(TaskMessage msg);
    void verify(TaskMessage msg);
    Object execute(TaskMessage msg);
    void routeNext(TaskProducer producer, TaskMessage msg, Object result);
}
