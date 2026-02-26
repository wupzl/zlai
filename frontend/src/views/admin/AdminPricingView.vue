<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Model Pricing</h2>
        <div class="meta">Manage billing multipliers per model</div>
      </div>
      <button class="cta" @click="loadPricing">Refresh</button>
      <button class="ghost" @click="exportPricing">Export Pricing CSV</button>
    </header>

    <div class="card">
      <div class="admin-toolbar">
        <div class="filters">
          <select v-model.number="pageSize">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
          </select>
        </div>
        <div class="pagination">
          <button class="ghost" :disabled="page <= 1" @click="page--; scrollTop()">Prev</button>
          <span>Page {{ page }} / {{ totalPages }}</span>
          <button class="ghost" :disabled="page >= totalPages" @click="page++; scrollTop()">Next</button>
        </div>
      </div>
      <div class="list-item" v-for="p in pagedPricing" :key="p.model">
        <div>
          <div class="session-title">{{ p.model }}</div>
          <div class="session-meta">
            Current rate: x{{ formatRate(p.multiplier) }}
            <span v-if="p.updatedAt"> 路 {{ String(p.updatedAt).slice(0, 10) }}</span>
          </div>
        </div>
        <div class="list-actions">
          <input class="rate-input" type="number" step="0.01" min="0.1" v-model.number="editRates[p.model]" />
          <button @click="saveRate(p.model)">Save</button>
        </div>
      </div>
      <div v-if="!pricing.length" class="empty">No pricing configured.</div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3>Pricing Audit Log</h3>
          <div class="meta">Recent changes</div>
        </div>
        <div class="list-actions">
          <input type="datetime-local" v-model="logFilters.startTime" />
          <input type="datetime-local" v-model="logFilters.endTime" />
          <button class="ghost" @click="loadLogs">Refresh</button>
          <button class="ghost" @click="exportLogs">Export Logs CSV</button>
        </div>
      </div>
      <div class="admin-toolbar">
        <div class="filters">
          <select v-model.number="logPageSize" @change="loadLogs">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
          </select>
        </div>
        <div class="pagination">
          <button class="ghost" :disabled="logPage <= 1" @click="logPage--; loadLogs()">Prev</button>
          <span>Page {{ logPage }} / {{ logTotalPages }}</span>
          <button class="ghost" :disabled="logPage >= logTotalPages" @click="logPage++; loadLogs()">Next</button>
        </div>
      </div>
      <div v-for="log in logs" :key="log.id" class="list-item">
        <div>
          <div class="session-title">{{ log.model }}</div>
          <div class="session-meta">
            {{ formatRate(log.oldMultiplier) }} 鈫?{{ formatRate(log.newMultiplier) }}
            路 {{ String(log.updatedAt).slice(0, 19).replace('T',' ') }}
          </div>
        </div>
        <div class="session-meta">adminId: {{ log.updatedBy || "-" }}</div>
      </div>
      <div v-if="!logs.length" class="empty">No audit logs.</div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, API_BASE } from "../../api";

export default {
  name: "AdminPricingView",
  data() {
    return {
      pricing: [],
      editRates: {},
      logs: [],
      page: 1,
      pageSize: 10,
      logPage: 1,
      logPageSize: 10,
      logTotalPages: 1,
      logFilters: {
        startTime: "",
        endTime: ""
      },
      status: ""
    };
  },
  mounted() {
    this.loadPricing();
    this.loadLogs();
  },
  methods: {
    async loadPricing() {
      try {
        const data = await apiRequest("/api/admin/model-pricing");
        this.pricing = Array.isArray(data) ? data : [];
        this.page = 1;
        const edits = {};
        this.pricing.forEach((p) => {
          edits[p.model] = p.multiplier;
        });
        this.editRates = edits;
      } catch (e) {
        this.status = e.message;
      }
    },
    async exportPricing() {
      try {
        await this.downloadCsv("/api/admin/model-pricing/export", "model_pricing.csv");
      } catch (e) {
        this.status = e.message;
      }
    },
    async saveRate(model) {
      const value = this.editRates[model];
      if (!value || value <= 0) {
        this.status = "Multiplier must be greater than 0";
        return;
      }
      try {
        await apiRequest(`/api/admin/model-pricing?model=${encodeURIComponent(model)}&multiplier=${encodeURIComponent(value)}`, {
          method: "PUT"
        });
        this.status = `Updated ${model}`;
        this.loadPricing();
      } catch (e) {
        this.status = e.message;
      }
    },
    async loadLogs() {
      try {
        const params = new URLSearchParams();
        params.set("page", this.logPage);
        params.set("size", this.logPageSize);
        if (this.logFilters.startTime) params.set("startTime", this.logFilters.startTime);
        if (this.logFilters.endTime) params.set("endTime", this.logFilters.endTime);
        const data = await apiRequest(`/api/admin/model-pricing/logs?${params.toString()}`);
        this.logs = data?.content || [];
        this.logTotalPages = data?.totalPages || 1;
      } catch (e) {
        this.status = e.message;
      }
    },
    async exportLogs() {
      try {
        const params = new URLSearchParams();
        if (this.logFilters.startTime) params.set("startTime", this.logFilters.startTime);
        if (this.logFilters.endTime) params.set("endTime", this.logFilters.endTime);
        await this.downloadCsv(`/api/admin/model-pricing/logs/export?${params.toString()}`, "model_pricing_logs.csv");
      } catch (e) {
        this.status = e.message;
      }
    },
    formatRate(rate) {
      if (rate === null || rate === undefined) return "-";
      return Number(rate).toFixed(2).replace(/\.00$/, "");
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
    },
    scrollTop() {
      try {
        window.scrollTo({ top: 0, behavior: "smooth" });
      } catch {
        window.scrollTo(0, 0);
      }
    }
  },
  computed: {
    totalPages() {
      return Math.max(1, Math.ceil(this.pricing.length / this.pageSize));
    },
    pagedPricing() {
      const start = (this.page - 1) * this.pageSize;
      return this.pricing.slice(start, start + this.pageSize);
    }
  }
};
</script>

