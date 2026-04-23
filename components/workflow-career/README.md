# Thesium Career Workflow Resources

This component owns app-specific workflow resources for **Thesium Career**.

The initial scope is the first agentic workflow family over the career
knowledge-graph tool surface:

- `career-profile`

Later slices should add the sibling workflow resources for ranking and
Lens generation beside this component rather than pushing product
workflows back into the shared `workflow` component.

## What This Component Owns

- career workflow definitions under `resources/workflows/`
- career-owned workflow tests under `test/`

## Why This Exists

The shared `workflow` component is the kernel. Product workflow
families belong in app-owned components that compose into the kernel
through the classpath.

That keeps:

- product-specific workflow ids
- KG tool requirements
- policy-pack references
- phase budgets and workflow defaults

in data surfaces owned by the career product layer.
