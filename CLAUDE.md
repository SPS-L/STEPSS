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
- **`checkout@v6+` stores persisted credentials in a separate file.** Workflows that `git push` in a later step rely on those credentials rather than passing a token explicitly — currently java-ui `release.yml`, uramses `sync-ramses-release.yml` and python-ui `sync-upstream-release.yml`. Those are the runs to check first after any `checkout` bump.
- **Bumping actions in a release workflow does not test it.** Codegen, dyngraph and ramses only run their release workflow on `release`/`workflow_dispatch`, and java-ui's runs on `repository_dispatch`/`workflow_dispatch` — where a manual dispatch *always publishes* a release; python-ui `python-publish.yml` publishes to PyPI. Those paths get exercised by the next genuine release, not by a test run.

## Secrets and cross-repo contracts

Every cross-repo call uses one secret name, **`STEPSS_TOKEN`**, held in each repo that needs it. It is what ramses dispatches to uramses and python-ui with, what ramses, helios, dyngraph and codegen dispatch to java-ui with, what uramses and python-ui read upstream releases with, and what java-ui checks out and re-pins components with. It is the **only** configured secret referenced anywhere in `stepss-*`; anything else that looks like one is GitHub's built-in token, spelled `${{ github.token }}` everywhere. Verify both halves with:

```sh
grep -rho 'secrets\.[A-Z_]*' stepss-*/.github/workflows/*.yml | sort -u   # expect STEPSS_TOKEN alone
```

Keeping the built-in on one spelling is the point: `${{ secrets.GITHUB_TOKEN }}` is the same value, but mixing the two spellings makes that grep answer "which of these is a real secret?" wrongly, which is how three divergent PAT names went unnoticed. PyPI and Pages publishing use OIDC trusted publishers (`id-token: write`) and no token at all; a trusted publisher binds to the workflow **filename**, so renaming a publishing workflow breaks it.

Two failure modes to know about, because neither is loud:

- **A dispatch under a name the repo has no secret for 401s**, and the downstream simply never hears about the release. Nothing fails on the receiving side, because nothing arrives. When renaming or rotating, change the workflow reference and the repository secret in the same pass, in every repo.
- **java-ui is now dispatch-driven, and dispatches do not retry.** It used to re-pin its five components off a daily schedule, which quietly absorbed any lost dispatch within 24 h. It now runs only on `repository_dispatch` from ramses, helios, dyngraph and codegen (uramses is covered by the ramses dispatch, since it only ever releases under the same tag), so a 401 or a dropped event means java-ui silently stops tracking that component until someone runs it by hand. A red `notify-java-ui` in a component repo is a real failure. Check which repos actually hold the secret before assuming an edge works:
  ```sh
  for r in ramses helios dyngraph Codegen; do
    printf '%-10s ' "$r"; gh api "repos/SPS-L/stepss-$r/actions/secrets" --jq .total_count
  done
  ```
- **The ramses → java-ui edge is ordered, not immediate.** java-ui pins ramses *and* uramses, and ramses dispatches to both java-ui and uramses from the same `publish` job, so java-ui wakes several minutes before uramses has published its matching tag. java-ui's first step waits for that tag (15 min ceiling, then fails loudly) so the two pins move together. Do not "optimise" that wait away, and do not let a uramses sync failure fall through to a bump.
- **Release-asset names are a contract between two repos.** RAMSES publishes `ramses-libs-{linux,windows,macos-arm64}-<ver>.zip` and python-ui's `tools/update_ramses_libs.sh` hard-fails when one is missing. Renaming assets on one side alone breaks every sync, and a renamed asset exists only on releases cut *after* the rename, so older tags stop being re-syncable.

## Eigenanalysis moved into the engine

Small-signal stability analysis is performed by RAMSES, triggered by the `EIG`
disturbance or the `run_ssa` C entry. **The MATLAB tool that used to do it is
retired.** Do not reintroduce it, document it as an alternative, or describe
`stepss-eigenanalysis` as a tool a user installs: that repository now holds the
reference spectra and the validation suite the engine is checked against, and
its tests need neither MATLAB nor a RAMSES licence.

The one MATLAB-adjacent thing that remains is `capture_golden.m`, which
regenerates the reference data and runs under GNU Octave. It is internal
tooling, not a user path.

`stepss-java-ui` still contains the old MATLAB launcher in `RamsesUI.java`.
That is a known leftover pending removal, not a supported route; its
`examples/kundur-ssa/` README says so explicitly.

## Licensing is per component, not per platform

STEPSS is the umbrella. The two user interfaces, **stepss-java-ui** and **stepss-python-ui**, are Apache 2.0, as are uramses, eigenanalysis, cg-studio and dyngraph (RamsesNN is MIT). The engines are not: **RAMSES** is the property of the University of Liège and is proprietary, free for non-commercial use and capped at 1000 buses and 2 cores; **Helios** and **CODEGEN** are under Academic Public Licenses. `getting-started/license.md` in stepss-docs is the single owner of these facts.

Two consequences: never describe STEPSS as a whole as Apache 2.0, and never apply a blanket find-and-replace to a bundled licence file. The licence texts under `stepss-java-ui/src/my/ramses/` each name their own component as "the Software", and a rename that swept through them once left the RAMSES and CODEGEN licences both claiming to govern the Apache-2.0 Python package.
