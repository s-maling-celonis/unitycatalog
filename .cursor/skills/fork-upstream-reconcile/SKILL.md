---
name: fork-upstream-reconcile
description: >-
  Reconciles the Celonis private fork (celonis/unitycatalog) with
  unitycatalog/unitycatalog: synchronizing main, classifying Celonis-only
  changes, rebuilding approved changes on the latest upstream tree, and
  landing that tree while preserving upstream ancestry. Use when the user asks
  to rebase against upstream, sync the fork, update main or develop from
  upstream, or reconcile Celonis changes with Unity Catalog upstream.
---

# Fork ↔ Upstream Reconciliation

## Purpose and invariants

Use a high-reasoning model for semantic classification and conflict decisions.
Verify all repository facts on every run.

- `origin` is the Celonis fork; `upstream` is the public project.
- `main` must equal the selected upstream target plus exactly one commit that
  deletes every workflow. It contains no feature code.
- `develop` contains upstream ancestry, approved Celonis changes, and Celonis
  build/publish workflows.
- A protected `develop` cannot be truly rebased through a PR. This workflow
  creates a reconciliation merge whose tree equals a reviewed upstream-based
  rebuild and whose parents preserve both histories.

## Safety rules

- Never mutate the user's active worktree. Use recorded, disposable worktrees
  for every checkout, cherry-pick, generation, test, and conflict resolution.
- Separate analysis from writes. Get explicit confirmation before rewriting
  `main`, and again before rebuilding or publishing reconciliation branches.
- Never use plain `--force`. A history replacement requires an explicit lease
  tied to the previously observed remote SHA.
- Never land if `origin/develop` moved after the safety snapshot.
- Treat prior decisions and repository rules as evidence to re-verify, not
  permanent facts.
- Preserve source-SHA and PR traceability for every reapplied group.

## Recorded state

Record these values once at the indicated gates; never silently recompute one
immediately before a destructive operation:

- `UP` and `UP_SHA`: selected upstream ref and immutable SHA
- `TS`: timestamp shared by all reconciliation branches
- `EXPECTED_MAIN`: observed `origin/main` SHA before its rewrite
- `MAIN_REWRITE`: reviewed main-rewrite branch
- `BEFORE_SHA`, `AFTER_SHA`, and `DEV`: reconciliation tips
- `LAND` and `EXPECTED_LAND`: landing branch and its last successfully pushed
  SHA

Before any safety-critical snippet, require every referenced variable to be
non-empty, require every recorded SHA to resolve to a full commit object, and
verify that each branch points to its recorded SHA.

## Workflow

```text
0. Preflight and gather current state
1. Rewrite main
2. Inventory and group Celonis changes
3. Classify groups against current upstream
4. Present findings
5. Resolve semantic conflicts
6. Select included groups and confirm
7. Build the reviewed after branch
8. Validate it
9. Create the reconciliation PR
10. Iterate on CI, merge, verify, and clean up
```

### Step 0: Preflight

Fetch both remotes, select the exact upstream target as `UP`, and record
`UP_SHA`. Inspect the active worktree without modifying it, including
unfinished Git operations. Verify remote URLs, default branch, applicable
rulesets, permitted merge methods, divergence, and merge bases.

Record current divergence without assuming `main` is already synchronized.
Inspect every main-only commit and all non-workflow tree differences.

Read the latest merged reconciliation PR and any legacy rebase PR. Its
self-contained group table is the starting hypothesis for this run, but
re-evaluate every group against current upstream.

### Step 1: Rewrite main

Do this only after explicit confirmation.

1. Record the current `origin/main` SHA as `EXPECTED_MAIN`.
2. Create `MAIN_REWRITE` from `UP` and a timestamped backup at `origin/main`.
   Preserve all drift, including workflows and binary content. Keep the backup
   locally by default until post-merge verification. Push it remotely only
   after considering whether that branch push would trigger workflows.
3. Confirm that every non-workflow main-only change already exists on
   `develop`/upstream or is selected for later porting.
4. In a disposable worktree on `MAIN_REWRITE`, delete every workflow and
   create one workflow-deletion commit.
5. Verify that its tree differs from `UP` only by workflow deletions and that
   it is exactly one commit ahead. The resulting divergence must be zero
   upstream-only commits and exactly one main-only commit.
6. Replace remote `main` with this explicit lease:

```bash
git push --force-with-lease="refs/heads/main:$EXPECTED_MAIN" \
  origin "${MAIN_REWRITE}:main"
```

If the lease fails, fetch, re-evaluate drift, and obtain confirmation again.

### Step 2: Inventory and group changes

Do not use every commit reachable from `origin/develop` but not `UP` as the
authoritative change set. Reconciliation merges retain old and reviewed
histories, so that range accumulates duplicate implementations.

Start from the prior PR's upstream base, reviewed `-after` tip, group decisions,
and final merge commit. Verify that the prior `-after` tip is an ancestor of
`origin/develop`; inventory approved commits between its upstream base and
`-after` tip, then add later first-parent work on `develop` and selected main
drift. Expand later merged PRs to their logical commits or diffs. Validate
completeness against the endpoint tree diff between `UP` and `origin/develop`.

On the first run, use the broad reachable range only as a candidate list and
reconcile it against the endpoint tree diff. For a legacy reconciliation that
did not retain its `-after` tip as a parent, use the retained branch or the
old/new SHA mapping in its PR. If neither exists, reconstruct candidates from
the recorded decisions and endpoint diff, then require user review.

Group logical changes by purpose, not author or date. Typical groups include:

- CI and release engineering
- Storage and credentials
- Authentication and authorization
- Rebranding and packaging
- Bug fixes and test-only changes

Record each group's source SHAs and dependencies. Ignore merge topology as a
logical group, inspect duplicate reapplications explicitly, and preserve
original topological order.

### Step 3: Classify against current upstream

For every group:

- Inspect current upstream history and the relevant call sites.
- Test applicability in a disposable worktree based on `UP`, restoring it to a
  clean `UP` state after every trial.
- Compare behavior rather than relying on textual similarity.
- Check whether retained Celonis workflows need adaptation to current modules,
  versions, shims, or publishing layout.

Assign one status, splitting mixed groups first:

| Status | Meaning |
|---|---|
| Still required — clean | Port unchanged |
| Still required — adapt | Preserve intent against changed upstream code |
| Superseded — drop | Upstream now provides equivalent behavior |
| Conflict — user decision | Upstream and Celonis requirements are incompatible |

### Steps 4–6: Decide

Present `Group | # commits | Status | Notes/risk`, with detailed evidence and
SHA mappings below it.

For conflicts and ambiguous adaptations, explain the concrete behavioral
tension and offer 2–3 resolution options using `AskQuestion`. Never choose a
semantic resolution silently.

Then use one multi-select `AskQuestion` for group inclusion. Recommend
still-required groups, omit superseded groups while allowing objections, and
get explicit confirmation before any Step 7 write.

### Step 7: Build the reviewed branch

Create timestamped branches sharing `TS`:

- `develop-reconcile-<TS>-before` points exactly to the observed
  `origin/develop`; never commit to it and preserve it as the safety snapshot.
- `develop-reconcile-<TS>-after` starts exactly at `UP` in a recorded
  disposable worktree.

Replay each approved group in original dependency/topological order as one
squashed commit. Its message must list all replaced SHAs and PRs. If a new
semantic conflict appears, return to Step 5.

Reconcile Celonis workflow deletions as well as additions. The final workflow
set should match the pre-reconciliation `develop` set except for explicitly
approved additions/removals. A `disabled_` filename does not disable a GitHub
workflow.

Keep the group → old SHAs → new SHA mapping for the PR.

### Step 8: Validate

Run all validation inside the `-after` worktree:

1. Run `build/sbt generate`, commit legitimate generated changes, and run it
   again to prove the second run is clean.
2. Compile and run targeted tests for changed modules.
3. Run the full suite when practical.
4. Compare the complete workflow file set before and after rebuilding.

Repository-specific traps to verify:

- Generated OpenAPI clients can become stale after upstream version changes.
- Dropping a shared API specification can break retained generated or
  handwritten code; restore only the required contract, not a superseded group.
- Celonis workflows can depend on upstream modules, Spark-version shims,
  versions, and publishing layout.

Report every check that could not run and why. Push the reviewed `-after`
branch only after validation and confirmation.

### Step 9: Create the reconciliation PR

Fetch `origin/develop` immediately before constructing the landing commit.
Resolve and record `DEV`, `BEFORE_SHA`, `AFTER_SHA`, and `UP_SHA`.

Stop unless `DEV` equals `BEFORE_SHA`. Create a reconciliation commit whose
tree equals `AFTER_SHA`, first parent is `DEV`, and second parent is
`AFTER_SHA`:

```bash
test "$DEV" = "$BEFORE_SHA" || exit 1

NEW=$(git commit-tree "$AFTER_SHA^{tree}" -p "$DEV" -p "$AFTER_SHA" \
  -m "Reconcile develop with $UP ($TS)")
```

The second parent retains the reviewed commits; because `AFTER_SHA` descends
from `UP_SHA`, it also retains upstream ancestry. Verify all invariants:

```bash
git diff --exit-code "$NEW" "$AFTER_SHA" || exit 1
test "$(git rev-parse "$NEW^1")" = "$DEV" || exit 1
test "$(git rev-parse "$NEW^2")" = "$AFTER_SHA" || exit 1
git merge-base --is-ancestor "$UP_SHA" "$NEW" || exit 1
```

Set `LAND=develop-land-$TS`, point it to `NEW`, and push it normally. After the
push succeeds, record the pushed SHA as `EXPECTED_LAND`. Open a PR explicitly
against `celonis/unitycatalog:develop`, deriving the title from `UP` rather than
hardcoding `upstream/main`.

Use [pr-body-template.md](pr-body-template.md). The PR body is the canonical
record and must include bases, branch names, group decisions, mappings,
conflict resolutions, validation, and landing invariants.

Try the normal GitHub CLI PR-body edit and verify the result. If it fails
because of the installed CLI or repository integration, update the same PR
through the GitHub REST API and verify it again.

The PR must use **Create a merge commit**. Squash or rebase merge can discard
the reconciliation commit's second parent. If merge commits are disabled or
linear history is required, stop and present an admin-gated true rewrite.

### Step 10: Iterate, merge, and clean up

Watch PR checks and diagnose failures. Commit normal fixes to `-after` and push
them normally. If `-after` history is intentionally rewritten, use an explicit
lease.

Before rebuilding `LAND`, repeat the `origin/develop == BEFORE_SHA` gate and
all Step 9 invariants. Fetch the exact remote landing ref and record it as
`REMOTE_LAND`. Stop unless it still equals the last successfully pushed
`EXPECTED_LAND`; do not use the current local branch as lease authority:

```bash
test "$REMOTE_LAND" = "$EXPECTED_LAND" || exit 1
git branch -f "$LAND" "$NEW"
git push --force-with-lease="refs/heads/$LAND:$EXPECTED_LAND" origin "$LAND"
```

After a successful update, set `EXPECTED_LAND` to the newly pushed SHA.

Immediately before merge, repeat the develop equality gate. After merge:

- Fetch `origin/develop`.
- Require its tree to equal `AFTER_SHA`.
- Verify `UP_SHA` and the reviewed `AFTER_SHA` are ancestors.
- Record any material CI fixes in the PR body.
- After user confirmation, remove clean disposable worktrees and prune them.
- Retain timestamped branches until separately approved for deletion.

## Final guardrails

- Same-file overlap is evidence, not proof of supersession.
- Never omit traceability from squashed commits or the PR body.
- Never silently skip validation or infer user choices.
- Never substitute a plain force-push, a direct `-after` merge, or a
  single-parent content-replacement commit.
