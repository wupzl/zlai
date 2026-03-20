import { createRouter, createWebHistory } from "vue-router";
import { ensureAuthState } from "./api";

const UserAuthView = () => import("./views/UserAuthView.vue");
const AdminAuthView = () => import("./views/AdminAuthView.vue");
const UserLayout = () => import("./views/UserLayout.vue");
const AdminLayout = () => import("./views/AdminLayout.vue");
const ChatView = () => import("./views/ChatView.vue");
const AgentsView = () => import("./views/AgentsView.vue");
const GptsView = () => import("./views/GptsView.vue");
const RagView = () => import("./views/RagView.vue");
const ProfileView = () => import("./views/ProfileView.vue");
const AdminDashboardView = () => import("./views/admin/AdminDashboardView.vue");
const AdminUsersView = () => import("./views/admin/AdminUsersView.vue");
const AdminGptsView = () => import("./views/admin/AdminGptsView.vue");
const AdminChatsView = () => import("./views/admin/AdminChatsView.vue");
const AdminLogsView = () => import("./views/admin/AdminLogsView.vue");
const AdminAgentsView = () => import("./views/admin/AdminAgentsView.vue");
const AdminSkillsView = () => import("./views/admin/AdminSkillsView.vue");
const AdminRagView = () => import("./views/admin/AdminRagView.vue");
const AdminPricingView = () => import("./views/admin/AdminPricingView.vue");
const AdminSettingsView = () => import("./views/admin/AdminSettingsView.vue");

const routes = [
  {
    path: "/",
    redirect: "/app/chat"
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
      { path: "skills", component: AdminSkillsView },
      { path: "rag", component: AdminRagView },
      { path: "chats", component: AdminChatsView },
      { path: "logs", component: AdminLogsView },
      { path: "pricing", component: AdminPricingView },
      { path: "settings", component: AdminSettingsView },
      { path: "", redirect: "/admin/dashboard" }
    ]
  }
];

export async function resolveRouteAccess(to, loadUser = ensureAuthState) {
  const user = await loadUser({
    allowCached: true,
    background: !to.path.startsWith("/auth")
  });
  if (to.path.startsWith("/auth")) {
    if (user) {
      return user.role === "ADMIN" ? "/admin/dashboard" : "/app/chat";
    }
    return true;
  }
  if (to.path.startsWith("/admin")) {
    if (!user || user.role !== "ADMIN") {
      return "/auth/admin";
    }
    return true;
  }
  if (!user) {
    return "/auth/user";
  }
  return true;
}

export function createAppRouter(history = createWebHistory()) {
  const router = createRouter({
    history,
    routes
  });

  router.beforeEach((to) => resolveRouteAccess(to));
  return router;
}

const router = createAppRouter();

export default router;
