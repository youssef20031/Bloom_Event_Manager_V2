package com.bloom.eventmanager.proposal.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedResponse(float[] embedding) {
}
