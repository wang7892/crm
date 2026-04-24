package mailmonitoring.client;

import mailmonitoring.model.MailBatchResult;
import mailmonitoring.model.MailAttachment;
import mailmonitoring.model.MailEvent;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImapMailProviderClient implements MailProviderClient {
    private static final Pattern UID_LIST_PATTERN = Pattern.compile("\\* SEARCH(.*)");
    private static final Pattern LIST_MAILBOX_PATTERN = Pattern.compile("\\* LIST \\(([^)]*)\\) \"[^\"]*\" \"([^\"]+)\"$");
    private static final Pattern MIME_ENCODED_WORD_PATTERN = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");

    private final String organizationId;
    private final String sourceMailbox;
    private final String targetMailbox;
    private final String imapHost;
    private final int imapPort;
    private final String imapUser;
    private final String imapPassword;
    private final String imapFolder;
    private final String attachmentSaveDir;

    public ImapMailProviderClient(String organizationId, String sourceMailbox, String targetMailbox,
                                  String imapHost, int imapPort, String imapUser,
                                  String imapPassword, String imapFolder, String attachmentSaveDir) {
        this.organizationId = organizationId;
        this.sourceMailbox = sourceMailbox;
        this.targetMailbox = targetMailbox;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.imapUser = imapUser;
        this.imapPassword = imapPassword;
        this.imapFolder = imapFolder;
        this.attachmentSaveDir = attachmentSaveDir == null || attachmentSaveDir.isBlank() ? "./attachments" : attachmentSaveDir;
    }

    @Override
    public MailBatchResult fetchNewEvents(String mailbox, String cursor) {
        long lastUid = parseCursor(cursor);
        long maxUid = lastUid;
        List<MailEvent> events = new ArrayList<>();

        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(imapHost, imapPort);
             // Use ISO-8859-1 to preserve raw bytes (0-255) and decode body per Content-Type charset later.
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(15000);

            readLine(reader); // greeting
            sendAndEnsureOk(writer, reader, "a0 CAPABILITY", "a0");
            sendAndEnsureOk(writer, reader, "a1 LOGIN " + quote(imapUser) + " " + quote(imapPassword), "a1");
            sendAndAllowFailure(writer, reader,
                    "a15 ID (\"name\" \"MailMonitor\" \"version\" \"1.0\" \"vendor\" \"custom\")", "a15");
            String selectedFolder = selectFolder(writer, reader);
            if (selectedFolder == null) {
                throw new IllegalStateException("Cannot select any sent folder.");
            }

            if (lastUid <= 0) {
                long latestUid = searchLatestUid(writer, reader);
                if (latestUid > 0) {
                    System.out.println("[IMAP] initial sync: set cursor to latest uid=" + latestUid);
                    return new MailBatchResult(List.of(), String.valueOf(latestUid));
                }
            }

            List<Long> uids = searchUidsAfter(writer, reader, lastUid);
            for (Long uid : uids) {
                MailEvent event = fetchEventByUid(writer, reader, uid);
                if (event != null) {
                    events.add(event);
                }
                if (uid > maxUid) {
                    maxUid = uid;
                }
            }
            sendAndEnsureOk(writer, reader, "a9 LOGOUT", "a9");
        } catch (Exception ex) {
            System.err.println("[IMAP] fetch failed: " + ex.getMessage());
        }
        return new MailBatchResult(events, String.valueOf(maxUid));
    }

    private String selectFolder(BufferedWriter writer, BufferedReader reader) throws Exception {
        List<ListItem> folders = listFolders(writer, reader);
        for (ListItem item : folders) {
            if (item.flags.toUpperCase(Locale.ROOT).contains("\\SENT") && trySelect(writer, reader, item.mailbox)) {
                System.out.println("[IMAP] selected by flag \\Sent: " + item.mailbox);
                return item.mailbox;
            }
        }

        List<String> candidates = List.of(
                imapFolder,
                "Sent Messages",
                "Sent",
                "INBOX.Sent",
                "已发送",
                "已发送邮件",
                "Sent Mail"
        );

        Set<String> folderNames = new LinkedHashSet<>();
        for (ListItem item : folders) {
            folderNames.add(item.mailbox);
        }

        for (String candidate : candidates) {
            String matched = findCaseInsensitive(folderNames, candidate);
            if (matched != null && trySelect(writer, reader, matched)) {
                System.out.println("[IMAP] selected folder: " + matched);
                return matched;
            }
        }
        System.out.println("[IMAP] LIST folders: " + folderNames);
        return null;
    }

    private List<ListItem> listFolders(BufferedWriter writer, BufferedReader reader) throws Exception {
        String tag = "a2";
        writeLine(writer, tag + " LIST \"\" \"*\"");
        List<ListItem> folders = new ArrayList<>();
        String line;
        while ((line = readLine(reader)) != null) {
            Matcher matcher = LIST_MAILBOX_PATTERN.matcher(line);
            if (matcher.find()) {
                String flags = matcher.group(1);
                String mailbox = matcher.group(2);
                folders.add(new ListItem(flags, mailbox));
            }
            if (line.startsWith(tag + " ")) {
                if (!line.toUpperCase(Locale.ROOT).contains("OK")) {
                    throw new IllegalStateException("LIST folders failed: " + line);
                }
                break;
            }
        }
        return folders;
    }

    private boolean trySelect(BufferedWriter writer, BufferedReader reader, String folder) throws Exception {
        String tag = "a3";
        writeLine(writer, tag + " SELECT " + quote(folder));
        String line;
        while ((line = readLine(reader)) != null) {
            if (line.startsWith(tag + " ")) {
                return line.toUpperCase(Locale.ROOT).contains("OK");
            }
        }
        return false;
    }

    private String findCaseInsensitive(Set<String> folders, String candidate) {
        for (String folder : folders) {
            if (folder.equalsIgnoreCase(candidate)) {
                return folder;
            }
        }
        return null;
    }

    private List<Long> searchUidsAfter(BufferedWriter writer, BufferedReader reader, long lastUid) throws Exception {
        String tag = "a4";
        writeLine(writer, tag + " UID SEARCH UID " + (lastUid + 1) + ":*");
        List<Long> uids = new ArrayList<>();
        String line;
        while ((line = readLine(reader)) != null) {
            Matcher matcher = UID_LIST_PATTERN.matcher(line);
            if (matcher.find()) {
                String[] parts = matcher.group(1).trim().split("\\s+");
                for (String part : parts) {
                    if (!part.isBlank()) {
                        uids.add(Long.parseLong(part));
                    }
                }
            }
            if (line.startsWith(tag + " ")) {
                if (!line.toUpperCase(Locale.ROOT).contains("OK")) {
                    throw new IllegalStateException("UID SEARCH failed: " + line);
                }
                break;
            }
        }
        return uids;
    }

    private long searchLatestUid(BufferedWriter writer, BufferedReader reader) throws Exception {
        String tag = "a40";
        writeLine(writer, tag + " UID SEARCH ALL");
        long maxUid = 0L;
        String line;
        while ((line = readLine(reader)) != null) {
            Matcher matcher = UID_LIST_PATTERN.matcher(line);
            if (matcher.find()) {
                String[] parts = matcher.group(1).trim().split("\\s+");
                for (String part : parts) {
                    if (!part.isBlank()) {
                        long uid = Long.parseLong(part);
                        if (uid > maxUid) {
                            maxUid = uid;
                        }
                    }
                }
            }
            if (line.startsWith(tag + " ")) {
                if (!line.toUpperCase(Locale.ROOT).contains("OK")) {
                    throw new IllegalStateException("UID SEARCH ALL failed: " + line);
                }
                break;
            }
        }
        return maxUid;
    }

    private MailEvent fetchEventByUid(BufferedWriter writer, BufferedReader reader, long uid) throws Exception {
        String tag = "a5";
        writeLine(writer, tag + " UID FETCH " + uid + " (BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT MESSAGE-ID DATE)])");
        String from = "";
        String to = "";
        String subject = "";
        String messageId = "";
        List<String> headerLines = new ArrayList<>();
        String line;
        while ((line = readLine(reader)) != null) {
            if (line.startsWith("* ") || line.isBlank() || line.startsWith(")")) {
                // skip envelope markers
            } else if (line.startsWith(tag + " ")) {
                if (!line.toUpperCase(Locale.ROOT).contains("OK")) {
                    throw new IllegalStateException("UID FETCH failed: " + line);
                }
                break;
            } else {
                headerLines.add(line);
            }
        }

        for (String headerLine : headerLines) {
            String lower = headerLine.toLowerCase(Locale.ROOT);
            if (lower.startsWith("from:")) {
                from = headerLine.substring(5).trim();
            } else if (lower.startsWith("to:")) {
                to = headerLine.substring(3).trim();
            } else if (lower.startsWith("subject:")) {
                subject = headerLine.substring(8).trim();
            } else if (lower.startsWith("message-id:")) {
                messageId = headerLine.substring(11).trim();
            }
        }

        if (messageId.isBlank()) {
            messageId = "<uid-" + uid + "@imap-local>";
        }
        String fromAddr = extractEmail(from);
        List<String> toList = parseAddressList(to);

        MailContent content = fetchMailContent(writer, reader, uid, messageId);
        return new MailEvent(
                organizationId,
                sourceMailbox,
                targetMailbox,
                messageId,
                "thread-" + uid,
                fromAddr,
                toList,
                List.of(),
                subject,
                content.textBody,
                Instant.now(),
                content.attachments
        );
    }

    private MailContent fetchMailContent(BufferedWriter writer, BufferedReader reader, long uid, String messageId) throws Exception {
        String tag = "a6";
        writeLine(writer, tag + " UID FETCH " + uid + " (BODY.PEEK[])");
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = readLine(reader)) != null) {
            if (line.startsWith(tag + " ")) {
                break;
            }
            if (line.startsWith("* ") || line.equals(")")) {
                continue;
            }
            lines.add(line);
        }
        String raw = String.join("\n", lines);
        return parseMailContent(raw, messageId);
    }

    private MailContent parseMailContent(String raw, String messageId) {
        if (raw == null || raw.isBlank()) {
            return new MailContent("", List.of());
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        String boundary = "";
        Matcher boundaryMatcher = Pattern.compile("boundary=\"?([^\";\\r\\n]+)\"?", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (boundaryMatcher.find()) {
            boundary = boundaryMatcher.group(1).trim();
        }
        boolean declaredMultipart = lower.contains("multipart/");
        if (boundary.isBlank()) {
            boundary = detectBoundaryFromBody(raw);
        }
        boolean hasBoundary = boundary != null && !boundary.isBlank();
        if (!declaredMultipart && !hasBoundary) {
            String body = extractSimpleBody(raw);
            // If the body still contains MIME boundary markers, force multipart parsing as a fallback.
            String forced = forceExtractTextFromMimeBody(body, messageId);
            return new MailContent(forced, List.of());
        }

        String marker = "--" + boundary;
        String[] parts = raw.split(Pattern.quote(marker));
        String textBody = "";
        String htmlBody = "";
        List<MailAttachment> attachments = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank() || part.startsWith("--")) {
                continue;
            }
            String partLower = part.toLowerCase(Locale.ROOT);
            int bodyStart = findHeaderBodySeparator(part);
            if (bodyStart < 0) {
                continue;
            }
            String headers = part.substring(0, bodyStart);
            String body = part.substring(bodyStart).trim();
            if (partLower.contains("content-disposition: attachment")) {
                MailAttachment attachment = parseAttachment(headers, body, messageId);
                if (attachment != null) {
                    attachments.add(attachment);
                }
            } else if (partLower.contains("content-type: text/plain") && textBody.isBlank()) {
                textBody = decodeBodyIfNeeded(headers, body);
            } else if (partLower.contains("content-type: text/html") && htmlBody.isBlank()) {
                htmlBody = decodeBodyIfNeeded(headers, body);
            }
        }
        if (textBody.isBlank() && !htmlBody.isBlank()) {
            textBody = stripHtmlToText(htmlBody);
        }
        if (textBody.isBlank()) {
            // Last resort: avoid persisting the entire multipart MIME if we failed to parse parts.
            textBody = "";
        }
        // Some providers embed boundary/headers into the decoded text part; strip it if detected.
        if (looksLikeMime(textBody)) {
            String forced = forceExtractTextFromMimeBody(textBody, messageId);
            if (!forced.isBlank()) {
                textBody = forced;
            }
        }
        return new MailContent(textBody, attachments);
    }

    private boolean looksLikeMime(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return (lower.contains("content-type:") && lower.contains("transfer-encoding"))
                || lower.contains("=_part_");
    }

    private String forceExtractTextFromMimeBody(String body, String messageId) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String boundary = detectBoundaryFromBody(body);
        if (boundary == null || boundary.isBlank()) {
            // If it still looks like MIME but we can't find boundary, best effort: drop obvious headers.
            if (!looksLikeMime(body)) {
                return body.trim();
            }
            return stripObviousMimeHeaders(body).trim();
        }
        MailContent parsed = parseMultipartFromBody(body, boundary, messageId);
        if (parsed != null && parsed.textBody != null && !parsed.textBody.isBlank()) {
            return parsed.textBody.trim();
        }
        return stripObviousMimeHeaders(body).trim();
    }

    private MailContent parseMultipartFromBody(String body, String boundary, String messageId) {
        if (body == null || body.isBlank() || boundary == null || boundary.isBlank()) {
            return null;
        }
        String marker = "--" + boundary;
        String[] parts = body.split(Pattern.quote(marker));
        String textBody = "";
        String htmlBody = "";
        List<MailAttachment> attachments = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank() || part.startsWith("--")) {
                continue;
            }
            String partLower = part.toLowerCase(Locale.ROOT);
            int bodyStart = findHeaderBodySeparator(part);
            if (bodyStart < 0) {
                continue;
            }
            String headers = part.substring(0, bodyStart);
            String content = part.substring(bodyStart).trim();
            if (partLower.contains("content-disposition: attachment")) {
                MailAttachment attachment = parseAttachment(headers, content, messageId);
                if (attachment != null) {
                    attachments.add(attachment);
                }
            } else if (partLower.contains("content-type: text/plain") && textBody.isBlank()) {
                textBody = decodeBodyIfNeeded(headers, content);
            } else if (partLower.contains("content-type: text/html") && htmlBody.isBlank()) {
                htmlBody = decodeBodyIfNeeded(headers, content);
            }
        }
        if (textBody.isBlank() && !htmlBody.isBlank()) {
            textBody = stripHtmlToText(htmlBody);
        }
        if (looksLikeMime(textBody)) {
            textBody = stripObviousMimeHeaders(textBody);
        }
        return new MailContent(textBody, attachments);
    }

    private String stripObviousMimeHeaders(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // Remove boundary lines and common header lines inside bodies.
        String s = text.replace("\r", "");
        s = s.replaceAll("(?m)^-{2,}=_Part_.*$", "");
        s = s.replaceAll("(?m)^Content-Type:.*$", "");
        s = s.replaceAll("(?m)^Content-Transfer-Encoding:.*$", "");
        s = s.replaceAll("(?m)^Content-Disposition:.*$", "");
        s = s.replaceAll("(?m)^charset=.*$", "");
        s = s.replaceAll("(?m)^boundary=.*$", "");
        s = s.replaceAll("(?m)^\\s*$\\n\\s*$", "\n");
        return s.trim();
    }

    private int findHeaderBodySeparator(String part) {
        if (part == null || part.isBlank()) {
            return -1;
        }
        // MIME parts separate headers and body by an empty line. Some servers may include spaces/tabs.
        Matcher m = Pattern.compile("\\n\\s*\\n").matcher(part);
        if (m.find()) {
            return m.end();
        }
        return -1;
    }

    private String detectBoundaryFromBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // Some mail providers (or IMAP FETCH responses) may omit/obscure the multipart Content-Type header,
        // while the body still contains boundary markers like: "------=_Part_....".
        //
        // Boundary marker line is: "--" + boundary. In practice the boundary itself often starts with many '-',
        // but the count is NOT stable across providers. So we detect any line like:
        //   ^-{2,}=_Part_....$
        // and treat the boundary as the marker line without the leading two '-' (the mandatory prefix).
        Matcher m = Pattern.compile("(?m)^-{2,}=_Part_[^\\r\\n]+\\s*$").matcher(raw);
        if (m.find()) {
            String markerLine = m.group(0).trim(); // e.g. "------=_Part_12345..."
            if (markerLine.length() > 2) {
                return markerLine.substring(2).trim(); // boundary without the leading "--"
            }
            return "";
        }
        return "";
    }

    private String stripHtmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = html;
        s = s.replace("\r", "");
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</p\\s*>", "\n");
        s = s.replaceAll("(?is)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&amp;", "&")
             .replace("&quot;", "\"")
             .replace("&#39;", "'");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
        s = s.replaceAll("\\n{3,}", "\n\n").trim();
        return s;
    }

    private MailAttachment parseAttachment(String headers, String body, String messageId) {
        try {
            Matcher nameMatcher = Pattern.compile("filename=\"?([^\";\\r\\n]+)\"?", Pattern.CASE_INSENSITIVE).matcher(headers);
            String fileName = nameMatcher.find() ? nameMatcher.group(1).trim() : "";
            if (fileName.isBlank()) {
                Matcher nameParamMatcher = Pattern.compile("name=\"?([^\";\\r\\n]+)\"?", Pattern.CASE_INSENSITIVE).matcher(headers);
                if (nameParamMatcher.find()) {
                    fileName = nameParamMatcher.group(1).trim();
                }
            }
            if (fileName.isBlank()) {
                fileName = "attachment-" + System.currentTimeMillis() + ".bin";
            }
            fileName = sanitizeFileName(decodeMimeEncodedWords(fileName));
            Matcher typeMatcher = Pattern.compile("content-type:\\s*([^;\\r\\n]+)", Pattern.CASE_INSENSITIVE).matcher(headers);
            String contentType = typeMatcher.find() ? typeMatcher.group(1).trim() : "application/octet-stream";
            byte[] bytes = decodeBytesIfNeeded(headers, body);

            String safeMessageId = messageId.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path dir = Path.of(attachmentSaveDir, safeMessageId);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(fileName);
            try (FileOutputStream outputStream = new FileOutputStream(filePath.toFile())) {
                outputStream.write(bytes);
            }
            return new MailAttachment(fileName, contentType, bytes.length, filePath.toAbsolutePath().toString());
        } catch (Exception ex) {
            System.err.println("[IMAP] parse attachment failed: " + ex.getMessage());
            return null;
        }
    }

    private String decodeMimeEncodedWords(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = MIME_ENCODED_WORD_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (matcher.start() > last) {
                sb.append(value, last, matcher.start());
            }
            sb.append(decodeSingleEncodedWord(matcher.group(1), matcher.group(2), matcher.group(3)));
            last = matcher.end();
        }
        if (!found) {
            return value;
        }
        if (last < value.length()) {
            sb.append(value.substring(last));
        }
        return sb.toString();
    }

    private String decodeSingleEncodedWord(String charsetName, String encoding, String encoded) {
        try {
            Charset charset = Charset.forName(charsetName);
            if ("B".equalsIgnoreCase(encoding)) {
                byte[] bytes = Base64.getDecoder().decode(encoded);
                return new String(bytes, charset);
            }
            String q = encoded.replace('_', ' ');
            byte[] bytes = decodeQuotedPrintableBytes(q);
            return new String(bytes, charset);
        } catch (Exception ignored) {
            return encoded;
        }
    }

    private byte[] decodeQuotedPrintableBytes(String text) {
        byte[] source = text.getBytes(StandardCharsets.US_ASCII);
        byte[] output = new byte[source.length];
        int idx = 0;
        for (int i = 0; i < source.length; i++) {
            if (source[i] == '=' && i + 2 < source.length) {
                int hi = Character.digit((char) source[i + 1], 16);
                int lo = Character.digit((char) source[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    output[idx++] = (byte) ((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            output[idx++] = source[i];
        }
        byte[] result = new byte[idx];
        System.arraycopy(output, 0, result, 0, idx);
        return result;
    }

    private String sanitizeFileName(String name) {
        String sanitized = name == null ? "" : name.trim();
        if (sanitized.isBlank()) {
            sanitized = "attachment-" + System.currentTimeMillis() + ".bin";
        }
        sanitized = sanitized.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (sanitized.startsWith(".")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.isBlank()) {
            return "attachment-" + System.currentTimeMillis() + ".bin";
        }
        return sanitized;
    }

    private String decodeBodyIfNeeded(String headers, String body) {
        if (headers == null || body == null) {
            return "";
        }
        String lower = headers.toLowerCase(Locale.ROOT);
        Charset charset = detectCharsetFromHeaders(headers);
        String normalized = body.replace("\r", "").trim();

        if (lower.contains("base64")) {
            try {
                byte[] decoded = Base64.getMimeDecoder().decode(normalized);
                return new String(decoded, charset);
            } catch (Exception ignored) {
                return normalized;
            }
        }
        if (lower.contains("quoted-printable")) {
            try {
                byte[] bytes = decodeQuotedPrintableBytes(normalized);
                return new String(bytes, charset);
            } catch (Exception ignored) {
                return normalized;
            }
        }

        // No transfer encoding: body may still be in a legacy charset (e.g. GBK) but we read from socket
        // using ISO-8859-1 to preserve raw bytes; convert those bytes into the declared charset here.
        byte[] rawBytes = normalized.getBytes(StandardCharsets.ISO_8859_1);
        try {
            return new String(rawBytes, charset);
        } catch (Exception ignored) {
            return normalized;
        }
    }

    private byte[] decodeBytesIfNeeded(String headers, String body) {
        if (body == null) {
            return new byte[0];
        }
        String lower = headers == null ? "" : headers.toLowerCase(Locale.ROOT);
        String normalized = body.replace("\r", "").trim();
        if (lower.contains("base64")) {
            try {
                return Base64.getMimeDecoder().decode(normalized);
            } catch (Exception ignored) {
                return normalized.getBytes(StandardCharsets.ISO_8859_1);
            }
        }
        if (lower.contains("quoted-printable")) {
            try {
                return decodeQuotedPrintableBytes(normalized);
            } catch (Exception ignored) {
                return normalized.getBytes(StandardCharsets.ISO_8859_1);
            }
        }
        return normalized.getBytes(StandardCharsets.ISO_8859_1);
    }

    private Charset detectCharsetFromHeaders(String headers) {
        if (headers == null || headers.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        Matcher matcher = Pattern.compile("charset\\s*=\\s*\"?([^\";\\s\\r\\n]+)\"?", Pattern.CASE_INSENSITIVE).matcher(headers);
        if (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isBlank()) {
                try {
                    // Common aliases in the wild.
                    if ("gb2312".equalsIgnoreCase(name)) {
                        return Charset.forName("GBK");
                    }
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private String extractSimpleBody(String raw) {
        int start = raw.indexOf("\n\n");
        if (start < 0) {
            return "";
        }
        return raw.substring(start + 2).trim();
    }

    private long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String extractEmail(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+)").matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> parseAddressList(String raw) {
        List<String> addresses = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return addresses;
        }
        Matcher matcher = Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+)").matcher(raw);
        while (matcher.find()) {
            addresses.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        if (addresses.isEmpty()) {
            String[] split = raw.split(",");
            for (String s : split) {
                if (!s.isBlank()) {
                    addresses.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return addresses;
    }

    private void sendAndEnsureOk(BufferedWriter writer, BufferedReader reader, String command, String tag) throws Exception {
        writeLine(writer, command);
        String line;
        while ((line = readLine(reader)) != null) {
            if (line.startsWith(tag + " ")) {
                if (!line.toUpperCase(Locale.ROOT).contains("OK")) {
                    throw new IllegalStateException("IMAP command failed: " + line);
                }
                return;
            }
        }
        throw new IllegalStateException("No IMAP response for command tag: " + tag);
    }

    private void sendAndAllowFailure(BufferedWriter writer, BufferedReader reader, String command, String tag) throws Exception {
        writeLine(writer, command);
        String line;
        while ((line = readLine(reader)) != null) {
            if (line.startsWith(tag + " ")) {
                return;
            }
        }
    }

    private String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void writeLine(BufferedWriter writer, String line) throws Exception {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    private String readLine(BufferedReader reader) throws Exception {
        return reader.readLine();
    }

    private static class ListItem {
        private final String flags;
        private final String mailbox;

        private ListItem(String flags, String mailbox) {
            this.flags = flags == null ? "" : flags;
            this.mailbox = mailbox;
        }
    }

    private static class MailContent {
        private final String textBody;
        private final List<MailAttachment> attachments;

        private MailContent(String textBody, List<MailAttachment> attachments) {
            this.textBody = textBody == null ? "" : textBody;
            this.attachments = attachments == null ? List.of() : attachments;
        }
    }
}
