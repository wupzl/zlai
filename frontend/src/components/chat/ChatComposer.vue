<template>
  <div class="composer">
    <textarea
      :value="draft"
      rows="2"
      placeholder="Type a message"
      @input="$emit('update:draft', $event.target.value)"
      @keydown="$emit('keydown', $event)"
    ></textarea>
    <button
      class="send-btn"
      :disabled="!isStreaming && !canSend"
      @click="$emit(isStreaming ? 'stop' : 'send')"
    >{{ isStreaming ? "Stop" : "Send" }}</button>
  </div>
</template>

<script>
export default {
  name: "ChatComposer",
  props: {
    draft: {
      type: String,
      default: ""
    },
    isStreaming: {
      type: Boolean,
      default: false
    },
    canSend: {
      type: Boolean,
      default: false
    }
  },
  emits: ["update:draft", "keydown", "send", "stop"]
};
</script>
