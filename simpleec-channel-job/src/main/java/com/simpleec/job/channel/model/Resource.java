package com.simpleec.job.channel.model;

import com.simpleec.core.kafka.TaskMessage;
import com.simpleec.core.kafka.TaskProducer;
import lombok.Data;

import java.util.Map;

@Data
public class Resource {
    private TaskMessage msg;
    private TaskProducer taskProducer;
    private Map<String, String> platformTokens;
}
