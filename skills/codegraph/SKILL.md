---
name: codegraph
description: "Use CodeGraph's tree-sitter parsed knowledge graph for structural code questions, call chains, impact analysis, and focused architecture context. Use when asking where symbols are defined, what calls what, how code flows, what a change affects, or how a feature area works."
---

# CodeGraph

CodeGraph is a structural code index. Prefer it for symbol, call-chain, flow,
impact, and architecture questions so you do not rebuild the same context with
plain text search.

## Quick Reference

| Question | Tool |
|---|---|
| Where is X defined? / Find symbol named X | `codegraph_search` |
| What calls function Y? | `codegraph_callers` |
| What does Y call? | `codegraph_callees` |
| How does X reach/become Y? / trace the flow from X to Y | `codegraph_trace` |
| What would break if I changed Z? | `codegraph_impact` |
| Show me Y's signature / source / docstring | `codegraph_node` |
| Give me focused context for a task/area | `codegraph_context` |
| See several related symbols' source at once | `codegraph_explore` |
| What files exist under path/ | `codegraph_files` |
| Is the index healthy? | `codegraph_status` |

## Workflow

1. For architecture or "how does X work" questions, start with
   `codegraph_context`, then use one `codegraph_explore` for the surfaced
   symbols.
2. For specific flows, start with `codegraph_trace` from source to target, then
   use one `codegraph_explore` for the relevant bodies.
3. Use native text search such as `rg` only for literal strings, comments, log
   messages, generated text, or after a specific file is already open.
4. Do not rebuild CodeGraph paths with `rg` plus file reads when CodeGraph has
   already answered the structural question.

## Availability

- If CodeGraph tools are not visible, say that CodeGraph is unavailable in this
  session and fall back to `rg` plus file reads, unless the user can enable or
  initialize CodeGraph.
- If a project has no `.codegraph/` index or the server says the project is not
  initialized, ask whether to run `codegraph init -i`.
- If a CodeGraph response reports stale files, read only those listed files
  directly before relying on their contents.

## Boundaries

- CodeGraph is for code structure, not for arbitrary documentation lookup.
- Use `rg` first for exact strings, docs text, comments, logs, UI copy, or
  non-code files.
- If the index and on-disk files disagree, trust the fresh file contents and
  mention the stale-index risk.
