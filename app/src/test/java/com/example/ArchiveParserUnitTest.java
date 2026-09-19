package com.example;

import com.example.compression.Lz4Decompressor;
import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
