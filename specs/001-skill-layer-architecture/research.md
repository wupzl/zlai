# Research: Skill Layer Architecture

## Question

How should zlAI use Specify Kit to model skill-layer work differently from ordinary feature work?

## Option A: Keep Ordinary Feature Templates Unchanged

### Pros

- No template migration cost
- Lowest immediate effort
- Existing scripts keep working without any update risk

### Cons

- Skill-layer concerns remain implicit
- Reviewers must remember extra questions manually
- Future skill features will drift in structure and quality
- Fallback and observability are easy to forget

## Option B: Extend Specify Kit Templates With Skill-Layer Sections

### Pros

- Keeps script-compatible file names
- Adds explicit skill type, trigger, contract, fallback, and traceability fields
- Makes skill-layer design review repeatable
- Provides a reusable example package for future work

### Cons

- Slightly more verbose templates
- Contributors must learn when to fill skill-specific sections

## Decision

Choose Option B.

The repository already has a functioning Specify Kit structure. The right move is not to invent a parallel system, but to extend the existing templates and constitution so skill-layer features are forced into a stronger design shape.
