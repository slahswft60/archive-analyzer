package com.example;

import com.example.compression.Lz4Decompressor;
import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;
import com.example.parser.RemoteTarParser;
import com.example.parser.RemoteZipParser;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ArchiveParserUnitTest {

    @Test
    public void testGoogleDriveUrlNormalization() {
        String input = "https://drive.google.com/file/d/1B2C3D4E5F6G7H8I9J0K/view?usp=sharing";
        String normalized = HttpRangeClient.normalizeUrl(input);
        Assert.assertTrue(normalized.contains("1B2C3D4E5F6G7H8I9J0K"));
        Assert.assertTrue(normalized.contains("export=download"));
    }

    @Test
    public void testDropboxUrlNormalization() {
        String input = "https://www.dropbox.com/s/abcdef12345/archive.zip?dl=0";
        String normalized = HttpRangeClient.normalizeUrl(input);
        Assert.assertTrue(normalized.contains("dl=1"));
    }

    @Test
    public void testMediaFireUrlDetection() {
        String url1 = "https://www.mediafire.com/file/a1b2c3d4e5/firmware.zip/file";
        String url2 = "http://mediafire.com/download/a1b2c3d4e5";
        String url3 = "https://example.com/file/test.zip";

        Assert.assertTrue(com.example.network.MediaFireResolver.isMediaFireUrl(url1));
        Assert.assertTrue(com.example.network.MediaFireResolver.isMediaFireUrl(url2));
        Assert.assertFalse(com.example.network.MediaFireResolver.isMediaFireUrl(url3));
    }

    @Test
    public void testArchiveEntryProperties() {
        ArchiveEntry entry = new ArchiveEntry(
                "firmware/boot.img.lz4",
                1024 * 1024,
                2 * 1024 * 1024,
                100,
                200,
                8,
                false,
                "ZIP",
                12345678L
        );

        Assert.assertEquals("boot.img.lz4", entry.getSimpleFileName());
        Assert.assertEquals("boot.img", entry.getDecompressedTargetName());
        Assert.assertTrue(entry.isLz4());
        Assert.assertFalse(entry.isDirectory());
        Assert.assertTrue(entry.getFormattedSize().contains("MB"));
    }

    @Test
    public void testTarMd5Detection() {
        ArchiveEntry entry = new ArchiveEntry(
                "AP_A705FXXU5DXD2_CL28391204_QB782910_REV00.tar.md5",
                5000000000L,
                5000000000L,
                1000,
                1050,
                0, // Stored
                false,
                "ZIP",
                0
        );

        Assert.assertTrue(entry.isTarMd5());
        Assert.assertTrue(entry.isTar());
        Assert.assertEquals("AP_A705FXXU5DXD2_CL28391204_QB782910_REV00.tar.md5", entry.getSimpleFileName());
    }

    @Test
    public void testTarHeaderMagicDetection() {
        byte[] dummyBlock = new byte[512];
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, dummyBlock, 257, 5);
        byte[] nameBytes = "boot.img.lz4".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, dummyBlock, 0, nameBytes.length);
        dummyBlock[156] = '0';

        Assert.assertTrue(RemoteTarParser.isTarBlockHeader(dummyBlock, 0));
    }

    @Test
    public void testLz4BlockDecompression() throws IOException {
        // Test LZ4 block decompression with simple literals
        byte[] original = "HELLO_REMOTE_ARCHIVE_ANALYZER_TEST_1234567890".getBytes(StandardCharsets.UTF_8);

        // Construct simple LZ4 block with literal run: token = (len << 4)
        ByteArrayOutputStream blockOut = new ByteArrayOutputStream();
        int token = (original.length < 15 ? original.length : 15) << 4;
        blockOut.write(token);
        if (original.length >= 15) {
            int rem = original.length - 15;
            while (rem >= 255) {
                blockOut.write(255);
                rem -= 255;
            }
            blockOut.write(rem);
        }
        blockOut.write(original);

        byte[] compressedBlock = blockOut.toByteArray();
        byte[] decompressed = new byte[original.length];
        int size = Lz4Decompressor.decompressBlock(compressedBlock, 0, compressedBlock.length, decompressed, 0, decompressed.length);

        Assert.assertEquals(original.length, size);
        Assert.assertEquals(new String(original, StandardCharsets.UTF_8), new String(decompressed, StandardCharsets.UTF_8));
    }
}
