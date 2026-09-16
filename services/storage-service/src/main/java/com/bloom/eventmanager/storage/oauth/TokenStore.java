package com.bloom.eventmanager.storage.oauth;

import java.util.Optional;

public interface TokenStore {

    Optional<StoredToken> load(String accountId);

    void save(String accountId, StoredToken token);

    void clear(String accountId);
}
