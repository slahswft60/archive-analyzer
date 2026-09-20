package com.example;

import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;
import com.example.service.ArchiveHeaderService;
import com.example.service.ArchiveParseResult;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ArchiveHeaderServiceTest {

    @Test
    public void testArchiveParseResultModel() {
        List<ArchiveEntry> entries = new ArrayList<>();
        entries.add(new ArchiveEntry("boot.img", 1000, 2000, 0, 100, 0, false, "ZIP", 0L));
        entries.add(new ArchiveEntry("recovery.img", 1500, 3000, 0, 1100, 0, false, "ZIP", 0L));

        ArchiveParseResult result = new ArchiveParseResult(
                ArchiveParseResult.ArchiveType.ZIP,
                "https://example.com/archive.zip",
                "https://example.com/archive.zip",
                5000000L,
                true,
                entries
        );

        Assert.assertEquals(ArchiveParseResult.ArchiveType.ZIP, result.getArchiveType());
        Assert.assertEquals("ZIP", result.getArchiveTypeString());
        Assert.assertEquals("https://example.com/archive.zip", result.getOriginalUrl());
        Assert.assertEquals("https://example.com/archive.zip", result.getResolvedUrl());
        Assert.assertEquals(5000000L, result.getTotalFileSize());
        Assert.assertTrue(result.isSupportsRange());
        Assert.assertEquals(2, result.getEntryCount());
        Assert.assertEquals("boot.img", result.getEntries().get(0).getName());
    }

    @Test
    public void testServiceInstantiation() {
        HttpRangeClient client = new HttpRangeClient();
        ArchiveHeaderService service = new ArchiveHeaderService(client);

        Assert.assertNotNull(service.getHttpClient());
        Assert.assertNotNull(service.getZipParser());
        Assert.assertNotNull(service.getTarParser());
        Assert.assertNotNull(service.getStreamingTarExtractor());
    }

    /**
     * Builds a minimal valid in-memory ZIP archive with a Central Directory
     * and End of Central Directory (EOCD) record to test header parsing without network.
     */
    @Test
    public void testParseZipHeadersUsingServiceWithMock() throws Exception {
        byte[] fileNameBytes = "test_boot.img".getBytes(StandardCharsets.UTF_8);
        int uncompressedSize = 1024;
        int compressedSize = 512;
        int headerOffset = 0;

        // Central Directory File Header (CDFH): 46 bytes + filename
        ByteArrayOutputStream cdfhOut = new ByteArrayOutputStream();
        ByteBuffer cdfh = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        cdfh.putInt(0x02014B50); // Signature
        cdfh.putShort((short) 20); // Version made by
        cdfh.putShort((short) 20); // Version needed
        cdfh.putShort((short) 0);  // Flags
        cdfh.putShort((short) 8);  // Compression (Deflate)
        cdfh.putShort((short) 0);  // Mod time
        cdfh.putShort((short) 0);  // Mod date
        cdfh.putInt(0x12345678);   // CRC32
        cdfh.putInt(compressedSize); // Compressed size
        cdfh.putInt(uncompressedSize); // Uncompressed size
        cdfh.putShort((short) fileNameBytes.length); // File name length
        cdfh.putShort((short) 0);  // Extra field len
        cdfh.putShort((short) 0);  // Comment len
        cdfh.putShort((short) 0);  // Disk number start
        cdfh.putShort((short) 0);  // Internal attrs
        cdfh.putInt(0);            // External attrs
        cdfh.putInt(headerOffset); // Relative offset of local header
        cdfhOut.write(cdfh.array());
        cdfhOut.write(fileNameBytes);
        byte[] cdfhBytes = cdfhOut.toByteArray();

        // Let dummy file payload be 100 bytes
        int payloadSize = 100;
        int cdOffset = payloadSize;

        // End of Central Directory (EOCD): 22 bytes
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054B50); // EOCD Signature
        eocd.putShort((short) 0); // Disk number
        eocd.putShort((short) 0); // Disk where CD starts
        eocd.putShort((short) 1); // Number of CD records on this disk
        eocd.putShort((short) 1); // Total number of CD records
        eocd.putInt(cdfhBytes.length); // Size of CD
        eocd.putInt(cdOffset); // Offset of CD with respect to starting disk
        eocd.putShort((short) 0); // Comment len

        ByteArrayOutputStream zipFullOut = new ByteArrayOutputStream();
        zipFullOut.write(new byte[payloadSize]); // dummy file data
        zipFullOut.write(cdfhBytes);
        zipFullOut.write(eocd.array());
        byte[] fullZipFile = zipFullOut.toByteArray();

        // Verify EOCD signature searching logic works on this generated central directory
        HttpRangeClient mockClient = new HttpRangeClient() {
            @Override
            public byte[] fetchRange(String url, long startByte, long endByte) throws IOException {
                int start = (int) Math.max(0, startByte);
                int end = (int) Math.min(fullZipFile.length - 1, endByte);
                return Arrays.copyOfRange(fullZipFile, start, end + 1);
            }

            @Override
            public RemoteFileInfo probeRemoteFile(String rawUrl) throws IOException {
                return new RemoteFileInfo(rawUrl, rawUrl, fullZipFile.length, true);
            }
        };

        ArchiveHeaderService service = new ArchiveHeaderService(mockClient);
        ArchiveParseResult result = service.parseRemoteArchive("https://example.com/test.zip", null);

        Assert.assertEquals(ArchiveParseResult.ArchiveType.ZIP, result.getArchiveType());
        Assert.assertEquals(1, result.getEntryCount());
        ArchiveEntry entry = result.getEntries().get(0);
        Assert.assertEquals("test_boot.img", entry.getName());
        Assert.assertEquals(uncompressedSize, entry.getUncompressedSize());
        Assert.assertEquals(compressedSize, entry.getCompressedSize());
    }
}
