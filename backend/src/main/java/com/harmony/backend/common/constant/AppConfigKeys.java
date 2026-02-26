package com.harmony.backend.common.constant;

public final class AppConfigKeys {
    private AppConfigKeys() {
    }

    public static final String TOOLS_SEARCH_SEARX_ENABLED = "tools.search.searx.enabled";
    public static final String TOOLS_SEARCH_SEARX_URL = "tools.search.searx.url";
    public static final String TOOLS_SEARCH_SERPAPI_KEY = "tools.search.serpapi.key";
    public static final String TOOLS_SEARCH_SERPAPI_ENGINE = "tools.search.serpapi.engine";
    public static final String TOOLS_SEARCH_WIKIPEDIA_ENABLED = "tools.search.wikipedia.enabled";
    public static final String TOOLS_SEARCH_BAIKE_ENABLED = "tools.search.baike.enabled";
    public static final String TOOLS_SEARCH_BOCHA_ENABLED = "tools.search.bocha.enabled";
    public static final String TOOLS_SEARCH_BOCHA_API_KEY = "tools.search.bocha.api_key";
    public static final String TOOLS_SEARCH_BOCHA_ENDPOINT = "tools.search.bocha.endpoint";
    public static final String TOOLS_SEARCH_BAIDU_ENABLED = "tools.search.baidu.enabled";
    public static final String TOOLS_SEARCH_WIKIPEDIA_UA = "tools.search.wikipedia.user_agent";
    public static final String TOOLS_SEARCH_WIKIPEDIA_PROXY_ENABLED = "tools.search.wikipedia.proxy_enabled";
    public static final String TOOLS_SEARCH_WIKIPEDIA_PROXY_URL = "tools.search.wikipedia.proxy_url";

    public static final String RATE_LIMIT_GLOBAL_ENABLED = "rate_limit.global.enabled";
    public static final String RATE_LIMIT_GLOBAL_ADMIN_BYPASS = "rate_limit.global.admin_bypass";
    public static final String RATE_LIMIT_GLOBAL_WINDOW_SECONDS = "rate_limit.global.window_seconds";
    public static final String RATE_LIMIT_GLOBAL_IP_LIMIT = "rate_limit.global.ip_limit";
    public static final String RATE_LIMIT_GLOBAL_USER_LIMIT = "rate_limit.global.user_limit";
    public static final String RATE_LIMIT_GLOBAL_WHITELIST_IPS = "rate_limit.global.whitelist_ips";
    public static final String RATE_LIMIT_GLOBAL_WHITELIST_PATHS = "rate_limit.global.whitelist_paths";

    public static final String RAG_OCR_ENABLED = "rag.ocr.enabled";
    public static final String RAG_OCR_MAX_IMAGES = "rag.ocr.max_images_per_request";
    public static final String RAG_OCR_MAX_IMAGE_BYTES = "rag.ocr.max_image_bytes";
    public static final String RAG_OCR_MAX_PDF_PAGES = "rag.ocr.max_pdf_pages";
    public static final String RAG_OCR_RATE_LIMIT_PER_DAY = "rag.ocr.rate_limit_per_day";
    public static final String RAG_OCR_RATE_LIMIT_WINDOW_SEC = "rag.ocr.rate_limit_window_seconds";
    public static final String RAG_OCR_DEFAULT_USER_QUOTA = "rag.ocr.default_user_quota";

    public static final String OPENAI_STREAM_ENABLED = "openai.stream.enabled";
}
