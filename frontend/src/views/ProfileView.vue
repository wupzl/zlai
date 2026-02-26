<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Profile</h2>
        <div class="meta">Manage your account details</div>
      </div>
    </header>

    <div class="grid">
      <div class="card">
        <h3>Account</h3>
        <div class="session-meta">Username: {{ userInfo?.username || "Unknown" }}</div>
        <div class="session-meta">Nickname: {{ userInfo?.nickname || "-" }}</div>
        <div class="session-meta">Role: {{ userInfo?.role || "USER" }}</div>
      </div>

      <form class="card" @submit.prevent="updateProfile">
        <h3>Update Profile</h3>
        <label>Nickname</label>
        <input v-model="form.nickname" placeholder="New nickname" />
        <label>Avatar URL</label>
        <input v-model="form.avatarUrl" placeholder="Avatar URL" />
        <button class="cta" type="submit">Save</button>
      </form>

      <form class="card" @submit.prevent="changePassword">
        <h3>Change Password</h3>
        <input v-model="password.oldPassword" type="password" placeholder="Old password" />
        <input v-model="password.newPassword" type="password" placeholder="New password" />
        <input v-model="password.confirmPassword" type="password" placeholder="Confirm password" />
        <button class="cta" type="submit">Update Password</button>
      </form>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, getUserInfo, setUserInfo } from "../api";

export default {
  name: "ProfileView",
  data() {
    return {
      status: "",
      userInfo: getUserInfo(),
      form: { nickname: "", avatarUrl: "" },
      password: { oldPassword: "", newPassword: "", confirmPassword: "" }
    };
  },
  methods: {
    async updateProfile() {
      try {
        const payload = {
          nickname: this.form.nickname || null,
          avatarUrl: this.form.avatarUrl || null
        };
        await apiRequest("/api/user/update", {
          method: "PUT",
          body: JSON.stringify(payload)
        });
        const updated = { ...(this.userInfo || {}), ...payload };
        setUserInfo(updated);
        this.userInfo = updated;
        this.status = "Profile updated";
      } catch (e) {
        this.status = e.message;
      }
    },
    async changePassword() {
      try {
        await apiRequest("/api/user/change-password", {
          method: "PUT",
          body: JSON.stringify(this.password)
        });
        this.status = "Password updated";
        this.password = { oldPassword: "", newPassword: "", confirmPassword: "" };
      } catch (e) {
        this.status = e.message;
      }
    }
  }
};
</script>