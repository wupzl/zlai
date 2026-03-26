package com.harmony.backend.ai.rag.controller;

import com.harmony.backend.ai.rag.controller.request.RagIngestRequest;
import com.harmony.backend.ai.rag.controller.request.RagMarkdownFileIngestRequest;
import com.harmony.backend.ai.rag.controller.request.RagMarkdownIngestRequest;
import com.harmony.backend.ai.rag.controller.request.RagQueryRequest;
import com.harmony.backend.ai.rag.controller.response.RagIngestResponse;
import com.harmony.backend.ai.rag.controller.response.RagQueryResponse;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.ai.rag.model.RagEvidenceResult;
import com.harmony.backend.ai.rag.config.RagOcrProperties;
import com.harmony.backend.ai.rag.model.OcrSettings;
import com.harmony.backend.ai.rag.service.OcrSettingsService;
import com.harmony.backend.ai.rag.service.OcrService;
import com.harmony.backend.ai.rag.service.RagAsyncService;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.ai.rag.service.impl.NoopRagService;
import com.harmony.backend.ai.rag.service.support.RagOcrOptimizer;
import com.harmony.backend.common.config.RagIngestFilepathProperties;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;
import com.harmony.backend.modules.chat.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
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
import org.springframework.http.HttpHeaders;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
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
    private final RagAsyncService ragAsyncService;
    private final RagOcrOptimizer ragOcrOptimizer;
    private final RagIngestFilepathProperties ragIngestFilepathProperties;
    @Qualifier("ragTaskExecutor")
    private final Executor ragTaskExecutor;
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
    private static final String INGEST_TASK_KEY_PREFIX = "rag:ingest:task:";
    private static final String INGEST_TASK_IDEMPOTENCY_KEY_PREFIX = "rag:ingest:idem:";
    private static final Duration INGEST_TASK_TTL = Duration.ofHours(24);
    private static final int REMOTE_IMAGE_MAX_REDIRECTS = 3;

    private record UploadedImagePayload(String name, byte[] bytes) {}

    private record MarkdownUploadPayload(String title,
                                         String markdownContent,
                                         Map<String, byte[]> imageMap,
                                         int requiredOcr) {}

    private record MarkdownAsyncResult(String title, String docId) {}

    @PostMapping("/ingest")
    public ApiResponse<RagIngestResponse> ingest(@RequestBody RagIngestRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
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

    @PostMapping("/ingest/async")
    public ApiResponse<Map<String, Object>> ingestAsync(@RequestBody RagIngestRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
        String taskId = UUID.randomUUID().toString();
        String title = request == null ? null : request.getTitle();
        updateTaskStatus(taskId, userId, "pending", 0, "Queued", null, title);
        updateTaskStatus(taskId, userId, "running", 15, "Embedding document", null, title);
        ragAsyncService.ingest(userId, title, request.getContent())
                .whenComplete((docId, throwable) -> {
            try {
                if (throwable == null) {
                updateTaskStatus(taskId, userId, "completed", 100, "Completed", docId, title);
                    return;
                }
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                if (cause instanceof BusinessException e) {
                    updateTaskStatus(taskId, userId, "failed", 100, e.getMessage(), null, title);
                } else {
                    log.error("Async RAG ingest failed: taskId={}", taskId, cause);
                    updateTaskStatus(taskId, userId, "failed", 100, "RAG ingest failed", null, title);
                }
            } catch (Exception e) {
                log.error("Async RAG ingest failed: taskId={}", taskId, e);
                updateTaskStatus(taskId, userId, "failed", 100, "RAG ingest failed", null, title);
            }
        });
        return ApiResponse.success(readTaskStatus(taskId));
    }

    @PostMapping("/ingest/markdown")
    public ApiResponse<RagIngestResponse> ingestMarkdown(@RequestBody RagMarkdownIngestRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
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
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
        try {
            if (!ragIngestFilepathProperties.isIngestFilepathEnabled()) {
                return ApiResponse.error(403, "Filepath ingest is disabled");
            }
            if (ragIngestFilepathProperties.isIngestFilepathAdminOnly() && !RequestUtils.isAdmin()) {
                return ApiResponse.error(403, "Filepath ingest is admin only");
            }
            if (request == null || request.getFilePath() == null || request.getFilePath().isBlank()) {
                return ApiResponse.error(400, "filePath is required");
            }
            Path path = validateIngestPath(request.getFilePath());
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

    @PostMapping("/ingest/markdown-upload/async")
    public ApiResponse<Map<String, Object>> ingestMarkdownUploadAsync(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "imagesZip", required = false) MultipartFile imagesZip) {
        Long userId = RequestUtils.getCurrentUserId();
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
        try {
            boolean isAdmin = RequestUtils.isAdmin();
            OcrSettings ocrSettings = ocrSettingsService.getSettings();
            if (file == null || file.isEmpty()) {
                return ApiResponse.error(400, "markdown file is required");
            }
            if (!isMarkdownFile(file)) {
                return ApiResponse.error(400, "Invalid markdown file type");
            }
            byte[] markdownBytes = file.getBytes();
            List<UploadedImagePayload> uploadedImages = collectUploadedImages(images);
            byte[] imagesZipBytes = imagesZip == null || imagesZip.isEmpty() ? null : imagesZip.getBytes();
            String title = file.getOriginalFilename() == null ? "Markdown Note" : file.getOriginalFilename();
            String fingerprint = hashUploadFingerprint("markdown-upload", title, markdownBytes, uploadedImages, imagesZipBytes);
            Map<String, Object> existingTask = findExistingUploadTask(userId, "markdown-upload", fingerprint);
            if (!existingTask.isEmpty()) {
                return ApiResponse.success(existingTask);
            }
            String taskId = createUploadTask(userId, "markdown-upload", fingerprint);
            if (!StringUtils.hasText(taskId)) {
                return ApiResponse.success(findExistingUploadTask(userId, "markdown-upload", fingerprint));
            }
            updateTaskStatus(taskId, userId, "pending", 0, "Queued", null, title);
            updateTaskStatus(taskId, userId, "running", 15, "Preparing markdown assets", null, title);
            submitRagTask(() -> {
                try {
                    MarkdownUploadPayload payload = prepareMarkdownUploadPayload(
                            userId,
                            file.getOriginalFilename(),
                            markdownBytes,
                            uploadedImages,
                            imagesZipBytes,
                            isAdmin,
                            ocrSettings);
                    updateTaskStatus(taskId, userId, "running", 70, "Embedding markdown document", null, payload.title());
                    String docId = ragService.ingestMarkdownWithImages(userId, payload.title(), payload.markdownContent(), payload.imageMap());
                    consumeMarkdownOcrQuota(userId, payload.requiredOcr(), isAdmin, ocrSettings);
                    return new MarkdownAsyncResult(payload.title(), docId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).whenComplete((result, throwable) -> {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                if (cause == null) {
                    updateTaskStatus(taskId, userId, "completed", 100, "Completed", result.docId(), result.title());
                    return;
                }
                if (cause instanceof BusinessException e) {
                    clearUploadTaskFingerprint(userId, "markdown-upload", fingerprint, taskId);
                    updateTaskStatus(taskId, userId, "failed", 100, e.getMessage(), null, title);
                    return;
                }
                if (cause.getCause() instanceof BusinessException e) {
                    clearUploadTaskFingerprint(userId, "markdown-upload", fingerprint, taskId);
                    updateTaskStatus(taskId, userId, "failed", 100, e.getMessage(), null, title);
                    return;
                }
                log.error("Async markdown upload failed: taskId={}", taskId, cause);
                clearUploadTaskFingerprint(userId, "markdown-upload", fingerprint, taskId);
                updateTaskStatus(taskId, userId, "failed", 100, "RAG markdown upload failed", null, title);
            });
            return ApiResponse.success(readTaskStatus(taskId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG async markdown upload failed", e);
            return ApiResponse.error("RAG markdown upload failed");
        }
    }

    private MarkdownUploadPayload prepareMarkdownUploadPayload(Long userId,
                                                               String filename,
                                                               byte[] markdownBytes,
                                                               List<UploadedImagePayload> uploadedImages,
                                                               byte[] imagesZipBytes,
                                                               boolean isAdmin,
                                                               OcrSettings ocrSettings) throws Exception {
        if (markdownBytes == null || markdownBytes.length == 0) {
            throw new BusinessException(400, "markdown file is required");
        }
        if (!StringUtils.hasText(filename) || !(filename.toLowerCase().endsWith(".md") || filename.toLowerCase().endsWith(".markdown"))) {
            throw new BusinessException(400, "Invalid markdown file type");
        }
        boolean allowOcr = isAdmin || ocrSettings.isEnabled();
        if (!isAdmin && ocrSettings.isEnabled()) {
            enforceOcrRateLimit(userId, ocrSettings);
        }
        long maxZipBytes = 300L * 1024 * 1024;
        if (imagesZipBytes != null && imagesZipBytes.length > maxZipBytes) {
            throw new BusinessException(400, "Images zip too large. Limit is 300MB.");
        }
        int maxImages = ocrSettings.getMaxImagesPerRequest();
        if (allowOcr && !isAdmin && uploadedImages.size() > maxImages) {
            throw new BusinessException(400, "Too many images. Limit is " + maxImages + ".");
        }
        String markdownContent = new String(markdownBytes, StandardCharsets.UTF_8);
        Map<String, byte[]> imageMap = new HashMap<>();
        if (allowOcr) {
            for (UploadedImagePayload image : uploadedImages) {
                if (!StringUtils.hasText(image.name()) || image.bytes() == null || image.bytes().length == 0) {
                    continue;
                }
                if (!isAdmin && image.bytes().length > ocrSettings.getMaxImageBytes()) {
                    continue;
                }
                imageMap.put(image.name(), image.bytes());
            }
            if (imagesZipBytes != null && imagesZipBytes.length > 0) {
                loadImagesFromZip(imagesZipBytes, imageMap, isAdmin ? Integer.MAX_VALUE : maxImages,
                        isAdmin ? Long.MAX_VALUE : ocrSettings.getMaxImageBytes());
            }
            Map<String, byte[]> remoteImages = downloadRemoteImages(markdownContent);
            if (!remoteImages.isEmpty()) {
                imageMap.putAll(remoteImages);
            }
        }
        if (allowOcr && !isAdmin && imageMap.size() > maxImages) {
            throw new BusinessException(400, "Too many images after filtering. Limit is " + maxImages + ".");
        }
        int requiredOcr = allowOcr ? imageMap.size() : 0;
        if (allowOcr && !isAdmin && requiredOcr > 0) {
            int remaining = resolveUserOcrBalance(userId, ocrSettings.getDefaultUserQuota());
            if (remaining < requiredOcr) {
                throw new BusinessException(400, "OCR quota exceeded. Remaining=" + remaining + ", required=" + requiredOcr + ".");
            }
        }
        return new MarkdownUploadPayload(filename, markdownContent, imageMap, requiredOcr);
    }

    private Path validateIngestPath(String requestedPath) throws Exception {
        Path realPath = Paths.get(requestedPath).toRealPath();
        if (!Files.isRegularFile(realPath) || !Files.isReadable(realPath)) {
            throw new BusinessException(400, "File is not readable");
        }

        List<String> allowedRoots = ragIngestFilepathProperties.getIngestFilepathAllowedRoots();
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            throw new BusinessException(403, "No filepath ingest roots configured");
        }

        for (String root : allowedRoots) {
            if (!StringUtils.hasText(root)) {
                continue;
            }
            Path allowedRoot = Paths.get(root).toRealPath();
            if (realPath.startsWith(allowedRoot)) {
                return realPath;
            }
        }
        throw new BusinessException(403, "Requested path is outside allowed roots");
    }

    private List<UploadedImagePayload> collectUploadedImages(List<MultipartFile> images) throws Exception {
        List<UploadedImagePayload> result = new java.util.ArrayList<>();
        if (images == null) {
            return result;
        }
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty() || !StringUtils.hasText(image.getOriginalFilename())) {
                continue;
            }
            result.add(new UploadedImagePayload(image.getOriginalFilename(), image.getBytes()));
        }
        return result;
    }

    private void consumeMarkdownOcrQuota(Long userId, int requiredOcr, boolean isAdmin, OcrSettings ocrSettings) {
        if (!isAdmin && requiredOcr > 0) {
            userMapper.updateOcrBalance(userId, -requiredOcr, ocrSettings.getDefaultUserQuota());
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

    private void loadImagesFromZip(byte[] zipBytes, Map<String, byte[]> imageMap, int limit, long maxBytes) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
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

    @PostMapping("/ingest/file-upload/async")
    public ApiResponse<Map<String, Object>> ingestFileUploadAsync(@RequestParam("file") MultipartFile file) {
        Long userId = RequestUtils.getCurrentUserId();
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
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
            byte[] fileBytes = file.getBytes();
            String filename = file.getOriginalFilename();
            String contentType = file.getContentType();
            String title = filename == null ? "Document" : filename;
            String fingerprint = hashUploadFingerprint("file-upload", title, fileBytes, java.util.List.of(), null);
            Map<String, Object> existingTask = findExistingUploadTask(userId, "file-upload", fingerprint);
            if (!existingTask.isEmpty()) {
                return ApiResponse.success(existingTask);
            }
            String taskId = createUploadTask(userId, "file-upload", fingerprint);
            if (!StringUtils.hasText(taskId)) {
                return ApiResponse.success(findExistingUploadTask(userId, "file-upload", fingerprint));
            }
            updateTaskStatus(taskId, userId, "pending", 0, "Queued", null, title);
            updateTaskStatus(taskId, userId, "running", 20, "Extracting text", null, title);
            submitRagTask(() -> {
                try {
                    String text = extractTextWithEmbeddedOcr(fileBytes, filename, contentType, ocrSettings, isAdmin);
                    if (text == null || text.isBlank()) {
                        throw new BusinessException(400, "file content is empty");
                    }
                    updateTaskStatus(taskId, userId, "running", 70, "Embedding document", null, title);
                    return text;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).thenCompose(text -> ragAsyncService.ingest(userId, title, text))
                    .whenComplete((docId, throwable) -> {
                        Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                                ? throwable.getCause()
                                : throwable;
                        if (cause == null) {
                            updateTaskStatus(taskId, userId, "completed", 100, "Completed", docId, title);
                            return;
                        }
                        if (cause instanceof BusinessException e) {
                            clearUploadTaskFingerprint(userId, "file-upload", fingerprint, taskId);
                            updateTaskStatus(taskId, userId, "failed", 100, e.getMessage(), null, title);
                            return;
                        }
                        if (cause.getCause() instanceof BusinessException e) {
                            clearUploadTaskFingerprint(userId, "file-upload", fingerprint, taskId);
                            updateTaskStatus(taskId, userId, "failed", 100, e.getMessage(), null, title);
                            return;
                        }
                        log.error("Async file ingest failed: taskId={}", taskId, cause);
                        clearUploadTaskFingerprint(userId, "file-upload", fingerprint, taskId);
                        updateTaskStatus(taskId, userId, "failed", 100, "RAG file upload failed", null, title);
                    });
            return ApiResponse.success(readTaskStatus(taskId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("RAG async file upload failed", e);
            return ApiResponse.error("RAG file upload failed");
        }
    }

    @GetMapping("/ingest/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getIngestTask(@PathVariable String taskId) {
        Long userId = RequestUtils.getCurrentUserId();
        Map<String, Object> status = readTaskStatus(taskId);
        Object ownerId = status.get("userId");
        if (ownerId != null && !String.valueOf(userId).equals(String.valueOf(ownerId)) && !RequestUtils.isAdmin()) {
            return ApiResponse.error(403, "Forbidden");
        }
        return ApiResponse.success(status);
    }

    private String extractTextWithEmbeddedOcr(byte[] fileBytes,
                                              String filename,
                                              String contentType,
                                              OcrSettings ocrSettings,
                                              boolean isAdmin) throws Exception {
        String lowerContentType = contentType == null ? "" : contentType.toLowerCase();
        if (isPlainText(filename, contentType)) {
            return decodeTextSmart(fileBytes);
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
                    String name = m.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                    if (!ragOcrOptimizer.shouldProcessImage(name, bytes)) {
                        return;
                    }
                    imageCount++;
                    if (ocrSettings.isEnabled()) {
                        String ocr = ragOcrOptimizer.cleanOcrText(ocrService.extractText(bytes, name, contentType));
                        if (ragOcrOptimizer.isUsefulOcrText(ocr)) {
                            ocrBlock.append("\n\n[OCR] ").append(name == null ? "image" : name).append("\n")
                                    .append(ocr.trim()).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        };
        context.set(EmbeddedDocumentExtractor.class, extractor);
        try (TikaInputStream input = TikaInputStream.get(fileBytes)) {
            parser.parse(input, handler, metadata, context);
        }
        String baseText = handler.toString();
        if (lowerContentType.contains("pdf")) {
            int maxPages = ragOcrOptimizer.shouldUsePdfOcr(baseText) ? 12 : 4;
            if (!isAdmin) {
                maxPages = Math.min(maxPages, ocrSettings.getMaxPdfPages());
            }
            if (ocrSettings.isEnabled() && ragOcrOptimizer.shouldUsePdfOcr(baseText)) {
                String pdfOcr = ocrPdfPages(fileBytes, maxPages);
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

    private String ocrPdfPages(byte[] fileBytes, int maxPages) {
        try (PDDocument doc = PDDocument.load(fileBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), Math.max(1, maxPages));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                java.awt.image.BufferedImage image = renderer.renderImageWithDPI(i, 200);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", baos);
                byte[] imageBytes = baos.toByteArray();
                if (!ragOcrOptimizer.shouldProcessImage("page-" + (i + 1) + ".png", imageBytes)) {
                    continue;
                }
                String ocr = ragOcrOptimizer.cleanOcrText(
                        ocrService.extractText(imageBytes, "page-" + (i + 1) + ".png", "image/png"));
                if (ragOcrOptimizer.isUsefulOcrText(ocr)) {
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
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        int count = 0;
        for (String url : urls) {
            if (count >= remoteImageMaxCount) {
                break;
            }
            try {
                URI uri = URI.create(url);
                byte[] bytes = downloadRemoteImage(client, uri);
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

    private byte[] downloadRemoteImage(HttpClient client, URI initialUri) {
        URI currentUri = initialUri;
        for (int redirectCount = 0; redirectCount <= REMOTE_IMAGE_MAX_REDIRECTS; redirectCount++) {
            if (!isSafeRemoteUri(currentUri)) {
                return null;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(currentUri)
                        .timeout(Duration.ofMillis(remoteImageReadTimeoutMs))
                        .header("User-Agent", "zlAI-RAG/1.0")
                        .GET()
                        .build();
                HttpResponse<java.io.InputStream> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (java.io.InputStream body = resp.body()) {
                    int status = resp.statusCode();
                    if (status >= 300 && status < 400) {
                        String location = resp.headers().firstValue(HttpHeaders.LOCATION).orElse(null);
                        currentUri = resolveRedirectUri(currentUri, location);
                        if (currentUri == null) {
                            return null;
                        }
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        return null;
                    }
                    String contentType = resp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("");
                    if (!contentType.toLowerCase().startsWith("image/")) {
                        return null;
                    }
                    return readUpTo(body, remoteImageMaxBytes);
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
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

    boolean isSafeRemoteUri(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        if (StringUtils.hasText(uri.getUserInfo())) {
            return false;
        }
        String host = uri.getHost();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || isPrivateIpv4(address)
                        || isUnsafeIpv6(address)) {
                    return false;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return true;
    }

    URI resolveRedirectUri(URI currentUri, String location) {
        if (currentUri == null || !StringUtils.hasText(location)) {
            return null;
        }
        try {
            URI resolved = currentUri.resolve(location.trim());
            return isSafeRemoteUri(resolved) ? resolved : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isPrivateIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        if (b0 == 0) return true;
        if (b0 == 10) return true;
        if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;
        if (b0 == 127) return true;
        if (b0 == 169 && b1 == 254) return true;
        if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
        if (b0 == 192 && b1 == 0) return true;
        if (b0 == 192 && b1 == 168) return true;
        if (b0 >= 224) return true;
        return false;
    }

    private boolean isUnsafeIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return false;
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        if ((first & 0xFE) == 0xFC) {
            return true;
        }
        return first == 0x20 && second == 0x01 && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8;
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

    private void updateTaskStatus(String taskId, Long userId, String status, int progress, String message, String docId, String title) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("userId", userId);
        payload.put("status", status);
        payload.put("progress", progress);
        payload.put("message", message);
        payload.put("docId", docId);
        payload.put("title", title);
        payload.put("updatedAt", System.currentTimeMillis());
        redisTemplate.opsForHash().putAll(taskKey(taskId), payload);
        redisTemplate.expire(taskKey(taskId), INGEST_TASK_TTL);
    }

    private Map<String, Object> readTaskStatus(String taskId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(taskKey(taskId));
        Map<String, Object> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            result.put("taskId", taskId);
            result.put("status", "missing");
            result.put("progress", 0);
            result.put("message", "Task not found");
            return result;
        }
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String taskKey(String taskId) {
        return INGEST_TASK_KEY_PREFIX + taskId;
    }

    private Map<String, Object> findExistingUploadTask(Long userId, String scope, String fingerprint) {
        Object existingTaskId = redisTemplate.opsForValue().get(taskIdempotencyKey(userId, scope, fingerprint));
        if (existingTaskId == null || !StringUtils.hasText(String.valueOf(existingTaskId))) {
            return Map.of();
        }
        Map<String, Object> status = readTaskStatus(String.valueOf(existingTaskId));
        if ("missing".equals(String.valueOf(status.get("status")))) {
            redisTemplate.delete(taskIdempotencyKey(userId, scope, fingerprint));
            return Map.of();
        }
        return status;
    }

    private String createUploadTask(Long userId, String scope, String fingerprint) {
        String taskId = UUID.randomUUID().toString();
        String key = taskIdempotencyKey(userId, scope, fingerprint);
        Boolean created = redisTemplate.opsForValue().setIfAbsent(key, taskId, INGEST_TASK_TTL);
        if (Boolean.TRUE.equals(created)) {
            return taskId;
        }
        return null;
    }

    private void clearUploadTaskFingerprint(Long userId, String scope, String fingerprint, String taskId) {
        String key = taskIdempotencyKey(userId, scope, fingerprint);
        Object current = redisTemplate.opsForValue().get(key);
        if (current != null && taskId.equals(String.valueOf(current))) {
            redisTemplate.delete(key);
        }
    }

    private String taskIdempotencyKey(Long userId, String scope, String fingerprint) {
        return INGEST_TASK_IDEMPOTENCY_KEY_PREFIX + userId + ":" + scope + ":" + fingerprint;
    }

    private String hashUploadFingerprint(String scope,
                                         String title,
                                         byte[] primaryBytes,
                                         List<UploadedImagePayload> uploadedImages,
                                         byte[] zipBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            if (StringUtils.hasText(title)) {
                digest.update(title.getBytes(StandardCharsets.UTF_8));
            }
            if (primaryBytes != null) {
                digest.update(primaryBytes);
            }
            if (uploadedImages != null && !uploadedImages.isEmpty()) {
                uploadedImages.stream()
                        .sorted(java.util.Comparator.comparing(UploadedImagePayload::name, java.util.Comparator.nullsLast(String::compareTo)))
                        .forEach(image -> {
                            if (image.name() != null) {
                                digest.update(image.name().getBytes(StandardCharsets.UTF_8));
                            }
                            if (image.bytes() != null) {
                                digest.update(image.bytes());
                            }
                        });
            }
            if (zipBytes != null) {
                digest.update(zipBytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to build upload fingerprint");
        }
    }

    @PostMapping("/query")
    public ApiResponse<RagQueryResponse> query(@RequestBody RagQueryRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
        try {
            RagEvidenceResult evidence = ragService.resolveEvidence(userId, request.getQuery(), request.getTopK());
            return ApiResponse.success(new RagQueryResponse(evidence.context(), evidence.matches()));
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
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
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
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
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
        if (!isRagDatasourceConfigured()) {
            return ragDatasourceUnavailable();
        }
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
    private boolean isRagDatasourceConfigured() {
        Class<?> targetClass = AopUtils.getTargetClass(ragService);
        return targetClass == null || !NoopRagService.class.isAssignableFrom(targetClass);
    }

    @SuppressWarnings("unchecked")
    private <T> ApiResponse<T> ragDatasourceUnavailable() {
        return (ApiResponse<T>) ApiResponse.error(503, "RAG datasource not configured");
    }

    private <T> java.util.concurrent.CompletableFuture<T> submitRagTask(Supplier<T> supplier) {
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        try {
            ragTaskExecutor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(new BusinessException(503, "RAG async executor is busy"));
        }
        return future;
    }

}
