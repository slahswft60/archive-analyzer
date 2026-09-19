package com.example.parser;

import com.example.compression.Lz4Decompressor;
import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class RemoteZipParser {

    private static final int EOCD_SIGNATURE = 0x06054B50;
    private static final int ZIP64_EOCD_LOCATOR_SIG = 0x07064B50;
    private static final int ZIP64_EOCD_RECORD_SIG = 0x06064B50;
    private static final int CDFH_SIGNATURE = 0x02014B50;
    private static final int LFH_SIGNATURE = 0x04034B50;

    private final HttpRangeClient httpClient;

    public RemoteZipParser(HttpRangeClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<ArchiveEntry> parseCentralDirectory(String resolvedUrl, long totalFileSize) throws IOException {
        if (totalFileSize < 22) {
            throw new IOException("File too small to be a valid ZIP archive (" + totalFileSize + " bytes)");
        }

        // Read up to 65KB + 22 bytes from the end of the file
        long readLength = Math.min(totalFileSize, 65536L + 22L + 1024L);
        long startByte = totalFileSize - readLength;
        byte[] endBuffer = httpClient.fetchRange(resolvedUrl, startByte, totalFileSize - 1);

        // Search backward for EOCD signature 0x06054B50
        int eocdOffsetInBuffer = -1;
        for (int i = endBuffer.length - 22; i >= 0; i--) {
            if (endBuffer[i] == 0x50 && endBuffer[i + 1] == 0x4B &&
                    endBuffer[i + 2] == 0x05 && endBuffer[i + 3] == 0x06) {
                eocdOffsetInBuffer = i;
                break;
            }
        }

        if (eocdOffsetInBuffer == -1) {
            throw new IOException("End of Central Directory (EOCD) signature not found. The file may not be a valid ZIP archive or corrupted.");
        }

        ByteBuffer eocdBuf = ByteBuffer.wrap(endBuffer, eocdOffsetInBuffer, endBuffer.length - eocdOffsetInBuffer);
        eocdBuf.order(ByteOrder.LITTLE_ENDIAN);

        eocdBuf.getInt(); // skip signature
        int diskNumber = eocdBuf.getShort() & 0xFFFF;
        int startDisk = eocdBuf.getShort() & 0xFFFF;
        int totalEntriesOnDisk = eocdBuf.getShort() & 0xFFFF;
        int totalEntries = eocdBuf.getShort() & 0xFFFF;
        long cdSize = eocdBuf.getInt() & 0xFFFFFFFFL;
        long cdOffset = eocdBuf.getInt() & 0xFFFFFFFFL;

        // Check for Zip64
        if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            int zip64LocatorOffset = -1;
            for (int i = eocdOffsetInBuffer - 20; i >= 0 && i >= eocdOffsetInBuffer - 40; i--) {
                if (endBuffer[i] == 0x50 && endBuffer[i + 1] == 0x4B &&
                        endBuffer[i + 2] == 0x06 && endBuffer[i + 3] == 0x07) {
                    zip64LocatorOffset = i;
                    break;
                }
            }
            if (zip64LocatorOffset != -1) {
                ByteBuffer locBuf = ByteBuffer.wrap(endBuffer, zip64LocatorOffset, 20);
                locBuf.order(ByteOrder.LITTLE_ENDIAN);
                locBuf.getInt(); // signature
                locBuf.getInt(); // disk
                long zip64EocdOffset = locBuf.getLong();

                // Fetch zip64 EOCD record
                byte[] zip64Eocd = httpClient.fetchRange(resolvedUrl, zip64EocdOffset, zip64EocdOffset + 55);
                ByteBuffer z64Buf = ByteBuffer.wrap(zip64Eocd);
                z64Buf.order(ByteOrder.LITTLE_ENDIAN);
                int z64Sig = z64Buf.getInt();
                if (z64Sig == ZIP64_EOCD_RECORD_SIG) {
                    z64Buf.getLong(); // size of zip64 EOCD
                    z64Buf.getShort(); // version made by
                    z64Buf.getShort(); // version needed
                    z64Buf.getInt(); // disk number
                    z64Buf.getInt(); // disk start
                    z64Buf.getLong(); // total entries on disk
                    long z64TotalEntries = z64Buf.getLong();
                    cdSize = z64Buf.getLong();
                    cdOffset = z64Buf.getLong();
                    if (z64TotalEntries > 0 && z64TotalEntries < Integer.MAX_VALUE) {
                        totalEntries = (int) z64TotalEntries;
                    }
                }
            }
        }

        if (cdOffset < 0 || cdOffset >= totalFileSize || cdSize <= 0) {
            throw new IOException("Invalid ZIP Central Directory bounds (offset=" + cdOffset + ", size=" + cdSize + ")");
        }

        // Check if Central Directory is already contained in our fetched endBuffer
        byte[] cdBytes;
        if (cdOffset >= startByte && (cdOffset + cdSize) <= totalFileSize) {
            int offsetInBuf = (int) (cdOffset - startByte);
            cdBytes = new byte[(int) cdSize];
            System.arraycopy(endBuffer, offsetInBuf, cdBytes, 0, (int) cdSize);
        } else {
            // Need a single targeted range request for central directory
            cdBytes = httpClient.fetchRange(resolvedUrl, cdOffset, cdOffset + cdSize - 1);
        }

        return parseEntriesFromCentralDirectory(cdBytes);
    }

    private List<ArchiveEntry> parseEntriesFromCentralDirectory(byte[] cdBytes) {
        List<ArchiveEntry> list = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(cdBytes);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        while (buf.remaining() >= 46) {
            int sig = buf.getInt();
            if (sig != CDFH_SIGNATURE) {
                break;
            }

            int versionMadeBy = buf.getShort() & 0xFFFF;
            int versionNeeded = buf.getShort() & 0xFFFF;
            int flags = buf.getShort() & 0xFFFF;
            int compressionMethod = buf.getShort() & 0xFFFF;
            int lastModTime = buf.getShort() & 0xFFFF;
            int lastModDate = buf.getShort() & 0xFFFF;
            long crc32 = buf.getInt() & 0xFFFFFFFFL;
            long compressedSize = buf.getInt() & 0xFFFFFFFFL;
            long uncompressedSize = buf.getInt() & 0xFFFFFFFFL;
            int fileNameLen = buf.getShort() & 0xFFFF;
            int extraLen = buf.getShort() & 0xFFFF;
            int commentLen = buf.getShort() & 0xFFFF;
            int diskStart = buf.getShort() & 0xFFFF;
            int internalAttrs = buf.getShort() & 0xFFFF;
            long externalAttrs = buf.getInt() & 0xFFFFFFFFL;
            long localHeaderOffset = buf.getInt() & 0xFFFFFFFFL;

            if (buf.remaining() < fileNameLen + extraLen + commentLen) {
                break;
            }

            byte[] nameBytes = new byte[fileNameLen];
            buf.get(nameBytes);

            Charset charset = ((flags & (1 << 11)) != 0) ? StandardCharsets.UTF_8 : Charset.forName("CP437");
            String fileName = new String(nameBytes, charset);

            byte[] extraBytes = new byte[extraLen];
            buf.get(extraBytes);

            // Zip64 extra field parser (ID 0x0001)
            if (extraLen >= 4) {
                ByteBuffer extraBuf = ByteBuffer.wrap(extraBytes);
                extraBuf.order(ByteOrder.LITTLE_ENDIAN);
                while (extraBuf.remaining() >= 4) {
                    int headerId = extraBuf.getShort() & 0xFFFF;
                    int dataSize = extraBuf.getShort() & 0xFFFF;
                    if (extraBuf.remaining() < dataSize) break;

                    if (headerId == 0x0001) {
                        if (uncompressedSize == 0xFFFFFFFFL && extraBuf.remaining() >= 8) {
                            uncompressedSize = extraBuf.getLong();
                        }
                        if (compressedSize == 0xFFFFFFFFL && extraBuf.remaining() >= 8) {
                            compressedSize = extraBuf.getLong();
                        }
                        if (localHeaderOffset == 0xFFFFFFFFL && extraBuf.remaining() >= 8) {
                            localHeaderOffset = extraBuf.getLong();
                        }
                        break;
                    } else {
                        extraBuf.position(extraBuf.position() + dataSize);
                    }
                }
            }

            if (commentLen > 0) {
                buf.position(buf.position() + commentLen);
            }

            boolean isDir = fileName.endsWith("/") || (externalAttrs & 0x10) != 0;
            list.add(new ArchiveEntry(fileName, compressedSize, uncompressedSize,
                    localHeaderOffset, -1, compressionMethod, isDir, "ZIP", crc32));
        }

        return list;
    }

    public long resolveEntryDataOffset(String resolvedUrl, ArchiveEntry entry) throws IOException {
        if (entry.getDataOffset() > 0) {
            return entry.getDataOffset();
        }
        long localHeaderOffset = entry.getHeaderOffset();
        byte[] lfhFixed = httpClient.fetchRange(resolvedUrl, localHeaderOffset, localHeaderOffset + 29);
        ByteBuffer lfhBuf = ByteBuffer.wrap(lfhFixed);
        lfhBuf.order(ByteOrder.LITTLE_ENDIAN);

        int sig = lfhBuf.getInt();
        if (sig != LFH_SIGNATURE) {
            throw new IOException("Invalid Local File Header signature at offset " + localHeaderOffset + " (0x" + Integer.toHexString(sig) + ")");
        }
        lfhBuf.position(26);
        int localNameLen = lfhBuf.getShort() & 0xFFFF;
        int localExtraLen = lfhBuf.getShort() & 0xFFFF;

        long dataStartOffset = localHeaderOffset + 30 + localNameLen + localExtraLen;
        entry.setDataOffset(dataStartOffset);
        return dataStartOffset;
    }

    public File downloadAndExtractEntry(String resolvedUrl, ArchiveEntry entry,
                                       File destinationDirectory, HttpRangeClient.ProgressListener progressListener)
            throws IOException {
        long dataStartOffset = resolveEntryDataOffset(resolvedUrl, entry);
        long dataEndOffset = dataStartOffset + entry.getCompressedSize() - 1;

        String targetFileName = entry.getSimpleFileName();
        File tempDownloadedFile = new File(destinationDirectory, targetFileName + ".part");
        File finalExtractedFile = new File(destinationDirectory, targetFileName);

        if (progressListener != null) {
            progressListener.onProgress(0, entry.getCompressedSize(), "Downloading remote byte range (" + ArchiveEntry.formatBytes(entry.getCompressedSize()) + ")...");
        }

        // Stream compressed byte range from server
        try (Response response = httpClient.fetchRangeResponse(resolvedUrl, dataStartOffset, dataEndOffset)) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body for entry byte range");

            try (InputStream netStream = body.byteStream();
                 BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(tempDownloadedFile))) {

                InputStream dataStream;
                if (entry.getCompressionMethod() == 8) {
                    // Raw DEFLATE in ZIP (nowrap = true)
                    dataStream = new InflaterInputStream(netStream, new Inflater(true));
                } else {
                    dataStream = netStream;
                }

                byte[] buffer = new byte[64 * 1024];
                long totalRead = 0;
                int read;
                while ((read = dataStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    totalRead += read;
                    if (progressListener != null) {
                        progressListener.onProgress(totalRead, entry.getUncompressedSize() > 0 ? entry.getUncompressedSize() : entry.getCompressedSize(), "Writing file data...");
                    }
                }
                fos.flush();
            }
        }

        if (finalExtractedFile.exists()) {
            finalExtractedFile.delete();
        }
        if (!tempDownloadedFile.renameTo(finalExtractedFile)) {
            // fallback
            copyFile(tempDownloadedFile, finalExtractedFile);
            tempDownloadedFile.delete();
        }

        // Check if file is LZ4 compressed
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
                // If LZ4 decompression encounters issue, return the extracted file as-is
                e.printStackTrace();
            }
        }

        return finalExtractedFile;
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
