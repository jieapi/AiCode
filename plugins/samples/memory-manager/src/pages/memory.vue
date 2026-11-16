<template>
  <div id="app" class="aicode-page" :class="{ 'selection-active': selectionMode }" v-cloak>
    <header class="aicode-page-header">
      <aicode-icon-button label="返回" @click="handleBackButton">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line x1="19" y1="12" x2="5" y2="12"></line>
          <polyline points="12 19 5 12 12 5"></polyline>
        </svg>
      </aicode-icon-button>
      <h1 class="aicode-page-title">{{ pageTitle }}</h1>
      <aicode-icon-button v-if="view === 'list'" label="多选" :class="{ active: selectionMode }"
        @click="toggleSelectionMode">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <polyline points="9 11 12 14 22 4"></polyline>
          <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"></path>
        </svg>
      </aicode-icon-button>
      <aicode-icon-button v-if="view === 'list'" label="新建" @click="openEdit(null)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line x1="12" y1="5" x2="12" y2="19"></line>
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
      </aicode-icon-button>
      <aicode-icon-button v-if="view === 'detail'" label="编辑" @click="editCurrent">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"></path>
        </svg>
      </aicode-icon-button>
      <button v-if="view === 'edit'" class="aicode-text-button"
        :disabled="saving || !!conflict || contextKey === null" @click="save">保存</button>
    </header>

    <div class="page-content">
      <p v-if="notice" class="aicode-text" role="status">{{ notice }}</p>
      <p v-if="refreshError" class="aicode-error" role="alert">{{ refreshError }}</p>
      <section v-show="view === 'list'" class="view">
        <aicode-group title="全局记忆" collapsible v-model:expanded="globalExpanded">
          <template v-if="globalItems.length">
            <template v-for="(item, index) in globalItems" :key="item.fileName">
              <div v-if="index > 0" class="aicode-divider"></div>
              <aicode-swipe-row :disabled="selectionMode" @delete="deleteItem(item)">
                <aicode-row
                  :title="item.name"
                  :subtitle="item.description || '（无描述）'"
                  :badge="scopeLabel(item.scope)"
                  :selectable="selectionMode"
                  :selected="isSelected(item)"
                  @click="handleRowClick(item)"></aicode-row>
              </aicode-swipe-row>
            </template>
          </template>
          <aicode-empty v-else text="暂无全局记忆" />
        </aicode-group>

        <aicode-group title="项目记忆" collapsible v-model:expanded="projectExpanded">
          <template v-if="projectItems.length">
            <template v-for="(item, index) in projectItems" :key="item.fileName">
              <div v-if="index > 0" class="aicode-divider"></div>
              <aicode-swipe-row :disabled="selectionMode" @delete="deleteItem(item)">
                <aicode-row
                  :title="item.name"
                  :subtitle="item.description || '（无描述）'"
                  :badge="scopeLabel(item.scope)"
                  :selectable="selectionMode"
                  :selected="isSelected(item)"
                  @click="handleRowClick(item)"></aicode-row>
              </aicode-swipe-row>
            </template>
          </template>
          <aicode-empty v-else text="当前项目暂无项目记忆" />
        </aicode-group>
      </section>

      <section v-show="view === 'detail'" class="view">
        <aicode-group title="信息" :collapsible="false">
          <div class="aicode-info-row">
            <span>范围</span>
            <span>{{ currentItem ? scopeLabel(currentItem.scope) : '' }}</span>
          </div>
          <div class="aicode-divider aicode-divider-flat"></div>
          <div class="aicode-info-row">
            <span>文件</span>
            <span>{{ currentItem ? currentItem.fileName : '' }}</span>
          </div>
        </aicode-group>
        <aicode-group title="摘要" :collapsible="false">
          <p class="aicode-text">{{ currentItem ? (currentItem.description || '（无描述）') : '' }}</p>
        </aicode-group>
        <aicode-group title="指令正文" :collapsible="false">
          <aicode-markdown :text="currentItem ? currentItem.content : ''" />
        </aicode-group>
      </section>

      <section v-show="view === 'edit'" class="view">
        <aicode-group v-if="conflict" title="编辑冲突" :collapsible="false">
          <p class="aicode-error" role="alert">{{ conflict }}</p>
          <aicode-button v-if="!contextConflict" compact :disabled="saving" @click="reloadEdit">重新载入</aicode-button>
          <aicode-button compact :disabled="saving" @click="discardEdit">放弃编辑</aicode-button>
        </aicode-group>
        <aicode-group title="基本信息" :collapsible="false">
          <aicode-field label="名称">
            <aicode-input v-model="form.name" :disabled="!!editingItem || saving"
              placeholder="例如 project-conventions" />
          </aicode-field>
          <aicode-field label="描述">
            <aicode-input v-model="form.description" :disabled="saving" placeholder="一句话摘要，会注入系统提示词" />
          </aicode-field>
          <div class="aicode-scope-row">
            <span class="aicode-scope-label">作用域</span>
            <aicode-segmented v-model="form.scope" :options="scopeOptions" :disabled="!!editingItem || saving" />
          </div>
        </aicode-group>
        <aicode-group title="指令正文" :collapsible="false">
          <div class="aicode-outlined-field aicode-outlined-field-body">
            <aicode-textarea v-model="form.content" :disabled="saving" placeholder="记忆的具体内容" />
          </div>
        </aicode-group>
        <p v-if="error" class="aicode-error">{{ error }}</p>
      </section>
    </div>

    <div v-if="selectionMode" class="aicode-selection-bar">
      <span>已选 {{ selected.length }} 项</span>
      <aicode-button compact @click="toggleSelectAll">{{ allSelected ? '取消全选' : '全选' }}</aicode-button>
      <aicode-button compact variant="danger" :disabled="!selected.length" @click="batchDelete">
        {{ batchConfirm ? '确认删除' : '删除' }}
      </aicode-button>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface MemoryItem {
  scope: 'global' | 'project';
  fileName: string;
  name: string;
  description: string;
  content: string;
}

interface MemoryForm {
  name: string;
  description: string;
  content: string;
  scope: 'global' | 'project';
}

interface MemoryData {
  view: 'list' | 'edit' | 'detail';
  globalExpanded: boolean;
  projectExpanded: boolean;
  globalItems: MemoryItem[];
  projectItems: MemoryItem[];
  currentItem: MemoryItem | null;
  editingItem: MemoryItem | null;
  form: MemoryForm;
  selectionMode: boolean;
  selected: string[];
  saving: boolean;
  error: string;
  notice: string;
  conflict: string;
  contextConflict: boolean;
  editEpoch: number | null;
  contextKey: string | null;
  contextEpoch: number;
  contextRequest: number;
  contextError: string;
  loadErrors: { global: string; project: string };
  watchErrors: { global: string; project: string };
  pendingScopes: Record<string, boolean>;
  scopeVersions: { global: number; project: number };
  refreshPromise: Promise<unknown> | null;
  watchStops: Array<() => void>;
  eventsStop: (() => void) | null;
  backStop: (() => void) | null;
  disposed: boolean;
  batchConfirm: boolean;
  batchTimer: ReturnType<typeof setTimeout> | null;
}

var GLOBAL_ROOT = '';
    var PROJECT_ROOT = '';

export default defineComponent({
      data(): MemoryData {
        return {
          view: 'list',
          globalExpanded: true,
          projectExpanded: true,
          globalItems: [],
          projectItems: [],
          currentItem: null,
          editingItem: null,
          form: { name: '', description: '', content: '', scope: 'global' },
          selectionMode: false,
          selected: [],
          saving: false,
          error: '',
          notice: '',
          conflict: '',
          contextConflict: false,
          editEpoch: null,
          contextKey: null,
          contextEpoch: 0,
          contextRequest: 0,
          contextError: '',
          loadErrors: { global: '', project: '' },
          watchErrors: { global: '', project: '' },
          pendingScopes: {},
          scopeVersions: { global: 0, project: 0 },
          refreshPromise: null,
          watchStops: [],
          eventsStop: null,
          backStop: null,
          disposed: false,
          batchConfirm: false,
          batchTimer: null
        };
      },

      computed: {
        pageTitle: function () {
          if (this.view === 'edit') return this.editingItem ? '编辑记忆' : '新建记忆';
          if (this.view === 'detail') return this.currentItem ? this.currentItem.name : '记忆管理';
          return '记忆管理';
        },
        allItems: function () {
          return this.globalItems.concat(this.projectItems);
        },
        allSelected: function () {
          return this.allItems.length > 0 && this.selected.length === this.allItems.length;
        },
        refreshError: function () {
          return [this.contextError, this.watchErrors.global, this.watchErrors.project,
            this.loadErrors.global, this.loadErrors.project].filter(Boolean).join('；');
        },
        scopeOptions: function () {
          return [
            { value: 'project', label: '当前项目' },
            { value: 'global', label: '全局' }
          ];
        }
      },

      methods: {
        rootFor: function (scope) {
          return scope === 'project' ? PROJECT_ROOT : GLOBAL_ROOT;
        },
        scopeLabel: function (scope) {
          return scope === 'project' ? '项目' : '全局';
        },
        keyFor: function (item) {
          return item.scope + ':' + item.fileName;
        },
        sanitizeName: function (raw) {
          var chars = Array.from(String(raw));
          var cleaned = chars.map(function (ch) {
            return /[\p{L}\p{N}_-]/u.test(ch) ? ch : '-';
          }).join('').replace(/^-+|-+$/g, '');
          return cleaned || 'memory';
        },
        yamlScalar: function (value) {
          return '"' + String(value == null ? '' : value).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
        },
        formatMemory: function (name, description, content) {
          return '---\nname: ' + this.yamlScalar(name) + '\ndescription: ' + this.yamlScalar(description) +
            '\n---\n' + content;
        },
        unquote: function (value) {
          var text = String(value == null ? '' : value).trim();
          if (text.length >= 2 && text.charAt(0) === '"' && text.charAt(text.length - 1) === '"') {
            return text.slice(1, -1).replace(/\\\\/g, '\\').replace(/\\"/g, '"');
          }
          return text;
        },
        parseMemory: function (text, fileName, scope) {
          var name = fileName.replace(/\.md$/i, '');
          var description = '';
          var body = text || '';
          var match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(body);
          if (match) {
            var front = match[1];
            var nameMatch = /^name:\s*(.+)$/m.exec(front);
            var descMatch = /^description:\s*(.+)$/m.exec(front);
            if (nameMatch) name = this.unquote(nameMatch[1]);
            if (descMatch) description = this.unquote(descMatch[1]);
            body = body.slice(match[0].length);
          }
          return {
            name: name,
            description: description,
            content: body.trim(),
            fileName: fileName,
            scope: scope,
            rawContent: text,
            contextEpoch: this.contextEpoch
          };
        },
        isCurrent: function (epoch) {
          return !this.disposed && this.contextKey !== null && this.contextEpoch === epoch;
        },
        refreshContext: async function () {
          var request = ++this.contextRequest;
          try {
            var context = await aicode.context();
            if (this.disposed || request !== this.contextRequest) return false;
            if (typeof context.contextKey !== 'string') throw new Error('无法获取页面上下文');
            GLOBAL_ROOT = context.home + '/.aicode/memory';
            PROJECT_ROOT = context.workspace + '/.aicode/memory';
            this.contextError = '';
            if (this.setContext(context.contextKey)) this.loadAll();
            return true;
          } catch (e) {
            if (!this.disposed && request === this.contextRequest) {
              this.contextError = '读取页面上下文失败：' + ((e && e.message) || e);
            }
            return false;
          }
        },
        checkContext: async function (epoch) {
          if (!this.isCurrent(epoch)) return false;
          return await this.refreshContext() && this.isCurrent(epoch);
        },
        setContext: function (key) {
          if (key === this.contextKey) return false;
          var changed = this.contextKey !== null;
          this.contextKey = key;
          this.contextEpoch++;
          this.pendingScopes = {};
          this.refreshPromise = null;
          this.loadErrors = { global: '', project: '' };
          this.stopWatching();
          if (changed) {
            this.globalItems = [];
            this.projectItems = [];
            this.currentItem = null;
            this.selected = [];
            this.selectionMode = false;
            this.batchConfirm = false;
            clearTimeout(this.batchTimer);
            this.batchTimer = null;
            if (this.view === 'edit') {
              this.contextConflict = true;
              this.conflict = '工作区或执行环境已切换，草稿已保留，不能保存到新上下文。请复制所需内容后放弃编辑。';
            } else {
              this.view = 'list';
              this.notice = '工作区或执行环境已切换。';
            }
          }
          this.watchScopes();
          return true;
        },
        handleLifecycle: function (event) {
          if (this.disposed || !event || event.name !== 'ui.lifecycle' || !event.payload) return;
          var payload = event.payload;
          if (typeof payload.contextKey !== 'string') return;
          this.contextRequest++;
          this.contextError = '';
          this.setContext(payload.contextKey);
          if (payload.visible) this.loadAll();
        },
        stopWatching: function () {
          this.watchStops.forEach(function (stop) { stop().catch(function () {}); });
          this.watchStops = [];
        },
        watchScopes: function () {
          var self = this;
          var epoch = this.contextEpoch;
          this.watchErrors = { global: '', project: '' };
          ['global', 'project'].forEach(async function (scope) {
            try {
              var stop = await aicode.fs.watch(self.rootFor(scope), { recursive: false }, function (payload) {
                if (!self.isCurrent(epoch)) return;
                if (payload && payload.contextKey != null && payload.contextKey !== self.contextKey) return;
                if (payload && payload.error) {
                  self.watchErrors[scope] = self.scopeLabel(scope) + '记忆文件订阅失败：' +
                    (payload.error.message || payload.error.code);
                  return;
                }
                self.watchErrors[scope] = '';
                self.requestRefresh([scope]);
              });
              if (self.isCurrent(epoch)) self.watchStops.push(stop);
              else await stop().catch(function () {});
            } catch (e) {
              if (self.isCurrent(epoch)) {
                self.watchErrors[scope] = self.scopeLabel(scope) + '记忆文件订阅失败：' + ((e && e.message) || e);
              }
            }
          });
        },
        readMemory: async function (scope, fileName) {
          try {
            var read = await aicode.fs.read(this.rootFor(scope) + '/' + fileName, { contextKey: this.contextKey });
            return this.parseMemory(read.content, fileName, scope);
          } catch (e) {
            if (e && e.code === 'NOT_FOUND') return null;
            throw e;
          }
        },
        listScope: async function (scope, epoch) {
          var result;
          try {
            result = await aicode.fs.list(this.rootFor(scope), { contextKey: this.contextKey });
          } catch (e) {
            if (e && e.code === 'NOT_FOUND') return [];
            throw e;
          }
          var files = (result.entries || []).filter(function (entry) {
            return !entry.isDir && /\.md$/i.test(entry.name);
          });
          var loaded = [];
          for (var i = 0; i < files.length; i++) {
            if (!this.isCurrent(epoch)) return [];
            var item = await this.readMemory(scope, files[i].name);
            if (item) loaded.push(item);
          }
          loaded.sort(function (a, b) {
            return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
          });
          return loaded;
        },
        applyScope: function (scope, items) {
          if (scope === 'project') this.projectItems = items;
          else this.globalItems = items;
          var keys = {};
          this.allItems.forEach(function (item) { keys[item.scope + ':' + item.fileName] = true; });
          this.selected = this.selected.filter(function (key) { return !!keys[key]; });
          if (this.currentItem && this.currentItem.scope === scope) {
            var fileName = this.currentItem.fileName;
            this.currentItem = items.find(function (item) { return item.fileName === fileName; }) || null;
            if (!this.currentItem && this.view === 'detail') {
              this.view = 'list';
              this.notice = '该记忆已被删除，已返回列表。';
            }
          }
          if (this.view === 'edit' && this.editingItem && this.editingItem.scope === scope && !this.contextConflict) {
            var editingName = this.editingItem.fileName;
            this.checkBaseline(items.find(function (item) { return item.fileName === editingName; }));
          }
        },
        checkBaseline: function (item) {
          if (!item) {
            this.conflict = '该记忆已被外部删除，草稿已保留，无法直接保存。请重新载入或放弃编辑。';
            return false;
          }
          if (item.rawContent !== this.editingItem.rawContent) {
            this.conflict = '该记忆的摘要或正文已被外部修改，草稿已保留，无法直接保存。请重新载入或放弃编辑。';
            return false;
          }
          return true;
        },
        requestRefresh: function (scopes) {
          if (this.disposed || this.contextKey === null) return Promise.resolve();
          var self = this;
          scopes.forEach(function (scope) {
            self.pendingScopes[scope] = true;
            self.scopeVersions[scope]++;
          });
          if (this.refreshPromise) return this.refreshPromise;
          var epoch = this.contextEpoch;
          var task = Promise.resolve().then(async function () {
            while (self.isCurrent(epoch) && Object.keys(self.pendingScopes).length) {
              var pending = Object.keys(self.pendingScopes);
              self.pendingScopes = {};
              await Promise.all(pending.map(async function (scope) {
                var version = self.scopeVersions[scope];
                try {
                  var items = await self.listScope(scope, epoch);
                  if (!self.isCurrent(epoch) || version !== self.scopeVersions[scope]) return;
                  self.loadErrors[scope] = '';
                  self.applyScope(scope, items);
                } catch (e) {
                  if (self.isCurrent(epoch) && version === self.scopeVersions[scope]) {
                    self.loadErrors[scope] = self.scopeLabel(scope) + '记忆刷新失败：' + ((e && e.message) || e);
                  }
                }
              }));
            }
            if (self.refreshPromise === task) self.refreshPromise = null;
          });
          this.refreshPromise = task;
          return task;
        },
        loadAll: function () {
          return this.requestRefresh(['global', 'project']);
        },
        isSelected: function (item) {
          return this.selected.indexOf(this.keyFor(item)) >= 0;
        },
        toggleSelected: function (item) {
          var key = this.keyFor(item);
          var index = this.selected.indexOf(key);
          if (index >= 0) {
            this.selected.splice(index, 1);
          } else {
            this.selected.push(key);
          }
        },
        toggleSelectionMode: function () {
          this.selectionMode = !this.selectionMode;
          this.selected = [];
          this.batchConfirm = false;
          if (this.batchTimer) {
            clearTimeout(this.batchTimer);
            this.batchTimer = null;
          }
        },
        toggleSelectAll: function () {
          if (this.allSelected) {
            this.selected = [];
          } else {
            this.selected = this.allItems.map(function (item) {
              return item.scope + ':' + item.fileName;
            });
          }
        },
        handleRowClick: function (item) {
          if (this.selectionMode) {
            this.toggleSelected(item);
            return;
          }
          this.openDetail(item);
        },
        openDetail: function (item) {
          if (!this.isCurrent(item.contextEpoch)) return;
          this.notice = '';
          this.currentItem = item;
          this.view = 'detail';
        },
        openEdit: function (item) {
          if (this.contextKey === null || this.disposed || (item && !this.isCurrent(item.contextEpoch))) return;
          this.editingItem = item;
          this.editEpoch = this.contextEpoch;
          this.contextConflict = false;
          this.conflict = '';
          this.notice = '';
          this.form = {
            name: item ? item.name : '',
            description: item ? item.description : '',
            content: item ? item.content : '',
            scope: item ? item.scope : 'global'
          };
          this.error = '';
          this.view = 'edit';
        },
        editCurrent: function () {
          if (this.currentItem) this.openEdit(this.currentItem);
        },
        discardEdit: function () {
          if (this.saving) return;
          var key = this.editingItem && this.keyFor(this.editingItem);
          var self = this;
          var item = this.isCurrent(this.editEpoch) && !this.contextConflict && this.allItems.find(function (item) {
            return self.keyFor(item) === key;
          });
          this.editingItem = null;
          this.editEpoch = null;
          this.conflict = '';
          this.contextConflict = false;
          this.error = '';
          this.form = { name: '', description: '', content: '', scope: 'global' };
          this.currentItem = item || null;
          this.view = item ? 'detail' : 'list';
        },
        reloadEdit: async function () {
          if (this.saving || this.contextConflict || !this.editingItem) return;
          var epoch = this.editEpoch;
          var item = this.editingItem;
          this.saving = true;
          this.error = '';
          try {
            if (!await this.checkContext(epoch)) return;
            var latest = await this.readMemory(item.scope, item.fileName);
            if (!this.isCurrent(epoch)) return;
            if (latest) {
              this.openEdit(latest);
            } else {
              this.saving = false;
              this.editingItem = null;
              this.discardEdit();
              this.notice = '该记忆已被删除，已返回列表。';
            }
            this.requestRefresh([item.scope]);
          } catch (e) {
            if (this.isCurrent(epoch)) this.error = '重新载入失败：' + ((e && e.message) || e);
          } finally {
            this.saving = false;
          }
        },
        handleBack: function () {
          if (this.saving) return true;
          if (this.view === 'list') return false;
          if (this.view === 'edit') {
            this.discardEdit();
            return true;
          }
          if (this.view === 'detail') {
            this.view = 'list';
            return true;
          }
          return false;
        },
        handleBackButton: function () {
          if (!this.handleBack()) {
            aicode.ui.close().catch(function () {});
          }
        },
        save: async function () {
          if (this.saving || this.view !== 'edit' || this.conflict) return;
          this.error = '';
          var name = this.form.name.trim();
          if (!name) {
            this.error = '请填写记忆名称';
            return;
          }
          var epoch = this.editEpoch;
          var contextKey = this.contextKey;
          var targetScope = this.editingItem ? this.editingItem.scope : this.form.scope;
          var fileName = this.editingItem ? this.editingItem.fileName : this.sanitizeName(name) + '.md';
          var text = this.formatMemory(name, this.form.description.trim(), this.form.content);
          this.saving = true;
          try {
            if (!await this.checkContext(epoch)) return;
            if (this.editingItem) {
              var latest = await this.readMemory(targetScope, fileName);
              if (!this.isCurrent(epoch) || !this.checkBaseline(latest)) return;
            }
            if (!this.isCurrent(epoch) || this.conflict) return;
            await aicode.fs.write(this.rootFor(targetScope) + '/' + fileName, text, { contextKey: contextKey });
            if (!this.isCurrent(epoch)) return;
            this.saving = false;
            this.editingItem = null;
            this.discardEdit();
            this.currentItem = null;
            this.view = 'list';
            this.requestRefresh([targetScope]);
            aicode.toast('已保存').catch(function () {});
          } catch (e) {
            if (e && e.code === 'STALE_CONTEXT') await this.refreshContext();
            if (this.isCurrent(epoch)) this.error = (e && e.message) || String(e);
          } finally {
            this.saving = false;
          }
        },
        deleteItem: async function (item) {
          var epoch = item.contextEpoch;
          var contextKey = this.contextKey;
          try {
            if (!await this.checkContext(epoch)) return;
            await aicode.fs.delete(this.rootFor(item.scope) + '/' + item.fileName, { contextKey: contextKey });
            if (!this.isCurrent(epoch)) return;
            await aicode.toast('已删除');
            if (this.isCurrent(epoch)) await this.requestRefresh([item.scope]);
          } catch (e) {
            if (e && e.code === 'STALE_CONTEXT') await this.refreshContext();
            if (this.isCurrent(epoch)) await aicode.toast('删除失败：' + ((e && e.message) || e));
          }
        },
        batchDelete: async function () {
          if (!this.selected.length) return;
          var self = this;
          var epoch = this.contextEpoch;
          var contextKey = this.contextKey;
          if (!this.batchConfirm) {
            this.batchConfirm = true;
            this.batchTimer = setTimeout(function () {
              self.batchConfirm = false;
              self.batchTimer = null;
            }, 3000);
            return;
          }
          if (this.batchTimer) {
            clearTimeout(this.batchTimer);
            this.batchTimer = null;
          }
          this.batchConfirm = false;
          var targets = this.allItems.filter(function (item) {
            return self.selected.indexOf(item.scope + ':' + item.fileName) >= 0;
          });
          if (!await this.checkContext(epoch)) return;
          for (var i = 0; i < targets.length; i++) {
            if (!this.isCurrent(epoch)) return;
            try {
              await aicode.fs.delete(this.rootFor(targets[i].scope) + '/' + targets[i].fileName, { contextKey: contextKey });
            } catch (e) {
              if (e && e.code === 'STALE_CONTEXT') {
                await this.refreshContext();
                return;
              }
              if (this.isCurrent(epoch)) await aicode.toast('删除失败：' + ((e && e.message) || e));
            }
          }
          if (!this.isCurrent(epoch)) return;
          await aicode.toast('已删除');
          if (!this.isCurrent(epoch)) return;
          this.selected = [];
          this.selectionMode = false;
          await this.loadAll();
        }
      },

      mounted: function () {
        var self = this;
        if (aicode.ui && aicode.ui.onBack) {
          aicode.ui.onBack(this.handleBack).then(function (stop) {
            if (self.disposed) stop();
            else self.backStop = stop;
          });
        }
        this.eventsStop = aicode.events.on(this.handleLifecycle);
        this.refreshContext();
      },
      beforeUnmount: function () {
        this.disposed = true;
        this.contextEpoch++;
        this.contextRequest++;
        this.pendingScopes = {};
        this.stopWatching();
        if (this.eventsStop) this.eventsStop();
        if (this.backStop) this.backStop();
        clearTimeout(this.batchTimer);
      }
    });
</script>

<style>
* { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; }
    [hidden], [v-cloak] { display: none !important; }
    body {
      background: var(--aicode-page-bg);
      color: var(--aicode-on-surface);
      font-family: var(--aicode-font-sans);
      font-size: calc(14px * var(--aicode-font-scale));
      line-height: 1.5;
      -webkit-text-size-adjust: 100%;
    }
    .page-content {
      display: flex;
      flex-direction: column;
      gap: 8px;
      width: 100%;
      max-width: 860px;
      margin: 0 auto;
      padding: 0 16px 24px;
    }
    .selection-active .page-content { padding-bottom: 96px; }
    .view {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .aicode-icon-button.active {
      background: var(--aicode-surface-variant);
    }
</style>
