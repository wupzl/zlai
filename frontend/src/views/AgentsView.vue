<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Agents</h2>
        <div class="meta">Create and manage agents with tools</div>
      </div>
      <button class="cta" @click="loadAll">Refresh</button>
    </header>

    <div class="grid agent-grid">
      <form class="card create-card" @submit.prevent="createAgent">
        <div class="card-head">
          <div>
            <h3>Create Agent</h3>
            <div class="meta">Configure tools and multi-agent behavior.</div>
          </div>
        </div>
        <input v-model="form.name" placeholder="Name" />
        <div class="field-group">
          <div class="field-label">Description</div>
          <input v-model="form.description" placeholder="What this agent does" />
        </div>
        <div class="field-group">
          <div class="field-label">System Prompt</div>
          <textarea v-model="form.instructions" rows="5" placeholder="System instructions"></textarea>
          <div class="hint">If you need multiple system prompts, separate them with a line containing only <b>---</b>. They will be merged in order.</div>
        </div>
        <div class="field-group">
          <div class="field-label">Tools</div>
          <div class="hint">Tools can call external services or the LLM. LLM-based tools will be billed separately.</div>
          <div class="tool-list">
            <label v-for="t in tools" :key="t.key">
              <input type="checkbox" :value="t.key" v-model="form.tools" />
              {{ t.name }}
            </label>
          </div>
        </div>
        <div class="field-group">
          <div class="field-label">Chat Model</div>
          <select v-model="form.model">
            <option value="">Default model</option>
            <option v-for="m in modelOptions" :key="'chat-' + m" :value="m">{{ formatModelLabel(m) }}</option>
          </select>
          <div class="hint">Billing rate: multiplier per model.</div>
        </div>
        <label class="checkbox">
          <input type="checkbox" v-model="form.requestPublic" />
          Request public
        </label>
        <label class="checkbox">
          <input type="checkbox" v-model="form.multiAgent" />
          Enable multi-agent orchestration
        </label>
        <div class="field-group" v-if="form.multiAgent">
          <div class="field-label">Team Agents (select multiple)</div>
          <div class="hint">Team agents inherit their own tool settings. You only assign roles here.</div>
          <div class="tool-list">
            <label v-for="a in mine" :key="a.agentId">
              <input type="checkbox" :value="a.agentId" v-model="form.teamAgentIds" />
              {{ a.name }}
            </label>
          </div>
          <div class="card" v-if="form.teamAgentIds.length">
            <div class="card-title">Team Roles & Tools</div>
            <div v-for="agentId in form.teamAgentIds" :key="agentId" class="field-group">
              <div class="field-label">Agent {{ agentName(agentId) }}</div>
              <input v-model="teamConfigMap[agentId].role" placeholder="Role (e.g. Researcher, Critic)" />
            </div>
          </div>
        </div>
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
            <h3>My Agents</h3>
            <div class="meta">Agents you created.</div>
          </div>
        </div>
        <div class="list-grid">
          <div v-for="a in mine" :key="a.agentId" class="list-item">
            <div>
              <div class="session-title">{{ a.name }}</div>
              <div class="session-meta">
                {{ a.model || "default model" }} · {{ a.isPublic ? "public" : "private" }} · {{ a.multiAgent ? "multi-agent" : "single-agent" }}
              </div>
            </div>
            <div class="list-actions">
              <button @click="openDetail(a)">View</button>
              <button @click="startChat(a.agentId)">Start Chat</button>
              <button @click="removeAgent(a.agentId)">Delete</button>
            </div>
          </div>
        </div>
      </div>

      <div class="card list-card">
        <div class="card-head">
          <div>
            <h3>Public Agents</h3>
            <div class="meta">Browse community agents.</div>
          </div>
        </div>
        <div class="list-grid">
          <div v-for="a in publicAgents" :key="a.agentId" class="list-item">
            <div>
              <div class="session-title">{{ a.name }}</div>
              <div class="session-meta">{{ a.description || "No description" }}</div>
            </div>
            <div class="list-actions">
              <button @click="openDetail(a)">View</button>
              <button @click="startChat(a.agentId)">Start Chat</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="detail" class="card detail-panel">
      <div class="detail-header">
        <div>
          <h3>{{ detail.name }}</h3>
          <div class="session-meta">{{ detail.description || "No description" }}</div>
        </div>
        <button class="ghost" @click="detail = null">Close</button>
      </div>
      <div class="detail-body">
        <div class="detail-row"><span>Model</span><span>{{ detail.model || "Default model" }}</span></div>
        <div class="detail-row"><span>Billing</span><span>Tools that call the LLM are billed separately.</span></div>
        <div class="detail-row"><span>Visibility</span><span>{{ detail.isPublic ? "Public" : "Private" }}</span></div>
        <div class="detail-row"><span>Mode</span><span>{{ detail.multiAgent ? "Multi-agent" : "Single-agent" }}</span></div>
        <div class="detail-block" v-if="detail.teamAgentIds && detail.teamAgentIds.length">
          <div class="detail-label">Team Agents</div>
          <div class="detail-content">{{ detail.teamAgentIds.join(", ") }}</div>
        </div>
        <div class="detail-block" v-if="detail.teamConfigs && detail.teamConfigs.length">
          <div class="detail-label">Team Roles</div>
          <div class="detail-content">
            <div v-for="cfg in detail.teamConfigs" :key="cfg.agentId">
              {{ cfg.agentId }} - {{ cfg.role || "member" }} - {{ (cfg.tools || []).join(", ") }}
            </div>
          </div>
        </div>
        <div class="detail-block" v-if="detail.teamAgentIds && detail.teamAgentIds.length">
          <div class="detail-label">Team Tools</div>
          <div class="detail-content">
            <div v-for="id in detail.teamAgentIds" :key="id">
              {{ id }} - {{ toolsForAgent(id) }}
            </div>
          </div>
        </div>
        <div class="detail-block">
          <div class="detail-label">System Instructions</div>
          <div class="detail-content">{{ detail.instructions || "No instructions" }}</div>
        </div>
        <div class="detail-block" v-if="detail.tools && detail.tools.length">
          <div class="detail-label">Tools</div>
          <div class="detail-content">{{ detail.tools.join(", ") }}</div>
        </div>
        <div class="detail-block">
          <div class="detail-label">Billing Note</div>
          <div class="detail-content">
            Tool usage is billed separately. Tool Model is selected in the chat session settings.
          </div>
        </div>
      </div>
      <div class="detail-actions">
        <button class="cta" @click="startChat(detail.agentId)">Start Chat</button>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest } from "../api";

export default {
  name: "AgentsView",
  data() {
    return {
      tools: [],
      mine: [],
      publicAgents: [],
      detail: null,
      status: "",
      form: {
        name: "",
        description: "",
        instructions: "",
        model: "",
        requestPublic: false,
        multiAgent: false,
        tools: [],
        teamAgentIds: []
      },
      modelOptions: [],
      modelRates: {},
      teamConfigMap: {}
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
        this.tools = await apiRequest("/api/agents/tools");
        this.mine = (await apiRequest("/api/agents/mine?page=1&size=50")).content || [];
        this.publicAgents = (await apiRequest("/api/agents/public?page=1&size=50")).content || [];
      } catch (e) {
        this.status = e.message;
      }
    },
    async createAgent() {
      try {
        const teamConfigs = this.form.multiAgent
          ? this.form.teamAgentIds.map((id) => ({
              agentId: id,
              role: this.teamConfigMap[id]?.role || ""
            }))
          : [];
        await apiRequest("/api/agents", {
          method: "POST",
          body: JSON.stringify({
            ...this.form,
            instructions: this.normalizeInstructions(this.form.instructions || ""),
            teamConfigs
          })
        });
        this.status = "Agent created";
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
    async removeAgent(agentId) {
      try {
        await apiRequest(`/api/agents/${agentId}`, { method: "DELETE" });
        this.status = "Agent deleted";
        this.loadAll();
      } catch (e) {
        this.status = e.message;
      }
    },
    openDetail(agent) {
      this.detail = agent;
    },
    startChat(agentId) {
      if (!agentId) return;
      this.$router.push(`/app/agent/${agentId}/chat`);
    },
    agentName(agentId) {
      const match = this.mine.find((a) => a.agentId === agentId);
      return match ? match.name : agentId;
    },
    toolsForAgent(agentId) {
      const match = this.mine.find((a) => a.agentId === agentId) || this.publicAgents.find((a) => a.agentId === agentId);
      const tools = (match && match.tools) ? match.tools : [];
      if (Array.isArray(tools) && tools.length) {
        return tools.join(", ");
      }
      return "No tools";
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
  },
  watch: {
    "form.teamAgentIds"(val) {
      const map = { ...this.teamConfigMap };
      val.forEach((id) => {
        if (!map[id]) {
          map[id] = { role: "" };
        }
      });
      Object.keys(map).forEach((id) => {
        if (!val.includes(id)) {
          delete map[id];
        }
      });
      this.teamConfigMap = map;
    }
  }
};
</script>
