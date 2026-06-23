# Project Skills

This directory contains agent skills that should travel with the repository.
When another developer clones the project, their agent can load these skills from
`skills/<skill-name>/SKILL.md` instead of relying on one person's global setup.

## Included

- `codegraph` - Prefer the CodeGraph knowledge graph for structural code
  questions before falling back to text search. Fresh clones may need the
  project to initialize `.codegraph/` locally with `codegraph init -i`.
- `grill-with-docs` - Also discoverable by the phrase `grill-with-doc`;
  pressure-test plans against project terminology and capture durable domain
  decisions in `CONTEXT.md` or ADRs.
- `self-improving-agent` - Capture reusable learnings, errors, and feature
  requests.
