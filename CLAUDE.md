# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Umbrella repo for the STEPSS simulation platform (https://stepss.sps-lab.org). It contains **no code of its own** — every `stepss-*` directory is a git submodule pointing at the matching repo in the SPS-L GitHub org, pinned to a commit and tracking that repo's default branch (a mix of `main` and `master`; see `.gitmodules`). Seven components are private: ramses, pfc, helios, Codegen, dyngraph, license-gen, test-systems.

Submodule URLs in `.gitmodules` are **relative** (`../<name>.git`) so the repo works over both SSH and HTTPS. Keep them relative when adding a component.

## Commands

```sh
# Sync all submodules to the commits pinned by this repo
git submodule update --init --recursive

# Move every submodule to the latest tip of its tracked branch
./update.sh
# ...then commit the bumped pointers:
git commit -am "Update components to latest" && git push

# Add a new component (use a relative URL and its default branch)
git submodule add -b <default-branch> ../<repo-name>.git <repo-name>
```

## Working in components

- Code changes belong in the component repos, not here. Work inside `stepss-<name>/` as a normal git repo: commit and push there first, then `git add stepss-<name>` and commit the pointer bump here. Never pin a commit that hasn't been pushed to the component's remote — it breaks cloning for everyone else.
- Several components (cg-studio, docs, helios, ramses, uramses, userguide) have their own `CLAUDE.md`; those apply when working inside them.
- A commit touching a `stepss-*` path in this repo is only ever a pointer (gitlink) change. If `git status` here shows a component as modified, that's uncommitted work inside the component — resolve it there.
- Never commit `.claude/` directories — one stray committed gitlink under `.claude/worktrees/` in a component previously broke `git clone --recurse-submodules` for the whole umbrella.

## CI workflows

Every component's workflows live in its own repo, but the action versions are kept **uniform across all of them**. When adding or editing a workflow anywhere in `stepss-*`, use these versions:

| Action | Version |
|---|---|
| `actions/checkout` | `@v7` |
| `actions/setup-python` | `@v7` |
| `actions/setup-node` | `@v7` |
| `actions/setup-java` | `@v5` |
| `actions/upload-artifact` | `@v7` |
| `actions/download-artifact` | `@v8` |
| `actions/upload-pages-artifact` | `@v5` |
| `actions/deploy-pages` | `@v5` |
| `softprops/action-gh-release` | `@v3` |
| `msys2/setup-msys2` | `@v2` |
| `pypa/gh-action-pypi-publish` | `@release/v1` (rolling tag — leave as is) |

Pin the **major** only (`@v7`, not `@v7.0.1`) so patch fixes arrive without a commit. `pypa/gh-action-pypi-publish` is the one exception: upstream publishes it through a rolling `release/v1` branch.

Rules for keeping this uniform:

- **Never pick a version by incrementing the major.** Two of the entries above are not the obvious next number, and both would have silently left the deprecation in place: `download-artifact` v5 *and* v6 still declare `node20` (v7 was the first that did not, and v8 is now current), and `upload-pages-artifact` is a composite action with no runtime of its own that warned through the `upload-artifact@v4` it called internally. Check the actual `action.yml` before trusting a version:
  ```sh
  curl -s https://raw.githubusercontent.com/actions/download-artifact/v8/action.yml | grep using:
  ```
  Anything reporting `node20` is deprecated; GitHub force-runs it on Node 24 and annotates every run.
- **A green run is not proof the warning is gone.** Deprecation notices surface as run *annotations*, not as log lines or failures. Check with `gh run view <run-id> --repo SPS-L/<repo>` and read the `ANNOTATIONS` section — a clean run prints none.
- **`download-artifact@v8` errors on a digest mismatch** where earlier versions only warned (`digest-mismatch` defaults to `error`). A corrupted artifact now fails the run instead of passing quietly. Do not downgrade this to silence a red run; investigate the artifact.
- **`checkout@v6+` stores persisted credentials in a separate file.** Workflows that `git push` in a later step rely on those credentials rather than passing a token explicitly — currently java-ui `release.yml`, uramses `sync-ramses-release.yml` and pyramses `sync-upstream-release.yml`. Those are the runs to check first after any `checkout` bump.
- **Bumping actions in a release workflow does not test it.** Codegen, dyngraph, ramses and java-ui only run their release workflow on `release`/`workflow_dispatch`, and a manual dispatch of java-ui's *always publishes* a release; pyramses `python-publish.yml` publishes to PyPI. Those paths get exercised by the next genuine release, not by a test run.
