# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Umbrella repo for the STEPSS simulation platform (https://stepss.sps-lab.org). It contains **no code of its own**: every `stepss-*` directory is a git submodule pointing at the matching repo in the SPS-L GitHub org, pinned to a commit and tracking that repo's default branch (a mix of `main` and `master`; see `.gitmodules`). Seven components are private: ramses, pfc, helios, Codegen, dyngraph, license-gen, test-systems.

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

- Code changes belong in the component repos, not here. Work inside `stepss-<name>/` as a normal git repo: commit and push there first, then `git add stepss-<name>` and commit the pointer bump here. Never pin a commit that hasn't been pushed to the component's remote, because that breaks cloning for everyone else.
- Most components have their own `CLAUDE.md`, and those apply when working inside them: cg-studio, Codegen, docs, dyngraph, helios, python-ui, ramses, test-systems, uramses, userguide. The seven without one are java-ui, pfc, license-gen, eigenanalysis, RamsesNN, apt and scoop. That list goes stale every time a component gains one, so check rather than trust it:
  ```sh
  for d in stepss-*/; do [ -f "$d/CLAUDE.md" ] && echo "${d%/}"; done
  ```
- A commit touching a `stepss-*` path in this repo is only ever a pointer (gitlink) change. If `git status` here shows a component as modified, that's uncommitted work inside the component; resolve it there.
- Never commit `.claude/` directories: one stray committed gitlink under `.claude/worktrees/` in a component previously broke `git clone --recurse-submodules` for the whole umbrella. A component should gitignore `/.claude/` and `.mcp.json`, and a new one needs both lines before its first commit. Every component carries both today, which was not true when this note was written (pfc had neither; helios, license-gen and test-systems covered `.claude` alone), so the check below should print nothing at all and any name it does print is a regression rather than a known gap:
  ```sh
  # prints every component missing one of the two lines
  for d in stepss-*/; do
    grep -q 'claude' "$d.gitignore" 2>/dev/null &&
      grep -q 'mcp.json' "$d.gitignore" 2>/dev/null || echo "${d%/}"
  done
  ```

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
| `pypa/gh-action-pypi-publish` | `@release/v1` (rolling tag, leave as is) |

Pin the **major** only (`@v7`, not `@v7.0.1`) so patch fixes arrive without a commit. `pypa/gh-action-pypi-publish` is the one exception: upstream publishes it through a rolling `release/v1` branch.

Rules for keeping this uniform:

- **Never pick a version by incrementing the major.** Two of the entries above are not the obvious next number, and both would have silently left the deprecation in place: `download-artifact` v5 *and* v6 still declare `node20` (v7 was the first that did not, and v8 is now current), and `upload-pages-artifact` is a composite action with no runtime of its own that warned through the `upload-artifact@v4` it called internally. Check the actual `action.yml` before trusting a version:
  ```sh
  curl -s https://raw.githubusercontent.com/actions/download-artifact/v8/action.yml | grep using:
  ```
  Anything reporting `node20` is deprecated; GitHub force-runs it on Node 24 and annotates every run.
- **A green run is not proof the warning is gone.** Deprecation notices surface as run *annotations*, not as log lines or failures. Check with `gh run view <run-id> --repo SPS-L/<repo>` and read the `ANNOTATIONS` section; a clean run prints none.
- **`download-artifact@v8` errors on a digest mismatch** where earlier versions only warned (`digest-mismatch` defaults to `error`). A corrupted artifact now fails the run instead of passing quietly. Do not downgrade this to silence a red run; investigate the artifact.
- **`checkout@v6+` stores persisted credentials in a separate file.** Workflows that `git push` in a later step rely on those credentials rather than passing a token explicitly: currently java-ui `release.yml`, uramses `sync-ramses-release.yml` and python-ui `sync-upstream-release.yml`. Those are the runs to check first after any `checkout` bump.
- **Bumping actions in a release workflow does not test it.** Codegen, dyngraph and ramses only run their release workflow on `release`/`workflow_dispatch`, and java-ui's runs on `repository_dispatch`/`workflow_dispatch`, where a manual dispatch *always cuts* a release, publishing it once all three bundle legs succeed; python-ui `python-publish.yml` publishes to PyPI. Those paths get exercised by the next genuine release, not by a test run.

## Secrets and cross-repo contracts

Every cross-repo call uses one secret name, **`STEPSS_TOKEN`**, held in each repo that needs it. It is what ramses dispatches to uramses and python-ui with, what ramses, helios, dyngraph and codegen dispatch to java-ui with, what codegen dispatches to cg-studio with, what java-ui dispatches to apt and scoop with, what uramses, python-ui and cg-studio read upstream releases with, and what java-ui checks out and re-pins components with. It is the only **token** anywhere in `stepss-*`; anything else that looks like one is GitHub's built-in token, spelled `${{ github.token }}` everywhere. Verify both halves with:

```sh
grep -rho 'secrets\.[A-Z_]*' stepss-*/.github/workflows/*.yml | sort -u
# expect STEPSS_TOKEN and APT_GPG_PRIVATE_KEY, and nothing else
```

**`APT_GPG_PRIVATE_KEY` is the one other configured secret, and it is not an exception to the rule.** It is a signing key rather than a credential for reaching another repository: it lives in `stepss-apt` alone, nothing else can use it, and there is nothing for it to share a name with. Do not fold it into `STEPSS_TOKEN`, and do not add a third name without the same justification.

Its public half belongs beside it, committed as `stepss-apt/sps-l-archive-keyring.asc`, and the two must always move together: users install the committed half once and never touch it again, so rotating the secret without committing the matching `.asc` breaks `apt update` for every one of them. The publish workflow refuses to run when the two fingerprints disagree, and warns a year before the key expires, which is the other way this breaks for everybody at once.

The key in use is `125ABE6917ED2F2965D6545B3600A756077AD061`, `SPS-L Packaging <stepss@sps-lab.org>`, RSA-4096, and it **expires on 2031-08-15**. Every user's `apt update` fails on the same day if it is allowed to. `stepss-apt/README.md` carries the rotation commands, and the private half lives offline and in the secret, never in a working tree: it exports as plain text and one `git add -A` publishes it.

Keeping the built-in on one spelling is the point: `${{ secrets.GITHUB_TOKEN }}` is the same value, but mixing the two spellings makes that grep answer "which of these is a real secret?" wrongly, which is how three divergent PAT names went unnoticed. PyPI and Pages publishing use OIDC trusted publishers (`id-token: write`) and no token at all; a trusted publisher binds to the workflow **filename**, so renaming a publishing workflow breaks it.

Five failure modes to know about, because none of them is loud:

- **A dispatch under a name the repo has no secret for 401s**, and the downstream simply never hears about the release. Nothing fails on the receiving side, because nothing arrives. When renaming or rotating, change the workflow reference and the repository secret in the same pass, in every repo.
- **codegen has two downstreams, not one.** `notify-java-ui` and `notify-cg-studio` both hang off `publish`, deliberately not off each other: java-ui re-pins CODEGEN inside the desktop bundle, and cg-studio refreshes the three executables in its PyPI wheel and derives its own version from the tag (CODEGEN v5.3 makes the next cg-studio release 5.3.0, a python-only change after it 5.3.1). That is why **CODEGEN versions are `vX.Y` from v5.3 onwards** and its release workflow rejects a three-component tag: a third component there becomes a fourth in every wheel. cg-studio needs `STEPSS_TOKEN` of its own too, because stepss-Codegen is private and the built-in token cannot read its release assets.

- **java-ui is now dispatch-driven, and dispatches do not retry.** It used to re-pin its five components off a daily schedule, which quietly absorbed any lost dispatch within 24 h. It now runs only on `repository_dispatch` from ramses, helios, dyngraph and codegen (uramses is covered by the ramses dispatch, since it only ever releases under the same tag), so a 401 or a dropped event means java-ui silently stops tracking that component until someone runs it by hand. A red `notify-java-ui` in a component repo is a real failure. Check which repos actually hold the secret before assuming an edge works:
  ```sh
  for r in ramses helios dyngraph Codegen; do
    printf '%-10s ' "$r"; gh api "repos/SPS-L/stepss-$r/actions/secrets" --jq .total_count
  done
  ```
- **The ramses → java-ui edge is ordered, not immediate.** java-ui pins ramses *and* uramses, and ramses dispatches to both java-ui and uramses from the same `publish` job, so java-ui wakes several minutes before uramses has published its matching tag. java-ui's first step waits for that tag (15 min ceiling, then fails loudly) so the two pins move together. Do not "optimise" that wait away, and do not let a uramses sync failure fall through to a bump.
- **Release-asset names are a contract between two repos.** RAMSES publishes `ramses-libs-{linux,windows,macos-arm64}-<ver>.zip` and python-ui's `tools/update_ramses_libs.sh` hard-fails when one is missing. CODEGEN publishes `codegen-{linux-x86_64,windows-x86_64,macos-arm64}-<tag>.{tar.gz,zip}`, which java-ui's `versions.properties` patterns and cg-studio's `tools/update_codegen.sh` both hard-fail on. java-ui publishes `STEPSS-<ver>-windows.zip` and stepss-scoop builds its download URL out of that exact name. Renaming assets on one side alone breaks every sync, and a renamed asset exists only on releases cut *after* the rename, so older tags stop being re-syncable.
- **A STEPSS release is all platforms or none, and the draft is what enforces it.** java-ui's `release` job creates the release as a **draft**, which creates no tag and shows nothing to users. The three `bundles` legs attach their installers to that draft, building the pin **commit** rather than the tag, because the tag does not exist yet. A `promote` job then publishes it and fires both package-manager dispatches (`stepss-apt`, `stepss-scoop`). A plain `needs:` makes `promote` *skip* when any leg fails, and a skipped job leaves a run green, so a `discard` job deletes the draft and exits 1. A failed release therefore leaves no tag, no release and no half-published set, and re-running reuses the same version number: the `release` job removes an abandoned draft for the same version before creating one, checking `.draft` so a published release can never be deleted by a re-run. `discard` fires on `result == 'failure' || 'cancelled'` and deliberately not on `!= 'success'`, because *skipped* is the third value and `bundles` is skipped when the release job itself failed before any platform ran.

  This reverses an earlier deliberate arrangement, so do not "restore" it. The dispatches used to fire from inside their own matrix legs precisely so that a partial release still reached the package managers: `bundles` is `fail-fast: false`, its aggregate result fails whenever any platform fails, and v3.74.8 shipped a `.deb` and a `.dmg` and no `.msi`. The case against that is v3.74.17, which published the Windows artifacts and neither the `.deb` nor the `.dmg` after two legs hit a transient GitHub 500 fetching pinned payloads: apt went on serving the previous version while the release page advertised a new one, and it took a hand re-run of two legs plus four of stepss-scoop's workflow to repair. Skipping a dispatch is right when there is nothing worth announcing.

  `fail-fast: false` stays, so one run reports every broken platform rather than only the first. Both weekly `schedule:` fallbacks stay too, and remain affordable only because both runs are idempotent: apt rebuilds the same site from the same releases, and scoop rewrites three fields and commits nothing when they have not moved. One trap in the promote job: its cleanup step is gated on `steps.publish.outcome`, not a bare `failure()`, because once the draft is published the release is real and a later dispatch failure must not delete it.

- **Nothing in the release workflow addresses a release by tag, because a draft has not got one.** The `release` job creates the draft through `POST /repos/{owner}/{repo}/releases` rather than `gh release create`, purely for the id that comes back, and publishes it as the `release_id` job output. Every attach, the publish (`PATCH .../releases/<id>` with `draft=false`, `make_latest=true`) and both discards address that id. `tools/attach-asset.sh` in stepss-java-ui does the attaching and replaces an asset of the same name, which is what `gh release upload --clobber` used to buy.

  This is not stylistic. `gh release upload <tag>` resolves a published release with a direct `GET /releases/tags/{tag}`, but a draft answers that 404, so gh falls back to **listing every release in the repository and scanning it for a matching `tag_name`**. That makes each attach two calls, one of them a paginated listing, and it is what broke the first draft-based run: two legs attached to the draft and the third got `release not found` for a draft that demonstrably existed, then the discard step got the same answer and left the draft behind. Switching to a draft had quietly traded a one-shot direct read for a lookup on the least reliable endpoint in the API. Do not reintroduce `gh release <verb> "$VERSION"` anywhere between the draft's creation and its publication.

  Two exceptions, both correct: the abandoned-draft cleanup looks up by tag once, is allowed to find nothing, and is what makes a re-run reuse the number; and the URAMSES wait looks up a tag in `stepss-uramses`, which is a *published* release and has one. `attach-asset.sh` uses gh's built-in `--jq` rather than system `jq`, which is not on every runner, and the workflow calls it through `bash` so a missing exec bit on the Windows runner cannot matter.

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

`stepss-java-ui` carries no MATLAB path at all: no launcher, no generated
`.m`, no reference outside the archived design notes under
`docs/superpowers/`. The removal is done, so do not add a caveat describing
one as a known leftover, and do not go looking for the code to delete. This
paragraph replaced exactly such a caveat, which outlived the launcher it
described and then survived a rename that updated the filename in it without
checking whether the sentence was still true.

The Java interface's package is `my.stepss` and its main class `StepssUI`, as
of v3.74.7. RAMSES keeps its own name wherever it means the engine, so
`ramsesLicense.txt`, `ramsesExec`, `toolchain.ramses()` and `ramses.version`
are all correct and not leftovers.

The preferences node followed the package to `my.stepss.StepssUI`, and
`PreferenceMigration` is what made that safe: on the first call to
`preferences()` it copies **every** key out of the old `my.ramses.RamsesUI`
node and only then removes it. The first-run flag moved in the same pass, from
the empty string to `stepssFirstTime`, with its own migration. **Do not delete
either migration.** Installations older than this still have the old node on
disk, and a rename without the copy abandons every user's theme, window
geometry, working directory and licence acceptance rather than moving them.
Copying every key rather than an enumerated list is deliberate too: an
enumeration silently drops whatever is added later.

## Licensing is per component, not per platform

STEPSS is the umbrella. The two user interfaces, **stepss-java-ui** and **stepss-python-ui**, are Apache 2.0, as are uramses, eigenanalysis, cg-studio and dyngraph (RamsesNN is MIT). **cg-studio's source is Apache 2.0 but the wheel it publishes is not**: it bundles the CODEGEN executables, so `pip install stepss-cg-studio` distributes a proprietary binary, the same way the desktop bundle does. The engines are not: **RAMSES** is the property of the University of Liège and is proprietary, free for non-commercial use and capped at 1000 buses and 2 cores; **Helios** and **CODEGEN** are under Academic Public Licenses. `getting-started/license.md` in stepss-docs is the single owner of these facts.

Two consequences: never describe STEPSS as a whole as Apache 2.0, and never apply a blanket find-and-replace to a bundled licence file. The licence texts under `stepss-java-ui/src/my/stepss/` each name their own component as "the Software", and a rename that swept through them once left the RAMSES and CODEGEN licences both claiming to govern the Apache-2.0 Python package.

Five places now restate this for people who never see a release page, and all five are summaries that must keep pointing at `getting-started/license.md` rather than drifting into second copies: `stepss-java-ui/packaging/linux/copyright`, which ships as `/usr/share/doc/stepss/copyright` in the `.deb`; the landing page at `apt.sps-lab.org`; `Components: non-free` in `stepss-apt/conf/distributions`, which is the word every user copies into their own `.sources` file; the `license` field plus `notes` of `stepss-scoop/bucket/stepss.json`, which Scoop prints after every install; and `stepss-cg-studio/pyproject.toml`, whose `license` reads `Apache-2.0 AND LicenseRef-STEPSS-Academic-Public-License` and which carries **no** `License :: OSI Approved :: Apache Software License` classifier, because its wheel bundles the CODEGEN executables (the licence text ships beside them as `cg_studio/bin/LICENSE-CODEGEN`). `non-free` is deliberate: `main` is Debian's label for software meeting the Free Software Guidelines, which an archive carrying RAMSES does not. The Scoop manifest's `license` names the mixed terms for the same reason, and is the field most likely to be "corrected" to the `Apache-2.0` that `stepss-java-ui/LICENSE` says, which covers the Java source alone.
