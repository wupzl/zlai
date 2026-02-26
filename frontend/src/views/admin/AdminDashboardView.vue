<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Admin Dashboard</h2>
        <div class="meta">Operations, safety, and usage tracking</div>
      </div>
      <button class="cta" @click="loadAll">Refresh</button>
    </header>

    <div class="admin-kpis">
      <div class="admin-kpi">
        <div class="label">Users</div>
        <div class="value">{{ stats.users }}</div>
        <div class="hint">Active accounts</div>
      </div>
      <div class="admin-kpi">
        <div class="label">GPTs</div>
        <div class="value">{{ stats.gpts }}</div>
        <div class="hint">Published + drafts</div>
      </div>
      <div class="admin-kpi">
        <div class="label">Chats</div>
        <div class="value">{{ stats.chats }}</div>
        <div class="hint">Total sessions</div>
      </div>
      <div class="admin-kpi">
        <div class="label">Logs</div>
        <div class="value">{{ stats.logs }}</div>
        <div class="hint">System events</div>
      </div>
      <div class="admin-kpi">
        <div class="label">Pricing</div>
        <div class="value">{{ stats.pricingCount }}</div>
        <div class="hint">Last update: {{ stats.pricingUpdatedAt || "-" }}</div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest } from "../../api";

export default {
  name: "AdminDashboardView",
  data() {
    return {
      status: "",
      stats: {
        users: 0,
        gpts: 0,
        chats: 0,
        logs: 0,
        pricingCount: 0,
        pricingUpdatedAt: ""
      }
    };
  },
  mounted() {
    this.loadAll();
  },
  methods: {
    async loadAll() {
      try {
        const users = await apiRequest("/api/admin/users?page=1&size=1");
        const gpts = await apiRequest("/api/admin/gpts?page=1&size=1");
        const chats = await apiRequest("/api/admin/chats/sessions?page=1&size=1");
        const logCount = await apiRequest("/api/admin/logs/count");
        const pricing = await apiRequest("/api/admin/model-pricing");
        const pricingList = Array.isArray(pricing) ? pricing : [];
        const latest = pricingList
          .map(p => p.updatedAt)
          .filter(Boolean)
          .sort()
          .slice(-1)[0] || "";
        this.stats = {
          users: users.totalElements || users.total || 0,
          gpts: gpts.totalElements || gpts.total || 0,
          chats: chats.totalElements || chats.total || 0,
          logs: logCount || 0,
          pricingCount: pricingList.length,
          pricingUpdatedAt: latest ? String(latest).slice(0, 10) : ""
        };
      } catch (e) {
        this.status = e.message;
      }
    }
  }
};
</script>
