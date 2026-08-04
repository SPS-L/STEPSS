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
