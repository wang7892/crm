package mailmonitoring.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class AttachmentHttpServer {
    private final HttpServer server;

    public AttachmentHttpServer(int port, String downloadPath, String attachmentSaveDir) throws Exception {
        String normalizedPath = normalizePath(downloadPath);
        Path baseDir = Path.of(attachmentSaveDir).toAbsolutePath().normalize();

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext(normalizedPath, new Handler(normalizedPath, baseDir));
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(Math.max(delaySeconds, 0));
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/api/attachments";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static class Handler implements HttpHandler {
        private final String contextPath;
        private final Path baseDir;

        private Handler(String contextPath, Path baseDir) {
            this.contextPath = contextPath;
            this.baseDir = baseDir;
        }

        @Override
        public void handle(HttpExchange exchange) {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeText(exchange, 405, "Method Not Allowed");
                    return;
                }

                String rawPath = exchange.getRequestURI().getPath();
                String suffix = rawPath.startsWith(contextPath) ? rawPath.substring(contextPath.length()) : "";
                if (suffix.startsWith("/")) {
                    suffix = suffix.substring(1);
                }
                String[] parts = suffix.isBlank() ? new String[0] : suffix.split("/", -1);
                if (parts.length < 2) {
                    writeText(exchange, 400, "Bad Request");
                    return;
                }

                String safeMessageId = decodePathSegment(parts[0]);
                String fileName = decodePathSegment(parts[1]);
                if (!isSafeSegment(safeMessageId) || !isSafeSegment(fileName)) {
                    writeText(exchange, 400, "Bad Request");
                    return;
                }

                Path file = baseDir.resolve(safeMessageId).resolve(fileName).normalize();
                if (!file.startsWith(baseDir)) {
                    writeText(exchange, 403, "Forbidden");
                    return;
                }
                if (!Files.exists(file) || !Files.isRegularFile(file)) {
                    writeText(exchange, 404, "Not Found");
                    return;
                }

                String contentType = Files.probeContentType(file);
                if (contentType == null || contentType.isBlank()) {
                    contentType = "application/octet-stream";
                }

                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", contentType);
                headers.set("X-Content-Type-Options", "nosniff");
                headers.set("Cache-Control", "private, max-age=60");

                String disposition = shouldInline(contentType, fileName) ? "inline" : "attachment";
                headers.set("Content-Disposition", contentDisposition(disposition, fileName));

                long length = Files.size(file);
                exchange.sendResponseHeaders(200, length);
                try (InputStream in = Files.newInputStream(file);
                     OutputStream out = exchange.getResponseBody()) {
                    in.transferTo(out);
                }
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                try {
                    writeText(exchange, 500, "Internal Server Error");
                } catch (Exception ignored) {
                    // ignored
                }
            } finally {
                exchange.close();
            }
        }

        private boolean shouldInline(String contentType, String fileName) {
            String lowerType = contentType.toLowerCase(Locale.ROOT);
            if (lowerType.startsWith("image/") || lowerType.startsWith("text/")) {
                return true;
            }
            if ("application/pdf".equalsIgnoreCase(lowerType)) {
                return true;
            }
            String lowerName = (fileName == null ? "" : fileName).toLowerCase(Locale.ROOT);
            return lowerName.endsWith(".pdf");
        }

        private String decodePathSegment(String raw) {
            if (raw == null) {
                return "";
            }
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        }

        private boolean isSafeSegment(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            if (value.contains("..") || value.contains("/") || value.contains("\\") || value.contains("\0")) {
                return false;
            }
            return true;
        }

        private String contentDisposition(String disposition, String fileName) {
            String fallback = asciiFileNameFallback(fileName);
            String encoded = URLEncoder.encode(fileName == null ? fallback : fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return disposition + "; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
        }

        private String asciiFileNameFallback(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                return "file";
            }
            StringBuilder builder = new StringBuilder(fileName.length());
            for (int i = 0; i < fileName.length(); i++) {
                char ch = fileName.charAt(i);
                if (ch >= 0x20 && ch <= 0x7E && ch != '"' && ch != '\\' && ch != ';') {
                    builder.append(ch);
                } else {
                    builder.append('_');
                }
            }
            String fallback = builder.toString().trim();
            return fallback.isBlank() ? "file" : fallback;
        }

        private void writeText(HttpExchange exchange, int status, String text) throws Exception {
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}

