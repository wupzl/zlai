import { createRouter, createWebHistory } from "vue-router";
import UserAuthView from "./views/UserAuthView.vue";
import AdminAuthView from "./views/AdminAuthView.vue";
import UserLayout from "./views/UserLayout.vue";
import AdminLayout from "./views/AdminLayout.vue";
import ChatView from "./views/ChatView.vue";
import AgentsView from "./views/AgentsView.vue";
import GptsView from "./views/GptsView.vue";
import RagView from "./views/RagView.vue";
import ProfileView from "./views/ProfileView.vue";
import AdminDashboardView from "./views/admin/AdminDashboardView.vue";
import AdminUsersView from "./views/admin/AdminUsersView.vue";
import AdminGptsView from "./views/admin/AdminGptsView.vue";
import AdminChatsView from "./views/admin/AdminChatsView.vue";
import AdminLogsView from "./views/admin/AdminLogsView.vue";
import AdminAgentsView from "./views/admin/AdminAgentsView.vue";
import AdminRagView from "./views/admin/AdminRagView.vue";
import AdminPricingView from "./views/admin/AdminPricingView.vue";
import AdminSettingsView from "./views/admin/AdminSettingsView.vue";
import { getToken, isAdmin } from "./api";

const routes = [
  {
    path: "/",
    redirect: () => (getToken() ? "/app/chat" : "/auth/user")
  },
  { path: "/auth/user", component: UserAuthView },
  { path: "/auth/admin", component: AdminAuthView },
  {
    path: "/app",
    component: UserLayout,
    children: [
      { path: "chat", component: ChatView },
      { path: "chat/:chatId", component: ChatView },
      { path: "rag/chat", component: ChatView },
      { path: "rag/chat/:chatId", component: ChatView },
      { path: "gpt/:gptId/chat", component: ChatView },
      { path: "gpt/:gptId/chat/:chatId", component: ChatView },
      { path: "agent/:agentId/chat", component: ChatView },
      { path: "agent/:agentId/chat/:chatId", component: ChatView },
      { path: "agents", component: AgentsView },
      { path: "gpts", component: GptsView },
      { path: "rag", component: RagView },
      { path: "profile", component: ProfileView },
      { path: "", redirect: "/app/chat" }
    ]
  },
  {
    path: "/admin",
    component: AdminLayout,
    children: [
      { path: "dashboard", component: AdminDashboardView },
      { path: "users", component: AdminUsersView },
      { path: "gpts", component: AdminGptsView },
      { path: "agents", component: AdminAgentsView },
      { path: "rag", component: AdminRagView },
      { path: "chats", component: AdminChatsView },
      { path: "logs", component: AdminLogsView },
      { path: "pricing", component: AdminPricingView },
      { path: "settings", component: AdminSettingsView },
      { path: "", redirect: "/admin/dashboard" }
    ]
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

router.beforeEach((to) => {
  if (to.path.startsWith("/auth")) {
    return true;
  }
  if (to.path.startsWith("/admin")) {
    if (!getToken() || !isAdmin()) {
      return "/auth/admin";
    }
    return true;
  }
  if (!getToken()) {
    return "/auth/user";
  }
  return true;
});

export default router;
