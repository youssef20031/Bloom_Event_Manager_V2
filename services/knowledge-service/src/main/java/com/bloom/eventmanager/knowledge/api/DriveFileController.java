package com.bloom.eventmanager.knowledge.api;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.NotFoundException;
import com.bloom.eventmanager.knowledge.api.dto.DriveFileUpsertRequest;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

@RestController
@RequestMapping("/api/v1/drive-files")
public class DriveFileController {

    private final ChunkRepository repository;

    public DriveFileController(ChunkRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Map<String, String> all() {
        return repository.allDriveFileMetadata();
    }

    @GetMapping("/{driveFileId}")
    public DriveFileMetadata get(@PathVariable String driveFileId) {
        return repository.driveFileMetadata(driveFileId)
                .orElseThrow(() -> new NotFoundException("No document for Drive file " + driveFileId));
    }

    @PutMapping("/{driveFileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsert(@PathVariable String driveFileId, @Valid @RequestBody DriveFileUpsertRequest request) {
        repository.upsertDriveFileMetadata(request.source(), driveFileId, request.modifiedTime());
    }
}
