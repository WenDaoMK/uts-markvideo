# ADR Format

ADRs live in `docs/adr/` and use sequential numbering: `0001-slug.md`,
`0002-slug.md`, and so on.

In a single-context repo, that is the root `docs/adr/` directory. In a
multi-context repo, use the ADR directory that belongs to the active context
listed in `CONTEXT-MAP.md`.

Create the `docs/adr/` directory lazily — only when the first ADR is needed.

## Template

```md
# {Short title of the decision}

{1-3 sentences: what's the context, what did we decide, and why.}
```

That's it. An ADR can be a single paragraph. The value is in recording that a
decision was made and why, not in filling out sections.

## Optional sections

Only include these when they add genuine value. Most ADRs won't need them.

- **Status** frontmatter (`proposed | accepted | deprecated | superseded by
  ADR-NNNN`) — useful when decisions are revisited
- **Considered Options** — only when the rejected alternatives are worth
  remembering
- **Consequences** — only when non-obvious downstream effects need to be called
  out

## Numbering

Scan `docs/adr/` for the highest existing number and increment by one.

## When to offer an ADR

All three of these must be true:

1. **Hard to reverse** — the cost of changing your mind later is meaningful
2. **Surprising without context** — a future reader will look at the code and
   wonder why it was done this way
3. **The result of a real trade-off** — there were genuine alternatives and you
   picked one for specific reasons

If a decision is easy to reverse, skip it. If it's not surprising, nobody will
wonder why. If there was no real alternative, there is nothing to record beyond
"we did the obvious thing."

### What qualifies

- **Architectural shape.** Example: "We're using a monorepo."
- **Integration patterns between contexts.** Example: "Ordering and Billing
  communicate via domain events, not synchronous HTTP."
- **Technology choices that carry lock-in.** Database, message bus, auth
  provider, deployment target. Not every library, just ones that would be costly
  to swap.
- **Boundary and scope decisions.** Example: "Customer data is owned by the
  Customer context; other contexts reference it by ID only."
- **Deliberate deviations from the obvious path.** Anything where a reasonable
  reader would assume the opposite.
- **Constraints not visible in the code.** Compliance, partner contracts,
  latency requirements, deployment constraints.
- **Rejected alternatives when the rejection is non-obvious.** Record subtle
  trade-offs so they do not get reopened accidentally.
