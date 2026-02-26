<template>
  <section class="panel auth-panel">
    <header class="panel-header">
      <div>
        <h2>User Access</h2>
        <div class="meta">Sign in to your workspace</div>
      </div>
    </header>

    <div class="grid">
      <form class="card" @submit.prevent="onLogin">
        <h3>Login</h3>
        <input v-model="login.username" placeholder="Username" />
        <input v-model="login.password" type="password" placeholder="Password" />
        <button class="cta" type="submit">Login</button>
      </form>

      <form class="card" @submit.prevent="onRegister">
        <h3>Register</h3>
        <input v-model="register.username" placeholder="Username" />
        <input v-model="register.password" type="password" placeholder="Password" />
        <input v-model="register.nickname" placeholder="Nickname" />
        <button class="cta" type="submit">Register</button>
      </form>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, setToken, setRefreshToken, setUserInfo, setAdminFlag } from "../api";

export default {
  name: "UserAuthView",
  data() {
    return {
      status: "",
      login: { username: "", password: "" },
      register: { username: "", password: "", nickname: "" }
    };
  },
  methods: {
    async onLogin() {
      try {
        const data = await apiRequest("/api/auth/login", {
          method: "POST",
          body: JSON.stringify(this.login)
        });
        setToken(data.accessToken);
        setRefreshToken(data.refreshToken);
        setUserInfo(data.userInfo || null);
        setAdminFlag(false);
        this.status = "Login success";
        this.$router.push("/app/chat");
      } catch (e) {
        this.status = e.message;
      }
    },
    async onRegister() {
      try {
        await apiRequest("/api/auth/register", {
          method: "POST",
          body: JSON.stringify(this.register)
        });
        this.status = "Register success, please login";
      } catch (e) {
        this.status = e.message;
      }
    }
  }
};
</script>
