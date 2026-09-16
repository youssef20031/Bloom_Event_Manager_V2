package com.bloom.eventmanager.knowledge.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** JSON-string publishing. {@code join()} surfaces broker failures to the caller. */
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
