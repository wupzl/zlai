const API_BASE = (import.meta.env.VITE_API_BASE || "").replace(/\/$/, "");

export function getToken() {
  return localStorage.getItem("accessToken") || "";
}

export function setToken(token) {
  if (!token) {
    localStorage.removeItem("accessToken");
    return;
  }
  localStorage.setItem("accessToken", token);
}

export function clearToken() {
  localStorage.removeItem("accessToken");
}

export function getRefreshToken() {
  return localStorage.getItem("refreshToken") || "";
}

export function setRefreshToken(token) {
  if (!token) {
    localStorage.removeItem("refreshToken");
    return;
  }
  localStorage.setItem("refreshToken", token);
}

export function clearRefreshToken() {
  localStorage.removeItem("refreshToken");
}

export function setUserInfo(info) {
  if (!info) {
    localStorage.removeItem("userInfo");
    return;
  }
  localStorage.setItem("userInfo", JSON.stringify(info));
}

export function getUserInfo() {
  const raw = localStorage.getItem("userInfo");
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
  localStorage.removeItem("userInfo");
}

export function clearAuthState() {
  clearToken();
  clearRefreshToken();
  clearUserInfo();
}

let refreshPromise = null;

async function refreshAccessToken() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return null;
  }
  const res = await fetch(`${API_BASE}/api/user/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken })
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || (data && data.code && data.code !== 200)) {
    return null;
  }
  const accessToken = data?.data?.accessToken || data?.accessToken || "";
  if (accessToken) {
    setToken(accessToken);
    return accessToken;
  }
  return null;
}

function redirectToLogin() {
  const admin = window.location.pathname.startsWith("/admin") || isAdmin();
  clearAuthState();
  const target = admin ? "/auth/admin" : "/auth/user";
  if (window.location.pathname !== target) {
    window.location.href = target;
  }
}

export async function authFetch(path, options = {}) {
  const headers = {
    ...(options.headers || {})
  };
  const token = getToken();
  if (token && !headers.Authorization) {
    headers.Authorization = `Bearer ${token}`;
  }

  let res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  });

  if (res.status === 401 && !path.includes("/api/user/refresh")) {
    if (!refreshPromise) {
      refreshPromise = refreshAccessToken().finally(() => {
        refreshPromise = null;
      });
    }
    const newToken = await refreshPromise;
    if (newToken) {
      headers.Authorization = `Bearer ${newToken}`;
      res = await fetch(`${API_BASE}${path}`, {
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

export { API_BASE };
