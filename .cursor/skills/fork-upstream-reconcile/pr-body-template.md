# Reconcile develop with upstream/<target> (<timestamp>)

## References

- Upstream target and SHA: `<target>` / `<sha>`
- Before branch: `develop-reconcile-<timestamp>-before`
- After branch: `develop-reconcile-<timestamp>-after`
- Landing branch: `develop-land-<timestamp>`

## Decisions

| Group | # commits | Old SHAs | Status | Decision | New squashed SHA |
|---|---:|---|---|---|---|
| `<group>` | `<count>` | `<shas>` | `<status>` | `<decision>` | `<sha>` |

## Dropped or adapted changes

- `<change>`: `<reason and supporting evidence>`

## Conflict resolutions

- `<group>`: `<decision and rationale>`

## Validation

- `build/sbt generate` completed twice; second run clean: `<yes/no>`
- Targeted tests: `<commands and results>`
- Full tests: `<commands and results, or reason not run>`
- Workflow file-set comparison reviewed: `<yes/no>`

## Landing invariants

- Landing tree equals the reviewed after tree: `<yes/no>`
- First parent equals the unchanged before/develop SHA: `<yes/no>`
- Second parent equals the reviewed after SHA: `<yes/no>`
- Upstream target is an ancestor of the reviewed after SHA: `<yes/no>`
- `origin/develop` still equals the recorded before SHA immediately before merge: `<yes/no>`
- PR must use **Create a merge commit**, not squash or rebase merge.

## Follow-up and observations

- `<anything the next reconciliation must re-verify>`
