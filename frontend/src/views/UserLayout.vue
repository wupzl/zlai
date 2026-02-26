<template>
  <div class="app-shell" :class="{ collapsed: sidebarCollapsed }">
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="brand">
        <div class="brand-logo">zl</div>
        <div v-if="!sidebarCollapsed">
          <h1>zlAI Studio</h1>
          <div class="session-meta">Personal workspace</div>
        </div>
      </div>

      <button class="ghost collapse-btn" @click="toggleSidebar">
        {{ sidebarCollapsed ? ">>" : "<<" }}
      </button>

      <nav class="nav">
        <RouterLink class="nav-item" to="/app/chat">
          <span class="nav-icon">C</span>
          <span class="nav-label">Chat</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/app/agents">
          <span class="nav-icon">A</span>
          <span class="nav-label">Agents</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/app/gpts">
          <span class="nav-icon">G</span>
          <span class="nav-label">GPT Store</span>
        </RouterLink>
        <RouterLink class="nav-item" to="/app/rag">
          <span class="nav-icon">R</span>
          <span class="nav-label">RAG Library</span>
        </RouterLink>
      </nav>

            <div
        class="sidebar-section sessions-list"
        v-if="!sidebarCollapsed"
        ref="sessionsList"
        @scroll="onSessionsScroll"
      >
        <div class="meta">Recent sessions</div>
        <div
          class="session-card"
          v-for="s in recentSessions"
          :key="s.chatId"
        >
          <div class="session-card-row">
            <div class="session-card-body" @click="openSession(s)">
              <div class="session-title">{{ s.title }}</div>
              <div class="session-meta">
                {{ s.model }}<span v-if="s.ragEnabled"> 路 RAG</span>
              </div>
            </div>
            <div class="session-actions">
              <button class="ghost icon-btn" @click.stop="toggleSessionMenu(s, $event)">
                ⋯
              </button>
            </div>
          </div>
        </div>
        <div class="session-meta" v-if="loadingSessions">Loading...</div>
      </div>

      <div class="sidebar-footer">
        <RouterLink class="nav-item" to="/app/profile">
          <span class="nav-icon">P</span>
          <span class="nav-label">Profile</span>
        </RouterLink>
      </div>
    </aside>

    <div class="content">
      <header class="topbar">
        <div>
          <div class="topbar-title">Workspace</div>
          <div class="topbar-sub">Build multi-branch conversations</div>
        </div>
        <div class="topbar-right">
          <RouterLink class="pill" to="/app/profile">Profile</RouterLink>
          <button class="pill" @click="logout">Logout</button>
        </div>
      </header>

      <main class="main">
        <RouterView />
      </main>
    </div>

    <div v-if="modal.visible" class="modal-backdrop" @click.self="closeModal">
      <div class="modal-card">
        <div class="modal-title">{{ modal.title }}</div>
        <div class="modal-body" v-if="modal.type === 'rename'">
          <label class="modal-label">New title</label>
          <input class="modal-input" v-model="modal.input" placeholder="Enter a title" />
        </div>
        <div class="modal-body" v-else>
          <div class="modal-text">{{ modal.message }}</div>
        </div>
        <div class="modal-actions">
          <button class="ghost" @click="closeModal">Cancel</button>
          <button class="solid danger" v-if="modal.type === 'delete'" @click="confirmDelete">Delete</button>
          <button class="solid" v-if="modal.type === 'rename'" @click="confirmRename">Save</button>
        </div>
      </div>
    </div>

    <div
      v-if="activeMenuChatId"
      class="session-menu floating"
      :style="{ top: `${menuPosition.top}px`, left: `${menuPosition.left}px` }"
      @click.stop
    >
      <button class="menu-item" @click="promptRename(activeMenuSession)">Rename</button>
      <button class="menu-item danger" @click="removeSession(activeMenuSession)">Delete</button>
    </div>
  </div>
</template>

<script>
import { apiRequest, clearAdminFlag, clearToken, clearUserInfo } from "../api";

export default {
  name: "UserLayout",
  data() {
    return {
      sidebarCollapsed: false,
      recentSessions: [],
      sessionPage: 1,
      sessionPageSize: 20,
      hasMoreSessions: true,
      loadingSessions: false,
      sessionEventHandler: null,
      activeMenuChatId: "",
      activeMenuSession: null,
      menuPosition: { top: 0, left: 0 },
      modal: {
        visible: false,
        type: "",
        title: "",
        message: "",
        input: "",
        session: null
      }
    };
  },
  mounted() {
    this.loadRecent(true);
    this.sessionEventHandler = () => this.loadRecent(true);
    window.addEventListener("sessions-updated", this.sessionEventHandler);
    window.addEventListener("click", this.closeSessionMenu);
  },
  beforeUnmount() {
    if (this.sessionEventHandler) {
      window.removeEventListener("sessions-updated", this.sessionEventHandler);
    }
    window.removeEventListener("click", this.closeSessionMenu);
  },
  watch: {
    "$route.fullPath"() {
      this.loadRecent(true);
    }
  },
  methods: {
    async loadRecent(reset = false) {
      try {
        if (reset) {
          this.sessionPage = 1;
          this.recentSessions = [];
          this.hasMoreSessions = true;
        }
        if (!this.hasMoreSessions || this.loadingSessions) return;
        this.loadingSessions = true;
        const data = await apiRequest(`/api/chat/sessions?page=${this.sessionPage}&size=${this.sessionPageSize}`);
        const content = Array.isArray(data) ? data : (data?.content || []);
        if (Array.isArray(data)) {
          this.recentSessions = content;
          this.hasMoreSessions = false;
        } else {
          this.recentSessions = this.recentSessions.concat(content);
          const totalPages = data?.totalPages || 1;
          this.hasMoreSessions = this.sessionPage < totalPages;
          this.sessionPage += 1;
        }
      } catch {
        this.recentSessions = [];
        this.hasMoreSessions = false;
      } finally {
        this.loadingSessions = false;
      }
    },
    onSessionsScroll(event) {
      const el = event.target;
      if (!el || this.loadingSessions || !this.hasMoreSessions) return;
      if (el.scrollTop + el.clientHeight >= el.scrollHeight - 40) {
        this.loadRecent(false);
      }
    },
    async openSession(session) {
      if (!session || !session.chatId) return;
      this.activeMenuChatId = "";
      this.activeMenuSession = null;
      if (session.gptId) {
        this.$router.push(`/app/gpt/${session.gptId}/chat/${session.chatId}`);
        return;
      }
      if (session.agentId) {
        this.$router.push(`/app/agent/${session.agentId}/chat/${session.chatId}`);
        return;
      }
      if (session.ragEnabled) {
        this.$router.push(`/app/rag/chat/${session.chatId}`);
        return;
      }
      try {
        const detail = await apiRequest(`/api/chat/${session.chatId}`);
        if (detail?.gptId) {
          this.$router.push(`/app/gpt/${detail.gptId}/chat/${session.chatId}`);
          return;
        }
        if (detail?.agentId) {
          this.$router.push(`/app/agent/${detail.agentId}/chat/${session.chatId}`);
          return;
        }
        if (detail?.ragEnabled) {
          this.$router.push(`/app/rag/chat/${session.chatId}`);
          return;
        }
      } catch {
        // fall through
      }
      this.$router.push(`/app/chat/${session.chatId}`);
    },
    toggleSessionMenu(session, event) {
      if (!session || !session.chatId) return;
      if (this.activeMenuChatId === session.chatId) {
        this.activeMenuChatId = "";
        this.activeMenuSession = null;
        return;
      }
      const rect = event?.currentTarget?.getBoundingClientRect?.();
      if (rect) {
        const menuWidth = 160;
        this.menuPosition = {
          top: rect.top,
          left: Math.min(window.innerWidth - menuWidth - 12, rect.right + 8)
        };
      } else {
        this.menuPosition = { top: 80, left: 80 };
      }
      this.activeMenuChatId = session.chatId;
      this.activeMenuSession = session;
    },
    closeSessionMenu() {
      this.activeMenuChatId = "";
      this.activeMenuSession = null;
    },
    promptRename(session) {
      if (!session || !session.chatId) return;
      this.activeMenuChatId = "";
      this.activeMenuSession = null;
      this.modal = {
        visible: true,
        type: "rename",
        title: "Rename session",
        message: "",
        input: session.title || "",
        session
      };
    },
    async confirmRename() {
      const session = this.modal.session;
      const next = (this.modal.input || "").trim();
      if (!session || !session.chatId || !next) return;
      try {
        await apiRequest(`/api/chat/session/${session.chatId}/title?title=${encodeURIComponent(next.trim())}`, {
          method: "PUT"
        });
        this.loadRecent(true);
        window.dispatchEvent(new Event("sessions-updated"));
      } catch {
        // ignore
      }
      this.closeModal();
    },
    removeSession(session) {
      if (!session || !session.chatId) return;
      this.activeMenuChatId = "";
      this.activeMenuSession = null;
      this.modal = {
        visible: true,
        type: "delete",
        title: "Delete session?",
        message: `This will delete “${session.title || "Untitled"}”.`,
        input: "",
        session
      };
    },
    async confirmDelete() {
      const session = this.modal.session;
      if (!session || !session.chatId) return;
      try {
        await apiRequest(`/api/chat/session/${session.chatId}`, { method: "DELETE" });
        if (this.$route.params.chatId === session.chatId) {
          this.$router.push("/app/chat");
        }
        this.loadRecent(true);
        window.dispatchEvent(new Event("sessions-updated"));
      } catch {
        // ignore
      }
      this.closeModal();
    },
    closeModal() {
      this.modal.visible = false;
      this.modal.type = "";
      this.modal.title = "";
      this.modal.message = "";
      this.modal.input = "";
      this.modal.session = null;
    },
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed;
    },
    logout() {
      clearToken();
      clearUserInfo();
      clearAdminFlag();
      this.$router.push("/auth/user");
    }
  }
};
</script>

