package com.bloom.eventmanager.proposal.domain;

public record IntakeSubmission(boolean success, String message, String companyName, String folderName,
        String fileName, String folderId, String fileId, String viewLink) {
}
