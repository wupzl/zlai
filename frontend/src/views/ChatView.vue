<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Chat</h2>
        <div class="meta">Multi-session - Multi-branch - Streaming</div>
      </div>
      <div class="toolbar">
        <ChatSessionSettings
          :model-options="modelOptions"
          :selected-model="selectedModel"
          :selected-tool-model="selectedToolModel"
          :use-streaming="useStreaming"
          :format-model-label="formatModelLabel"
          @update:selectedModel="selectedModel = $event"
          @update:selectedToolModel="selectedToolModel = $event"
          @update:useStreaming="useStreaming = $event"
        />
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
        <ChatMessageList
          :messages="branchMessages"
          :child-map="childMap"
          :active-message-id="activeMessageId"
          :editing-message-id="editingMessageId"
          :edited-content="editedContent"
          :is-streaming="isStreaming"
          @update:editedContent="editedContent = $event"
          @start-edit="startEditUser"
          @cancel-edit="cancelEditUser"
          @submit-edit="submitEditUser"
          @regenerate="regenerate"
          @switch-branch="switchSiblingBranch"
        />

        <ChatComposer
          :draft="draft"
          :is-streaming="isStreaming"
          :can-send="canSend"
          @update:draft="draft = $event"
          @keydown="onDraftKeydown"
          @send="sendMessage"
          @stop="stopStreaming"
        />
      </div>
    </div>
  </section>
</template>

<script>
import { apiRequest } from "../api";
import { streamChat } from "../utils/sse";
import ChatComposer from "../components/chat/ChatComposer.vue";
import ChatMessageList from "../components/chat/ChatMessageList.vue";
import ChatSessionSettings from "../components/chat/ChatSessionSettings.vue";
import { loadChatSessionPreferences, saveChatSessionPreferences } from "../utils/chatSessionPrefs";
import {
  buildChildMap,
  initBranchSelections,
  normalizeInterruptedMessages,
  resolveBranchMessages,
  resolveDefaultLeaf,
  stripInterruptedMarkers
} from "../utils/chatTree";

export default {
  name: "ChatView",
  components: {
    ChatComposer,
    ChatMessageList,
    ChatSessionSettings
  },
  data() {
    const prefs = loadChatSessionPreferences();
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
      selectedModel: prefs.selectedModel,
      selectedToolModel: prefs.selectedToolModel,
      editingMessageId: "",
      editedContent: "",
      lastValidModel: prefs.selectedModel,
      activeStream: null,
      activeStreamingMessageId: "",
      pendingGptId: "",
      pendingAgentId: "",
      isStreaming: false,
      useStreaming: prefs.useStreaming,
      ragEnabled: false,
      pendingRag: false
    };
  },
  computed: {
    canSend() {
      return (this.draft || "").trim().length > 0;
    },
    branchCount() {
      let count = 0;
      for (const key in this.childMap) {
        if (this.childMap[key] && this.childMap[key].length > 1) count++;
      }
      return count;
    },
    branchMessages() {
      return resolveBranchMessages(this.messages, this.activeMessageId);
    }
  },
  watch: {
    selectedModel(newVal, oldVal) {
      if (!this.activeChatId) {
        this.lastValidModel = newVal;
        this.persistSessionPreferences();
        return;
      }
      if (this.hasImageMessages()) {
        this.selectedModel = this.lastValidModel;
        window.alert("This session contains images, model switching is disabled.");
        return;
      }
      this.lastValidModel = newVal;
      this.persistSessionPreferences();
    },
    selectedToolModel() {
      this.persistSessionPreferences();
    },
    useStreaming() {
      this.persistSessionPreferences();
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
  beforeUnmount() {
    this.stopStreaming();
  },
  methods: {
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
    labelWithPublic(name, isPublic) {
      if (!name) return isPublic ? "Public" : "";
      return isPublic ? `${name} (Public)` : name;
    },
    async loadSessions() {
      const data = await apiRequest("/api/chat/sessions?page=1&size=10");
      this.sessions = Array.isArray(data) ? data : (data?.content || []);
      window.dispatchEvent(new Event("sessions-updated"));
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
    persistSessionPreferences() {
      saveChatSessionPreferences({
        selectedModel: this.selectedModel,
        selectedToolModel: this.selectedToolModel,
        useStreaming: this.useStreaming
      });
    },
    async selectSession(chatId) {
      this.activeChatId = chatId;
      this.pendingGptId = "";
      this.pendingAgentId = "";
      this.pendingRag = false;
      const data = await apiRequest(`/api/chat/${chatId}`);
      this.messages = this.normalizeInterruptedMessages(data.messages || []);
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
      this.childMap = buildChildMap(this.messages);
    },
    initBranchSelections(leafMessageId) {
      this.selectedBranchMap = initBranchSelections(this.messages, leafMessageId);
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
      const keepRagContext = this.$route.path.startsWith("/app/rag/chat") || this.pendingRag || this.ragEnabled;
      const prefs = loadChatSessionPreferences();
      this.activeChatId = "";
      this.messages = [];
      this.activeMessageId = "";
      this.childMap = {};
      this.selectedBranchMap = {};
      this.editingMessageId = "";
      this.editedContent = "";
      this.selectedModel = prefs.selectedModel;
      this.selectedToolModel = prefs.selectedToolModel;
      this.useStreaming = prefs.useStreaming;
      this.lastValidModel = prefs.selectedModel;
      this.ragEnabled = false;
      this.pendingRag = keepRagContext;
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
      if (this.pendingRag || this.ragEnabled) {
        const target = chatId ? `/app/rag/chat/${chatId}` : "/app/rag/chat";
        if (this.$route.path !== target) {
          this.$router.replace(target);
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
        requestId: this.generateRequestId("chat"),
        messageId: this.generateMessageId(),
        prompt: content,
        parentMessageId: parentId || null,
        model: this.selectedModel || null,
        toolModel: this.selectedToolModel || null,
        useRag: this.pendingRag ? true : null,
        gptId: this.activeChatId ? null : (this.pendingGptId || null),
        agentId: this.activeChatId ? null : (this.pendingAgentId || null)
      };
      const shouldUseStreaming = this.useStreaming
        || (!this.activeChatId && (this.pendingGptId || this.pendingAgentId || this.pendingRag));
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

      if (!shouldUseStreaming) {
        await this.sendMessageSync(payload, userTempId, assistantTempId);
        return;
      }

      this.isStreaming = true;
      this.activeStreamingMessageId = assistantTempId;
      let stream;
      try {
        stream = await streamChat("/api/chat/stream", payload, (event, data) => {
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
        this.activeStream = stream;
        await stream.done;
      } catch (error) {
        if (error?.name === "StreamAborted") {
          this.markStreamInterrupted(assistantTempId);
          await this.refreshSessionAfterInterrupt();
        } else {
          this.markStreamFailed(assistantTempId, error);
        }
      } finally {
        if (this.activeStream === stream) {
          this.activeStream = null;
        }
        this.finishStreamingMessage(assistantTempId);
        this.activeStreamingMessageId = "";
        this.isStreaming = false;
      }
    },
    async sendMessageSync(payload, userTempId, assistantTempId) {
      try {
        if (!this.activeChatId) {
          const title = this.pendingRag ? "RAG Chat" : "New Chat";
          const sessionPath = this.pendingRag ? "/api/rag/session" : "/api/chat/session";
          const session = await apiRequest(`${sessionPath}?title=${encodeURIComponent(title)}&model=${encodeURIComponent(this.selectedModel || "")}&toolModel=${encodeURIComponent(this.selectedToolModel || "")}`, {
            method: "POST"
          });
          this.activeChatId = session?.chatId || "";
          this.ragEnabled = Boolean(session?.ragEnabled) || this.pendingRag;
          await this.loadSessions();
          this.syncRouteWithSession();
          payload.chatId = this.activeChatId || null;
        }
        const response = await apiRequest("/api/chat/message", {
          method: "POST",
          body: JSON.stringify(payload)
        });
        const answer = typeof response === "string" ? response : (response?.content || "");
        const additiveMetadata = typeof response === "string" ? null : {
          citations: Array.isArray(response?.citations) ? response.citations : [],
          grounding: response?.grounding || null
        };
        this.applyStreamChunk(answer || "", assistantTempId, userTempId);
        await this.selectSession(this.activeChatId);
        this.attachAssistantMetadata(additiveMetadata);
      } finally {
        this.isStreaming = false;
      }
    },
    attachAssistantMetadata(additiveMetadata) {
      if (!additiveMetadata) return;
      const { citations, grounding } = additiveMetadata;
      if ((!citations || citations.length === 0) && !grounding) {
        return;
      }
      for (let i = this.messages.length - 1; i >= 0; i -= 1) {
        const message = this.messages[i];
        if (message && message.role === "assistant") {
          if (citations && citations.length > 0) {
            message.citations = citations;
          }
          if (grounding) {
            message.grounding = grounding;
          }
          break;
        }
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
    finishStreamingMessage(messageId) {
      const target = this.messages.find((m) => m.messageId === messageId);
      if (target) {
        target.streaming = false;
      }
    },
    stopStreaming() {
      if (!this.activeStream) return;
      const stream = this.activeStream;
      this.activeStream = null;
      stream.close();
    },
    markStreamInterrupted(messageId) {
      const target = this.messages.find((m) => m.messageId === messageId);
      if (target) {
        target.content = this.stripInterruptedMarkers(target.content);
        target.status = "INTERRUPTED";
        target.streaming = false;
      }
    },
    normalizeInterruptedMessages(messages) {
      return normalizeInterruptedMessages(messages);
    },
    stripInterruptedMarkers(content) {
      return stripInterruptedMarkers(content);
    },
    async refreshSessionAfterInterrupt() {
      if (!this.activeChatId) return;
      await new Promise((resolve) => setTimeout(resolve, 250));
      try {
        await this.selectSession(this.activeChatId);
      } catch {
      }
    },
    markStreamFailed(messageId, error) {
      const message = (error && error.message) ? String(error.message) : "Stream failed";
      const target = this.messages.find((m) => m.messageId === messageId);
      if (target) {
        target.content = target.content || `Request failed: ${message}`;
        target.streaming = false;
        return;
      }
      window.alert(message);
    },
    switchSiblingBranch(msg, delta) {
      if (!msg) return;
      const parentKey = msg.parentMessageId || "__root__";
      const siblings = this.childMap[parentKey] || [];
      if (siblings.length <= 1) return;
      const currentIndex = siblings.findIndex((item) => item.messageId === msg.messageId);
      if (currentIndex < 0) return;
      const nextIndex = (currentIndex + delta + siblings.length) % siblings.length;
      this.setBranch(siblings[nextIndex].messageId, parentKey);
    },
    resolveDefaultLeaf(messageId) {
      return resolveDefaultLeaf(this.childMap, this.selectedBranchMap, messageId);
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
      if (this.isStreaming) return;
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
        requestId: this.generateRequestId("regen"),
        regenerateFromAssistantMessageId: msg.messageId,
        model: this.selectedModel || null,
        toolModel: this.selectedToolModel || null,
        useRag: null
      };

      this.isStreaming = true;
      this.activeStreamingMessageId = assistantTempId;
      let stream;
      try {
        stream = await streamChat("/api/chat/stream", payload, (event, data) => {
          if (event === "message_chunk") {
            const parsed = JSON.parse(data);
            this.applyStreamChunk(parsed.content, assistantTempId, msg.parentMessageId || null);
          }
          if (event === "done") {
            this.selectSession(this.activeChatId);
          }
        });
        this.activeStream = stream;
        await stream.done;
      } catch (error) {
        if (error?.name === "StreamAborted") {
          this.markStreamInterrupted(assistantTempId);
          await this.refreshSessionAfterInterrupt();
        } else {
          this.markStreamFailed(assistantTempId, error);
        }
      } finally {
        if (this.activeStream === stream) {
          this.activeStream = null;
        }
        this.finishStreamingMessage(assistantTempId);
        this.activeStreamingMessageId = "";
        this.isStreaming = false;
      }
    },
    generateMessageId() {
      if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return `msg-${window.crypto.randomUUID()}`;
      }
      return `msg-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    },
    generateRequestId(prefix = "req") {
      if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return `${prefix}-${window.crypto.randomUUID()}`;
      }
      return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    }
  }
};
</script>
