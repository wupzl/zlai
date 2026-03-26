<template>
  <div class="toast-container">
    <TransitionGroup name="toast">
      <div
        v-for="n in notifications"
        :key="n.id"
        class="toast-item"
        :class="n.type"
        @click="remove(n.id)"
      >
        <span class="toast-message">{{ n.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { useNotificationStore } from "../../stores/notification";
import { storeToRefs } from "pinia";

const store = useNotificationStore();
const { notifications } = storeToRefs(store);
const { remove } = store;
</script>

<style scoped>
.toast-container {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}

.toast-item {
  pointer-events: auto;
  min-width: 200px;
  max-width: 400px;
  padding: 12px 20px;
  border-radius: 8px;
  background: white;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  display: flex;
  align-items: center;
  cursor: pointer;
  transition: all 0.3s ease;
}

.toast-message {
  font-size: 14px;
  color: #333;
}

.toast-item.error {
  border-left: 4px solid #ff4d4f;
  background: #fff2f0;
}

.toast-item.success {
  border-left: 4px solid #52c41a;
  background: #f6ffed;
}

.toast-item.warning {
  border-left: 4px solid #faad14;
  background: #fffbe6;
}

.toast-item.info {
  border-left: 4px solid #1890ff;
  background: #e6f7ff;
}

/* Transition */
.toast-enter-from {
  opacity: 0;
  transform: translateX(30px);
}
.toast-leave-to {
  opacity: 0;
  transform: scale(0.9);
}
</style>
