<template>
  <div class="markdown-body" v-html="rendered"></div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  content: { type: String, default: '' }
})

marked.setOptions({ breaks: true, gfm: true })

// 让所有链接在新标签打开且带安全属性。
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

const rendered = computed(() => {
  const raw = props.content || ''
  if (!raw.trim()) return ''
  const html = marked.parse(raw)
  return DOMPurify.sanitize(html)
})
</script>

<style scoped>
.markdown-body {
  font-size: 13px;
  line-height: 1.75;
  color: #303133;
  word-break: break-word;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 18px 0 8px;
  font-weight: 700;
  line-height: 1.4;
  color: #1f2d3d;
}
.markdown-body :deep(h1) { font-size: 19px; }
.markdown-body :deep(h2) {
  font-size: 16px;
  padding-bottom: 4px;
  border-bottom: 1px solid #ebeef5;
}
.markdown-body :deep(h3) { font-size: 14px; color: #303133; }
.markdown-body :deep(h4) { font-size: 13px; color: #5a6573; }
.markdown-body :deep(p) { margin: 8px 0; }
.markdown-body :deep(ul),
.markdown-body :deep(ol) { margin: 8px 0; padding-left: 22px; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(strong) { color: #1f2d3d; font-weight: 700; }
.markdown-body :deep(em) { color: #606266; }
.markdown-body :deep(a) { color: #409eff; text-decoration: none; word-break: break-all; }
.markdown-body :deep(a:hover) { text-decoration: underline; }
.markdown-body :deep(code) {
  background: #f4f6f8;
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 12px;
  color: #c7254e;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.markdown-body :deep(pre) {
  background: #f6f8fa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  margin: 10px 0;
}
.markdown-body :deep(pre code) { background: none; padding: 0; color: #303133; }
.markdown-body :deep(blockquote) {
  margin: 10px 0;
  padding: 4px 12px;
  border-left: 3px solid #c0c4cc;
  background: #fafafa;
  color: #606266;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
  width: 100%;
  font-size: 12px;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #ebeef5;
  padding: 6px 10px;
  text-align: left;
}
.markdown-body :deep(th) { background: #f5f7fa; font-weight: 600; }
.markdown-body :deep(hr) { border: none; border-top: 1px solid #ebeef5; margin: 14px 0; }
.markdown-body :deep(> *:first-child) { margin-top: 0; }
.markdown-body :deep(> *:last-child) { margin-bottom: 0; }
</style>
