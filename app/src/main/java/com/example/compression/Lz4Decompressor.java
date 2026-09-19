package com.example.compression;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class Lz4Decompressor {

    public static final int LZ4_FRAME_MAGIC = 0x184D2204;
    public static final int LZ4_LEGACY_MAGIC = 0x184C2102;

    public static boolean isLz4Header(byte[] header) {
        if (header == null || header.length < 4) return false;
        int magic = (header[0] & 0xFF)
                | ((header[1] & 0xFF) << 8)
                | ((header[2] & 0xFF) << 16)
                | ((header[3] & 0xFF) << 24);
        return magic == LZ4_FRAME_MAGIC || magic == LZ4_LEGACY_MAGIC;
    }

    public static boolean isLz4File(File file) {
        if (!file.exists() || file.length() < 4) return false;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magicBytes = new byte[4];
            int read = fis.read(magicBytes);
            return read == 4 && isLz4Header(magicBytes);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean decompressLz4File(File inputFile, File outputFile) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inputFile));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            return decompressLz4Stream(in, out);
        }
    }

    public static boolean decompressLz4Stream(InputStream in, OutputStream out) throws IOException {
        byte[] magicBytes = new byte[4];
        readFully(in, magicBytes, 0, 4);
        int magic = (magicBytes[0] & 0xFF)
                | ((magicBytes[1] & 0xFF) << 8)
                | ((magicBytes[2] & 0xFF) << 16)
                | ((magicBytes[3] & 0xFF) << 24);

        if (magic == LZ4_FRAME_MAGIC) {
            decompressFrame(in, out);
            return true;
        } else if (magic == LZ4_LEGACY_MAGIC) {
            decompressLegacy(in, out);
            return true;
        } else {
            throw new IOException("Invalid LZ4 magic header: 0x" + Integer.toHexString(magic));
        }
    }

    private static void decompressFrame(InputStream in, OutputStream out) throws IOException {
        int flg = in.read();
        int bd = in.read();
        if (flg == -1 || bd == -1) throw new IOException("Truncated LZ4 frame header");

        int version = (flg >>> 6) & 0x03;
        if (version != 1) {
            throw new IOException("Unsupported LZ4 frame version: " + version);
        }

        boolean blockChecksum = ((flg >>> 4) & 1) != 0;
        boolean contentSizeFlag = ((flg >>> 3) & 1) != 0;
        boolean contentChecksum = ((flg >>> 2) & 1) != 0;
        boolean dictIdFlag = (flg & 1) != 0;

        int blockSizeId = (bd >>> 4) & 0x07;
        int maxBlockSize;
        switch (blockSizeId) {
            case 4: maxBlockSize = 64 * 1024; break;
            case 5: maxBlockSize = 256 * 1024; break;
            case 6: maxBlockSize = 1024 * 1024; break;
            case 7: maxBlockSize = 4 * 1024 * 1024; break;
            default: maxBlockSize = 4 * 1024 * 1024; break;
        }

        if (contentSizeFlag) {
            skipBytes(in, 8);
        }
        if (dictIdFlag) {
            skipBytes(in, 4);
        }
        // Header checksum (1 byte)
        int hc = in.read();
        if (hc == -1) throw new IOException("Truncated LZ4 frame header checksum");

        byte[] compressedBuf = new byte[maxBlockSize];
        byte[] decompressedBuf = new byte[maxBlockSize];

        byte[] sizeBytes = new byte[4];
        while (true) {
            int read = in.read(sizeBytes);
            if (read < 4) {
                if (read <= 0) break;
                readFully(in, sizeBytes, read, 4 - read);
            }
            int blockSize = (sizeBytes[0] & 0xFF)
                    | ((sizeBytes[1] & 0xFF) << 8)
                    | ((sizeBytes[2] & 0xFF) << 16)
                    | ((sizeBytes[3] & 0xFF) << 24);

            if (blockSize == 0) {
                // End of blocks
                if (contentChecksum) {
                    skipBytes(in, 4);
                }
                break;
            }

            boolean isUncompressed = (blockSize & 0x80000000) != 0;
            int actualSize = blockSize & 0x7FFFFFFF;

            if (actualSize > compressedBuf.length) {
                compressedBuf = new byte[actualSize];
            }
            readFully(in, compressedBuf, 0, actualSize);

            if (isUncompressed) {
                out.write(compressedBuf, 0, actualSize);
            } else {
                int decompressedSize = decompressBlock(compressedBuf, 0, actualSize, decompressedBuf, 0, decompressedBuf.length);
                out.write(decompressedBuf, 0, decompressedSize);
            }

            if (blockChecksum) {
                skipBytes(in, 4);
            }
        }
    }

    private static void decompressLegacy(InputStream in, OutputStream out) throws IOException {
        byte[] sizeBytes = new byte[4];
        byte[] compressedBuf = new byte[8 * 1024 * 1024];
        byte[] decompressedBuf = new byte[8 * 1024 * 1024];

        while (true) {
            int read = in.read(sizeBytes);
            if (read < 4) break;
            int blockSize = (sizeBytes[0] & 0xFF)
                    | ((sizeBytes[1] & 0xFF) << 8)
                    | ((sizeBytes[2] & 0xFF) << 16)
                    | ((sizeBytes[3] & 0xFF) << 24);

            if (blockSize <= 0) break;
            if (blockSize > compressedBuf.length) {
                compressedBuf = new byte[blockSize];
            }
            readFully(in, compressedBuf, 0, blockSize);
            int decompressedSize = decompressBlock(compressedBuf, 0, blockSize, decompressedBuf, 0, decompressedBuf.length);
            out.write(decompressedBuf, 0, decompressedSize);
        }
    }

    public static int decompressBlock(byte[] src, int srcOffset, int srcLen,
                                      byte[] dst, int dstOffset, int maxDstLen) throws IOException {
        int srcPos = srcOffset;
        int srcEnd = srcOffset + srcLen;
        int dstPos = dstOffset;
        int dstEnd = dstOffset + maxDstLen;

        while (srcPos < srcEnd) {
            int token = src[srcPos++] & 0xFF;

            // 1. Literal length
            int literalLen = token >>> 4;
            if (literalLen == 15) {
                int s;
                while (srcPos < srcEnd && (s = src[srcPos++] & 0xFF) == 255) {
                    literalLen += 255;
                }
                if (srcPos <= srcEnd) {
                    literalLen += (src[srcPos - 1] & 0xFF);
                }
            }

            // Copy literals
            if (literalLen > 0) {
                if (srcPos + literalLen > srcEnd || dstPos + literalLen > dstEnd) {
                    throw new IOException("Corrupt LZ4 block: literal overflow (pos=" + srcPos + ", len=" + literalLen + ")");
                }
                System.arraycopy(src, srcPos, dst, dstPos, literalLen);
                srcPos += literalLen;
                dstPos += literalLen;
            }

            if (srcPos >= srcEnd) {
                break;
            }

            // 2. Match distance (2 bytes little endian)
            if (srcPos + 2 > srcEnd) {
                throw new IOException("Corrupt LZ4 block: incomplete match distance");
            }
            int matchDist = (src[srcPos++] & 0xFF) | ((src[srcPos++] & 0xFF) << 8);
            if (matchDist == 0) {
                throw new IOException("Corrupt LZ4 block: zero match distance");
            }

            // 3. Match length
            int matchLen = (token & 0x0F) + 4;
            if ((token & 0x0F) == 15) {
                int s;
                while (srcPos < srcEnd && (s = src[srcPos++] & 0xFF) == 255) {
                    matchLen += 255;
                }
                if (srcPos <= srcEnd) {
                    matchLen += (src[srcPos - 1] & 0xFF);
                }
            }

            if (dstPos + matchLen > dstEnd) {
                throw new IOException("Corrupt LZ4 block: match overflow");
            }

            // Copy matching bytes (byte-by-byte to handle overlapping / run-length encoding)
            int matchSrc = dstPos - matchDist;
            if (matchSrc < dstOffset) {
                throw new IOException("Corrupt LZ4 block: match distance exceeds buffer start");
            }
            for (int i = 0; i < matchLen; i++) {
                dst[dstPos++] = dst[matchSrc++];
            }
        }

        return dstPos - dstOffset;
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int count = in.read(b, off + n, len - n);
            if (count < 0) {
                throw new IOException("Unexpected EOF while reading LZ4 data");
            }
            n += count;
        }
    }

    private static void skipBytes(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("Unexpected EOF while skipping");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
