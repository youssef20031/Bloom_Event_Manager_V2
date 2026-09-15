package com.bloom.eventmanager.ai.domain;

public interface GenerationPort {

    GenerationResult generate(String input, GenerationOptions options);
}
