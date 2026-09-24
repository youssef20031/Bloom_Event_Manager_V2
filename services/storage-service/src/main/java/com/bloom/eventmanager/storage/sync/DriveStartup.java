package com.bloom.eventmanager.storage.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;

/** V1's main.ts Drive block: load credentials, start auto-sync if a token is already stored. */
@Component
public class DriveStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DriveStartup.class);

    private final DriveAuthManager auth;
    private final AutoSyncScheduler scheduler;
    private final StorageProperties properties;

    public DriveStartup(DriveAuthManager auth, AutoSyncScheduler scheduler, StorageProperties properties) {
        this.auth = auth;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            auth.loadCredentials();
            if (auth.isAuthenticated()) {
                scheduler.start(properties.syncIntervalMs(), properties.syncInitialDelayMs());
            }
        }
        catch (RuntimeException ex) {
            log.warn("Google Drive initialisation skipped: {}", ex.getMessage());
        }
    }
}
