import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";
import "./style.css";
import "katex/dist/katex.min.css";

createApp(App).use(router).mount("#app");
