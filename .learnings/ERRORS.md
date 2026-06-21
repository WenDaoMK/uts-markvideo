# Errors

Command failures and integration errors.

---

## [ERR-20260621-001] hbuilderx_nvue_css_compile

**Logged**: 2026-06-21T15:40:38Z
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
HBuilderX nvue CSS 编译阶段不支持 `margin-left/right: auto`，会在运行编译时输出 nvue-css error。

### Error
```text
[plugin:vite:nvue-css] ERROR: property value `auto` is not supported for `margin-left`
[plugin:vite:nvue-css] ERROR: property value `auto` is not supported for `margin-right`
```

### Context
- Operation: HBuilderX 5.07 运行 `uts-markvideo`
- Surface: `pages/cameraX/index.nvue`
- Trigger: mode switch 居中布局曾使用 Web 风格 `margin-left: auto; margin-right: auto;`
- Correct route: use nvue-supported flex/absolute wrapper centering instead of auto margins.

### Suggested Fix
Remove `margin-left/right: auto`; center fixed-width controls through a full-width parent using `justify-content: center`. Add static tests that fail on `margin auto` in nvue/component files.

### Metadata
- Reproducible: yes
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- See Also: LRN-20260621-001

### Resolution
- **Resolved**: 2026-06-21T15:40:38Z
- **Commit/PR**: pending
- **Notes**: Current source no longer contains `margin auto`; regression guard added to `test/structure.test.mjs`.

---
