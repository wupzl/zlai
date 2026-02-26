<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Admin</h2>
        <div class="meta">Users - GPTs - Chats - Logs</div>
      </div>
      <button class="cta" @click="loadActive">Refresh</button>
    </header>

    <div class="tabs">
      <button :class="{ active: tab === 'users' }" @click="tab = 'users'">Users</button>
      <button :class="{ active: tab === 'gpts' }" @click="tab = 'gpts'">GPTs</button>
      <button :class="{ active: tab === 'chats' }" @click="tab = 'chats'">Chats</button>
      <button :class="{ active: tab === 'logs' }" @click="tab = 'logs'">Logs</button>
    </div>

    <div v-if="tab === 'users'" class="card">
      <h3>Users</h3>
      <div v-for="u in users" :key="u.id" class="list-item">
        <div>
          <div class="session-title">{{ u.username }}</div>
          <div class="session-meta">role: {{ u.role }} - balance: {{ u.tokenBalance }}</div>
        </div>
      </div>
    </div>

    <div v-if="tab === 'gpts'" class="card">
      <h3>GPTs</h3>
      <div v-for="g in gpts" :key="g.id" class="list-item">
        <div>
          <div class="session-title">{{ g.name }}</div>
          <div class="session-meta">{{ g.category }} - public: {{ g.isPublic }}</div>
        </div>
      </div>
    </div>

    <div v-if="tab === 'chats'" class="card">
      <h3>Chat Sessions</h3>
      <div v-for="s in chats" :key="s.chatId" class="list-item">
        <div>
          <div class="session-title">{{ s.chatId }}</div>
          <div class="session-meta">user: {{ s.userId }} - model: {{ s.model }}</div>
        </div>
      </div>
    </div>

    <div v-if="tab === 'logs'" class="card">
      <h3>Logs</h3>
      <div class="grid">
        <div>
          <h4>Login Logs</h4>
          <div v-for="l in loginLogs" :key="l.id" class="session-meta">
            {{ l.userId }} - {{ l.loginTime }}
          </div>
        </div>
        <div>
          <h4>Token Logs</h4>
          <div v-for="t in tokenLogs" :key="t.id" class="session-meta">
            {{ t.userId }} - {{ t.tokens }} tokens
          </div>
        </div>
        <div>
          <h4>System Logs</h4>
          <div v-for="s in systemLogs" :key="s.id" class="session-meta">
            {{ s.userId }} - {{ s.action }}
          </div>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest } from "../api";

export default {
  name: "AdminView",
  data() {
    return {
      tab: "users",
      users: [],
      gpts: [],
      chats: [],
      loginLogs: [],
      tokenLogs: [],
      systemLogs: [],
      status: ""
    };
  },
  mounted() {
    this.loadActive();
  },
  methods: {
    async loadActive() {
      try {
        if (this.tab === "users") {
          this.users = (await apiRequest("/api/admin/users?page=1&size=50")).content || [];
        } else if (this.tab === "gpts") {
          this.gpts = (await apiRequest("/api/admin/gpts?page=1&size=50")).content || [];
        } else if (this.tab === "chats") {
          this.chats = (await apiRequest("/api/admin/chats/sessions?page=1&size=50")).content || [];
        } else if (this.tab === "logs") {
          this.loginLogs = (await apiRequest("/api/admin/logs/login?page=1&size=20")).content || [];
          this.tokenLogs = (await apiRequest("/api/admin/logs/tokens?page=1&size=20")).content || [];
          this.systemLogs = (await apiRequest("/api/admin/logs/system?page=1&size=20")).content || [];
        }
      } catch (e) {
        this.status = e.message;
      }
    }
  },
  watch: {
    tab() {
      this.loadActive();
    }
  }
};
</script>
