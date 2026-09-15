package com.bloom.eventmanager.retrieval.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** V1's {specific, broad} query pair. Either may be null straight off the wire. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchVariations(String specific, String broad) {

    public static SearchVariations same(String query) {
        return new SearchVariations(query, query);
    }
}
