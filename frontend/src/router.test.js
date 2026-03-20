import { describe, expect, it, vi } from "vitest";
import { resolveRouteAccess } from "./router.js";

describe("route auth guard", () => {
  it("redirects authenticated user away from auth pages", async () => {
    const target = await resolveRouteAccess(
      { path: "/auth/user" },
      vi.fn().mockResolvedValue({ role: "USER" })
    );

    expect(target).toBe("/app/chat");
  });

  it("redirects unauthenticated admin route to admin login", async () => {
    const target = await resolveRouteAccess(
      { path: "/admin/dashboard" },
      vi.fn().mockResolvedValue(null)
    );

    expect(target).toBe("/auth/admin");
  });

  it("allows cached checks on non-auth routes with background refresh", async () => {
    const loadUser = vi.fn().mockResolvedValue({ role: "USER" });

    const target = await resolveRouteAccess({ path: "/app/chat" }, loadUser);

    expect(target).toBe(true);
    expect(loadUser).toHaveBeenCalledWith({
      allowCached: true,
      background: true
    });
  });

  it("blocks non-admin user from admin routes", async () => {
    const target = await resolveRouteAccess(
      { path: "/admin/users" },
      vi.fn().mockResolvedValue({ role: "USER" })
    );

    expect(target).toBe("/auth/admin");
  });
});
