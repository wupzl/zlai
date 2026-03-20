import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("api auth state", () => {
  let api;

  beforeEach(async () => {
    localStorage.clear();
    vi.resetModules();
    vi.unstubAllGlobals();
    global.fetch = vi.fn();
    api = await import("./api.js");
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("reuses fresh cached user without probing session", async () => {
    api.setUserInfo({ id: 1, role: "USER" });

    const user = await api.ensureAuthState();

    expect(user).toEqual({ id: 1, role: "USER" });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("probes session when cached state is stale", async () => {
    api.setUserInfo({ id: 1, role: "USER" });
    localStorage.setItem("authCheckedAt", String(Date.now() - 5 * 60 * 1000));
    fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ code: 200, data: { id: 2, role: "ADMIN" } })
    });

    const user = await api.ensureAuthState();

    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/api/user/check"), expect.objectContaining({
      method: "GET",
      credentials: "include"
    }));
    expect(user).toEqual({ id: 2, role: "ADMIN" });
    expect(api.getUserInfo()).toEqual({ id: 2, role: "ADMIN" });
  });

  it("keeps cached user and refreshes in background when requested", async () => {
    api.setUserInfo({ id: 1, role: "USER" });
    localStorage.setItem("authCheckedAt", String(Date.now() - 5 * 60 * 1000));
    fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ code: 200, data: { id: 3, role: "USER" } })
    });

    const user = await api.ensureAuthState({ background: true });
    await Promise.resolve();
    await Promise.resolve();

    expect(user).toEqual({ id: 1, role: "USER" });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(api.getUserInfo()).toEqual({ id: 3, role: "USER" });
  });

  it("logout clears cached auth state even if request fails", async () => {
    api.setUserInfo({ id: 1, role: "USER" });
    fetch.mockRejectedValueOnce(new Error("network"));

    await api.logout();

    expect(api.getUserInfo()).toBeNull();
    expect(localStorage.getItem("authCheckedAt")).toBeNull();
  });
});
