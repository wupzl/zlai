package com.harmony.backend.ai.rag.controller;

import com.harmony.backend.ai.rag.controller.request.RagIngestRequest;
import com.harmony.backend.ai.rag.controller.request.RagMarkdownFileIngestRequest;
import com.harmony.backend.ai.rag.controller.request.RagMarkdownIngestRequest;
import com.harmony.backend.ai.rag.controller.request.RagQueryRequest;
import com.harmony.backend.ai.rag.controller.response.RagIngestResponse;
import com.harmony.backend.ai.rag.controller.response.RagQueryResponse;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.ai.rag.config.RagOcrProperties;
import com.harmony.backend.ai.rag.model.OcrSettings;
import com.harmony.backend.ai.rag.service.OcrSettingsService;
import com.harmony.backend.ai.rag.service.OcrService;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;
import com.harmony.backend.modules.chat.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.io.TikaInputStream;
import org.xml.sax.ContentHandler;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(RagService.class)
public class RagController {

    private final RagService ragService;
    private final SessionService sessionService;
    private final OcrService ocrService;
    private final RagOcrProperties ragOcrProperties;
    private final RedisTemplate<String, Object> redisTemplate;
    private final OcrSettingsService ocrSettingsService;
    private final com.harmony.backend.common.mapper.UserMapper userMapper;
    @Value("${app.rag.ingest-filepath-enabled:false}")
    private boolean ingestFilepathEnabled;
    @Value("${app.rag.remote-images.max-count:20}")
    private int remoteImageMaxCount;
    @Value("${app.rag.remote-images.max-bytes:5242880}")
    private long remoteImageMaxBytes;
    @Value("${app.rag.remote-images.connect-timeout-ms:5000}")
    private long remoteImageConnectTimeoutMs;
    @Value("${app.rag.remote-images.read-timeout-ms:8000}")
    private long remoteImageReadTimeoutMs;

    private static final Pattern REMOTE_MD_IMAGE =
            Pattern.compile("!\\[[^\\]]*\\]\\((https?://[^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern REMOTE_HTML_IMAGE =
            Pattern.compile("<img[^>]*src=[\"'](https?://[^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    @PostMapping("/ingest")
    public ApiResponse<RagIngestResponse> ingest(@RequestBody RagIngestRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            String docId = ragService.ingest(userId, request.getTitle(), request.getContent());
            return ApiResponse.success(new RagIngestResponse(docId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG ingest failed", e);
            return ApiResponse.error("RAG ingest failed");
        }
    }

    @PostMapping("/ingest/markdown")
    public ApiResponse<RagIngestResponse> ingestMarkdown(@RequestBody RagMarkdownIngestRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            String docId = ragService.ingestMarkdown(
                    userId,
                    request.getTitle(),
                    request.getMarkdownContent(),
                    request.getSourcePath());
            return ApiResponse.success(new RagIngestResponse(docId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG markdown ingest failed", e);
            return ApiResponse.error("RAG markdown ingest failed");
        }
    }

    @PostMapping("/ingest/markdown-file")
    public ApiResponse<RagIngestResponse> ingestMarkdownFile(@RequestBody RagMarkdownFileIngestRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            if (!ingestFilepathEnabled) {
                return ApiResponse.error(403, "Filepath ingest is disabled");
            }
            if (request == null || request.getFilePath() == null || request.getFilePath().isBlank()) {
                return ApiResponse.error(400, "filePath is required");
            }
            Path path = Path.of(request.getFilePath()).normalize();
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String docId = ragService.ingestMarkdown(
                    userId,
                    request.getTitle(),
                    content,
                    path.toString());
            return ApiResponse.success(new RagIngestResponse(docId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG markdown-file ingest failed", e);
            return ApiResponse.error("RAG markdown-file ingest failed");
        }
    }

    @PostMapping("/ingest/markdown-upload")
    public ApiResponse<RagIngestResponse> ingestMarkdownUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "imagesZip", required = false) MultipartFile imagesZip) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            boolean isAdmin = RequestUtils.isAdmin();
            OcrSettings ocrSettings = ocrSettingsService.getSettings();
            boolean allowOcr = isAdmin || ocrSettings.isEnabled();
            if (file == null || file.isEmpty()) {
                return ApiResponse.error(400, "markdown file is required");
            }
            if (!isMarkdownFile(file)) {
                return ApiResponse.error(400, "Invalid markdown file type");
            }
            if (!isAdmin && ocrSettings.isEnabled()) {
                enforceOcrRateLimit(userId, ocrSettings);
            }
            int totalImages = images != null ? images.size() : 0;
            if (imagesZip != null && !imagesZip.isEmpty()) {
                totalImages += 1;
            }
            long maxZipBytes = 300L * 1024 * 1024;
            if (imagesZip != null && !imagesZip.isEmpty() && imagesZip.getSize() > maxZipBytes) {
                return ApiResponse.error(400, "Images zip too large. Limit is 300MB.");
            }
            int maxImages = ocrSettings.getMaxImagesPerRequest();
            if (allowOcr && !isAdmin && images != null && images.size() > maxImages) {
                return ApiResponse.error(400, "Too many images. Limit is " + maxImages + ".");
            }
            String mdContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            Map<String, byte[]> imageMap = new HashMap<>();
            if (allowOcr) {
                if (images != null) {
                    for (MultipartFile img : images) {
                        if (img == null || img.isEmpty() || img.getOriginalFilename() == null) {
                            continue;
                        }
                        String name = img.getOriginalFilename();
                        byte[] bytes = img.getBytes();
                        if (!isAdmin && bytes.length > ocrSettings.getMaxImageBytes()) {
                            continue;
                        }
                        imageMap.put(name, bytes);
                    }
                }
                if (imagesZip != null && !imagesZip.isEmpty()) {
                    loadImagesFromZip(imagesZip, imageMap, isAdmin ? Integer.MAX_VALUE : maxImages,
                            isAdmin ? Long.MAX_VALUE : ocrSettings.getMaxImageBytes());
                }
                Map<String, byte[]> remoteImages = downloadRemoteImages(mdContent);
                if (!remoteImages.isEmpty()) {
                    imageMap.putAll(remoteImages);
                }
            }
            if (allowOcr && !isAdmin && imageMap.size() > maxImages) {
                return ApiResponse.error(400, "Too many images after filtering. Limit is " + maxImages + ".");
            }
            int requiredOcr = allowOcr ? imageMap.size() : 0;
            if (allowOcr && !isAdmin && requiredOcr > 0) {
                int remaining = resolveUserOcrBalance(userId, ocrSettings.getDefaultUserQuota());
                if (remaining < requiredOcr) {
                    return ApiResponse.error(400, "OCR quota exceeded. Remaining=" + remaining + ", required=" + requiredOcr + ".");
                }
            }
            String docId = ragService.ingestMarkdownWithImages(
                    userId,
                    file.getOriginalFilename(),
                    mdContent,
                    imageMap);
            if (allowOcr && !isAdmin && requiredOcr > 0) {
                userMapper.updateOcrBalance(userId, -requiredOcr, ocrSettings.getDefaultUserQuota());
            }
            return ApiResponse.success(new RagIngestResponse(docId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG markdown upload failed", e);
            return ApiResponse.error("RAG markdown upload failed");
        }
    }

    private void loadImagesFromZip(MultipartFile zipFile, Map<String, byte[]> imageMap, int limit, long maxBytes) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(zipFile.getInputStream())) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                        || lower.endsWith(".svg"))) {
                    continue;
                }
                if (imageMap.size() >= limit) {
                    break;
                }
                byte[] bytes = zis.readAllBytes();
                if (bytes.length > maxBytes) {
                    continue;
                }
                imageMap.put(java.nio.file.Paths.get(name).getFileName().toString(), bytes);
            }
        }
    }

    @PostMapping("/ingest/file-upload")
    public ApiResponse<RagIngestResponse> ingestFileUpload(
            @RequestParam("file") MultipartFile file) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            boolean isAdmin = RequestUtils.isAdmin();
            OcrSettings ocrSettings = ocrSettingsService.getSettings();
            if (file == null || file.isEmpty()) {
                return ApiResponse.error(400, "file is required");
            }
            if (!isAllowedDocFile(file)) {
                return ApiResponse.error(400, "Unsupported file type");
            }
            if (!isAdmin && ocrSettings.isEnabled()) {
                enforceOcrRateLimit(userId, ocrSettings);
            }
            String text = extractTextWithEmbeddedOcr(file, ocrSettings, isAdmin);
            if (text == null || text.isBlank()) {
                return ApiResponse.error(400, "file content is empty");
            }
            String title = file.getOriginalFilename() == null ? "Document" : file.getOriginalFilename();
            String docId = ragService.ingest(userId, title, text);
            return ApiResponse.success(new RagIngestResponse(docId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG file upload failed", e);
            return ApiResponse.error("RAG file upload failed");
        }
    }

    private String extractTextWithEmbeddedOcr(MultipartFile file, OcrSettings ocrSettings, boolean isAdmin) throws Exception {
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (isPlainText(filename, contentType)) {
            byte[] raw = file.getBytes();
            return decodeTextSmart(raw);
        }
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);

        StringBuilder ocrBlock = new StringBuilder();
        EmbeddedDocumentExtractor extractor = new EmbeddedDocumentExtractor() {
            private int imageCount = 0;
            @Override
            public boolean shouldParseEmbedded(Metadata m) {
                return true;
            }

            @Override
            public void parseEmbedded(java.io.InputStream stream, ContentHandler handler,
                                      Metadata m, boolean outputHtml) {
                try {
                    String contentType = m.get(Metadata.CONTENT_TYPE);
                    if (contentType == null) {
                        return;
                    }
                    if (!contentType.toLowerCase().startsWith("image/")) {
                        return;
                    }
                    if (!isAdmin && imageCount >= ocrSettings.getMaxImagesPerRequest()) {
                        return;
                    }
                    byte[] bytes = stream.readAllBytes();
                    if (!isAdmin && bytes.length > ocrSettings.getMaxImageBytes()) {
                        return;
                    }
                    imageCount++;
                    if (ocrSettings.isEnabled()) {
                        String name = m.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                        String ocr = ocrService.extractText(bytes, name, contentType);
                        if (ocr != null && !ocr.isBlank()) {
                            ocrBlock.append("\n\n[OCR] ").append(name == null ? "image" : name).append("\n")
                                    .append(ocr.trim()).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        };
        context.set(EmbeddedDocumentExtractor.class, extractor);
        try (TikaInputStream input = TikaInputStream.get(file.getInputStream())) {
            parser.parse(input, handler, metadata, context);
        }
        String baseText = handler.toString();
        if (contentType.contains("pdf")) {
            int maxPages = (baseText == null || baseText.trim().length() < 200) ? 12 : 6;
            if (!isAdmin) {
                maxPages = Math.min(maxPages, ocrSettings.getMaxPdfPages());
            }
            if (ocrSettings.isEnabled()) {
                String pdfOcr = ocrPdfPages(file, maxPages);
                if (pdfOcr != null && !pdfOcr.isBlank()) {
                    ocrBlock.append("\n\n---\nPDF Page OCR\n").append(pdfOcr.trim()).append("\n");
                }
            }
        }
        if (ocrBlock.length() > 0) {
            return baseText + "\n\n---\nEmbedded OCR\n" + ocrBlock;
        }
        return baseText;
    }

    private boolean isPlainText(String filename, String contentType) {
        if (contentType != null && contentType.contains("text/plain")) {
            return true;
        }
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".txt");
    }

    private String decodeTextSmart(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        String utf8Strict = decodeUtf8Strict(raw);
        if (utf8Strict != null) {
            return utf8Strict;
        }
        String gbk = new String(raw, java.nio.charset.Charset.forName("GB18030"));
        return gbk;
    }

    private double scoreText(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        int len = text.length();
        int replacement = 0;
        int cjk = 0;
        int ascii = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD') {
                replacement++;
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else if (c >= 0x20 && c <= 0x7E) {
                ascii++;
            }
        }
        double replacementPenalty = replacement * 5.0;
        double cjkBonus = cjk * 2.0;
        double asciiBonus = ascii * 0.2;
        return cjkBonus + asciiBonus - replacementPenalty;
    }

    private String decodeUtf8Strict(byte[] raw) {
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String ocrPdfPages(MultipartFile file, int maxPages) {
        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), Math.max(1, maxPages));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                java.awt.image.BufferedImage image = renderer.renderImageWithDPI(i, 200);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", baos);
                String ocr = ocrService.extractText(baos.toByteArray(), "page-" + (i + 1) + ".png", "image/png");
                if (ocr != null && !ocr.isBlank()) {
                    sb.append("\n\n[OCR] page-").append(i + 1).append("\n")
                      .append(ocr.trim()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isMarkdownFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private boolean isAllowedDocFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".pdf")
                || lower.endsWith(".doc")
                || lower.endsWith(".docx")
                || lower.endsWith(".txt");
    }

    private Map<String, byte[]> downloadRemoteImages(String markdown) {
        Map<String, byte[]> result = new HashMap<>();
        if (markdown == null || markdown.isBlank()) {
            return result;
        }
        List<String> urls = new java.util.ArrayList<>();
        Matcher mdMatcher = REMOTE_MD_IMAGE.matcher(markdown);
        while (mdMatcher.find()) {
            urls.add(mdMatcher.group(1));
        }
        Matcher htmlMatcher = REMOTE_HTML_IMAGE.matcher(markdown);
        while (htmlMatcher.find()) {
            urls.add(htmlMatcher.group(1));
        }
        if (urls.isEmpty()) {
            return result;
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(remoteImageConnectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        int count = 0;
        for (String url : urls) {
            if (count >= remoteImageMaxCount) {
                break;
            }
            try {
                URI uri = URI.create(url);
                if (!isSafeRemoteUri(uri)) {
                    continue;
                }
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(remoteImageReadTimeoutMs))
                        .header("User-Agent", "zlAI-RAG/1.0")
                        .GET()
                        .build();
                HttpResponse<java.io.InputStream> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    continue;
                }
                String contentType = resp.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase().startsWith("image/")) {
                    continue;
                }
                byte[] bytes = readUpTo(resp.body(), remoteImageMaxBytes);
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                String filename = extractFileNameFromUrl(url);
                result.put(filename, bytes);
                count++;
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private byte[] readUpTo(java.io.InputStream in, long maxBytes) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        long total = 0;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            baos.write(buf, 0, read);
        }
        return baos.toByteArray();
    }

    private String extractFileNameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) {
                return "remote-image";
            }
            String name = java.nio.file.Paths.get(path).getFileName().toString();
            return name == null || name.isBlank() ? "remote-image" : name;
        } catch (Exception e) {
            return "remote-image";
        }
    }

    private boolean isSafeRemoteUri(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || isPrivateIpv4(address)) {
                return false;
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return true;
    }

    private boolean isPrivateIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        if (b0 == 10) return true;
        if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
        if (b0 == 192 && b1 == 168) return true;
        if (b0 == 127) return true;
        return false;
    }

    private void enforceOcrRateLimit(Long userId, OcrSettings settings) {
        if (userId == null || settings == null || settings.getRateLimitPerDay() <= 0) {
            return;
        }
        String key = "rate_limit:ocr:user:" + userId;
        long windowSeconds = Math.max(1, settings.getRateLimitWindowSeconds());
        long now = System.currentTimeMillis() / 1000;
        long cutoff = now - windowSeconds;
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoff);
        String member = now + "-" + UUID.randomUUID();
        redisTemplate.opsForZSet().add(key, member, now);
        Long count = redisTemplate.opsForZSet().zCard(key);
        redisTemplate.expire(key, Duration.ofSeconds(windowSeconds * 2));
        if (count != null && count > settings.getRateLimitPerDay()) {
            throw new BusinessException(429, "OCR rate limit exceeded");
        }
    }

    private int resolveUserOcrBalance(Long userId, int defaultQuota) {
        if (userId == null) {
            return 0;
        }
        com.harmony.backend.common.entity.User user = userMapper.selectById(userId);
        if (user == null) {
            return 0;
        }
        Integer balance = user.getOcrBalance();
        if (balance == null) {
            return Math.max(0, defaultQuota);
        }
        return Math.max(0, balance);
    }

    @PostMapping("/query")
    public ApiResponse<RagQueryResponse> query(@RequestBody RagQueryRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            List<RagChunkMatch> matches = ragService.search(userId, request.getQuery(), request.getTopK());
            String context = ragService.buildContext(userId, request.getQuery(), request.getTopK());
            return ApiResponse.success(new RagQueryResponse(context, matches));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG query failed", e);
            return ApiResponse.error("RAG query failed");
        }
    }

    @GetMapping("/documents")
    public ApiResponse<PageResult<RagDocumentSummary>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            return ApiResponse.success(ragService.listDocuments(userId, page, size));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG list documents failed", e);
            return ApiResponse.error("RAG list documents failed");
        }
    }

    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Boolean> deleteDocument(@PathVariable String docId) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            return ApiResponse.success(ragService.deleteDocument(userId, docId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG delete document failed", e);
            return ApiResponse.error("RAG delete document failed");
        }
    }

    @PostMapping("/session")
    public ApiResponse<ChatSessionVO> createRagSession(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String toolModel) {
        Long userId = RequestUtils.getCurrentUserId();
        try {
            ChatSessionVO session = sessionService.createRagSession(userId, title, model, toolModel);
            return ApiResponse.success(session);
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Create RAG session failed", e);
            return ApiResponse.error("Create RAG session failed");
        }
    }
}
