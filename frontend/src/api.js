import { useUserStore } from "./stores/user";
import { useNotificationStore } from "./stores/notification";
import { pinia } from "./stores/pinia";

const API_BASE = (import.meta.env.VITE_API_BASE || "").replace(/\/$/, "");

const DEFAULT_FETCH_OPTIONS = {
  credentials: "include"
};

const AUTH_CACHE_TTL_MS = 2 * 60 * 1000;

let refreshPromise = null;
let sessionPromise = null;

/**
 * Legacy compatibility / internal helpers
 * Now delegates to userStore where possible
 */
function getStore() {
  try {
    const store = useUserStore(pinia);
    store.syncFromStorage?.();
    return store;
  } catch (e) {
    // Fail-safe for non-vue context if needed
    return null;
  }
}

function getNotificationStore() {
  try {
    return useNotificationStore(pinia);
  } catch (e) {
    return null;
  }
}

export function setUserInfo(info) {
  getStore()?.setUserInfo(info);
}

export function getUserInfo() {
  return getStore()?.userInfo || null;
}

export function getUserRole() {
  return getStore()?.userRole || "";
}

export function isAdmin() {
  return getStore()?.isAdmin || false;
}

export function clearUserInfo() {
  getStore()?.clearUserInfo();
}

export function clearAuthState() {
  clearUserInfo();
}

function isAuthCacheFresh() {
  const store = getStore();
  if (!store) return false;
  const checkedAt = store.authCheckedAt || 0;
  return checkedAt > 0 && Date.now() - checkedAt < AUTH_CACHE_TTL_MS;
}

async function fetchSessionUser() {
  const res = await fetch(`${API_BASE}/api/user/check`, {
    ...DEFAULT_FETCH_OPTIONS,
    method: "GET"
  });
  if (!res.ok) {
    clearAuthState();
    return null;
  }
  const data = await res.json().catch(() => ({}));
  if (!data || (data.code && data.code !== 200)) {
    clearAuthState();
    return null;
  }
  const user = data.data || null;
  setUserInfo(user);
  return user;
}

function ensureSessionProbe() {
  if (!sessionPromise) {
    sessionPromise = fetchSessionUser()
      .catch(() => {
        clearAuthState();
        return null;
      })
      .finally(() => {
        sessionPromise = null;
      });
  }
  return sessionPromise;
}

function resolveAuthTarget() {
  const admin = window.location.pathname.startsWith("/admin") || isAdmin();
  return admin ? "/auth/admin" : "/auth/user";
}

function redirectToLogin() {
  const target = resolveAuthTarget();
  clearAuthState();
  if (window.location.pathname !== target) {
    window.location.href = target;
  }
}

async function refreshAccessToken() {
  const res = await fetch(`${API_BASE}/api/user/refresh`, {
    ...DEFAULT_FETCH_OPTIONS,
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{}"
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || (data && data.code && data.code !== 200)) {
    return false;
  }
  return true;
}

/**
 * T002: Enhanced authFetch with interceptor-like logic
 */
export async function authFetch(path, options = {}) {
  const headers = {
    ...(options.headers || {})
  };

  const executeFetch = async () => {
    return fetch(`${API_BASE}${path}`, {
      ...DEFAULT_FETCH_OPTIONS,
      ...options,
      headers
    });
  };

  let res = await executeFetch();

  // Handle 401 Unauthorized (Token expired)
  if (res.status === 401 && !path.includes("/api/user/refresh")) {
    if (!refreshPromise) {
      refreshPromise = refreshAccessToken().finally(() => {
        refreshPromise = null;
      });
    }
    const refreshed = await refreshPromise;
    if (refreshed) {
      res = await executeFetch();
    } else {
      redirectToLogin();
      throw new Error("Session expired. Please login again.");
    }
  }

  // Handle terminal 401/403
  if (res.status === 401 || res.status === 403) {
    redirectToLogin();
  }

  return res;
}

/**
 * Unified API Request wrapper with global error handling
 */
export async function apiRequest(path, options = {}) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {})
  };
  const res = await authFetch(path, {
    ...options,
    headers
  });
  
  const data = await res.json().catch(() => ({}));
  
  if (!res.ok || (data && data.code && data.code !== 200)) {
    const errMsg = data?.message || `Request failed with status ${res.status}`;
    // If backend returns 401 in body even if HTTP was 200/other
    if (data?.code === 401 || data?.code === 403) {
      redirectToLogin();
    }
    // T008: Global error notification
    getNotificationStore()?.error(errMsg);
    throw new Error(errMsg);
  }
  return data?.data ?? data;
}

export async function ensureAuthState(options = {}) {
  const {
    force = false,
    allowCached = true,
    background = false
  } = options;

  const cached = getUserInfo();
  const fresh = isAuthCacheFresh();

  if (!force && allowCached && cached && fresh) {
    return cached;
  }

  if (!force && allowCached && cached && background) {
    ensureSessionProbe();
    return cached;
  }

  return ensureSessionProbe();
}

export async function logout() {
  try {
    await fetch(`${API_BASE}/api/user/logout`, {
      ...DEFAULT_FETCH_OPTIONS,
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    });
  } catch (e) {
    console.error("Logout request failed", e);
  } finally {
    clearAuthState();
  }
}

export { API_BASE };
