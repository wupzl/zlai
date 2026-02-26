<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Platform Settings</h2>
        <div class="meta">Search tool configuration for administrators</div>
      </div>
      <button class="cta" @click="loadSettings">Refresh</button>
    </header>

    <div class="card">
      <div class="card-head">
        <div>
          <h3>Web Search Settings</h3>
          <div class="meta">SearXNG is preferred when enabled, then SerpAPI fallback.</div>
        </div>
        <button class="ghost" @click="saveSettings" :disabled="saving">Save</button>
      </div>

      <div class="form-grid settings-grid">
        <div class="field-group full">
          <label>Wikipedia User-Agent</label>
          <input v-model="settings.wikipediaUserAgent" placeholder="zlAI/1.0 (contact: email@example.com)" />
          <div class="hint">Required by MediaWiki API. Include a contact email.</div>
        </div>
        <div class="field-group toggle">
          <label>Enable Wikipedia</label>
          <input type="checkbox" v-model="settings.wikipediaEnabled" />
          <div class="hint">Disable if Wikipedia is blocked in your network.</div>
        </div>
        <div class="field-group toggle">
          <label>Enable Baidu Baike</label>
          <input type="checkbox" v-model="settings.baikeEnabled" />
          <div class="hint">Enable Baidu Baike scraping as a fallback knowledge source.</div>
        </div>
        <div class="field-group toggle">
          <label>Enable Baidu Search</label>
          <input type="checkbox" v-model="settings.baiduEnabled" />
          <div class="hint">Lowest-priority fallback source.</div>
        </div>
        <div class="field-group toggle">
          <label>Enable Bocha Search</label>
          <input type="checkbox" v-model="settings.bochaEnabled" />
          <div class="hint">Primary search source when enabled.</div>
        </div>
        <div class="field-group full">
          <label>Bocha API Key</label>
          <input :type="showKey ? 'text' : 'password'" v-model="settings.bochaApiKey" placeholder="bocha api key" />
        </div>
        <div class="field-group full">
          <label>Bocha Endpoint</label>
          <input v-model="settings.bochaEndpoint" placeholder="https://api.bocha.cn/v1/web-search" />
        </div>
        <div class="field-group toggle">
          <label>Enable SearXNG</label>
          <input type="checkbox" v-model="settings.searxEnabled" />
          <div class="hint">Highest priority when enabled.</div>
        </div>
        <div class="field-group full">
          <label>SearXNG Base URL</label>
          <input v-model="settings.searxUrl" placeholder="https://searxng.example.com" />
        </div>
        <div class="field-group full">
          <label>SerpAPI Key (optional)</label>
          <input :type="showKey ? 'text' : 'password'" v-model="settings.serpApiKey" placeholder="serpapi key" />
          <div class="hint">
            Leave empty to disable SerpAPI. Toggle display:
            <input type="checkbox" v-model="showKey" />
            Show
          </div>
        </div>
        <div class="field-group full">
          <label>SerpAPI Engine</label>
          <select v-model="settings.serpApiEngine">
            <option value="baidu">baidu</option>
            <option value="google">google</option>
            <option value="bing">bing</option>
            <option value="duckduckgo">duckduckgo</option>
          </select>
          <div class="hint">Engine is used only when SerpAPI key is set.</div>
        </div>
        <div class="field-group toggle">
          <label>Enable Wikipedia Proxy</label>
          <input type="checkbox" v-model="settings.wikipediaProxyEnabled" />
          <div class="hint">Use a proxy (e.g. r.jina.ai) if direct access is blocked.</div>
        </div>
        <div class="field-group full">
          <label>Wikipedia Proxy URL</label>
          <input v-model="settings.wikipediaProxyUrl" placeholder="https://r.jina.ai/http://" />
          <div class="hint">Only used when proxy is enabled.</div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3>Global Rate Limiting</h3>
          <div class="meta">Protect all endpoints. Use whitelist for trusted IPs or internal traffic.</div>
        </div>
        <button class="ghost" @click="saveRateLimit" :disabled="savingRate">Save</button>
      </div>

      <div class="form-grid settings-grid">
        <div class="field-group toggle">
          <label>Enable Global Limit</label>
          <input type="checkbox" v-model="rateLimit.enabled" />
          <div class="hint">Apply to all requests except whitelisted paths.</div>
        </div>
        <div class="field-group toggle">
          <label>Admin Bypass</label>
          <input type="checkbox" v-model="rateLimit.adminBypass" />
          <div class="hint">Skip limits for admin requests.</div>
        </div>
        <div class="field-group">
          <label>Window Seconds</label>
          <input type="number" min="1" v-model.number="rateLimit.windowSeconds" />
        </div>
        <div class="field-group">
          <label>IP Limit</label>
          <input type="number" min="1" v-model.number="rateLimit.ipLimit" />
        </div>
        <div class="field-group">
          <label>User Limit</label>
          <input type="number" min="1" v-model.number="rateLimit.userLimit" />
        </div>
        <div class="field-group full">
          <label>Whitelist IPs / CIDR</label>
          <textarea rows="3" v-model="rateLimitWhitelistIps" placeholder="127.0.0.1&#10;192.168.0.0/16&#10;10.0.*"></textarea>
          <div class="hint">One per line. Supports CIDR (e.g. 192.168.0.0/16) or prefix with *.</div>
        </div>
        <div class="field-group full">
          <label>Whitelist Paths</label>
          <textarea rows="3" v-model="rateLimitWhitelistPaths" placeholder="/actuator&#10;/swagger-ui"></textarea>
          <div class="hint">One prefix per line. Requests starting with these paths are not limited.</div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3>OCR Limits</h3>
          <div class="meta">Limits apply to normal users. Admins are exempt.</div>
        </div>
        <button class="ghost" @click="saveOcrSettings" :disabled="savingOcr">Save</button>
      </div>

      <div class="form-grid settings-grid">
        <div class="field-group toggle">
          <label>Enable OCR</label>
          <input type="checkbox" v-model="ocrSettings.enabled" />
          <div class="hint">Disable to skip OCR even when files contain images.</div>
        </div>
        <div class="field-group">
          <label>Max Images per Request</label>
          <input type="number" min="1" v-model.number="ocrSettings.maxImagesPerRequest" />
        </div>
        <div class="field-group">
          <label>Max Image Bytes</label>
          <input type="number" min="1024" v-model.number="ocrSettings.maxImageBytes" />
          <div class="hint">Bytes per image (e.g. 5242880 = 5MB).</div>
        </div>
        <div class="field-group">
          <label>Max PDF Pages</label>
          <input type="number" min="1" v-model.number="ocrSettings.maxPdfPages" />
        </div>
        <div class="field-group">
          <label>Rate Limit / Day</label>
          <input type="number" min="0" v-model.number="ocrSettings.rateLimitPerDay" />
        </div>
        <div class="field-group">
          <label>Rate Limit Window Seconds</label>
          <input type="number" min="1" v-model.number="ocrSettings.rateLimitWindowSeconds" />
          <div class="hint">Default is 86400 seconds (1 day).</div>
        </div>
        <div class="field-group">
          <label>Default User OCR Quota</label>
          <input type="number" min="0" v-model.number="ocrSettings.defaultUserQuota" />
          <div class="hint">Initial OCR image quota for newly registered users.</div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3>OpenAI Streaming</h3>
          <div class="meta">GPT streaming is unstable on some gateways. Default is disabled.</div>
        </div>
        <button class="ghost" @click="saveOpenAiStream" :disabled="savingOpenAiStream">Save</button>
      </div>
      <div class="form-grid settings-grid">
        <div class="field-group toggle">
          <label>Enable GPT Streaming</label>
          <input type="checkbox" v-model="openAiStream.enabled" />
          <div class="hint">If disabled, GPT models use non-stream response.</div>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest } from "../../api";

export default {
  name: "AdminSettingsView",
  data() {
    return {
      settings: {
        searxEnabled: false,
        searxUrl: "",
        serpApiKey: "",
        serpApiEngine: "baidu",
        wikipediaUserAgent: "",
        wikipediaEnabled: false,
        baikeEnabled: false,
        bochaEnabled: false,
        bochaApiKey: "",
        bochaEndpoint: "",
        baiduEnabled: true,
        wikipediaProxyEnabled: false,
        wikipediaProxyUrl: ""
      },
      rateLimit: {
        enabled: false,
        adminBypass: true,
        windowSeconds: 60,
        ipLimit: 300,
        userLimit: 200,
        whitelistIps: [],
        whitelistPaths: []
      },
      ocrSettings: {
        enabled: true,
        maxImagesPerRequest: 50,
        maxImageBytes: 5242880,
        maxPdfPages: 8,
        rateLimitPerDay: 200,
        rateLimitWindowSeconds: 86400,
        defaultUserQuota: 200
      },
      openAiStream: {
        enabled: false
      },
      rateLimitWhitelistIps: "",
      rateLimitWhitelistPaths: "",
      saving: false,
      savingRate: false,
      savingOcr: false,
      savingOpenAiStream: false,
      status: "",
      showKey: false
    };
  },
  mounted() {
    this.loadSettings();
    this.loadRateLimit();
    this.loadOcrSettings();
    this.loadOpenAiStream();
  },
  methods: {
    async loadSettings() {
      this.status = "";
      try {
        const data = await apiRequest("/api/admin/settings/tools-search");
        this.settings = {
          searxEnabled: !!data?.searxEnabled,
          searxUrl: data?.searxUrl || "",
          serpApiKey: data?.serpApiKey || "",
          serpApiEngine: data?.serpApiEngine || "baidu",
          wikipediaUserAgent: data?.wikipediaUserAgent || "",
          wikipediaEnabled: data?.wikipediaEnabled !== undefined ? !!data?.wikipediaEnabled : false,
          baikeEnabled: data?.baikeEnabled !== undefined ? !!data?.baikeEnabled : false,
          bochaEnabled: data?.bochaEnabled !== undefined ? !!data?.bochaEnabled : false,
          bochaApiKey: data?.bochaApiKey || "",
          bochaEndpoint: data?.bochaEndpoint || "",
          baiduEnabled: data?.baiduEnabled !== undefined ? !!data?.baiduEnabled : true,
          wikipediaProxyEnabled: !!data?.wikipediaProxyEnabled,
          wikipediaProxyUrl: data?.wikipediaProxyUrl || ""
        };
      } catch (e) {
        this.status = e.message || "Failed to load settings";
      }
    },
    async saveSettings() {
      this.saving = true;
      this.status = "";
      try {
        await apiRequest("/api/admin/settings/tools-search", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(this.settings)
        });
        this.status = "Settings saved.";
      } catch (e) {
        this.status = e.message || "Failed to save settings";
      } finally {
        this.saving = false;
      }
    }
    ,
    async loadRateLimit() {
      try {
        const data = await apiRequest("/api/admin/settings/rate-limit");
        this.rateLimit = {
          enabled: data?.enabled !== undefined ? !!data.enabled : false,
          adminBypass: data?.adminBypass !== undefined ? !!data.adminBypass : true,
          windowSeconds: data?.windowSeconds || 60,
          ipLimit: data?.ipLimit || 300,
          userLimit: data?.userLimit || 200,
          whitelistIps: Array.isArray(data?.whitelistIps) ? data.whitelistIps : [],
          whitelistPaths: Array.isArray(data?.whitelistPaths) ? data.whitelistPaths : []
        };
        this.rateLimitWhitelistIps = this.rateLimit.whitelistIps.join("\n");
        this.rateLimitWhitelistPaths = this.rateLimit.whitelistPaths.join("\n");
      } catch (e) {
        this.status = e.message || "Failed to load rate limit settings";
      }
    },
    async saveRateLimit() {
      this.savingRate = true;
      this.status = "";
      try {
        const whitelistIps = this.parseList(this.rateLimitWhitelistIps);
        const whitelistPaths = this.parseList(this.rateLimitWhitelistPaths);
        await apiRequest("/api/admin/settings/rate-limit", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ...this.rateLimit,
            whitelistIps,
            whitelistPaths
          })
        });
        this.status = "Rate limit settings saved.";
      } catch (e) {
        this.status = e.message || "Failed to save rate limit settings";
      } finally {
        this.savingRate = false;
      }
    },
    async loadOcrSettings() {
      try {
        const data = await apiRequest("/api/admin/settings/ocr");
        this.ocrSettings = {
          enabled: data?.enabled !== undefined ? !!data.enabled : true,
          maxImagesPerRequest: data?.maxImagesPerRequest ?? 50,
          maxImageBytes: data?.maxImageBytes ?? 5242880,
          maxPdfPages: data?.maxPdfPages ?? 8,
          rateLimitPerDay: data?.rateLimitPerDay ?? 200,
          rateLimitWindowSeconds: data?.rateLimitWindowSeconds ?? 86400,
          defaultUserQuota: data?.defaultUserQuota ?? 200
        };
      } catch (e) {
        this.status = e.message || "Failed to load OCR settings";
      }
    },
    async saveOcrSettings() {
      this.savingOcr = true;
      this.status = "";
      try {
        await apiRequest("/api/admin/settings/ocr", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(this.ocrSettings)
        });
        this.status = "OCR settings saved.";
      } catch (e) {
        this.status = e.message || "Failed to save OCR settings";
      } finally {
        this.savingOcr = false;
      }
    },
    async loadOpenAiStream() {
      try {
        const data = await apiRequest("/api/admin/settings/openai-stream");
        this.openAiStream = {
          enabled: data?.enabled !== undefined ? !!data.enabled : false
        };
      } catch (e) {
        this.status = e.message || "Failed to load OpenAI stream settings";
      }
    },
    async saveOpenAiStream() {
      this.savingOpenAiStream = true;
      this.status = "";
      try {
        await apiRequest("/api/admin/settings/openai-stream", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(this.openAiStream)
        });
        this.status = "OpenAI streaming settings saved.";
      } catch (e) {
        this.status = e.message || "Failed to save OpenAI streaming settings";
      } finally {
        this.savingOpenAiStream = false;
      }
    },
    parseList(value) {
      if (!value) return [];
      return value
        .split(/\r?\n|,/)
        .map((item) => item.trim())
        .filter((item) => item.length > 0);
    }
  }
};
</script>

<style scoped>
.settings-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(240px, 1fr));
  gap: 16px 20px;
  align-items: start;
}

.panel h2 {
  font-size: 20px;
}

.panel .meta {
  font-size: 13px;
  color: #4b5563;
}

.settings-grid label {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

.settings-grid .hint {
  font-size: 12px;
  color: #6b7280;
}

.settings-grid input,
.settings-grid select {
  font-size: 14px;
}

.settings-grid .field-group.full {
  grid-column: 1 / -1;
}

.settings-grid .field-group.toggle {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.settings-grid .field-group.toggle input[type="checkbox"] {
  width: 16px;
  height: 16px;
}

@media (max-width: 960px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }
}
</style>
