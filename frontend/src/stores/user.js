import { defineStore } from "pinia";
import { ref, computed } from "vue";

const AUTH_CACHE_KEY = "userInfo";
const AUTH_CHECKED_AT_KEY = "authCheckedAt";

export const useUserStore = defineStore("user", () => {
  const userInfo = ref(null);
  const authCheckedAt = ref(null);

  const syncFromStorage = () => {
    const raw = localStorage.getItem(AUTH_CACHE_KEY);
    if (!raw) {
      userInfo.value = null;
      authCheckedAt.value = null;
      return;
    }

    try {
      userInfo.value = JSON.parse(raw);
      const persistedCheckedAt = Number(localStorage.getItem(AUTH_CHECKED_AT_KEY));
      authCheckedAt.value = Number.isFinite(persistedCheckedAt) ? persistedCheckedAt : null;
    } catch {
      userInfo.value = null;
      authCheckedAt.value = null;
      localStorage.removeItem(AUTH_CACHE_KEY);
      localStorage.removeItem(AUTH_CHECKED_AT_KEY);
    }
  };

  // Initialize from localStorage
  syncFromStorage();

  const setUserInfo = (info) => {
    if (!info) {
      userInfo.value = null;
      authCheckedAt.value = null;
      localStorage.removeItem(AUTH_CACHE_KEY);
      localStorage.removeItem(AUTH_CHECKED_AT_KEY);
      return;
    }
    userInfo.value = info;
    authCheckedAt.value = Date.now();
    localStorage.setItem(AUTH_CACHE_KEY, JSON.stringify(info));
    localStorage.setItem(AUTH_CHECKED_AT_KEY, String(authCheckedAt.value));
  };

  const userRole = computed(() => String(userInfo.value?.role || "").toUpperCase());
  const isAdmin = computed(() => userRole.value === "ADMIN");
  const isAuthenticated = computed(() => !!userInfo.value);

  const clearUserInfo = () => {
    setUserInfo(null);
  };

  return {
    userInfo,
    authCheckedAt,
    userRole,
    isAdmin,
    isAuthenticated,
    syncFromStorage,
    setUserInfo,
    clearUserInfo
  };
});
