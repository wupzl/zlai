<template>
  <div class="app-shell admin-shell">
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="brand">
        <div class="brand-logo">adm</div>
        <div v-if="!sidebarCollapsed">
          <h1>Admin Console</h1>
          <div class="session-meta">Operations & monitoring</div>
        </div>
      </div>

      <button class="ghost" @click="toggleSidebar">
        {{ sidebarCollapsed ? "Expand" : "Collapse" }}
      </button>

      <nav class="nav">
        <RouterLink class="nav-item" to="/admin/dashboard">Dashboard</RouterLink>
        <RouterLink class="nav-item" to="/admin/users">User Management</RouterLink>
        <RouterLink class="nav-item" to="/admin/gpts">GPT Management</RouterLink>
        <RouterLink class="nav-item" to="/admin/agents">Agent Management</RouterLink>
        <RouterLink class="nav-item" to="/admin/rag">RAG Management</RouterLink>
        <RouterLink class="nav-item" to="/admin/chats">Chat Monitoring</RouterLink>
        <RouterLink class="nav-item" to="/admin/logs">System Logs</RouterLink>
        <RouterLink class="nav-item" to="/admin/pricing">Model Pricing</RouterLink>
        <RouterLink class="nav-item" to="/admin/settings">Settings</RouterLink>
      </nav>
    </aside>

    <div class="content">
      <header class="topbar">
        <div>
          <div class="topbar-title">Admin Dashboard</div>
          <div class="topbar-sub">Governance, safety, and usage tracking</div>
        </div>
        <div class="topbar-right">
          <button class="pill" @click="logout">Logout</button>
        </div>
      </header>

      <main class="main">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<script>
import { clearAdminFlag, clearToken, clearUserInfo } from "../api";

export default {
  name: "AdminLayout",
  data() {
    return {
      sidebarCollapsed: false
    };
  },
  methods: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed;
    },
    logout() {
      clearToken();
      clearUserInfo();
      clearAdminFlag();
      this.$router.push("/auth/admin");
    }
  }
};
</script>
