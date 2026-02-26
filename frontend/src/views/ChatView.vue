<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Chat</h2>
        <div class="meta">Multi-session - Multi-branch - Streaming</div>
      </div>
      <div class="toolbar">
        <div class="session-settings inline">
          <h3>Session Settings</h3>
          <div class="grid">
            <div>
              <label>Model</label>
              <select v-model="selectedModel">
                <option v-for="m in modelOptions" :key="m" :value="m">{{ formatModelLabel(m) }}</option>
              </select>
              <div class="hint">Billing rate: multiplier per model.</div>
            </div>
            <div>
              <label>Tool Model</label>
              <select v-model="selectedToolModel">
                <option value="">Default tool model</option>
                <option v-for="m in modelOptions" :key="'tool-' + m" :value="m">{{ formatModelLabel(m) }}</option>
              </select>
              <div class="hint">Tool calls are billed using the Tool Model rate.</div>
            </div>
            <div>
              <label>Response Mode</label>
              <div class="switch-row">
                <label class="switch">
                  <input type="checkbox" v-model="useStreaming" />
                  <span class="slider"></span>
                </label>
                <span class="switch-label">{{ useStreaming ? "Streaming" : "Non-streaming" }}</span>
              </div>
              <div class="hint">Non-streaming sends a single response. Streaming is recommended.</div>
              <div class="warning-note" v-if="selectedModel && selectedModel.toLowerCase().startsWith('gpt-')">
                GPT streaming may be unstable. Non-streaming is recommended.
              </div>
            </div>
          </div>
        </div>
        <button class="cta" @click="createSession()">New Chat</button>
      </div>
    </header>

    <div class="chat-layout">
      <div class="chat-body">
        <div class="chip-row" v-if="pendingGptId || pendingAgentId || ragEnabled || pendingRag">
          <span class="chip" v-if="pendingGptId">GPT: {{ pendingGptId }}</span>
          <span class="chip" v-if="pendingAgentId">Agent: {{ pendingAgentId }}</span>
          <span class="chip" v-if="ragEnabled || pendingRag">RAG Enabled</span>
          <button class="ghost" @click="clearPendingContext">Clear</button>
        </div>
        <div class="chat-window">
          <div v-if="branchMessages.length === 0" class="empty">
            Start a new conversation or pick a session on the left.
          </div>

          <div
            v-for="msg in branchMessages"
            :key="msg.messageId"
            class="message"
            :class="msg.role"
          >
            <div class="avatar">{{ msg.role === "user" ? "U" : "AI" }}</div>
            <div class="bubble">
              <div v-if="editingMessageId === msg.messageId" class="edit-box">
                <textarea
                  v-model="editedContent"
                  rows="3"
                  class="edit-textarea"
                ></textarea>
                <div class="message-actions">
                  <button @click="submitEditUser(msg)">Send Branch</button>
                  <button @click="cancelEditUser">Cancel</button>
                </div>
              </div>
              <div
                v-else-if="msg.role === 'assistant'"
                class="markdown-content"
                v-html="renderMarkdown(msg.content)"
              ></div>
              <div v-else>{{ msg.content }}</div>
              <div class="message-meta">
                <span>{{ msg.role }}</span>
                <span v-if="msg.messageId === activeMessageId">Current</span>
              </div>
              <div class="message-actions" v-if="msg.role === 'user'">
                <button @click="startEditUser(msg)">Edit & Branch</button>
              </div>
              <div class="message-actions" v-if="msg.role === 'assistant'">
                <button @click="regenerate(msg)">Regenerate</button>
              </div>
              <div class="branch-controls" v-if="getSiblingBranches(msg).length > 1">
                <span>Branch</span>
                <button @click="switchSiblingBranch(msg, -1)">Prev</button>
                <span class="branch-index">{{ getSiblingPosition(msg) }}</span>
                <button @click="switchSiblingBranch(msg, 1)">Next</button>
              </div>
            </div>
          </div>
        </div>

        <div class="composer">
          <textarea
            v-model="draft"
            rows="2"
            placeholder="Type a message"
            @keydown="onDraftKeydown"
          ></textarea>
          <button class="send-btn" @click="sendMessage()">Send</button>
        </div>
      </div>
    </div>
  </section>
</template>

<script>
import { apiRequest } from "../api";
import { streamChat } from "../utils/sse";
import { marked } from "marked";
import katex from "katex";
import DOMPurify from "dompurify";

export default {
  name: "ChatView",
  data() {
    return {
      sessions: [],
      activeChatId: "",
      messages: [],
      activeMessageId: "",
      draft: "",
      childMap: {},
      selectedBranchMap: {},
      modelOptions: [],
      modelRates: {},
      selectedModel: "deepseek-chat",
      selectedToolModel: "",
      editingMessageId: "",
      editedContent: "",
      lastValidModel: "deepseek-chat",
      pendingGptId: "",
      pendingAgentId: "",
      isStreaming: false,
      useStreaming: true,
      ragEnabled: false,
      pendingRag: false
    };
  },
  computed: {
    branchCount() {
      let count = 0;
      for (const key in this.childMap) {
        if (this.childMap[key] && this.childMap[key].length > 1) count++;
      }
      return count;
    },
    branchMessages() {
      if (!this.activeMessageId) return [];
      const byId = this.messages.reduce((acc, m) => {
        acc[m.messageId] = m;
        return acc;
      }, {});
      const path = [];
      let current = byId[this.activeMessageId];
      while (current) {
        path.push(current);
        current = current.parentMessageId ? byId[current.parentMessageId] : null;
      }
      return path.reverse();
    }
  },
  watch: {
    selectedModel(newVal, oldVal) {
      if (!this.activeChatId) {
        this.lastValidModel = newVal;
        return;
      }
      if (this.hasImageMessages()) {
        this.selectedModel = this.lastValidModel;
        window.alert("This session contains images, model switching is disabled.");
        return;
      }
      this.lastValidModel = newVal;
    },
    "$route.params.chatId": function (val) {
      if (val) {
        if (this.isStreaming && val === this.activeChatId) {
          return;
        }
        this.selectSession(val);
      }
    },
    "$route.params.gptId": function () {
      this.applyRouteContext();
    },
    "$route.params.agentId": function () {
      this.applyRouteContext();
    },
    "$route.path": function () {
      this.applyRouteContext();
    }
  },
  async mounted() {
    this.applyRouteContext();
    await this.loadOptions();
    await this.loadSessions();
    const chatId = this.$route.params.chatId;
    if (chatId) {
      this.selectSession(chatId);
    }
  },
  methods: {
    normalizeMathBlocks(text) {
      if (!text) return "";
      let out = text;
      out = out.split("\\[").join("$$");
      out = out.split("\\]").join("$$");
      out = out.split("\\(").join("$");
      out = out.split("\\)").join("$");
      out = out.replace(/^\s*\[\s*$/gm, "$$");
      out = out.replace(/^\s*\]\s*$/gm, "$$");
      out = out.replace(/^\s*\$\s*$/gm, "");
      return out;
    },
    applyRouteContext() {
      this.pendingGptId = this.$route.params.gptId || "";
      this.pendingAgentId = this.$route.params.agentId || "";
      this.pendingRag = this.$route.path.startsWith("/app/rag/chat");
      if ((this.pendingGptId || this.pendingAgentId) && !this.$route.params.chatId) {
        this.createSession();
      }
    },
    clearPendingContext() {
      this.pendingGptId = "";
      this.pendingAgentId = "";
      this.pendingRag = false;
      this.$router.replace({ path: "/app/chat" });
    },
    hasImageMessages() {
      return (this.messages || []).some((m) => {
        const content = (m && m.content) ? String(m.content) : "";
        return /!\[[^\]]*]\([^)]*\)/.test(content)
          || /!\[\[[^\]]+]]/.test(content)
          || /data:image\//.test(content);
      });
    },
    renderMarkdown(content) {
      const raw = this.normalizeMathBlocks(content || "");
      const blockHtml = [];
      const inlineHtml = [];
      let prepared = raw;
      prepared = prepared.replace(/\$\$([\s\S]+?)\$\$/g, (m, expr) => {
        const html = katex.renderToString(expr.trim(), { throwOnError: false, displayMode: true });
        const key = `@@KATEX_BLOCK_${blockHtml.length}@@`;
        blockHtml.push(html);
        return key;
      });
      prepared = prepared.replace(/\$([^\n$]+?)\$/g, (m, expr) => {
        const html = katex.renderToString(expr.trim(), { throwOnError: false, displayMode: false });
        const key = `@@KATEX_INLINE_${inlineHtml.length}@@`;
        inlineHtml.push(html);
        return key;
      });
      let html = marked.parse(prepared, { gfm: true, breaks: true });
      blockHtml.forEach((h, i) => {
        html = html.replace(`@@KATEX_BLOCK_${i}@@`, h);
      });
      inlineHtml.forEach((h, i) => {
        html = html.replace(`@@KATEX_INLINE_${i}@@`, h);
      });
      return DOMPurify.sanitize(html);
    },
    labelWithPublic(name, isPublic) {
      if (!name) return isPublic ? "Public" : "";
      return isPublic ? `${name} (Public)` : name;
    },
    async loadSessions() {
      const data = await apiRequest("/api/chat/sessions?page=1&size=10");
      this.sessions = Array.isArray(data) ? data : (data?.content || []);
      window.dispatchEvent(new Event("sessions-updated"));
      if (!this.activeChatId && !this.pendingGptId && !this.pendingAgentId && !this.pendingRag && this.sessions.length > 0) {
        this.selectSession(this.sessions[0].chatId);
      }
    },
    async loadOptions() {
      try {
        const models = await apiRequest("/api/chat/models/options");
        const pricing = await apiRequest("/api/chat/models/pricing");
        this.modelRates = this.toRateMap(pricing);
        if (Array.isArray(models) && models.length > 0) {
          this.modelOptions = models;
          if (!this.modelOptions.includes(this.selectedModel)) {
            this.selectedModel = this.modelOptions[0];
          }
        } else if (!this.modelOptions.length) {
          this.modelOptions = ["deepseek-chat"];
          this.selectedModel = "deepseek-chat";
        }
      } catch {
        if (!this.modelOptions.length) {
          this.modelOptions = ["deepseek-chat"];
          this.selectedModel = "deepseek-chat";
        }
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
    },
    async selectSession(chatId) {
      this.activeChatId = chatId;
      this.pendingGptId = "";
      this.pendingAgentId = "";
      this.pendingRag = false;
      const data = await apiRequest(`/api/chat/${chatId}`);
      this.messages = data.messages || [];
      this.activeMessageId = data.currentMessageId || (this.messages.length ? this.messages[this.messages.length - 1].messageId : "");
      if (data.model) {
        this.selectedModel = data.model;
        this.lastValidModel = data.model;
      }
      this.selectedToolModel = data.toolModel || "";
      this.ragEnabled = Boolean(data.ragEnabled);
      if (data.gptId) {
        this.pendingGptId = data.gptId;
      }
      if (data.agentId) {
        this.pendingAgentId = data.agentId;
      }
      this.buildChildMap();
      this.initBranchSelections(this.activeMessageId);
      this.syncRouteWithSession();
    },
    buildChildMap() {
      const map = {};
      this.messages.forEach((m) => {
        const parentKey = m.parentMessageId || "__root__";
        if (!map[parentKey]) map[parentKey] = [];
        map[parentKey].push(m);
      });
      Object.keys(map).forEach((key) => {
        map[key].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
      });
      this.childMap = map;
    },
    initBranchSelections(leafMessageId) {
      const byId = this.messages.reduce((acc, m) => {
        acc[m.messageId] = m;
        return acc;
      }, {});
      const selections = {};
      let current = byId[leafMessageId];
      while (current) {
        const parentKey = current.parentMessageId || "__root__";
        selections[parentKey] = current.messageId;
        current = current.parentMessageId ? byId[current.parentMessageId] : null;
      }
      this.selectedBranchMap = selections;
    },
    setBranch(messageId, parentKey) {
      if (!messageId) return;
      let resolvedParent = parentKey;
      if (!resolvedParent) {
        const msg = this.messages.find((m) => m.messageId === messageId);
        resolvedParent = msg ? (msg.parentMessageId || "__root__") : "__root__";
      }
      this.selectedBranchMap = {
        ...this.selectedBranchMap,
        [resolvedParent]: messageId
      };
      this.activeMessageId = this.resolveDefaultLeaf(messageId);
    },
    createSession() {
      this.activeChatId = "";
      this.messages = [];
      this.activeMessageId = "";
      this.childMap = {};
      this.selectedBranchMap = {};
      this.editingMessageId = "";
      this.editedContent = "";
      this.ragEnabled = false;
      this.pendingRag = false;
      this.syncRouteWithSession();
    },
    syncRouteWithSession() {
      const chatId = this.activeChatId;
      if (this.pendingGptId) {
        const target = chatId
          ? `/app/gpt/${this.pendingGptId}/chat/${chatId}`
          : `/app/gpt/${this.pendingGptId}/chat`;
        if (this.$route.path !== target) {
          this.$router.replace(target);
        }
        return;
      }
      if (this.pendingAgentId) {
        const target = chatId
          ? `/app/agent/${this.pendingAgentId}/chat/${chatId}`
          : `/app/agent/${this.pendingAgentId}/chat`;
        if (this.$route.path !== target) {
          this.$router.replace(target);
        }
        return;
      }
      if (this.ragEnabled) {
        if (chatId) {
          const target = `/app/rag/chat/${chatId}`;
          if (this.$route.path !== target) {
            this.$router.replace(target);
          }
        }
        return;
      }
      if (chatId) {
        const target = `/app/chat/${chatId}`;
        if (this.$route.path !== target) {
          this.$router.replace(target);
        }
      } else if (this.$route.path !== "/app/chat") {
        this.$router.replace("/app/chat");
      }
    },
    async sendMessage(parentOverride, promptOverride) {
      if (parentOverride && typeof parentOverride === "object" && parentOverride.target) {
        parentOverride = null;
      }
      const content = ((promptOverride !== undefined && promptOverride !== null)
        ? promptOverride
        : this.draft).trim();
      if (!content) return;
      let parentId;
      if (parentOverride === "__root__") {
        parentId = null;
      } else if (parentOverride !== undefined && parentOverride !== null) {
        parentId = parentOverride;
      } else {
        parentId = this.activeMessageId;
      }
      const payload = {
        chatId: this.activeChatId || null,
        prompt: content,
        parentMessageId: parentId || null,
        model: this.selectedModel || null,
        toolModel: this.selectedToolModel || null,
        useRag: this.pendingRag ? true : null,
        gptId: this.activeChatId ? null : (this.pendingGptId || null),
        agentId: this.activeChatId ? null : (this.pendingAgentId || null)
      };
      const userTempId = `tmp-user-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const assistantTempId = `tmp-assistant-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const now = new Date().toISOString();
      this.messages.push({
        messageId: userTempId,
        parentMessageId: parentId || null,
        role: "user",
        content,
        createdAt: now
      });
      this.messages.push({
        messageId: assistantTempId,
        parentMessageId: userTempId,
        role: "assistant",
        content: "",
        streaming: true,
        createdAt: now
      });
      const rootKey = parentId || "__root__";
      this.selectedBranchMap = {
        ...this.selectedBranchMap,
        [rootKey]: userTempId,
        [userTempId]: assistantTempId
      };
      this.activeMessageId = assistantTempId;
      this.buildChildMap();
      if (promptOverride == null) {
        this.draft = "";
      }

      if (!this.useStreaming) {
        await this.sendMessageSync(payload, userTempId, assistantTempId);
        return;
      }

      this.isStreaming = true;
      const stream = await streamChat("/api/chat/stream", payload, (event, data) => {
        if (event === "session_created") {
          const parsed = JSON.parse(data);
          this.activeChatId = parsed.chatId;
          this.loadSessions();
          this.syncRouteWithSession();
        }
        if (event === "message_chunk") {
          const parsed = JSON.parse(data);
          this.applyStreamChunk(parsed.content, assistantTempId, userTempId);
        }
        if (event === "done") {
          this.selectSession(this.activeChatId);
        }
      });

      await stream.done;
      stream.close();
      this.isStreaming = false;
    },
    async sendMessageSync(payload, userTempId, assistantTempId) {
      try {
        if (!this.activeChatId) {
          if (this.pendingGptId || this.pendingAgentId || this.pendingRag) {
            this.useStreaming = true;
            await this.sendMessage(null, payload.prompt);
            return;
          }
          const session = await apiRequest(`/api/chat/session?title=New%20Chat&model=${encodeURIComponent(this.selectedModel || "")}&toolModel=${encodeURIComponent(this.selectedToolModel || "")}`, {
            method: "POST"
          });
          this.activeChatId = session?.chatId || "";
          await this.loadSessions();
          this.syncRouteWithSession();
          payload.chatId = this.activeChatId || null;
        }
        const response = await apiRequest("/api/chat/message", {
          method: "POST",
          body: JSON.stringify(payload)
        });
        const answer = typeof response === "string" ? response : (response?.content || "");
        this.applyStreamChunk(answer || "", assistantTempId, userTempId);
        await this.selectSession(this.activeChatId);
      } finally {
        this.isStreaming = false;
      }
    },
    applyStreamChunk(chunk, assistantMessageId, parentMessageId) {
      const target = this.messages.find((m) => m.messageId === assistantMessageId);
      if (target) {
        target.content = (target.content || "") + chunk;
        target.streaming = true;
        return;
      }
      this.messages.push({
        messageId: assistantMessageId || `stream-${Date.now()}`,
        parentMessageId: parentMessageId || null,
        role: "assistant",
        content: chunk,
        streaming: true,
        createdAt: new Date().toISOString()
      });
      if (assistantMessageId && parentMessageId) {
        this.selectedBranchMap = {
          ...this.selectedBranchMap,
          [parentMessageId]: assistantMessageId
        };
      }
      this.buildChildMap();
    },
    getSiblingBranches(msg) {
      if (!msg) return [];
      const parentKey = msg.parentMessageId || "__root__";
      return this.childMap[parentKey] || [];
    },
    getSiblingPosition(msg) {
      const siblings = this.getSiblingBranches(msg);
      if (!siblings.length) return "1/1";
      const index = siblings.findIndex((item) => item.messageId === msg.messageId);
      return `${index + 1}/${siblings.length}`;
    },
    switchSiblingBranch(msg, delta) {
      const siblings = this.getSiblingBranches(msg);
      if (siblings.length <= 1) return;
      const currentIndex = siblings.findIndex((item) => item.messageId === msg.messageId);
      if (currentIndex < 0) return;
      const nextIndex = (currentIndex + delta + siblings.length) % siblings.length;
      const parentKey = msg.parentMessageId || "__root__";
      this.setBranch(siblings[nextIndex].messageId, parentKey);
    },
    resolveDefaultLeaf(messageId) {
      let currentId = messageId;
      while (currentId) {
        const children = this.childMap[currentId] || [];
        if (!children.length) break;
        const selected = this.selectedBranchMap[currentId];
        if (selected && children.find((c) => c.messageId === selected)) {
          currentId = selected;
        } else {
          currentId = children[0].messageId;
        }
      }
      return currentId;
    },
    startEditUser(msg) {
      if (!msg || msg.role !== "user") return;
      this.editingMessageId = msg.messageId;
      this.editedContent = msg.content || "";
    },
    cancelEditUser() {
      this.editingMessageId = "";
      this.editedContent = "";
    },
    async submitEditUser(msg) {
      if (!msg || msg.role !== "user") return;
      const edited = this.editedContent.trim();
      if (!edited) return;
      this.cancelEditUser();
      const parentKey = msg.parentMessageId ? msg.parentMessageId : "__root__";
      await this.sendMessage(parentKey, edited);
    },
    onDraftKeydown(event) {
      if (event.isComposing) return;
      if (event.key === "Enter" && !event.altKey && !event.shiftKey) {
        const content = (this.draft || "").trim();
        if (!content) return;
        event.preventDefault();
        this.sendMessage();
      }
    },
    async regenerate(msg) {
      if (!msg || msg.role !== "assistant") return;
      if (!this.activeChatId) return;
      const assistantTempId = `tmp-assistant-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const now = new Date().toISOString();
      this.messages.push({
        messageId: assistantTempId,
        parentMessageId: msg.parentMessageId || null,
        role: "assistant",
        content: "",
        streaming: true,
        createdAt: now
      });
      if (msg.parentMessageId) {
        this.selectedBranchMap = {
          ...this.selectedBranchMap,
          [msg.parentMessageId]: assistantTempId
        };
      }
      this.activeMessageId = assistantTempId;
      this.buildChildMap();
      const payload = {
        chatId: this.activeChatId,
        regenerateFromAssistantMessageId: msg.messageId,
        model: this.selectedModel || null,
        toolModel: this.selectedToolModel || null,
        useRag: null
      };

      this.isStreaming = true;
      const stream = await streamChat("/api/chat/stream", payload, (event, data) => {
        if (event === "message_chunk") {
          const parsed = JSON.parse(data);
          this.applyStreamChunk(parsed.content, assistantTempId, msg.parentMessageId || null);
        }
        if (event === "done") {
          this.selectSession(this.activeChatId);
        }
      });

      await stream.done;
      stream.close();
      this.isStreaming = false;
    }
  }
};
</script>
