package com.example.network;

import com.example.compression.Lz4Decompressor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LocalArchiveServer {

    private static LocalArchiveServer instance;
    private ServerSocket serverSocket;
    private int port = -1;
    private byte[] demoZipData;
    private byte[] demoTarData;
    private boolean isRunning = false;

    public static synchronized LocalArchiveServer getInstance() {
        if (instance == null) {
            instance = new LocalArchiveServer();
        }
        return instance;
    }

    public synchronized void start() {
        if (isRunning) return;
        try {
            buildDemoArchives();
            serverSocket = new ServerSocket(0); // bind to random available port
            port = serverSocket.getLocalPort();
            isRunning = true;

            Thread serverThread = new Thread(this::runServer, "LocalArchiveServerThread");
            serverThread.setDaemon(true);
            serverThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getPort() {
        return port;
    }

    public String getDemoZipUrl() {
        return "http://127.0.0.1:" + port + "/demo_firmware_archive.zip";
    }

    public String getDemoTarUrl() {
        return "http://127.0.0.1:" + port + "/demo_rom_archive.tar";
    }

    private void runServer() {
        while (isRunning && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                handleClient(client);
            } catch (IOException e) {
                if (!isRunning) break;
            }
        }
    }

    private void handleClient(Socket client) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                 OutputStream out = client.getOutputStream()) {

                String requestLine = reader.readLine();
                if (requestLine == null) return;

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String path = parts[1];

                long rangeStart = -1;
                long rangeEnd = -1;

                String headerLine;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    if (headerLine.toLowerCase().startsWith("range: bytes=")) {
                        String rangeVal = headerLine.substring("range: bytes=".length()).trim();
                        int dash = rangeVal.indexOf('-');
                        if (dash != -1) {
                            String sStart = rangeVal.substring(0, dash).trim();
                            String sEnd = rangeVal.substring(dash + 1).trim();
                            if (!sStart.isEmpty()) {
                                rangeStart = Long.parseLong(sStart);
                            }
                            if (!sEnd.isEmpty()) {
                                rangeEnd = Long.parseLong(sEnd);
                            }
                        }
                    }
                }

                byte[] data = path.contains(".tar") ? demoTarData : demoZipData;
                if (data == null) data = new byte[0];

                long totalLen = data.length;

                if ("HEAD".equalsIgnoreCase(method)) {
                    String resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: " + totalLen + "\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }

                if (rangeStart >= 0) {
                    if (rangeEnd < 0 || rangeEnd >= totalLen) {
                        rangeEnd = totalLen - 1;
                    }
                    long count = rangeEnd - rangeStart + 1;
                    if (count < 0) count = 0;

                    String resp = "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: " + count + "\r\n" +
                            "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + totalLen + "\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    if (count > 0 && rangeStart < data.length) {
                        out.write(data, (int) rangeStart, (int) count);
                    }
                    out.flush();
                } else {
                    String resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: " + totalLen + "\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    out.write(data);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }).start();
    }

    private void buildDemoArchives() throws IOException {
        // 1. Create a dummy boot.img with realistic headers and content
        ByteArrayOutputStream bootImgStream = new ByteArrayOutputStream();
        bootImgStream.write("ANDROID!".getBytes(StandardCharsets.US_ASCII)); // Android boot image magic
        byte[] pad = new byte[1024 * 64]; // 64KB boot image content
        for (int i = 0; i < pad.length; i++) {
            pad[i] = (byte) (i % 256);
        }
        bootImgStream.write(pad);
        byte[] bootImgRaw = bootImgStream.toByteArray();

        // 2. Create valid LZ4 Frame format for boot.img.lz4
        byte[] bootImgLz4 = createLz4Frame(bootImgRaw);

        // 3. Build Demo ZIP
        ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBaos)) {
            // Entry 1: boot.img.lz4
            ZipEntry e1 = new ZipEntry("firmware/boot.img.lz4");
            zos.putNextEntry(e1);
            zos.write(bootImgLz4);
            zos.closeEntry();

            // Entry 2: vendor_boot.img
            ZipEntry e2 = new ZipEntry("firmware/vendor_boot.img");
            zos.putNextEntry(e2);
            byte[] vendorBoot = new byte[1024 * 32];
            for (int i = 0; i < vendorBoot.length; i++) vendorBoot[i] = (byte) (i * 3);
            zos.write(vendorBoot);
            zos.closeEntry();

            // Entry 3: recovery.img.lz4
            ZipEntry e3 = new ZipEntry("images/recovery.img.lz4");
            zos.putNextEntry(e3);
            byte[] recoveryRaw = "RECOVERY_IMAGE_PAYLOAD_V2".getBytes(StandardCharsets.UTF_8);
            zos.write(createLz4Frame(recoveryRaw));
            zos.closeEntry();

            // Entry 4: META-INF/com/google/android/updater-script
            ZipEntry e4 = new ZipEntry("META-INF/com/google/android/updater-script");
            zos.putNextEntry(e4);
            zos.write("# Android OTA Updater Script\nui_print(\"Flashing firmware...\");\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Entry 5: README.txt
            ZipEntry e5 = new ZipEntry("README.txt");
            zos.putNextEntry(e5);
            zos.write("Remote Archive Analyzer Demo ZIP Archive\nUses HTTP Range requests to extract single files!\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        demoZipData = zipBaos.toByteArray();

        // 4. Build Demo TAR
        ByteArrayOutputStream tarBaos = new ByteArrayOutputStream();
        writeTarEntry(tarBaos, "boot.img.lz4", bootImgLz4);
        writeTarEntry(tarBaos, "system/build.prop", "ro.build.version.release=15\nro.product.model=Pixel 8\n".getBytes(StandardCharsets.UTF_8));
        writeTarEntry(tarBaos, "kernel/Image.lz4", createLz4Frame("KERNEL_IMAGE_BINARY_TEST".getBytes(StandardCharsets.UTF_8)));
        writeTarEntry(tarBaos, "info.txt", "Demo POSIX ustar TAR archive for HTTP Range inspection\n".getBytes(StandardCharsets.UTF_8));
        // TAR end of file: two 512-byte zero blocks
        tarBaos.write(new byte[1024]);
        demoTarData = tarBaos.toByteArray();
    }

    private byte[] createLz4Frame(byte[] rawData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Magic
        baos.write(new byte[]{0x04, 0x22, 0x4D, 0x18});
        // FLG: version 1 (0x40), block independence (0x20) => 0x60
        baos.write(0x60);
        // BD: 64KB max block (0x40)
        baos.write(0x40);
        // Header checksum (dummy simple byte)
        baos.write(0x00);

        // Write as uncompressed block (highest bit 0x80000000 set)
        int len = rawData.length;
        int blockSize = len | 0x80000000;
        baos.write(new byte[]{
                (byte) (blockSize & 0xFF),
                (byte) ((blockSize >>> 8) & 0xFF),
                (byte) ((blockSize >>> 16) & 0xFF),
                (byte) ((blockSize >>> 24) & 0xFF)
        });
        baos.write(rawData);

        // End of blocks mark (4 bytes zero)
        baos.write(new byte[]{0x00, 0x00, 0x00, 0x00});
        return baos.toByteArray();
    }

    private void writeTarEntry(ByteArrayOutputStream out, String name, byte[] data) throws IOException {
        byte[] header = new byte[512];
        // Name (100 bytes)
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 99));

        // Mode (8 bytes)
        writeOctal(header, 100, 8, 0644);
        // UID
        writeOctal(header, 108, 8, 0);
        // GID
        writeOctal(header, 116, 8, 0);
        // Size (12 bytes)
        writeOctal(header, 124, 12, data.length);
        // Mtime (12 bytes)
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        // Typeflag ('0')
        header[156] = '0';
        // Magic: "ustar " (6 bytes)
        System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        // Version: " \0"
        header[263] = ' ';
        header[264] = 0;

        // Checksum (8 bytes): calculate sum of all 512 bytes with chksum field treated as spaces
        for (int i = 148; i < 156; i++) header[i] = ' ';
        long chksum = 0;
        for (byte b : header) {
            chksum += (b & 0xFF);
        }
        writeOctal(header, 148, 7, chksum);
        header[155] = ' ';

        out.write(header);
        out.write(data);

        // Pad to 512 boundary
        int pad = (512 - (data.length % 512)) % 512;
        if (pad > 0) {
            out.write(new byte[pad]);
        }
    }

    private void writeOctal(byte[] buf, int offset, int len, long val) {
        String s = Long.toOctalString(val);
        int padZeros = len - 1 - s.length();
        int pos = offset;
        for (int i = 0; i < padZeros; i++) {
            buf[pos++] = '0';
        }
        for (int i = 0; i < s.length(); i++) {
            buf[pos++] = (byte) s.charAt(i);
        }
        buf[offset + len - 1] = 0; // null terminate
    }
}
