package com.bloom.eventmanager.proposal.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One of V1's nine slide types; {@code content} is the type-specific object, kept generic for rendering. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProposalSlide(int slideNumber, String type, String title, String subtitle, Map<String, Object> content) {

    public ProposalSlide withNumber(int number) {
        return new ProposalSlide(number, type, title, subtitle, content);
    }

    public ProposalSlide withContent(Map<String, Object> newContent) {
        return new ProposalSlide(slideNumber, type, title, subtitle, newContent);
    }
}
