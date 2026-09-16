package com.bloom.eventmanager.proposal.client.dto;

public record CompanyFileRequest(String companyName, String fileName, String mimeType, String contentBase64) {
}
