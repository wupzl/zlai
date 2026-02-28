<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>GPT Management</h2>
        <div class="meta">Review and approve GPTs</div>
      </div>
      <button class="cta" @click="loadGpts">Refresh</button>
      <button class="ghost" @click="exportGpts">Export CSV</button>
    </header>

    <div class="admin-toolbar">
      <div class="filters">
        <input v-model="keyword" placeholder="Search name / category" @keyup.enter="onSearch" />
        <label class="checkbox">
          <input type="checkbox" v-model="requestedOnly" @change="onSearch" />
          Requested only
        </label>
        <select v-model.number="size" @change="onSearch">
          <option :value="10">10</option>
          <option :value="20">20</option>
          <option :value="50">50</option>
        </select>
      </div>
      <div class="pagination">
        <button class="ghost" :disabled="page <= 1" @click="page--; loadGpts()">Prev</button>
        <span>Page {{ page }} / {{ totalPages }}</span>
        <button class="ghost" :disabled="page >= totalPages" @click="page++; loadGpts()">Next</button>
      </div>
    </div>

    <div class="card">
        <div v-for="g in gpts" :key="g.id || g.gptId" class="list-item">
          <div>
            <div class="session-title">{{ g.name }}</div>
            <div class="session-meta">
              {{ g.category }} ·
              {{ g.isPublic ? "public" : (g.requestPublic ? "requested" : "private") }}
            </div>
          </div>
          <div class="list-actions">
            <button @click="openEdit(g)">View</button>
            <button v-if="g.requestPublic && !g.isPublic" @click="approve(g.id)">Approve</button>
            <button @click="remove(g.id)">Delete</button>
          </div>
        </div>
      <div v-if="!gpts.length" class="empty">No GPTs found.</div>
    </div>

    <div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>GPT Detail</h3>
          <button class="ghost" @click="closeModal">Close</button>
        </div>
        <div v-if="selected" class="modal-body">
          <div class="session-meta">id: {{ selected.id }} | gptId: {{ selected.gptId }}</div>
          <div class="form-grid">
            <input v-model="edit.name" placeholder="Name" />
            <input v-model="edit.category" placeholder="Category" />
            <input v-model="edit.model" placeholder="Model" />
            <input v-model="edit.avatarUrl" placeholder="Avatar URL" />
          </div>
          <div class="hint" v-if="rateSummary">Billing rate: {{ rateSummary }}</div>
          <div class="field-group">
            <div class="field-label">Description</div>
            <textarea v-model="edit.description" rows="3" placeholder="How this GPT should be presented"></textarea>
          </div>
          <div class="field-group">
            <div class="field-label">System Prompt</div>
            <textarea v-model="edit.instructions" rows="5" placeholder="System prompt"></textarea>
            <div class="hint">To add multiple system prompts, separate them with a line containing only <b>---</b>. They will be concatenated with separators.</div>
          </div>
          <div class="field-group" v-if="edit.instructions">
            <div class="field-label">System Prompt Preview</div>
            <div class="prompt-blocks">
              <div v-for="(block, idx) in splitPrompts(edit.instructions)" :key="idx" class="prompt-block">
                {{ block }}
              </div>
            </div>
          </div>
        </div>
        <div class="modal-actions">
          <button @click="saveGpt">Save</button>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, API_BASE } from "../../api";

export default {
  name: "AdminGptsView",
  data() {
    return {
      gpts: [],
      status: "",
      selected: null,
      edit: {},
      modelRates: {},
      keyword: "",
      requestedOnly: false,
      page: 1,
      size: 20,
      totalPages: 1,
      showModal: false
    };
  },
  mounted() {
    this.loadGpts();
  },
  methods: {
    async loadGpts() {
      try {
        const pricing = await apiRequest("/api/chat/models/pricing");
        this.modelRates = this.toRateMap(pricing);
        const query = new URLSearchParams();
        query.set("page", this.page);
        query.set("size", this.size);
        if (this.keyword) query.set("keyword", this.keyword);
        if (this.requestedOnly) query.set("requestPublic", "true");
        const data = await apiRequest(`/api/admin/gpts?${query.toString()}`);
        this.gpts = data.content || data.records || data || [];
        this.totalPages = data.totalPages || Math.max(1, Math.ceil((data.totalElements || this.gpts.length) / this.size));
      } catch (e) {
        this.status = e.message;
      }
    },
    async exportGpts() {
      try {
        const params = new URLSearchParams();
        if (this.keyword) params.set("keyword", this.keyword);
        await this.downloadCsv(`/api/admin/gpts/export?${params.toString()}`, "gpts.csv");
      } catch (e) {
        this.status = e.message;
      }
    },
    onSearch() {
      this.page = 1;
      this.loadGpts();
    },
    openEdit(g) {
      this.selected = g;
      this.edit = {
        name: g.name || "",
        category: g.category || "",
        model: g.model || "",
        avatarUrl: g.avatarUrl || "",
        description: g.description || "",
        instructions: g.instructions || ""
      };
      this.showModal = true;
    },
    closeModal() {
      this.showModal = false;
    },
    async saveGpt() {
      if (!this.selected) return;
      try {
        await apiRequest(`/api/admin/gpts/${this.selected.id}`, {
          method: "PUT",
          body: JSON.stringify({
            ...this.edit,
            instructions: this.normalizeInstructions(this.edit.instructions || "")
          })
        });
        this.status = "Updated";
        this.loadGpts();
      } catch (e) {
        this.status = e.message;
      }
    },
    normalizeInstructions(text) {
      const parts = String(text)
        .split(/\n-{3,}\n/)
        .map(p => p.trim())
        .filter(Boolean);
      if (!parts.length) return "";
      if (parts.length === 1) return parts[0];
      return parts.join("\n\n---\n\n");
    },
    splitPrompts(text) {
      return String(text)
        .split(/\n-{3,}\n/)
        .map(p => p.trim())
        .filter(Boolean);
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
    formatRate(rate) {
      return Number(rate).toFixed(2).replace(/\.00$/, "");
    },
    async approve(gptId) {
      try {
        await apiRequest(`/api/admin/gpts/${gptId}/public?isPublic=true`, { method: "PUT" });
        this.status = "Approved";
        this.loadGpts();
      } catch (e) {
        this.status = e.message;
      }
    },
    async remove(gptId) {
      try {
        await apiRequest(`/api/admin/gpts/${gptId}`, { method: "DELETE" });
        this.status = "Deleted";
        if (this.gpts.length === 1 && this.page > 1) {
          this.page -= 1;
        }
        this.loadGpts();
      } catch (e) {
        this.status = e.message;
      }
    },
    async downloadCsv(path, filename) {
      const token = localStorage.getItem("accessToken") || "";
      const res = await fetch(`${API_BASE}${path}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
      if (!res.ok) {
        throw new Error("Export failed");
      }
      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
    }
  },
  computed: {
    rateSummary() {
      const entries = Object.entries(this.modelRates || {});
      if (!entries.length) return "";
      return entries
        .slice(0, 4)
        .map(([m, info]) => {
          const shown = this.formatRate(info.multiplier);
          const stamp = info.updatedAt ? ` ${String(info.updatedAt).slice(0, 10)}` : "";
          return `${m} x${shown}${stamp}`;
        })
        .join(" / ");
    }
  }
};
</script>

