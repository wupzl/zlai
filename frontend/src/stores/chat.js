import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { 
  buildChildMap, 
  initBranchSelections, 
  resolveBranchMessages, 
  resolveDefaultLeaf,
  normalizeInterruptedMessages
} from "../utils/chatTree";
import { streamChat } from "../utils/sse";

export const useChatStore = defineStore("chat", () => {
  const currentChatId = ref(null);
  const allMessages = ref([]);
  const selectedBranchMap = ref({});
  const isStreaming = ref(false);
  const streamController = ref(null);

  // Getters
  const childMap = computed(() => buildChildMap(allMessages.value));
  
  const activeMessages = computed(() => {
    const leafId = resolveDefaultLeaf(childMap.value, selectedBranchMap.value, "__root__");
    return resolveBranchMessages(allMessages.value, leafId);
  });

  const lastMessage = computed(() => {
    const msgs = activeMessages.value;
    return msgs.length > 0 ? msgs[msgs.length - 1] : null;
  });

  // Actions
  const setChat = (chatId, messages = []) => {
    currentChatId.value = chatId;
    allMessages.value = normalizeInterruptedMessages(messages);
    
    // Default selection to the last message's path
    if (messages.length > 0) {
      const last = messages[messages.length - 1];
      selectedBranchMap.value = initBranchSelections(messages, last.messageId);
    } else {
      selectedBranchMap.value = {};
    }
  };

  const addMessage = (message) => {
    allMessages.value.push(message);
    const parentKey = message.parentMessageId || "__root__";
    selectedBranchMap.value[parentKey] = message.messageId;
  };

  const updateMessageContent = (messageId, content) => {
    const msg = allMessages.value.find(m => m.messageId === messageId);
    if (msg) {
      msg.content = content;
    }
  };

  const switchBranch = (parentMessageId, targetMessageId) => {
    selectedBranchMap.value[parentMessageId || "__root__"] = targetMessageId;
  };

  const stopStream = () => {
    if (streamController.value) {
      streamController.value.close();
      streamController.value = null;
    }
    isStreaming.value = false;
  };

  const sendStreamRequest = async (payload, onUpdate) => {
    if (isStreaming.value) return;
    
    isStreaming.value = true;
    let lastUpdateAt = 0;
    const UPDATE_INTERVAL_MS = 32; // ~30fps rendering cap

    try {
      const { close, done } = await streamChat("/api/chat/stream", {
        ...payload,
        chatId: currentChatId.value
      }, (event, data) => {
        if (event === "message") {
          try {
            const parsed = JSON.parse(data);
            const now = Date.now();
            
            // Limit UI updates to prevent UI jank
            if (now - lastUpdateAt >= UPDATE_INTERVAL_MS || parsed.status === "COMPLETED") {
              onUpdate?.(parsed);
              lastUpdateAt = now;
            }
          } catch (e) {
            onUpdate?.({ content: data });
          }
        }
      });

      streamController.value = { close };
      await done;
    } finally {
      isStreaming.value = false;
      streamController.value = null;
    }
  };

  return {
    currentChatId,
    allMessages,
    selectedBranchMap,
    isStreaming,
    activeMessages,
    lastMessage,
    setChat,
    addMessage,
    updateMessageContent,
    switchBranch,
    sendStreamRequest,
    stopStream
  };
});
