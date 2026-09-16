package com.bloom.eventmanager.storage.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public EventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publish(String topic, String key, Object payload) {
        kafka.send(topic, key, json.writeValueAsString(payload)).join();
    }
}
