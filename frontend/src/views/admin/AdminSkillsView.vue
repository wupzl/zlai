<template>
  <section class="panel">
    <header class="panel-header">
      <div>
        <h2>Skill Management</h2>
        <div class="meta">Package backend tools into reusable agent skills.</div>
      </div>
      <div class="list-actions">
        <button class="ghost" @click="resetForm">New Skill</button>
        <button class="cta" @click="loadSkills">Refresh</button>
      </div>
    </header>

    <div class="card skill-import-panel">
      <div class="card-head">
        <div>
          <h3>Import From Markdown</h3>
          <div class="meta">Convert structured <code>SKILL.md</code> content into managed skills.</div>
        </div>
        <div class="tabs skill-import-tabs">
          <button :class="{ active: importMode === 'text' }" @click="importMode = 'text'">Paste Markdown</button>
          <button :class="{ active: importMode === 'file' }" @click="importMode = 'file'">Upload File</button>
        </div>
      </div>

      <div class="form-grid skill-import-grid">
        <div class="field-group">
          <div class="field-label">Source Name</div>
          <input v-model="importForm.sourceName" placeholder="SKILL.md or repo-relative path" />
        </div>

        <div class="field-group">
          <div class="field-label">Default Execution Mode</div>
          <select v-model="importForm.defaultExecutionMode">
            <option value="">Auto detect</option>
            <option value="single_tool">single_tool</option>
            <option value="pipeline">pipeline</option>
          </select>
        </div>

        <div class="field-group">
          <div class="field-label">Default Tool Keys</div>
          <input v-model="importForm.defaultToolKeysText" placeholder="web_search, summarize" />
          <div class="hint">Used when markdown does not explicitly declare tools.</div>
        </div>

        <div class="field-group">
          <div class="field-label">Tool Aliases (JSON object)</div>
          <input v-model="importForm.toolAliasesText" placeholder='{"search":"web_search","rag":"rag_knowledge_search"}' />
          <div class="hint">Map markdown aliases to real backend tool keys.</div>
        </div>
      </div>

      <div v-if="importMode === 'text'" class="field-group">
        <div class="field-label">Markdown Content</div>
        <textarea
          v-model="importForm.markdownContent"
          rows="12"
          placeholder="# Skill Name&#10;&#10;## Description&#10;...&#10;&#10;## Tools&#10;- web_search"
        ></textarea>
      </div>

      <div v-else class="field-group">
        <div class="field-label">Markdown File</div>
        <input type="file" accept=".md,.markdown,.txt" @change="onImportFileChange" />
        <div class="hint" v-if="importForm.file">Selected: {{ importForm.file.name }}</div>
      </div>

      <div class="skill-import-actions">
        <label class="checkbox">
          <input type="checkbox" v-model="importForm.overwriteExisting" />
          Overwrite existing skill
        </label>
        <label class="checkbox">
          <input type="checkbox" v-model="importForm.enabled" />
          Enable after import
        </label>
        <div class="list-actions">
          <button class="ghost" @click="resetImportForm" :disabled="saving">Reset Import</button>
          <button class="cta" @click="submitImport" :disabled="saving">{{ saving ? "Importing..." : "Import Skill" }}</button>
        </div>
      </div>
    </div>

    <div class="admin-toolbar">
      <div class="filters">
        <input v-model="keyword" placeholder="Filter skills" />
      </div>
      <div class="session-meta">{{ filteredSkills.length }} skills</div>
    </div>

    <div class="form-grid skill-admin-grid">
      <div class="card skill-list">
        <div
          v-for="skill in filteredSkills"
          :key="skill.key"
          class="list-item"
          :class="{ active: selectedKey === skill.key }"
          @click="selectSkill(skill)"
        >
          <div>
            <div class="session-title">{{ skill.name }}</div>
            <div class="session-meta">{{ skill.key }} | enabled: {{ skill.enabled }}</div>
            <div class="hint">{{ skill.description || "No description" }}</div>
          </div>
        </div>
        <div v-if="!filteredSkills.length" class="empty">No skills found.</div>
      </div>

      <div class="card skill-editor">
        <div class="field-group">
          <div class="field-label">Skill Key</div>
          <input v-model="form.key" :disabled="isEditing" placeholder="document_brief" />
          <div class="hint">Lowercase letters, numbers, `_` or `-` only.</div>
        </div>

        <div class="field-group">
          <div class="field-label">Display Name</div>
          <input v-model="form.name" placeholder="Document Brief" />
        </div>

        <div class="field-group">
          <div class="field-label">Description</div>
          <textarea v-model="form.description" rows="3" placeholder="What this skill is good at"></textarea>
        </div>

        <div class="field-group">
          <div class="field-label">Input Schema (JSON array)</div>
          <textarea
            v-model="form.inputSchemaText"
            rows="6"
            placeholder='[{"key":"query","type":"string","required":true,"description":"Search query"}]'
          ></textarea>
          <div class="hint">Define the structured input fields this skill expects.</div>
        </div>

        <div class="field-group">
          <div class="field-label">Bound Tools</div>
          <select v-model="form.executionMode">
            <option value="single_tool">single_tool</option>
            <option value="pipeline">pipeline</option>
          </select>
          <div class="hint">`single_tool` uses the first selected tool. `pipeline` runs selected tools in order and passes output forward.</div>
          <div class="tool-list">
            <label v-for="tool in tools" :key="tool.key">
              <input type="checkbox" :value="tool.key" v-model="form.toolKeys" />
              {{ tool.name }}
            </label>
          </div>
          <div class="hint">A skill can wrap one or more backend tools. Agents only see the skill.</div>
        </div>

        <div class="field-group">
          <div class="field-label">Step Config (JSON array)</div>
          <textarea
            v-model="form.stepConfigText"
            rows="7"
            placeholder='[{"toolKey":"web_search","prompt":"Search official and authoritative sources first"}]'
          ></textarea>
          <div class="hint">Each step must reference one selected tool. Pipeline mode runs these steps in order.</div>
        </div>

        <label class="checkbox">
          <input type="checkbox" v-model="form.enabled" />
          Enabled
        </label>

        <div v-if="selected" class="hint">
          Updated: {{ selected.updatedAt || "-" }}
        </div>

        <div class="modal-actions">
          <button @click="saveSkill" :disabled="saving">{{ saving ? "Saving..." : isEditing ? "Update Skill" : "Create Skill" }}</button>
          <button class="ghost" @click="resetForm">Reset</button>
          <button v-if="isEditing" class="danger" @click="removeSkill" :disabled="saving">Delete</button>
        </div>
      </div>
    </div>

    <div class="status" v-if="status">{{ status }}</div>
  </section>
</template>

<script>
import { apiRequest, authFetch } from "../../api";

function emptyForm() {
  return {
    key: "",
    name: "",
    description: "",
    toolKeys: [],
    executionMode: "single_tool",
    inputSchemaText: "[]",
    stepConfigText: "[]",
    enabled: true
  };
}

function emptyImportForm() {
  return {
    sourceName: "",
    markdownContent: "",
    defaultToolKeysText: "",
    defaultExecutionMode: "",
    overwriteExisting: false,
    enabled: true,
    toolAliasesText: "",
    file: null
  };
}

export default {
  name: "AdminSkillsView",
  data() {
    return {
      skills: [],
      tools: [],
      selected: null,
      selectedKey: "",
      form: emptyForm(),
      importForm: emptyImportForm(),
      importMode: "text",
      saving: false,
      keyword: "",
      status: ""
    };
  },
  computed: {
    isEditing() {
      return !!this.selectedKey;
    },
    filteredSkills() {
      const q = String(this.keyword || "").trim().toLowerCase();
      if (!q) return this.skills;
      return this.skills.filter((skill) => {
        return [skill.key, skill.name, skill.description]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(q));
      });
    }
  },
  mounted() {
    this.loadSkills();
    this.loadTools();
  },
  methods: {
    async loadSkills() {
      try {
        this.skills = await apiRequest("/api/admin/skills");
        if (this.selectedKey) {
          const next = this.skills.find((skill) => skill.key === this.selectedKey);
          if (next) {
            this.selectSkill(next);
          } else {
            this.resetForm();
          }
        }
      } catch (e) {
        this.status = e.message;
      }
    },
    async loadTools() {
      try {
        this.tools = await apiRequest("/api/agents/tools");
      } catch (e) {
        this.status = e.message;
      }
    },
    selectSkill(skill) {
      this.selected = skill;
      this.selectedKey = skill.key;
      this.form = {
        key: skill.key || "",
        name: skill.name || "",
        description: skill.description || "",
        toolKeys: Array.isArray(skill.toolKeys) ? [...skill.toolKeys] : [],
        executionMode: skill.executionMode || "single_tool",
        inputSchemaText: JSON.stringify(skill.inputSchema || [], null, 2),
        stepConfigText: JSON.stringify(skill.stepConfig || [], null, 2),
        enabled: !!skill.enabled
      };
    },
    resetForm() {
      this.selected = null;
      this.selectedKey = "";
      this.form = emptyForm();
      this.status = "";
    },
    resetImportForm() {
      this.importForm = emptyImportForm();
    },
    async saveSkill() {
      this.saving = true;
      this.status = "";
      try {
        const inputSchema = this.parseJsonArray(this.form.inputSchemaText, "Input schema");
        const stepConfig = this.parseJsonArray(this.form.stepConfigText, "Step config");
        const payload = {
          key: this.form.key,
          name: this.form.name,
          description: this.form.description,
          toolKeys: this.form.toolKeys,
          executionMode: this.form.executionMode,
          inputSchema,
          stepConfig,
          enabled: !!this.form.enabled
        };
        if (this.isEditing) {
          await apiRequest(`/api/admin/skills/${this.selectedKey}`, {
            method: "PUT",
            body: JSON.stringify(payload)
          });
          this.status = "Skill updated";
        } else {
          await apiRequest("/api/admin/skills", {
            method: "POST",
            body: JSON.stringify(payload)
          });
          this.status = "Skill created";
        }
        await this.loadSkills();
      } catch (e) {
        this.status = e.message;
      } finally {
        this.saving = false;
      }
    },
    onImportFileChange(event) {
      const files = event?.target?.files;
      this.importForm.file = files && files[0] ? files[0] : null;
    },
    async submitImport() {
      this.saving = true;
      this.status = "";
      try {
        const defaultToolKeys = this.parseDelimitedList(this.importForm.defaultToolKeysText);
        const toolAliases = this.parseJsonObject(this.importForm.toolAliasesText, "Tool aliases");
        let skill;
        if (this.importMode === "file") {
          if (!this.importForm.file) {
            throw new Error("Please select a markdown file.");
          }
          const form = new FormData();
          form.append("file", this.importForm.file);
          if (this.importForm.sourceName) form.append("sourceName", this.importForm.sourceName);
          defaultToolKeys.forEach((toolKey) => form.append("defaultToolKeys", toolKey));
          if (this.importForm.defaultExecutionMode) {
            form.append("defaultExecutionMode", this.importForm.defaultExecutionMode);
          }
          form.append("overwriteExisting", String(!!this.importForm.overwriteExisting));
          form.append("enabled", String(!!this.importForm.enabled));
          if (toolAliases && Object.keys(toolAliases).length) {
            form.append("toolAliasesJson", JSON.stringify(toolAliases));
          }
          const res = await authFetch("/api/admin/skills/import-markdown-file", {
            method: "POST",
            body: form
          });
          const data = await res.json().catch(() => ({}));
          if (!res.ok || (data && data.code && data.code !== 200)) {
            throw new Error(data?.message || "Import failed");
          }
          skill = data?.data ?? data;
        } else {
          if (!String(this.importForm.markdownContent || "").trim()) {
            throw new Error("Markdown content is required.");
          }
          skill = await apiRequest("/api/admin/skills/import-markdown", {
            method: "POST",
            body: JSON.stringify({
              sourceName: this.importForm.sourceName || undefined,
              markdownContent: this.importForm.markdownContent,
              defaultToolKeys,
              defaultExecutionMode: this.importForm.defaultExecutionMode || undefined,
              overwriteExisting: !!this.importForm.overwriteExisting,
              enabled: !!this.importForm.enabled,
              toolAliases
            })
          });
        }
        this.status = `Imported skill ${skill?.key || ""}`.trim();
        this.resetImportForm();
        await this.loadSkills();
        if (skill?.key) {
          const imported = this.skills.find((item) => item.key === skill.key);
          if (imported) {
            this.selectSkill(imported);
          }
        }
      } catch (e) {
        this.status = e.message;
      } finally {
        this.saving = false;
      }
    },
    parseJsonArray(text, label) {
      try {
        const parsed = JSON.parse(text || "[]");
        if (!Array.isArray(parsed)) {
          throw new Error(`${label} must be a JSON array`);
        }
        return parsed;
      } catch (e) {
        throw new Error(e.message || `${label} is invalid JSON`);
      }
    },
    parseJsonObject(text, label) {
      const raw = String(text || "").trim();
      if (!raw) return undefined;
      try {
        const parsed = JSON.parse(raw);
        if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
          throw new Error(`${label} must be a JSON object`);
        }
        return parsed;
      } catch (e) {
        throw new Error(e.message || `${label} is invalid JSON`);
      }
    },
    parseDelimitedList(text) {
      return String(text || "")
        .split(/[\n,]/)
        .map((item) => item.trim())
        .filter(Boolean);
    },
    async removeSkill() {
      if (!this.selectedKey) return;
      if (!window.confirm(`Delete skill ${this.selectedKey}?`)) {
        return;
      }
      this.saving = true;
      this.status = "";
      try {
        await apiRequest(`/api/admin/skills/${this.selectedKey}`, {
          method: "DELETE"
        });
        this.status = "Skill deleted";
        this.resetForm();
        await this.loadSkills();
      } catch (e) {
        this.status = e.message;
      } finally {
        this.saving = false;
      }
    }
  }
};
</script>
