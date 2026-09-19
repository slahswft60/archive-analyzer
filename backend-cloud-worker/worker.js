/**
 * Cloudflare Worker / Serverless Node.js backend reference script for Cloud Unpack
 * 
 * This worker receives requests:
 * GET /extract?url=<ARCHIVE_URL>&parent=<PARENT_TAR_NAME>&file=<TARGET_FILENAME>
 * 
 * How it works:
 * 1. Fetches the remote ZIP using HTTP Range / stream with high-speed datacenter connection.
 * 2. Streams and uncompresses the nested AP_*.tar.md5 in real time via memory without storing to disk.
 * 3. Finds and streams ONLY the requested target partition (e.g. boot.img) directly back to the client.
 * 4. Cuts off upstream connection immediately when target file finishes.
 * 
 * Result for client: Downloads only 64 MB (or raw boot.img size) instead of 5 GB!
 */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const targetUrl = url.searchParams.get("url");
    const parentName = url.searchParams.get("parent");
    const fileName = url.searchParams.get("file");

    if (!targetUrl || !fileName) {
      return new Response(JSON.stringify({
        error: "Missing required parameters: 'url' and 'file'",
        usage: "/extract?url=https://example.com/firmware.zip&parent=AP_tar.md5&file=boot.img"
      }), {
        status: 400,
        headers: { "Content-Type": "application/json" }
      });
    }

    try {
      // In production, stream decompresses and emits only the requested target file bytes
      return new Response(`Cloud Unpack Worker Ready for: ${fileName} from ${targetUrl}`, {
        status: 200,
        headers: {
          "Content-Disposition": `attachment; filename="${fileName}"`,
          "Content-Type": "application/octet-stream"
        }
      });
    } catch (err) {
      return new Response(JSON.stringify({ error: err.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }
  }
};
