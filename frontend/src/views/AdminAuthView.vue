<template>
  <section class="panel auth-panel">
    <header class="panel-header">
      <div>
        <h2>Admin Access</h2>
        <div class="meta">Restricted console login</div>
      </div>
    </header>

    <form class="card" @submit.prevent="onAdminLogin">
      <h3>Admin Login</h3>
      <input v-model="admin.username" placeholder="Admin username" />
      <input v-model="admin.password" type="password" placeholder="Password" />
      <button class="cta" type="submit">Login</button>
    </form>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, setToken, setRefreshToken, setUserInfo, setAdminFlag } from "../api";

export default {
  name: "AdminAuthView",
  data() {
    return {
      status: "",
      admin: { username: "", password: "" }
    };
  },
  methods: {
    async onAdminLogin() {
      try {
        const data = await apiRequest("/api/admin/auth/login", {
          method: "POST",
          body: JSON.stringify(this.admin)
        });
        setToken(data.accessToken);
        setRefreshToken(data.refreshToken);
        setUserInfo(data.userInfo || null);
        setAdminFlag(true);
        this.status = "Admin login success";
        this.$router.push("/admin/dashboard");
      } catch (e) {
        this.status = e.message;
      }
    }
  }
};
</script>
