<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>System Logs</h2>
        <div class="meta">Authentication, usage, and system actions</div>
      </div>
      <button class="cta" @click="loadLogs">Refresh</button>
      <button class="ghost" @click="exportLogs">Export CSV</button>
    </header>

    <div class="admin-log-toolbar">
      <div class="tabs">
        <button :class="{ active: activeTab === 'all' }" @click="activeTab = 'all'">All</button>
        <button :class="{ active: activeTab === 'login' }" @click="activeTab = 'login'">Login</button>
        <button :class="{ active: activeTab === 'token' }" @click="activeTab = 'token'">Token</button>
        <button :class="{ active: activeTab === 'system' }" @click="activeTab = 'system'">System</button>
      </div>
      <div class="filters">
        <input v-model="filter.userId" placeholder="User ID" />
        <input v-model="filter.keyword" placeholder="Keyword" />
        <input v-model="filter.startTime" type="datetime-local" />
        <input v-model="filter.endTime" type="datetime-local" />
        <select v-model="filter.status">
          <option value="">Any status</option>
          <option value="SUCCESS">SUCCESS</option>
          <option value="FAILED">FAILED</option>
        </select>
        <select v-model.number="size" @change="loadLogs">
          <option :value="10">10</option>
          <option :value="20">20</option>
          <option :value="50">50</option>
        </select>
      </div>
    </div>

    <div class="admin-toolbar">
      <div></div>
      <div class="pagination">
        <button class="ghost" :disabled="page <= 1" @click="page--; loadLogs()">Prev</button>
        <span>Page {{ page }} / {{ currentTotalPages }}</span>
        <button class="ghost" :disabled="page >= currentTotalPages" @click="page++; loadLogs()">Next</button>
      </div>
    </div>

    <div class="admin-log-layout">
      <div class="card admin-log-list">
        <div v-for="item in filteredLogs" :key="item.key" class="log-row"
             :class="{ active: selectedLog && selectedLog.key === item.key }"
             @click="selectLog(item)">
          <div class="log-type">{{ item.typeLabel }}</div>
          <div class="log-main">
            <div class="log-title">{{ item.title }}</div>
            <div class="log-meta">{{ item.meta }}</div>
          </div>
          <div class="log-status" :class="item.statusClass">{{ item.statusText }}</div>
        </div>
        <div v-if="!filteredLogs.length" class="empty">No logs found.</div>
      </div>

      <div class="card admin-log-detail">
        <h3>Log Detail</h3>
        <div v-if="selectedLog">
          <div class="detail-row"><span>Type</span><b>{{ selectedLog.typeLabel }}</b></div>
          <div class="detail-row"><span>Time</span><b>{{ selectedLog.time || "-" }}</b></div>
          <div class="detail-row"><span>User</span><b>{{ selectedLog.user || "-" }}</b></div>
          <div class="detail-row"><span>Status</span><b>{{ selectedLog.statusText }}</b></div>
          <pre class="detail-json">{{ selectedLog.raw }}</pre>
        </div>
        <div v-else class="empty">Select a log to inspect.</div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest } from "../../api";

export default {
  name: "AdminLogsView",
  data() {
    return {
      loginLogs: [],
      tokens: [],
      systemLogs: [],
      status: "",
      activeTab: "all",
      filter: { userId: "", keyword: "", status: "", startTime: "", endTime: "" },
      selectedLog: null,
      page: 1,
      size: 20,
      pageMeta: { login: 1, token: 1, system: 1 }
    };
  },
  mounted() {
    this.loadLogs();
  },
  computed: {
    currentTotalPages() {
      if (this.activeTab === "login") return this.pageMeta.login || 1;
      if (this.activeTab === "token") return this.pageMeta.token || 1;
      if (this.activeTab === "system") return this.pageMeta.system || 1;
      return Math.max(this.pageMeta.login || 1, this.pageMeta.token || 1, this.pageMeta.system || 1);
    },
    mergedLogs() {
      const login = (this.loginLogs || []).map((l) => ({
        key: `login-${l.id}`,
        type: "login",
        typeLabel: "Login",
        title: `${l.username || "User"} ${l.success ? "login success" : "login failed"}`,
        meta: `${l.ipAddress || "-"} · ${l.loginTime || "-"}`,
        time: l.loginTime,
        user: l.username || l.userId,
        statusText: l.success ? "SUCCESS" : "FAILED",
        statusClass: l.success ? "ok" : "fail",
        raw: JSON.stringify(l, null, 2)
      }));
      const tokens = (this.tokens || []).map((t) => ({
        key: `token-${t.id}`,
        type: "token",
        typeLabel: "Token",
        title: `${t.model || "model"} · ${t.totalTokens || 0} tokens`,
        meta: `${t.userId || "-"} · ${t.chatId || "-"} · ${t.createdAt || "-"}`,
        time: t.createdAt,
        user: t.userId,
        statusText: "SUCCESS",
        statusClass: "ok",
        raw: JSON.stringify(t, null, 2)
      }));
      const system = (this.systemLogs || []).map((s) => ({
        key: `system-${s.id}`,
        type: "system",
        typeLabel: "System",
        title: `${s.module || "SYSTEM"} · ${s.operation || "-"}`,
        meta: `${s.requestIp || "-"} · ${s.createdAt || "-"}`,
        time: s.createdAt,
        user: s.userId,
        statusText: s.status || "UNKNOWN",
        statusClass: s.status === "SUCCESS" ? "ok" : "fail",
        raw: JSON.stringify(s, null, 2)
      }));
      return [...login, ...tokens, ...system];
    },
    filteredLogs() {
      const keyword = (this.filter.keyword || "").toLowerCase();
      const userId = (this.filter.userId || "").trim();
      const status = this.filter.status;
      return this.mergedLogs
        .filter((l) => (this.activeTab === "all" ? true : l.type === this.activeTab))
        .filter((l) => (userId ? String(l.user || "").includes(userId) : true))
        .filter((l) => (status ? l.statusText === status : true))
        .filter((l) =>
          keyword
            ? (l.title || "").toLowerCase().includes(keyword) ||
              (l.meta || "").toLowerCase().includes(keyword) ||
              (l.typeLabel || "").toLowerCase().includes(keyword)
            : true
        );
    }
  },
  methods: {
    normalizeDateTime(value) {
      if (!value) return "";
      if (value.length === 16) return `${value}:00`;
      return value;
    },
    async loadLogs() {
      try {
        const query = new URLSearchParams();
        query.set("page", this.page);
        query.set("size", this.size);
        if (this.filter.userId) query.set("userId", this.filter.userId);
        const startTime = this.normalizeDateTime(this.filter.startTime);
        const endTime = this.normalizeDateTime(this.filter.endTime);
        if (startTime) query.set("startTime", startTime);
        if (endTime) query.set("endTime", endTime);
        const loginData = await apiRequest(`/api/admin/logs/login?${query.toString()}`);
        const tokenData = await apiRequest(`/api/admin/logs/tokens?${query.toString()}`);
        const systemData = await apiRequest(`/api/admin/logs/system?${query.toString()}`);
        this.loginLogs = loginData.content || loginData.records || [];
        this.tokens = tokenData.content || tokenData.records || [];
        this.systemLogs = systemData.content || systemData.records || [];
        this.pageMeta.login = loginData.totalPages || 1;
        this.pageMeta.token = tokenData.totalPages || 1;
        this.pageMeta.system = systemData.totalPages || 1;
        this.selectedLog = null;
      } catch (e) {
        this.status = e.message;
      }
    },
    async exportLogs() {
      try {
        const params = new URLSearchParams();
        const type = this.activeTab === "all" ? "all" : this.activeTab;
        params.set("type", type);
        if (this.filter.userId) params.set("userId", this.filter.userId);
        if (this.filter.startTime) params.set("startTime", this.filter.startTime);
        if (this.filter.endTime) params.set("endTime", this.filter.endTime);
        await this.downloadCsv(`/api/admin/logs/export?${params.toString()}`, "logs.csv");
      } catch (e) {
        this.status = e.message;
      }
    },
    selectLog(item) {
      this.selectedLog = item;
    }
  ,
    async downloadCsv(path, filename) {
      const token = localStorage.getItem("accessToken") || "";
      const res = await fetch(`${import.meta.env.VITE_API_BASE || "http://localhost:8080"}${path}`, {
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
