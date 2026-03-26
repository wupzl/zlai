<template>
  <div class="chat-window">
    <div v-if="messages.length === 0" class="empty">
      Start a new conversation or pick a session on the left.
    </div>

    <div
      v-for="msg in messages"
      :key="msg.messageId"
      class="message"
      :class="msg.role"
    >
      <div class="avatar">{{ msg.role === "user" ? "U" : "AI" }}</div>
      <div class="bubble">
        <div v-if="editingMessageId === msg.messageId" class="edit-box">
          <textarea
            :value="editedContent"
            rows="3"
            class="edit-textarea"
            @input="$emit('update:editedContent', $event.target.value)"
          ></textarea>
          <div class="message-actions">
            <button @click="$emit('submit-edit', msg)">Send Branch</button>
            <button @click="$emit('cancel-edit')">Cancel</button>
          </div>
        </div>
        <div
          v-else-if="msg.role === 'assistant'"
          class="markdown-content"
          v-html="renderMarkdown(msg.content)"
        ></div>
        <div v-else>{{ msg.content }}</div>
        <div v-if="msg.role === 'assistant' && hasGrounding(msg)" class="grounding-chip" :class="groundingClass(msg)">
          {{ groundingLabel(msg) }}
        </div>
        <div v-if="msg.role === 'assistant' && hasCitations(msg)" class="citation-panel">
          <div class="citation-header">Sources</div>
          <div
            v-for="(citation, index) in msg.citations"
            :key="citation.docId || citation.title || index"
            class="citation-item"
          >
            <div class="citation-title">
              <span class="citation-order">{{ index + 1 }}.</span>
              <span>{{ citation.title || citation.documentTitle || citation.docId || "Unknown source" }}</span>
            </div>
            <div v-if="citation.headings && citation.headings.length" class="citation-heading">
              {{ citation.headings.join(" / ") }}
            </div>
            <div v-else-if="citation.sectionHeading" class="citation-heading">
              {{ citation.sectionHeading }}
            </div>
            <div v-if="citation.sourcePath" class="citation-heading">{{ citation.sourcePath }}</div>
            <div v-if="citation.excerpt" class="citation-excerpt">{{ formatCitationExcerpt(citation.excerpt) }}</div>
          </div>
        </div>
        <div v-if="msg.role === 'assistant' && msg.status === 'INTERRUPTED'" class="message-state interrupted">
          Interrupted
        </div>
        <div class="message-meta">
          <span>{{ msg.role }}</span>
          <span v-if="msg.messageId === activeMessageId">Current</span>
        </div>
        <div class="message-actions" v-if="msg.role === 'user'">
          <button @click="$emit('start-edit', msg)">Edit & Branch</button>
        </div>
        <div class="message-actions" v-if="msg.role === 'assistant'">
          <button :disabled="isStreaming" @click="$emit('regenerate', msg)">Regenerate</button>
        </div>
        <div class="branch-controls" v-if="getSiblingBranches(msg).length > 1">
          <span>Branch</span>
          <button @click="$emit('switch-branch', msg, -1)">Prev</button>
          <span class="branch-index">{{ getSiblingPosition(msg) }}</span>
          <button @click="$emit('switch-branch', msg, 1)">Next</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { renderChatMarkdown } from "../../utils/chatMarkdown";

export default {
  name: "ChatMessageList",
  props: {
    messages: {
      type: Array,
      default: () => []
    },
    childMap: {
      type: Object,
      default: () => ({})
    },
    activeMessageId: {
      type: String,
      default: ""
    },
    editingMessageId: {
      type: String,
      default: ""
    },
    editedContent: {
      type: String,
      default: ""
    },
    isStreaming: {
      type: Boolean,
      default: false
    }
  },
  emits: [
    "update:editedContent",
    "start-edit",
    "cancel-edit",
    "submit-edit",
    "regenerate",
    "switch-branch"
  ],
  methods: {
    renderMarkdown(content) {
      return renderChatMarkdown(content);
    },
    hasCitations(msg) {
      return Array.isArray(msg?.citations) && msg.citations.length > 0;
    },
    hasGrounding(msg) {
      return Boolean(msg?.grounding?.status);
    },
    groundingLabel(msg) {
      const status = msg?.grounding?.status || "";
      if (status === "grounded") return "Grounded";
      if (status === "partial") return "Partially grounded";
      if (status === "insufficient_evidence") return "Weak evidence";
      return status;
    },
    groundingClass(msg) {
      const status = msg?.grounding?.status || "";
      return status ? `grounding-${status}` : "";
    },
    formatCitationExcerpt(excerpt) {
      if (!excerpt) return "";
      return String(excerpt).replace(/\s+/g, " ").trim();
    },
    getSiblingBranches(msg) {
      if (!msg) return [];
      const parentKey = msg.parentMessageId || "__root__";
      return this.childMap[parentKey] || [];
    },
    getSiblingPosition(msg) {
      const siblings = this.getSiblingBranches(msg);
      if (!siblings.length) return "1/1";
      const index = siblings.findIndex((item) => item.messageId === msg.messageId);
      return `${index + 1}/${siblings.length}`;
    }
  }
};
</script>
