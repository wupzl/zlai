import { beforeEach, describe, expect, it, vi } from "vitest";

const apiRequest = vi.fn();

vi.mock("../api", () => ({
  apiRequest
}));

describe("ChatView sync payload compatibility", () => {
  beforeEach(() => {
    apiRequest.mockReset();
    window.localStorage.clear();
  });

  it("keeps rag route context when creating a new session", async () => {
    const { default: ChatView } = await import("./ChatView.vue");
    const replace = vi.fn();
    const vm = {
      $route: { path: "/app/rag/chat" },
      $router: { replace },
      activeChatId: "chat-old",
      messages: [{ messageId: "m1" }],
      activeMessageId: "m1",
      childMap: { "__root__": [{ messageId: "m1" }] },
      selectedBranchMap: { "__root__": "m1" },
      editingMessageId: "m1",
      editedContent: "draft",
      ragEnabled: true,
      pendingRag: true,
      pendingGptId: "",
      pendingAgentId: "",
      syncRouteWithSession: ChatView.methods.syncRouteWithSession
    };

    ChatView.methods.createSession.call(vm);

    expect(vm.activeChatId).toBe("");
    expect(vm.messages).toEqual([]);
    expect(vm.pendingRag).toBe(true);
    expect(replace).not.toHaveBeenCalledWith("/app/chat");
  });

  it("keeps legacy plain string sync responses working", async () => {
    const { default: ChatView } = await import("./ChatView.vue");
    const vm = {
      activeChatId: "chat-1",
      selectedModel: "deepseek-chat",
      selectedToolModel: "",
      messages: [
        {
          messageId: "assistant-persisted",
          role: "assistant",
          content: "Legacy answer"
        }
      ],
      isStreaming: true,
      applyStreamChunk: vi.fn(),
      selectSession: vi.fn().mockResolvedValue(),
      loadSessions: vi.fn().mockResolvedValue(),
      syncRouteWithSession: vi.fn(),
      attachAssistantMetadata: ChatView.methods.attachAssistantMetadata
    };

    apiRequest.mockResolvedValueOnce("Legacy answer");

    await ChatView.methods.sendMessageSync.call(vm, {
      chatId: "chat-1",
      requestId: "req-legacy",
      prompt: "Say hello"
    }, "tmp-user-legacy", "tmp-assistant-legacy");

    expect(vm.applyStreamChunk).toHaveBeenCalledWith("Legacy answer", "tmp-assistant-legacy", "tmp-user-legacy");
    expect(vm.selectSession).toHaveBeenCalledWith("chat-1");
    expect(vm.messages[0].citations).toBeUndefined();
    expect(vm.messages[0].grounding).toBeUndefined();
    expect(vm.isStreaming).toBe(false);
  });

  it("extracts content from additive grounded payload without breaking sync chat", async () => {
    const { default: ChatView } = await import("./ChatView.vue");
    const vm = {
      activeChatId: "chat-1",
      selectedModel: "deepseek-chat",
      selectedToolModel: "",
      messages: [
        {
          messageId: "assistant-persisted",
          role: "assistant",
          content: "Grounded answer"
        }
      ],
      isStreaming: true,
      applyStreamChunk: vi.fn(),
      selectSession: vi.fn().mockResolvedValue(),
      loadSessions: vi.fn().mockResolvedValue(),
      syncRouteWithSession: vi.fn(),
      attachAssistantMetadata: ChatView.methods.attachAssistantMetadata
    };

    apiRequest.mockResolvedValueOnce({
      content: "Grounded answer",
      citations: [
        {
          docId: "doc-1",
          title: "distributed-systems.md",
          excerpt: "CAP theorem excerpt"
        }
      ],
      grounding: {
        status: "grounded",
        groundingScore: 0.87
      }
    });

    await ChatView.methods.sendMessageSync.call(vm, {
      chatId: "chat-1",
      requestId: "req-1",
      prompt: "Explain CAP theorem"
    }, "tmp-user-1", "tmp-assistant-1");

    expect(apiRequest).toHaveBeenCalledWith("/api/chat/message", {
      method: "POST",
      body: JSON.stringify({
        chatId: "chat-1",
        requestId: "req-1",
        prompt: "Explain CAP theorem"
      })
    });
    expect(vm.applyStreamChunk).toHaveBeenCalledWith("Grounded answer", "tmp-assistant-1", "tmp-user-1");
    expect(vm.selectSession).toHaveBeenCalledWith("chat-1");
    expect(vm.messages[0].citations).toHaveLength(1);
    expect(vm.messages[0].grounding).toEqual({
      status: "grounded",
      groundingScore: 0.87
    });
    expect(vm.isStreaming).toBe(false);
  });

  it("creates a rag session for sync rag chats", async () => {
    const { default: ChatView } = await import("./ChatView.vue");
    const vm = {
      activeChatId: "",
      pendingRag: true,
      ragEnabled: false,
      selectedModel: "claude-3-7-sonnet",
      selectedToolModel: "",
      messages: [
        {
          messageId: "assistant-persisted",
          role: "assistant",
          content: "Grounded answer"
        }
      ],
      isStreaming: true,
      applyStreamChunk: vi.fn(),
      selectSession: vi.fn().mockResolvedValue(),
      loadSessions: vi.fn().mockResolvedValue(),
      syncRouteWithSession: vi.fn(),
      attachAssistantMetadata: ChatView.methods.attachAssistantMetadata
    };

    apiRequest
      .mockResolvedValueOnce({
        chatId: "rag-chat-1",
        ragEnabled: true
      })
      .mockResolvedValueOnce({
        content: "Grounded answer",
        citations: [],
        grounding: {
          status: "grounded",
          groundingScore: 0.92
        }
      });

    await ChatView.methods.sendMessageSync.call(vm, {
      chatId: null,
      requestId: "req-rag-1",
      prompt: "Explain CAP theorem",
      useRag: true
    }, "tmp-user-rag", "tmp-assistant-rag");

    expect(apiRequest).toHaveBeenNthCalledWith(1,
      "/api/rag/session?title=RAG%20Chat&model=claude-3-7-sonnet&toolModel=",
      { method: "POST" }
    );
    expect(apiRequest).toHaveBeenNthCalledWith(2, "/api/chat/message", expect.any(Object));
    expect(vm.activeChatId).toBe("rag-chat-1");
    expect(vm.ragEnabled).toBe(true);
    expect(vm.syncRouteWithSession).toHaveBeenCalled();
  });
});
