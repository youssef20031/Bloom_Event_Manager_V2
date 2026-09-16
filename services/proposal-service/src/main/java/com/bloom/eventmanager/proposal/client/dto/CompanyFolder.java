package com.bloom.eventmanager.proposal.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyFolder(String id, String name, int briefCount) {
}
