# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260621-001] best_practice

**Logged**: 2026-06-21T15:40:38Z
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
uni-app x nvue 样式里不要用 `margin-left: auto`、`margin-right: auto` 或 `margin: auto` 做居中。

### Details
HBuilderX 5.07 的 nvue CSS 编译器会报错：`property value auto is not supported for margin-left/margin-right`。在 `pages/cameraX/index.nvue` 这类 App Android nvue 页面中，居中布局应改为外层容器控制，例如 `position: absolute; left: 0; right: 0; flex-direction: row; justify-content: center;`，或用固定宽度容器配合 flex 对齐，不能套用 Web CSS 的 `margin: auto` 居中习惯。

### Suggested Action
修改 nvue UI 前先检查是否引入 `margin auto`。结构测试已增加扫描 `pages` 和 `uni_modules/xyc-markvideo` 下 `.nvue/.vue` 文件的守卫，防止再次提交不兼容写法。

### Metadata
- Source: error
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- Tags: uni-app-x, nvue-css, hbuilderx-5.07, android-ui
- Pattern-Key: uniappx.nvue.margin_auto_unsupported
- Recurrence-Count: 1
- First-Seen: 2026-06-21
- Last-Seen: 2026-06-21

### Resolution
- **Resolved**: 2026-06-21T15:40:38Z
- **Commit/PR**: pending
- **Notes**: Current `cameraX` UI uses a full-width wrapper plus flex centering for the mode switch, and tests now forbid `margin auto` in active nvue/component surfaces.

---
