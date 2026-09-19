package com.example.parser;

import com.example.compression.Lz4Decompressor;
import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class RemoteTarParser {

    private static final int BLOCK_SIZE = 512;
    private static final int BUFFER_CHUNK_SIZE = 64 * 1024; // 64KB chunks to optimize network roundtrips

    private final HttpRangeClient httpClient;

    public interface TarParseProgressListener {
        void onHeaderParsed(int count, String currentFileName, long currentOffset);
    }

    public RemoteTarParser(HttpRangeClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Parse a standalone TAR archive from the beginning.
     */
    public List<ArchiveEntry> parseTarArchive(String resolvedUrl, long totalFileSize) throws IOException {
        return parseTarArchive(resolvedUrl, totalFileSize, null);
    }

    public List<ArchiveEntry> parseTarArchive(String resolvedUrl, long totalFileSize, TarParseProgressListener listener) throws IOException {
        return parseTarStream(resolvedUrl, 0, totalFileSize, listener);
    }

    /**
     * Parse a TAR archive located at a specific byte offset inside a remote file (such as a uncompressed .tar / .tar.md5 inside a ZIP).
     */
    public List<ArchiveEntry> parseTarAtOffset(String resolvedUrl, long baseOffset, long tarLength, TarParseProgressListener listener) throws IOException {
        return parseTarStream(resolvedUrl, baseOffset, tarLength, listener);
    }

    /**
     * Checks if the first block at baseOffset has valid TAR headers (ustar magic at byte 257).
     */
    public boolean verifyTarHeader(String resolvedUrl, long baseOffset) {
        try {
            // Read 8KB to check first header
            byte[] initialBytes = httpClient.fetchRange(resolvedUrl, baseOffset, baseOffset + 8191);
            if (initialBytes.length < BLOCK_SIZE) {
                return false;
            }
            return isTarBlockHeader(initialBytes, 0);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isTarBlockHeader(byte[] buffer, int offset) {
        if (offset + BLOCK_SIZE > buffer.length) return false;
        // Check magic at offset + 257 ("ustar")
        if (buffer[offset + 257] == 'u' &&
            buffer[offset + 258] == 's' &&
            buffer[offset + 259] == 't' &&
            buffer[offset + 260] == 'a' &&
            buffer[offset + 261] == 'r') {
            return true;
        }
        // Also check if valid non-empty name exists and typeflag is valid
        String name = readString(buffer, offset, 100);
        byte typeFlag = buffer[offset + 156];
        if (!name.isEmpty() && (typeFlag == 0 || (typeFlag >= '0' && typeFlag <= '6'))) {
            // Check if size field is valid octal
            long size = readOctal(buffer, offset + 124, 12);
            return size >= 0;
        }
        return false;
    }

    private List<ArchiveEntry> parseTarStream(String resolvedUrl, long baseOffset, long totalLength, TarParseProgressListener listener) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        long currentOffset = 0; // Relative to baseOffset

        // For .tar.md5 files, the last 32-34 bytes are ASCII md5 checksum ("...  filename\n")
        long usableTarLength = totalLength;
        if (usableTarLength > 64) {
            usableTarLength -= 34; // Don't parse the trailing MD5 trailer as TAR blocks
        }

        byte[] currentBuffer = null;
        long bufferStartOffset = -1; // Relative to baseOffset
        long bufferEndOffset = -1;
        int consecutiveZeroBlocks = 0;

        while (currentOffset + BLOCK_SIZE <= usableTarLength) {
            // Ensure 512-byte block at currentOffset is cached in currentBuffer
            if (currentBuffer == null || currentOffset < bufferStartOffset || currentOffset + BLOCK_SIZE > bufferEndOffset) {
                bufferStartOffset = currentOffset;
                long fetchStart = baseOffset + bufferStartOffset;
                long fetchEnd = Math.min(baseOffset + currentOffset + BUFFER_CHUNK_SIZE - 1, baseOffset + usableTarLength - 1);
                currentBuffer = httpClient.fetchRange(resolvedUrl, fetchStart, fetchEnd);
                bufferEndOffset = bufferStartOffset + currentBuffer.length;
            }

            int offsetInBuffer = (int) (currentOffset - bufferStartOffset);
            if (offsetInBuffer + BLOCK_SIZE > currentBuffer.length) {
                break;
            }

            // Check for zero block
            boolean isAllZero = true;
            for (int i = 0; i < BLOCK_SIZE; i++) {
                if (currentBuffer[offsetInBuffer + i] != 0) {
                    isAllZero = false;
                    break;
                }
            }

            if (isAllZero) {
                consecutiveZeroBlocks++;
                if (consecutiveZeroBlocks >= 2) {
                    // Standard TAR end of archive marker
                    break;
                }
                currentOffset += BLOCK_SIZE;
                continue;
            } else {
                consecutiveZeroBlocks = 0;
            }

            // Parse TAR header
            String rawName = readString(currentBuffer, offsetInBuffer, 100);
            if (rawName.isEmpty()) {
                currentOffset += BLOCK_SIZE;
                continue;
            }

            long fileSize = readOctal(currentBuffer, offsetInBuffer + 124, 12);
            byte typeFlag = currentBuffer[offsetInBuffer + 156];
            String prefix = readString(currentBuffer, offsetInBuffer + 345, 155);

            String fullPath = rawName;
            if (!prefix.isEmpty()) {
                fullPath = prefix + "/" + rawName;
            }

            boolean isDirectory = (typeFlag == '5') || fullPath.endsWith("/");

            long entryHeaderAbsolute = baseOffset + currentOffset;
            long dataOffsetAbsolute = entryHeaderAbsolute + BLOCK_SIZE;

            ArchiveEntry entry = new ArchiveEntry(
                    fullPath,
                    fileSize,
                    fileSize,
                    entryHeaderAbsolute,
                    dataOffsetAbsolute,
                    -1,
                    isDirectory,
                    "TAR",
                    0
            );
            entries.add(entry);

            if (listener != null) {
                listener.onHeaderParsed(entries.size(), fullPath, entryHeaderAbsolute);
            }

            // Calculate next entry header offset:
            // In TAR, file data is padded to 512-byte boundaries
            long paddedDataSize = ((fileSize + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;
            currentOffset += BLOCK_SIZE + paddedDataSize;
        }

        return entries;
    }

    public File downloadAndExtractEntry(String resolvedUrl, ArchiveEntry entry,
                                       File destinationDirectory, HttpRangeClient.ProgressListener progressListener)
            throws IOException {
        long dataOffset = entry.getDataOffset();
        if (dataOffset <= 0) {
            dataOffset = entry.getHeaderOffset() + BLOCK_SIZE;
            entry.setDataOffset(dataOffset);
        }

        long fileSize = entry.getUncompressedSize();
        long endOffset = dataOffset + fileSize - 1;

        String targetFileName = entry.getSimpleFileName();
        File tempDownloadedFile = new File(destinationDirectory, targetFileName + ".part");
        File finalExtractedFile = new File(destinationDirectory, targetFileName);

        if (progressListener != null) {
            progressListener.onProgress(0, fileSize, "Downloading TAR byte range (" + ArchiveEntry.formatBytes(fileSize) + ")...");
        }

        if (fileSize == 0) {
            finalExtractedFile.createNewFile();
            return finalExtractedFile;
        }

        try (Response response = httpClient.fetchRangeResponse(resolvedUrl, dataOffset, endOffset)) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body for TAR entry");

            try (InputStream netStream = body.byteStream();
                 BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(tempDownloadedFile))) {

                byte[] buffer = new byte[64 * 1024];
                long totalRead = 0;
                int read;
                while ((read = netStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    totalRead += read;
                    if (progressListener != null) {
                        progressListener.onProgress(totalRead, fileSize, "Writing extracted file data...");
                    }
                }
                fos.flush();
            }
        }

        if (finalExtractedFile.exists()) {
            finalExtractedFile.delete();
        }
        if (!tempDownloadedFile.renameTo(finalExtractedFile)) {
            copyFile(tempDownloadedFile, finalExtractedFile);
            tempDownloadedFile.delete();
        }

        // Decompress LZ4 if needed
        if (entry.isLz4() || Lz4Decompressor.isLz4File(finalExtractedFile)) {
            if (progressListener != null) {
                progressListener.onProgress(0, 0, "Decompressing LZ4 payload...");
            }
            String decompressedName = entry.getDecompressedTargetName();
            File decompressedFile = new File(destinationDirectory, decompressedName);
            try {
                boolean success = Lz4Decompressor.decompressLz4File(finalExtractedFile, decompressedFile);
                if (success && decompressedFile.exists() && decompressedFile.length() > 0) {
                    return decompressedFile;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return finalExtractedFile;
    }

    private static String readString(byte[] buf, int offset, int length) {
        int end = offset;
        int max = offset + length;
        while (end < max && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long readOctal(byte[] buf, int offset, int length) {
        long result = 0;
        int end = offset + length;
        int start = offset;
        while (start < end && (buf[start] == ' ' || buf[start] == 0)) {
            start++;
        }
        while (start < end && buf[start] >= '0' && buf[start] <= '7') {
            result = (result << 3) + (buf[start] - '0');
            start++;
        }
        return result;
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
