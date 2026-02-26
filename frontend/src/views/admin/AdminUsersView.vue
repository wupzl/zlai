<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>User Management</h2>
        <div class="meta">Manage accounts and balances</div>
      </div>
      <button class="cta" @click="loadUsers">Refresh</button>
      <button class="ghost" @click="exportUsers">Export CSV</button>
    </header>

    <div class="admin-toolbar">
      <div class="filters">
        <input v-model="keyword" placeholder="Search username / nickname" @keyup.enter="onSearch" />
        <select v-model.number="size" @change="onSearch">
          <option :value="10">10</option>
          <option :value="20">20</option>
          <option :value="50">50</option>
        </select>
      </div>
      <div class="pagination">
        <button class="ghost" :disabled="page <= 1" @click="page--; loadUsers()">Prev</button>
        <span>Page {{ page }} / {{ totalPages }}</span>
        <button class="ghost" :disabled="page >= totalPages" @click="page++; loadUsers()">Next</button>
      </div>
    </div>

    <div class="card">
      <div v-for="u in users" :key="u.id" class="list-item">
        <div>
          <div class="session-title">{{ u.username }}</div>
          <div class="session-meta">
            role: {{ u.role }} - balance: {{ u.tokenBalance ?? u.token_balance ?? u.balance ?? 0 }}
          </div>
        </div>
        <div class="list-actions">
          <button @click="openEdit(u)">View</button>
          <button @click="deleteUser(u.id)">Delete</button>
        </div>
      </div>
      <div v-if="!users.length" class="empty">No users found.</div>
    </div>

    <div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h3>User Detail</h3>
          <button class="ghost" @click="closeModal">Close</button>
        </div>
        <div v-if="selected" class="modal-body">
          <div class="session-meta">
            id: {{ selected.id }} | username: {{ selected.username }} | balance: {{ selected.tokenBalance ?? selected.token_balance ?? selected.balance ?? 0 }}
          </div>
          <div class="form-grid">
            <input v-model="edit.nickname" placeholder="Nickname" />
            <input v-model="edit.avatarUrl" placeholder="Avatar URL" />
            <input v-model="edit.role" placeholder="Role (USER/ADMIN)" />
            <input v-model="edit.status" placeholder="Status (ACTIVE/LOCKED)" />
          </div>
          <div class="form-grid">
            <input v-model.number="balanceDelta" type="number" placeholder="Balance delta" />
            <input v-model="resetPassword" type="password" placeholder="Reset password" />
          </div>
        </div>
        <div class="modal-actions">
          <button @click="updateUser">Save</button>
          <button @click="updateBalance">Adjust Balance</button>
          <button @click="resetUserPassword">Reset Password</button>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, API_BASE } from "../../api";

export default {
  name: "AdminUsersView",
  data() {
    return {
      users: [],
      status: "",
      selected: null,
      edit: { nickname: "", avatarUrl: "", role: "", status: "" },
      balanceDelta: 0,
      resetPassword: "",
      keyword: "",
      page: 1,
      size: 20,
      totalPages: 1,
      showModal: false
    };
  },
  mounted() {
    this.loadUsers();
  },
  methods: {
    async loadUsers() {
      try {
        const query = new URLSearchParams();
        query.set("page", this.page);
        query.set("size", this.size);
        if (this.keyword) query.set("keyword", this.keyword);
        const data = await apiRequest(`/api/admin/users?${query.toString()}`);
        this.users = data.content || data.records || data || [];
        this.totalPages = data.totalPages || Math.max(1, Math.ceil((data.totalElements || this.users.length) / this.size));
      } catch (e) {
        this.status = e.message;
      }
    },
    async exportUsers() {
      try {
        const params = new URLSearchParams();
        if (this.keyword) params.set("keyword", this.keyword);
        await this.downloadCsv(`/api/admin/users/export?${params.toString()}`, "users.csv");
      } catch (e) {
        this.status = e.message;
      }
    },
    onSearch() {
      this.page = 1;
      this.loadUsers();
    },
    openEdit(u) {
      this.selected = u;
      this.edit = {
        nickname: u.nickname || "",
        avatarUrl: u.avatarUrl || "",
        role: u.role || "",
        status: u.status || ""
      };
      this.balanceDelta = 0;
      this.resetPassword = "";
      this.showModal = true;
    },
    closeModal() {
      this.showModal = false;
    },
    async updateUser() {
      if (!this.selected) return;
      try {
        await apiRequest(`/api/admin/users/${this.selected.id}`, {
          method: "PUT",
          body: JSON.stringify(this.edit)
        });
        this.status = "Updated";
        this.loadUsers();
      } catch (e) {
        this.status = e.message;
      }
    },
    async updateBalance() {
      if (!this.selected) return;
      try {
        await apiRequest(`/api/admin/users/${this.selected.id}/balance?delta=${this.balanceDelta}`, {
          method: "PUT"
        });
        this.status = "Balance updated";
        this.loadUsers();
      } catch (e) {
        this.status = e.message;
      }
    },
    async resetUserPassword() {
      if (!this.selected || !this.resetPassword) return;
      try {
        await apiRequest(`/api/admin/users/${this.selected.id}/reset-password?newPassword=${encodeURIComponent(this.resetPassword)}`, {
          method: "PUT"
        });
        this.status = "Password reset";
        this.resetPassword = "";
      } catch (e) {
        this.status = e.message;
      }
    },
    async deleteUser(id) {
      try {
        await apiRequest(`/api/admin/users/${id}`, { method: "DELETE" });
        this.status = "Deleted";
        if (this.selected && this.selected.id === id) {
          this.selected = null;
        }
        if (this.users.length === 1 && this.page > 1) {
          this.page -= 1;
        }
        this.loadUsers();
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

