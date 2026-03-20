<template>
  <div class="session-settings inline">
    <h3>Session Settings</h3>
    <div class="grid">
      <div>
        <label>Model</label>
        <select :value="selectedModel" @change="$emit('update:selectedModel', $event.target.value)">
          <option v-for="m in modelOptions" :key="m" :value="m">{{ formatModelLabel(m) }}</option>
        </select>
        <div class="hint">Billing rate: multiplier per model.</div>
      </div>
      <div>
        <label>Tool Model</label>
        <select :value="selectedToolModel" @change="$emit('update:selectedToolModel', $event.target.value)">
          <option value="">Default tool model</option>
          <option v-for="m in modelOptions" :key="'tool-' + m" :value="m">{{ formatModelLabel(m) }}</option>
        </select>
        <div class="hint">Tool calls are billed using the Tool Model rate.</div>
      </div>
      <div>
        <label>Response Mode</label>
        <div class="switch-row">
          <label class="switch">
            <input type="checkbox" :checked="useStreaming" @change="$emit('update:useStreaming', $event.target.checked)" />
            <span class="slider"></span>
          </label>
          <span class="switch-label">{{ useStreaming ? "Streaming" : "Non-streaming" }}</span>
        </div>
        <div class="hint">Non-streaming sends a single response. Streaming is recommended.</div>
        <div class="warning-note" v-if="selectedModel && selectedModel.toLowerCase().startsWith('gpt-')">
          GPT streaming may be unstable. Non-streaming is recommended.
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: "ChatSessionSettings",
  props: {
    modelOptions: {
      type: Array,
      default: () => []
    },
    selectedModel: {
      type: String,
      default: ""
    },
    selectedToolModel: {
      type: String,
      default: ""
    },
    useStreaming: {
      type: Boolean,
      default: true
    },
    formatModelLabel: {
      type: Function,
      required: true
    }
  },
  emits: ["update:selectedModel", "update:selectedToolModel", "update:useStreaming"]
};
</script>
