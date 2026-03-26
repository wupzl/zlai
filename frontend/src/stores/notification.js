import { defineStore } from "pinia";
import { ref } from "vue";

export const useNotificationStore = defineStore("notification", () => {
  const notifications = ref([]);

  const notify = (message, type = "info", duration = 4000) => {
    const id = Date.now() + Math.random();
    notifications.value.push({ id, message, type });
    
    if (duration > 0) {
      setTimeout(() => {
        remove(id);
      }, duration);
    }
    return id;
  };

  const remove = (id) => {
    notifications.value = notifications.value.filter(n => n.id !== id);
  };

  const error = (message, duration) => notify(message, "error", duration);
  const success = (message, duration) => notify(message, "success", duration);
  const info = (message, duration) => notify(message, "info", duration);
  const warning = (message, duration) => notify(message, "warning", duration);

  return {
    notifications,
    notify,
    remove,
    error,
    success,
    info,
    warning
  };
});
