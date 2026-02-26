<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Chat Monitoring</h2>
        <div class="meta">Track active chat sessions</div>
      </div>
      <button class="cta" @click="loadChats">Refresh</button>
      <button class="ghost" @click="exportChats">Export CSV</button>
    </header>

    <div class="admin-toolbar">
      <div class="filters">
        <input v-model="filterUserId" placeholder="User ID" @keyup.enter="onSearch" />
        <input v-model="keyword" placeholder="Search title / chatId" @keyup.enter="onSearch" />
        <select v-model.number="size" @change="onSearch">
          <option :value="10">10</option>
          <option :value="20">20</option>
          <option :value="50">50</option>
        </select>
      </div>
      <div class="pagination">
        <button class="ghost" :disabled="page <= 1" @click="page--; loadChats()">Prev</button>
        <span>Page {{ page }} / {{ totalPages }}</span>
        <button class="ghost" :disabled="page >= totalPages" @click="page++; loadChats()">Next</button>
      </div>
    </div>

    <div class="card">
      <div v-for="s in chats" :key="s.chatId" class="list-item">
        <div>
          <div class="session-title">{{ s.title }}</div>
          <div class="session-meta">user: {{ s.userId }} - model: {{ s.model }}</div>
        </div>
        <div class="list-actions">
          <button @click="openDetail(s)">View</button>
        </div>
      </div>
      <div v-if="!chats.length" class="empty">No chats found.</div>
    </div>

    <div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal modal-wide">
        <div class="modal-header">
          <h3>Chat Detail</h3>
          <button class="ghost" @click="closeModal">Close</button>
        </div>
        <div v-if="selected" class="modal-body">
          <div class="session-meta">chatId: {{ selected.chatId }} | user: {{ selected.userId }}</div>
          <div class="session-meta">model: {{ selected.model }} | messages: {{ selected.messageCount }}</div>
          <div class="list-actions">
            <button @click="loadMessages">Load Messages</button>
          </div>
          <div class="admin-chat-messages" v-if="messages.length">
            <div v-for="m in messages" :key="m.messageId" class="list-item">
              <div>
                <div class="session-title">{{ m.role }}</div>
                <div class="session-meta">{{ m.content }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, API_BASE } from "../../api";

export default {
  name: "AdminChatsView",
  data() {
    return {
      chats: [],
      status: "",
      selected: null,
      messages: [],
      keyword: "",
      filterUserId: "",
      page: 1,
      size: 20,
      totalPages: 1,
      showModal: false
    };
  },
  mounted() {
    this.loadChats();
  },
  methods: {
    async loadChats() {
      try {
        const query = new URLSearchParams();
        query.set("page", this.page);
        query.set("size", this.size);
        if (this.keyword) query.set("keyword", this.keyword);
        if (this.filterUserId) query.set("userId", this.filterUserId);
        const data = await apiRequest(`/api/admin/chats/sessions?${query.toString()}`);
        this.chats = data.content || data.records || data || [];
        this.totalPages = data.totalPages || Math.max(1, Math.ceil((data.totalElements || this.chats.length) / this.size));
      } catch (e) {
        this.status = e.message;
      }
    },
    async exportChats() {
      try {
        const params = new URLSearchParams();
        if (this.filterUserId) params.set("userId", this.filterUserId);
        if (this.keyword) params.set("keyword", this.keyword);
        await this.downloadCsv(`/api/admin/chats/sessions/export?${params.toString()}`, "chat_sessions.csv");
      } catch (e) {
        this.status = e.message;
      }
    },
    onSearch() {
      this.page = 1;
      this.loadChats();
    },
    openDetail(s) {
      this.selected = s;
      this.messages = [];
      this.showModal = true;
    },
    closeModal() {
      this.showModal = false;
    },
    async loadMessages() {
      if (!this.selected) return;
      try {
        const data = await apiRequest(`/api/admin/chats/messages?chatId=${this.selected.chatId}&page=1&size=50`);
        this.messages = data.content || data.records || data || [];
      } catch (e) {
        this.status = e.message;
      }
    }
  ,
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
  }
};
</script>

