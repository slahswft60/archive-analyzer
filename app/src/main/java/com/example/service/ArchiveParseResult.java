package com.example.service;

import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;
import java.util.List;

/**
 * Result representing the parsed archive and its metadata.
 */
public class ArchiveParseResult {

    public enum ArchiveType {
        ZIP,
        TAR,
        UNKNOWN
    }

    private final ArchiveType archiveType;
    private final String originalUrl;
    private final String resolvedUrl;
    private final long totalFileSize;
    private final boolean supportsRange;
    private final List<ArchiveEntry> entries;

    public ArchiveParseResult(
            ArchiveType archiveType,
            String originalUrl,
            String resolvedUrl,
            long totalFileSize,
            boolean supportsRange,
            List<ArchiveEntry> entries) {
        this.archiveType = archiveType;
        this.originalUrl = originalUrl;
        this.resolvedUrl = resolvedUrl;
        this.totalFileSize = totalFileSize;
        this.supportsRange = supportsRange;
        this.entries = entries;
    }

    public ArchiveType getArchiveType() {
        return archiveType;
    }

    public String getArchiveTypeString() {
        return archiveType != null ? archiveType.name() : "UNKNOWN";
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getResolvedUrl() {
        return resolvedUrl;
    }

    public long getTotalFileSize() {
        return totalFileSize;
    }

    public boolean isSupportsRange() {
        return supportsRange;
    }

    public List<ArchiveEntry> getEntries() {
        return entries;
    }

    public int getEntryCount() {
        return entries != null ? entries.size() : 0;
    }
}
