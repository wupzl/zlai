const API_BASE = (import.meta.env.VITE_API_BASE || "").replace(/\/$/, "");

const DEFAULT_FETCH_OPTIONS = {
  credentials: "include"
};

const AUTH_CACHE_KEY = "userInfo";
const AUTH_CHECKED_AT_KEY = "authCheckedAt";
const AUTH_CACHE_TTL_MS = 2 * 60 * 1000;

let refreshPromise = null;
let sessionPromise = null;

export function setUserInfo(info) {
  if (!info) {
    localStorage.removeItem(AUTH_CACHE_KEY);
    localStorage.removeItem(AUTH_CHECKED_AT_KEY);
    return;
  }
  localStorage.setItem(AUTH_CACHE_KEY, JSON.stringify(info));
  localStorage.setItem(AUTH_CHECKED_AT_KEY, String(Date.now()));
}

export function getUserInfo() {
  const raw = localStorage.getItem(AUTH_CACHE_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function getUserRole() {
  return String(getUserInfo()?.role || "").toUpperCase();
}

export function isAdmin() {
  return getUserRole() === "ADMIN";
}

export function clearUserInfo() {
  localStorage.removeItem(AUTH_CACHE_KEY);
  localStorage.removeItem(AUTH_CHECKED_AT_KEY);
}

export function clearAuthState() {
  clearUserInfo();
}

function getAuthCheckedAt() {
  const raw = localStorage.getItem(AUTH_CHECKED_AT_KEY);
  const value = Number(raw);
  return Number.isFinite(value) ? value : 0;
}

function isAuthCacheFresh() {
  const checkedAt = getAuthCheckedAt();
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

export async function authFetch(path, options = {}) {
  const headers = {
    ...(options.headers || {})
  };

  let res = await fetch(`${API_BASE}${path}`, {
    ...DEFAULT_FETCH_OPTIONS,
    ...options,
    headers
  });

  if (res.status === 401 && !path.includes("/api/user/refresh")) {
    if (!refreshPromise) {
      refreshPromise = refreshAccessToken().finally(() => {
        refreshPromise = null;
      });
    }
    const refreshed = await refreshPromise;
    if (refreshed) {
      res = await fetch(`${API_BASE}${path}`, {
        ...DEFAULT_FETCH_OPTIONS,
        ...options,
        headers
      });
    } else {
      redirectToLogin();
      throw new Error("Unauthorized");
    }
  }

  if (path.includes("/api/user/refresh") && res.status === 401) {
    redirectToLogin();
    throw new Error("Unauthorized");
  }

  if (res.status === 401 || res.status === 403) {
    redirectToLogin();
  }

  return res;
}

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
    const errMsg = data?.message || "Request failed";
    if (res.status === 401 || res.status === 403 || data?.code === 401 || data?.code === 403) {
      redirectToLogin();
    }
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
  } catch {
  } finally {
    clearAuthState();
  }
}

export { API_BASE };
