package com.bloom.eventmanager.proposal.api.dto;

import java.util.List;

import com.bloom.eventmanager.proposal.domain.ProposalSlide;

public record SaveProposalRequest(List<ProposalSlide> slides, String companyName) {

    public boolean isComplete() {
        return slides != null && !slides.isEmpty() && companyName != null && !companyName.isBlank();
    }
}
