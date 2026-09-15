package com.bloom.eventmanager.ingestion.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Defaults are V1's CONSTANTS and .env defaults. */
@Validated
@ConfigurationProperties(prefix = "bloom.ingestion")
public record IngestionProperties(
        @DefaultValue("./ISM_PDF") String pdfDirectory,
        @DefaultValue("1000") @Min(50) int chunkSize,
        @DefaultValue("200") @Min(0) int chunkOverlap,
        /** Below this many extracted characters the PDF is treated as scanned and OCR runs. */
        @DefaultValue("100") @Min(0) int ocrMinChars,
        @DefaultValue("300") float ocrDpi,
        @DefaultValue("eng") String ocrLanguage,
        /** Empty means: extract the bundled tessdata from the classpath. */
        @DefaultValue("") String ocrDataPath,
        @DefaultValue("20") @Min(1) @Max(100) int embeddingBatchSize) {
}
