<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>RAG Management</h2>
        <div class="meta">Manage uploaded documents</div>
      </div>
      <button class="cta" @click="loadDocs">Refresh</button>
    </header>

    <div class="card">
      <div v-for="d in docs" :key="d.docId" class="list-item">
        <div>
          <div class="session-title">{{ d.title }}</div>
          <div class="session-meta">chunks: {{ d.chunkCount }} - created: {{ d.createdAt }}</div>
        </div>
        <div class="list-actions">
          <button @click="removeDoc(d.docId)">Delete</button>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest } from "../../api";

export default {
  name: "AdminRagView",
  data() {
    return { docs: [], status: "" };
  },
  mounted() {
    this.loadDocs();
  },
  methods: {
    async loadDocs() {
      try {
        const data = await apiRequest("/api/admin/rag/documents?page=1&size=50");
        this.docs = data.content || data.records || data || [];
      } catch (e) {
        this.status = e.message;
      }
    },
    async removeDoc(docId) {
      try {
        await apiRequest(`/api/admin/rag/documents/${docId}`, { method: "DELETE" });
        this.status = "Deleted";
        this.loadDocs();
      } catch (e) {
        this.status = e.message;
      }
    }
  }
};
</script>
