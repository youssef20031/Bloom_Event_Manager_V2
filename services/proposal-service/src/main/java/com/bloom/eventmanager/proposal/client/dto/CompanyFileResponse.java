package com.bloom.eventmanager.proposal.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyFileResponse(String folderId, String fileId, String webViewLink) {
}
