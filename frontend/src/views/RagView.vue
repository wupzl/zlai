<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>RAG</h2>
        <div class="meta">Ingest documents - Manage knowledge</div>
      </div>
      <div class="toolbar">
        <div class="session-settings inline">
          <div class="grid">
            <div>
              <label>Model</label>
              <select v-model="ragModel">
                <option v-for="m in modelOptions" :key="'rag-' + m" :value="m">{{ formatModelLabel(m) }}</option>
              </select>
              <div class="hint">Billing rate: multiplier per model.</div>
            </div>
            <div>
              <label>Tool Model</label>
              <select v-model="ragToolModel">
                <option value="">Default tool model</option>
                <option v-for="m in modelOptions" :key="'rag-tool-' + m" :value="m">{{ formatModelLabel(m) }}</option>
              </select>
              <div class="hint">Tool calls are billed using the Tool Model rate.</div>
            </div>
          </div>
        </div>
        <button class="cta" @click="createRagSession">Start RAG Chat</button>
        <button class="cta" @click="loadDocs">Refresh</button>
      </div>
    </header>

    <div class="rag-shell">
      <div class="rag-column">
        <form class="card rag-card" @submit.prevent="ingest">
          <h3>Ingest Document</h3>
          <input v-model="doc.title" placeholder="Title" />
          <textarea v-model="doc.content" rows="6" placeholder="Content"></textarea>
          <button class="cta" type="submit">Ingest</button>
        </form>

        <div class="card rag-card">
          <h3>Import Markdown</h3>
          <label class="session-meta">1. Markdown file</label>
          <input type="file" accept=".md,.markdown" @change="onMdFile" />
          <label class="session-meta">2. Select image folder (we will only upload referenced images)</label>
          <input type="file" webkitdirectory directory multiple @change="onImageFolder" :disabled="!mdFile" />
          <div class="session-meta">
            If you are not importing Markdown, use the "Ingest Document" form instead.
          </div>
          <div class="session-meta" v-if="requiredImageNames.size">
            Referenced images:
          </div>
          <div class="chip-row rag-chips" v-if="requiredImageNames.size">
            <span class="chip" v-for="name in Array.from(requiredImageNames)" :key="name">{{ name }}</span>
          </div>
          <div class="session-meta" v-if="mdFile">
            Matched images to upload: <strong>{{ imageFiles.length }}</strong> (limit 200)
          </div>
          <button class="cta" @click="uploadMarkdown">Upload</button>
        </div>

        <div class="card rag-card">
          <h3>Import File</h3>
          <label class="session-meta">Supported: PDF, DOC, DOCX, TXT</label>
          <input type="file" accept=".pdf,.doc,.docx,.txt" @change="onDocFile" />
          <div class="session-meta" v-if="docFile">
            Selected: {{ docFile.name }}
          </div>
          <button class="cta" @click="uploadDocFile">Upload</button>
        </div>
      </div>

      <div class="rag-column">
        <div class="card rag-card">
          <h3>Documents</h3>
          <div class="session-meta">Showing {{ docs.length }} / {{ totalDocs }}</div>
          <div class="rag-docs" ref="docsList" @scroll="onDocsScroll">
            <div v-for="d in docs" :key="d.docId" class="list-item">
              <div>
                <div class="session-title">{{ d.title }}</div>
                <div class="session-meta">{{ d.docId }}</div>
              </div>
              <div class="list-actions">
                <button @click="removeDoc(d.docId)">Delete</button>
              </div>
            </div>
            <div class="session-meta" v-if="loadingDocs">Loading…</div>
            <div class="session-meta" v-if="!hasMoreDocs && docs.length">No more documents</div>
          </div>
        </div>

        <div class="card rag-card">
          <h3>Search</h3>
          <input v-model="query.text" placeholder="Query" />
          <input v-model.number="query.topK" placeholder="TopK" type="number" />
          <button class="cta" @click="search">Search</button>
          <pre class="rag-result" v-if="queryResult">{{ queryResult }}</pre>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, API_BASE } from "../api";
import JSZip from "jszip";

export default {
  name: "RagView",
  data() {
    return {
      doc: { title: "", content: "" },
      docs: [],
      docsPage: 1,
      docsPageSize: 10,
      totalDocs: 0,
      hasMoreDocs: true,
      loadingDocs: false,
      modelOptions: [],
      modelRates: {},
      ragModel: "deepseek-chat",
      ragToolModel: "",
      query: { text: "", topK: 5 },
      queryResult: "",
      status: "",
      mdFile: null,
      imageFiles: [],
      docFile: null,
      requiredImageNames: new Set(),
      requiredImagePaths: new Set()
    };
  },
  mounted() {
    this.loadOptions();
    this.loadDocs(true);
  },
  methods: {
    async loadOptions() {
      try {
        const models = await apiRequest("/api/chat/models/options");
        const pricing = await apiRequest("/api/chat/models/pricing");
        this.modelRates = this.toRateMap(pricing);
        this.modelOptions = Array.isArray(models) ? models : [];
        if (this.modelOptions.length && !this.modelOptions.includes(this.ragModel)) {
          this.ragModel = this.modelOptions[0];
        }
      } catch (e) {
        this.status = e.message;
      }
    },
    async loadDocs(reset = false) {
      try {
        if (reset) {
          this.docsPage = 1;
          this.docs = [];
          this.hasMoreDocs = true;
        }
        if (!this.hasMoreDocs || this.loadingDocs) return;
        this.loadingDocs = true;
        const data = await apiRequest(`/api/rag/documents?page=${this.docsPage}&size=${this.docsPageSize}`);
        const content = data?.content || [];
        this.docs = this.docs.concat(content);
        this.totalDocs = data?.totalElements || this.docs.length;
        const totalPages = data?.totalPages || 1;
        this.hasMoreDocs = this.docsPage < totalPages;
        this.docsPage += 1;
      } catch (e) {
        this.status = e.message;
      } finally {
        this.loadingDocs = false;
      }
    },
    onDocsScroll(event) {
      const el = event.target;
      if (!el || this.loadingDocs || !this.hasMoreDocs) return;
      if (el.scrollTop + el.clientHeight >= el.scrollHeight - 40) {
        this.loadDocs(false);
      }
    },
    async ingest() {
      try {
        await apiRequest("/api/rag/ingest", {
          method: "POST",
          body: JSON.stringify(this.doc)
        });
        this.status = "Ingested";
        this.doc = { title: "", content: "" };
        this.loadDocs(true);
      } catch (e) {
        this.status = e.message;
      }
    },
    async removeDoc(docId) {
      try {
        await apiRequest(`/api/rag/documents/${docId}`, { method: "DELETE" });
        this.status = "Deleted";
        this.loadDocs(true);
      } catch (e) {
        this.status = e.message;
      }
    },
    async search() {
      try {
        const data = await apiRequest("/api/rag/query", {
          method: "POST",
          body: JSON.stringify({ query: this.query.text, topK: this.query.topK })
        });
        this.queryResult = data.context || "(no context)";
      } catch (e) {
        this.status = e.message;
      }
    },
    async createRagSession() {
      try {
        const query = new URLSearchParams();
        if (this.ragModel) query.set("model", this.ragModel);
        if (this.ragToolModel) query.set("toolModel", this.ragToolModel);
        const data = await apiRequest(`/api/rag/session?${query.toString()}`, { method: "POST" });
        const chatId = data?.chatId;
        if (chatId) {
          this.$router.push(`/app/rag/chat/${chatId}`);
        } else {
          this.$router.push(`/app/rag/chat`);
        }
      } catch (e) {
        this.status = e.message;
      }
    },
    onMdFile(e) {
      const file = e.target.files && e.target.files[0];
      this.mdFile = file || null;
      if (this.mdFile) {
        this.extractRequiredImages(this.mdFile);
      } else {
        this.requiredImageNames = new Set();
        this.requiredImagePaths = new Set();
      }
    },
    onImageFolder(e) {
      const files = Array.from(e.target.files || []);
      const filtered = files.filter((f) => {
        const name = (f.name || "").toLowerCase();
        return f.type.startsWith("image/")
          || name.endsWith(".png")
          || name.endsWith(".jpg")
          || name.endsWith(".jpeg")
          || name.endsWith(".gif")
          || name.endsWith(".webp")
          || name.endsWith(".bmp")
          || name.endsWith(".svg");
      });
      if ((this.requiredImageNames && this.requiredImageNames.size > 0)
        || (this.requiredImagePaths && this.requiredImagePaths.size > 0)) {
        let matched = filtered.filter((f) => {
          const name = (f.name || "").toLowerCase();
          if (this.requiredImageNames.has(name)) {
            return true;
          }
          const rel = (f.webkitRelativePath || "").toLowerCase().replace(/\\/g, "/");
          for (const p of this.requiredImagePaths) {
            if (rel.endsWith(p)) {
              return true;
            }
          }
          return false;
        });
        if (matched.length > 200) {
          this.status = "Too many images matched. Limit is 200.";
          matched = matched.slice(0, 200);
        }
        this.imageFiles = matched;
      } else {
        this.imageFiles = [];
      }
    },
    async extractRequiredImages(file) {
      try {
        const text = await file.text();
        const names = new Set();
        const paths = new Set();
        const mdRegex = /!\[[^\]]*]\(([^)]+)\)/g;
        const wikiRegex = /!\[\[([^\]]+)\]\]/g;
        const htmlRegex = /<img[^>]*src=["']([^"']+)["'][^>]*>/gi;
        let match;
        const collect = (rawInput, allowSpaces) => {
          let raw = rawInput.trim();
          const pipeIdx = raw.indexOf("|");
          if (pipeIdx > 0) {
            raw = raw.substring(0, pipeIdx);
          }
          const hashIdx = raw.indexOf("#");
          if (hashIdx > 0) {
            raw = raw.substring(0, hashIdx);
          }
          if (!allowSpaces) {
            const titleIdx = raw.search(/\s+['"]/);
            if (titleIdx > 0) {
              raw = raw.substring(0, titleIdx);
            }
          }
          raw = raw.trim();
          if (!raw || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) {
            return;
          }
          const normalized = raw.replace(/\\/g, "/").replace(/^\.\//, "");
          const base = normalized.split("/").pop();
          if (base) {
            const cleanedBase = base.replace(/\\$/, "").trim();
            names.add(cleanedBase.toLowerCase());
          }
          const cleanedPath = normalized.replace(/\\$/, "").trim();
          paths.add(cleanedPath.toLowerCase());
        };
        while ((match = mdRegex.exec(text)) !== null) {
          collect(match[1], false);
        }
        while ((match = wikiRegex.exec(text)) !== null) {
          collect(match[1], true);
        }
        while ((match = htmlRegex.exec(text)) !== null) {
          collect(match[1], false);
        }
        this.requiredImageNames = names;
        this.requiredImagePaths = paths;
      } catch {
        this.requiredImageNames = new Set();
        this.requiredImagePaths = new Set();
      }
    },
    onDocFile(e) {
      const file = e.target.files && e.target.files[0];
      this.docFile = file || null;
    },
    async uploadMarkdown() {
      if (!this.mdFile) {
        this.status = "Please select a markdown file.";
        return;
      }
      try {
        const form = new FormData();
        form.append("file", this.mdFile);
        if (this.imageFiles.length > 0) {
          const totalBytes = this.imageFiles.reduce((sum, f) => sum + (f.size || 0), 0);
          const limit = 300 * 1024 * 1024;
          if (totalBytes > limit) {
            throw new Error("Selected images exceed 300MB. Please reduce or split the upload.");
          }
          const zip = new JSZip();
          this.imageFiles.forEach((f) => zip.file(f.name, f));
          const blob = await zip.generateAsync({ type: "blob" });
          form.append("imagesZip", blob, "images.zip");
        }
        const token = localStorage.getItem("accessToken") || "";
        const res = await fetch(`${API_BASE}/api/rag/ingest/markdown-upload`, {
          method: "POST",
          headers: token ? { Authorization: `Bearer ${token}` } : {},
          body: form
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok || (data && data.code && data.code !== 200)) {
          throw new Error(data?.message || "Upload failed");
        }
        this.status = "Imported";
        this.mdFile = null;
        this.imageFiles = [];
        this.loadDocs();
      } catch (e) {
        this.status = e.message;
      }
    }
    ,
    async uploadDocFile() {
      if (!this.docFile) {
        this.status = "Please select a file.";
        return;
      }
      try {
        const form = new FormData();
        form.append("file", this.docFile);
        const token = localStorage.getItem("accessToken") || "";
        const res = await fetch(`${API_BASE}/api/rag/ingest/file-upload`, {
          method: "POST",
          headers: token ? { Authorization: `Bearer ${token}` } : {},
          body: form
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok || (data && data.code && data.code !== 200)) {
          throw new Error(data?.message || "Upload failed");
        }
        this.status = "Imported";
        this.docFile = null;
        this.loadDocs();
      } catch (e) {
        this.status = e.message;
      }
    },
    toRateMap(pricing) {
      if (!Array.isArray(pricing)) return {};
      return pricing.reduce((acc, item) => {
        if (item && item.model) {
          acc[item.model] = {
            multiplier: item.multiplier,
            updatedAt: item.updatedAt || null
          };
        }
        return acc;
      }, {});
    },
    formatModelLabel(model) {
      const info = this.modelRates[model];
      if (!info || info.multiplier === undefined || info.multiplier === null) return model;
      const shown = Number(info.multiplier).toFixed(2).replace(/\.00$/, "");
      const stamp = info.updatedAt ? `, ${String(info.updatedAt).slice(0, 10)}` : "";
      return `${model} (x${shown}${stamp})`;
    }
  }
};
</script>
