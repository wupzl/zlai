<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Agent Management</h2>
        <div class="meta">Review published agents</div>
      </div>
      <button class="cta" @click="loadAgents">Refresh</button>
      <button class="ghost" @click="exportAgents">Export CSV</button>
    </header>

    <div class="admin-toolbar">
      <div class="filters">
        <input v-model="keyword" placeholder="Search name / model" @keyup.enter="onSearch" />
        <select v-model.number="size" @change="onSearch">
          <option :value="10">10</option>
          <option :value="20">20</option>
          <option :value="50">50</option>
        </select>
      </div>
      <div class="pagination">
        <button class="ghost" :disabled="page <= 1" @click="page--; loadAgents()">Prev</button>
        <span>Page {{ page }} / {{ totalPages }}</span>
        <button class="ghost" :disabled="page >= totalPages" @click="page++; loadAgents()">Next</button>
      </div>
    </div>

    <div class="card">
      <div v-for="a in agents" :key="a.agentId" class="list-item">
        <div>
          <div class="session-title">{{ a.name }}</div>
          <div class="session-meta">{{ a.model }} - public: {{ a.isPublic }}</div>
        </div>
        <div class="list-actions">
          <button @click="openEdit(a)">View</button>
          <button @click="remove(a.agentId)">Delete</button>
        </div>
      </div>
      <div v-if="!agents.length" class="empty">No agents found.</div>
    </div>

    <div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>Agent Detail</h3>
          <button class="ghost" @click="closeModal">Close</button>
        </div>
        <div v-if="selected" class="modal-body">
          <div class="session-meta">agentId: {{ selected.agentId }} | owner: {{ selected.userId }}</div>
          <div class="form-grid">
            <input v-model="edit.name" placeholder="Name" />
            <input v-model="edit.model" placeholder="Model" />
          </div>
          <div class="hint">Billing rate: {{ rateSummary }}</div>
          <div class="hint">Tool usage is billed separately. Tool Model is selected in chat session settings.</div>
          <div class="field-group">
            <div class="field-label">Description</div>
            <textarea v-model="edit.description" rows="3" placeholder="What this agent does and how it should behave"></textarea>
          </div>
          <div class="field-group">
            <div class="field-label">System Prompt</div>
            <textarea v-model="edit.instructions" rows="5" placeholder="System instructions"></textarea>
            <div class="hint">You can split multiple system prompts with a line containing only <b>---</b>. They will be concatenated with separators.</div>
          </div>
          <div class="tool-list">
            <label v-for="t in tools" :key="t.key">
              <input type="checkbox" :value="t.key" v-model="edit.tools" />
              {{ t.name }}
            </label>
          </div>
          <div class="field-group">
            <div class="field-label">Team Agent IDs (comma separated)</div>
            <textarea v-model="edit.teamAgentIds" rows="2" placeholder="agent-id-1, agent-id-2"></textarea>
          </div>
          <div class="field-group" v-if="selected && selected.teamConfigs && selected.teamConfigs.length">
            <div class="field-label">Team Configs (read-only)</div>
            <pre class="detail-json">{{ JSON.stringify(selected.teamConfigs, null, 2) }}</pre>
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
          <button @click="saveAgent">Save</button>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, API_BASE } from "../../api";

export default {
  name: "AdminAgentsView",
  data() {
    return {
      agents: [],
      status: "",
      selected: null,
      edit: {},
      tools: [],
      modelRates: {},
      keyword: "",
      page: 1,
      size: 20,
      totalPages: 1,
      showModal: false
    };
  },
  mounted() {
    this.loadAgents();
    this.loadTools();
    this.loadPricing();
  },
  methods: {
    async loadTools() {
      try {
        this.tools = await apiRequest("/api/agents/tools");
      } catch (e) {
        this.status = e.message;
      }
    },
    async loadPricing() {
      try {
        const pricing = await apiRequest("/api/chat/models/pricing");
        this.modelRates = this.toRateMap(pricing);
      } catch (e) {
        this.status = e.message;
      }
    },
    async loadAgents() {
      try {
        const query = new URLSearchParams();
        query.set("page", this.page);
        query.set("size", this.size);
        if (this.keyword) query.set("keyword", this.keyword);
        const data = await apiRequest(`/api/admin/agents?${query.toString()}`);
        this.agents = data.content || data.records || data || [];
        this.totalPages = data.totalPages || Math.max(1, Math.ceil((data.totalElements || this.agents.length) / this.size));
      } catch (e) {
        this.status = e.message;
      }
    },
    async exportAgents() {
      try {
        const params = new URLSearchParams();
        if (this.keyword) params.set("keyword", this.keyword);
        await this.downloadCsv(`/api/admin/agents/export?${params.toString()}`, "agents.csv");
      } catch (e) {
        this.status = e.message;
      }
    },
    onSearch() {
      this.page = 1;
      this.loadAgents();
    },
    openEdit(a) {
      this.selected = a;
      this.edit = {
        name: a.name || "",
        model: a.model || "",
        description: a.description || "",
        instructions: a.instructions || "",
        tools: (a.tools || []).slice ? (a.tools || []) : (a.tools ? String(a.tools).split(",") : []),
        teamAgentIds: (a.teamAgentIds || []).join ? (a.teamAgentIds || []).join(",") : (a.teamAgentIds || "")
      };
      this.showModal = true;
    },
    closeModal() {
      this.showModal = false;
    },
    async saveAgent() {
      if (!this.selected) return;
      try {
        const body = {
          name: this.edit.name,
          model: this.edit.model,
          description: this.edit.description,
          instructions: this.normalizeInstructions(this.edit.instructions || ""),
          tools: this.edit.tools || [],
          teamAgentIds: this.edit.teamAgentIds
            ? String(this.edit.teamAgentIds).split(",").map(t => t.trim()).filter(Boolean)
            : []
        };
        await apiRequest(`/api/admin/agents/${this.selected.agentId}`, {
          method: "PUT",
          body: JSON.stringify(body)
        });
        this.status = "Updated";
        this.loadAgents();
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
    async remove(agentId) {
      try {
        await apiRequest(`/api/admin/agents/${agentId}`, { method: "DELETE" });
        this.status = "Deleted";
        if (this.agents.length === 1 && this.page > 1) {
          this.page -= 1;
        }
        this.loadAgents();
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

