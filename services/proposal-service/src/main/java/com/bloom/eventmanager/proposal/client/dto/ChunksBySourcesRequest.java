package com.bloom.eventmanager.proposal.client.dto;

import java.util.List;

public record ChunksBySourcesRequest(List<String> sources, int limit) {
}
