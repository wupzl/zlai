<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>GPT Store</h2>
        <div class="meta">Create and publish GPTs</div>
      </div>
      <button class="cta" @click="loadAll">Refresh</button>
    </header>

    <div class="grid gpt-grid">
      <form class="card create-card" @submit.prevent="createGpt">
        <div class="card-head">
          <div>
            <h3>Create GPT</h3>
            <div class="meta">Design a dedicated assistant with a system prompt.</div>
          </div>
        </div>
        <input v-model="form.name" placeholder="Name" />
        <input v-model="form.category" placeholder="Category" />
        <input v-model="form.avatarUrl" placeholder="Avatar URL" />
        <div class="field-group">
          <div class="field-label">Model</div>
          <select v-model="form.model">
            <option value="">Default model</option>
            <option v-for="m in modelOptions" :key="'gpt-model-' + m" :value="m">{{ formatModelLabel(m) }}</option>
          </select>
          <div class="hint" v-if="rateSummary">Billing rate: {{ rateSummary }}</div>
        </div>
        <div class="field-group">
          <div class="field-label">Description</div>
          <textarea v-model="form.description" rows="2" placeholder="How this GPT should be presented"></textarea>
        </div>
        <div class="field-group">
          <div class="field-label">System Prompt</div>
          <textarea v-model="form.instructions" rows="5" placeholder="System prompt"></textarea>
          <div class="hint">If you need multiple system prompts, separate them with a line containing only <b>---</b>. They will be merged in order.</div>
        </div>
        <label class="checkbox">
          <input type="checkbox" v-model="form.requestPublic" />
          Request public
        </label>
        <div class="field-group" v-if="form.instructions">
          <div class="field-label">System Prompt Preview</div>
          <div class="prompt-blocks">
            <div v-for="(block, idx) in splitPrompts(form.instructions)" :key="idx" class="prompt-block">
              {{ block }}
            </div>
          </div>
        </div>
        <button class="cta" type="submit">Create</button>
      </form>

      <div class="card list-card">
        <div class="card-head">
          <div>
            <h3>My GPTs</h3>
            <div class="meta">Private and pending-public GPTs.</div>
          </div>
        </div>
        <div class="list-grid">
          <div v-for="g in mine" :key="g.gptId" class="list-item">
            <div>
              <div class="session-title">{{ g.name }}</div>
              <div class="session-meta">{{ g.category || "General" }} ¡¤ {{ g.isPublic ? "public" : (g.requestPublic ? "requested" : "private") }}</div>
            </div>
            <div class="list-actions">
              <button @click="openDetail(g)">View</button>
              <button @click="startChat(g.gptId)">Start Chat</button>
              <button v-if="!g.isPublic" @click="removeGpt(g.gptId)">Delete</button>
            </div>
          </div>
        </div>
      </div>

      <div class="card list-card">
        <div class="card-head">
          <div>
            <h3>Public GPTs</h3>
            <div class="meta">Discover community GPTs.</div>
          </div>
        </div>
        <div class="list-grid">
          <div v-for="g in publicGpts" :key="g.gptId" class="list-item">
            <div>
              <div class="session-title">{{ g.name }}</div>
              <div class="session-meta">{{ g.description || "No description" }}</div>
            </div>
            <div class="list-actions">
              <button @click="openDetail(g)">View</button>
              <button @click="startChat(g.gptId)">Start Chat</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="detail" class="card detail-panel">
      <div class="detail-header">
        <div>
          <h3>{{ detail.name }}</h3>
          <div class="session-meta">{{ detail.category || "Uncategorized" }}</div>
        </div>
        <button class="ghost" @click="detail = null">Close</button>
      </div>
      <div class="detail-body">
        <div class="detail-row"><span>Description</span><span>{{ detail.description || "No description" }}</span></div>
        <div class="detail-row"><span>Model</span><span>{{ detail.model || "Default model" }}</span></div>
        <div class="detail-row" v-if="detail.model && modelRates[detail.model] != null">
          <span>Rate</span>
          <span>
            x{{ formatRate(modelRates[detail.model].multiplier) }}
            <span v-if="modelRates[detail.model].updatedAt">
              ({{ String(modelRates[detail.model].updatedAt).slice(0, 10) }})
            </span>
          </span>
        </div>
        <div class="detail-row"><span>Visibility</span><span>{{ detail.isPublic ? "Public" : (detail.requestPublic ? "Requested" : "Private") }}</span></div>
        <div class="detail-block">
          <div class="detail-label">System Prompt</div>
          <div class="detail-content">{{ detail.instructions || "No instructions" }}</div>
        </div>
      </div>
      <div class="detail-actions">
        <button class="cta" @click="startChat(detail.gptId)">Start Chat</button>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest } from "../api";

export default {
  name: "GptsView",
  data() {
    return {
      mine: [],
      publicGpts: [],
      detail: null,
      status: "",
      modelRates: {},
      modelOptions: [],
      form: {
        name: "",
        description: "",
        instructions: "",
        model: "",
        avatarUrl: "",
        category: "",
        requestPublic: false
      }
    };
  },
  mounted() {
    this.loadAll();
  },
  methods: {
    async loadAll() {
      try {
        const models = await apiRequest("/api/chat/models/options");
        this.modelOptions = Array.isArray(models) ? models : [];
        const pricing = await apiRequest("/api/chat/models/pricing");
        this.modelRates = this.toRateMap(pricing);
        this.mine = (await apiRequest("/api/gpts/mine?page=1&size=50")).content || [];
        this.publicGpts = (await apiRequest("/api/gpts/public?page=1&size=50")).content || [];
      } catch (e) {
        this.status = e.message;
      }
    },
    async createGpt() {
      try {
        await apiRequest("/api/gpts", {
          method: "POST",
          body: JSON.stringify({
            ...this.form,
            instructions: this.normalizeInstructions(this.form.instructions || "")
          })
        });
        this.status = "GPT created";
        this.loadAll();
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
    async removeGpt(gptId) {
      try {
        await apiRequest(`/api/gpts/${gptId}`, { method: "DELETE" });
        this.status = "GPT deleted";
        this.loadAll();
      } catch (e) {
        this.status = e.message;
      }
    },
    openDetail(gpt) {
      this.detail = gpt;
    },
    startChat(gptId) {
      if (!gptId) return;
      this.$router.push(`/app/gpt/${gptId}/chat`);
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
    formatModelLabel(model) {
      if (!model) return "Default model";
      const rate = this.modelRates[model];
      if (!rate) return model;
      return `${model} (x${this.formatRate(rate.multiplier)})`;
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

