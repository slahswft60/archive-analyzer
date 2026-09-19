package com.example.model;

import java.util.Locale;

public class ArchiveEntry {
    private final String name;
    private final long compressedSize;
    private final long uncompressedSize;
    private final long headerOffset;
    private long dataOffset;
    private final int compressionMethod;
    private final boolean isDirectory;
    private final String format;
    private final long crc32;

    public ArchiveEntry(String name, long compressedSize, long uncompressedSize,
                        long headerOffset, long dataOffset, int compressionMethod,
                        boolean isDirectory, String format, long crc32) {
        this.name = name;
        this.compressedSize = compressedSize;
        this.uncompressedSize = uncompressedSize;
        this.headerOffset = headerOffset;
        this.dataOffset = dataOffset;
        this.compressionMethod = compressionMethod;
        this.isDirectory = isDirectory;
        this.format = format;
        this.crc32 = crc32;
    }

    public String getName() {
        return name;
    }

    public String getSimpleFileName() {
        if (name == null || name.isEmpty()) return "";
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        int lastSlash = clean.lastIndexOf('/');
        return lastSlash >= 0 ? clean.substring(lastSlash + 1) : clean;
    }

    public String getDirectoryPath() {
        if (name == null || name.isEmpty()) return "";
        int lastSlash = name.lastIndexOf('/');
        return lastSlash >= 0 ? name.substring(0, lastSlash + 1) : "";
    }

    public long getCompressedSize() {
        return compressedSize;
    }

    public long getUncompressedSize() {
        return uncompressedSize;
    }

    public long getHeaderOffset() {
        return headerOffset;
    }

    public long getDataOffset() {
        return dataOffset;
    }

    public void setDataOffset(long dataOffset) {
        this.dataOffset = dataOffset;
    }

    public int getCompressionMethod() {
        return compressionMethod;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getFormat() {
        return format;
    }

    public long getCrc32() {
        return crc32;
    }

    public boolean isLz4() {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".lz4");
    }

    public boolean isTarMd5() {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar.md5");
    }

    public boolean isTar() {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar") || lower.endsWith(".tar.md5");
    }

    public String getDecompressedTargetName() {
        String base = getSimpleFileName();
        if (isLz4() && base.toLowerCase(Locale.ROOT).endsWith(".lz4")) {
            return base.substring(0, base.length() - 4);
        }
        return base;
    }

    public String getCompressionLabel() {
        if ("TAR".equalsIgnoreCase(format)) {
            return isLz4() ? "TAR (LZ4 payload)" : "TAR (Standard)";
        }
        switch (compressionMethod) {
            case 0:
                return isLz4() ? "ZIP: Stored (LZ4)" : "ZIP: Stored";
            case 8:
                return isLz4() ? "ZIP: Deflated (LZ4)" : "ZIP: Deflated";
            case 12:
                return "ZIP: BZIP2";
            case 14:
                return "ZIP: LZMA";
            default:
                return "ZIP: Method " + compressionMethod;
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    public String getFormattedSize() {
        return formatBytes(uncompressedSize);
    }

    public String getFormattedCompressedSize() {
        return formatBytes(compressedSize);
    }
}
