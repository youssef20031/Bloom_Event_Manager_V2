package com.bloom.eventmanager.storage.mongo;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DriveTokenRepository extends MongoRepository<DriveTokenDocument, String> {

    Optional<DriveTokenDocument> findByAccountId(String accountId);

    void deleteByAccountId(String accountId);
}
