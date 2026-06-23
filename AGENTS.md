# Project Agent Instructions

This file is the project entry point for AI coding agents. Follow it for work in
this repository.

## Project Boundary

- Treat the repository root of the current checkout as the project root.
- Read `README.md` before changing runtime code; it describes the current MVP
  branch, active plugin path, and deprecated routes.
- Keep changes small and tied to the current request.
- Do not restore deprecated `uni_modules/uts-markvideo`, `pages/index/index.vue`,
  or `pages/camera/camera.vue` routes unless the user explicitly asks.

## Repository Skills

Load project skills from `skills/<skill-name>/SKILL.md` when their trigger
matches the task:

- `skills/self-improving-agent/SKILL.md` - record command failures, corrections,
  knowledge gaps, and broadly useful lessons.
- `skills/codegraph/SKILL.md` - use CodeGraph for structural code questions,
  symbol lookup, call chains, flow tracing, and impact analysis when available.
- `skills/grill-with-docs/SKILL.md` - use when the user wants to stress-test a
  plan against project terms, docs, or decisions.

If an agent does not support automatic skill discovery, read the relevant
`SKILL.md` file directly before acting.

## Self-Improvement Log

This project tracks shared learnings in `.learnings/`.

Use `skills/self-improving-agent/SKILL.md` and append entries when any of these
are noticed or confirmed during agent work:

- A command, build, test, IDE action, device run, or integration step fails in a
  non-trivial way: write to `.learnings/ERRORS.md`.
- The user corrects an assumption, a project fact is discovered, or a recurring
  best practice is learned: write to `.learnings/LEARNINGS.md`.
- The user asks for a capability that is missing from the workflow or tooling:
  write to `.learnings/FEATURE_REQUESTS.md`.

Do not log secrets, tokens, private keys, raw full transcripts, or large command
outputs. Prefer concise summaries, reproduction steps, related file paths, and a
specific suggested fix.

## CodeGraph

- Prefer CodeGraph tools for structure questions: definitions, callers,
  callees, traces, impact, and focused architecture context.
- Use `rg` first for literal strings, docs text, comments, logs, UI copy, and
  non-code files.
- `.codegraph/` is intentionally ignored. Fresh clones may need to initialize a
  local index with `codegraph init -i` before CodeGraph tools can answer.
- If CodeGraph is unavailable, say so and fall back to `rg` plus direct file
  reads.

## Verification

- Run the narrowest meaningful check for the change.
- For general repository checks, `npm test` runs the current Node test suite.
- Do not claim code is fixed or passing without fresh verification evidence.
- Before committing, avoid `git add -A` when screenshots, `.serena/`, or other
  generated files are present. Stage only files that belong to the task.
