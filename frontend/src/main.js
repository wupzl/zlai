import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";
import { pinia } from "./stores/pinia";
import "./style.css";
import "katex/dist/katex.min.css";

const app = createApp(App);

app.use(pinia);
app.use(router);
app.mount("#app");
