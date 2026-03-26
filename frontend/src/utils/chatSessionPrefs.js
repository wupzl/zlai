const STORAGE_KEY = "zlai.chat.sessionPrefs";

function hasStorage() {
  return typeof window !== "undefined" && typeof window.localStorage !== "undefined";
}

export function loadChatSessionPreferences() {
  if (!hasStorage()) {
    return {
      selectedModel: "deepseek-chat",
      selectedToolModel: "",
      useStreaming: true
    };
  }
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return {
        selectedModel: "deepseek-chat",
        selectedToolModel: "",
        useStreaming: true
      };
    }
    const parsed = JSON.parse(raw);
    return {
      selectedModel: typeof parsed?.selectedModel === "string" && parsed.selectedModel.trim()
        ? parsed.selectedModel.trim()
        : "deepseek-chat",
      selectedToolModel: typeof parsed?.selectedToolModel === "string"
        ? parsed.selectedToolModel.trim()
        : "",
      useStreaming: typeof parsed?.useStreaming === "boolean"
        ? parsed.useStreaming
        : true
    };
  } catch {
    return {
      selectedModel: "deepseek-chat",
      selectedToolModel: "",
      useStreaming: true
    };
  }
}

export function saveChatSessionPreferences(prefs) {
  if (!hasStorage()) {
    return;
  }
  const current = loadChatSessionPreferences();
  const next = {
    selectedModel: typeof prefs?.selectedModel === "string" && prefs.selectedModel.trim()
      ? prefs.selectedModel.trim()
      : current.selectedModel,
    selectedToolModel: typeof prefs?.selectedToolModel === "string"
      ? prefs.selectedToolModel.trim()
      : current.selectedToolModel,
    useStreaming: typeof prefs?.useStreaming === "boolean"
      ? prefs.useStreaming
      : current.useStreaming
  };
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
}
