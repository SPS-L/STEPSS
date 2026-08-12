# MATLAB-free eigenanalysis for STEPSS

Date: 2026-08-12
Status: approved design, not yet implemented
Components touched: `stepss-ramses`, `stepss-python-ui`, `stepss-java-ui`,
`stepss-eigenanalysis`, `stepss-docs`

## 1. Problem

Small-signal stability analysis in STEPSS currently requires MATLAB. Both user
editions depend on it:

- The **Python** edition exports the Jacobian (`sim.getJac()`,
  `stepss-python-ui/src/stepss/simulator.py:221-287`) and stops there. The user
  is directed to MATLAB.
- The **Java** edition launches MATLAB directly:
  `new ProcessBuilder("matlab", "-desktop", "-r", "ssa")`
  (`stepss-java-ui/src/my/ramses/RamsesUI.java:3117-3121`), after dropping the
  bundled obfuscated p-code `src/my/ramses/ssa.p` beside the exported
  `jac_*.dat` files.

The analysis itself lives in `stepss-eigenanalysis`, a MATLAB tool. This design
removes MATLAB from both editions.

## 2. What is actually being replaced

Only one path in `stepss-eigenanalysis/ssa.m` works end to end. Establishing
this matters, because "feature parity with the MATLAB tool" is the wrong target.

**Works (the `QZ` path).** `init.m` parses the four `jac_*.dat` files and builds
the descriptor pencil `(S, E)`; `calc_Jdyn.m` eliminates the algebraic variables
by Schur complement; `eigenvals_eig.m` calls three-output `eig`;
`analyze_results.m` filters, computes damping and participation factors and
plots; `loop_analysis.m` runs a blocking `input()` menu.

**Nominally runs, quietly wrong (the `ARP` path).** `eigenvals_eig_descr.m`
calls `eigs(S,E,Nx,'SM')` and then `eigs(S',E,Nx,'SM')` as two fully independent
solves, and `analyze_results.m` never pairs their eigenvalue orderings.
Participation factors additionally index rows `1..Ndiff` of full
descriptor-space vectors, which are network variables. The tool covers this by
declaring participation factors and mode shapes unavailable for `ARP`.

**Dead.** The `IRA` sigma-sweep and the `JDQR` call are commented out of
`ssa.m`; `eigenvals_jdqr.m` never writes its results back into the shared
state. The `DPS` decomposed backend calls `solve_decomposed`, which does not
exist anywhere in the repository, and `decomp_fact_system.m` indexes
`Sp(2*nbbus+adf(j):...)` while the shipped `*_struc.dat` files carry absolute
RAMSES addresses (`adf(1)` is 29, 1113 and 1653 in the three examples), so it
would index out of bounds even if the solve phase existed.

**One free performance win.** `calc_Jdyn.m` loops
`gygx(:,i) = gy\gx(:,i)` over all `Nx` columns, re-running the full sparse LU of
`gy` on every column. The replacement factors once and back-substitutes.

## 3. Constraints that shaped the design

**The Python package already has every ingredient.** `numpy`, `scipy` and
`matplotlib` are hard dependencies in `stepss-python-ui/src/setup.py`.
`scipy.sparse.linalg.eigs` is ARPACK, the same library MATLAB's `eigs` calls.
The package is pure Python over `ctypes` and auto-generates its bindings by
parsing `libs/ramses.h` (`simulator.py:114-191`), so a new C entry point in
RAMSES appears in Python with no binding work.

**The Java package has no numeric stack and no room in its jar.** Its only
third-party jars are commons-exec and commons-io. It has no Python runtime and
no JNI. It runs four native tools as subprocesses, unpacked from per-platform
archives embedded in a **31 MB fat jar carrying all three platforms**; the
individual payloads run from 28 KB to 11.6 MB. A PyInstaller-frozen scipy CLI
would be roughly 60 to 150 MB per platform, so shipping the Python tool to Java
as a frozen binary is not viable.

**RAMSES already holds the hard parts.** KLU is vendored in-tree
(`libs/libklu/`, wrapper `src/linalg/i_KLU.f90`, dispatch
`src/linalg/sparse_solvers.f90`), LAPACK is a hard dependency (`-lopenblas` at
`build/Makefile.gfortran:81`, static MKL at `build/Makefile.ifort:20`), and
`dump_jacobian` **already assembles the global unreduced DAE Jacobian in memory**
into the `NET_JACOB` COO arrays (`src/core/simul_decomp.f90:2708-2842`) before
writing anything to disk. ARPACK is not present anywhere in the tree.

**Scale splits the problem in two.** The shipped examples are small; the test
systems are not.

| System | Differential states | Algebraic | Dense eigensolve |
|---|---|---|---|
| `1link_island_*` fixture | 24 | 74 | trivial |
| `test_*` fixture (Nordic) | 312 | 346 | trivial |
| `py_*` fixture | 981 | 1,588 | milliseconds |
| Pegase (15,226 bus, 3,483 mach) | ~50k | ~30k | infeasible |
| ENTSOE (56,051 bus, 1,014 mach) | ~15-20k | ~112k | infeasible, `gy^-1 gx` alone is ~18 GB dense |

Above a few thousand states the reduced state matrix cannot be formed at all,
and the only route is shift-invert Krylov on the pencil directly.

## 4. Architecture

Responsibility is split so that each half lands where the machinery already
exists.

```
                  assemble (exists)      reduce + dgeev (new)
RAMSES  ---------------------------------------------------> results files
  |                                                              |
  | run_ssa() C entry                          EIG disturbance   |
  v                                                              v
Python stepss.ssa                                          Java Swing panel
  |  also: scipy shift-invert path for large systems
  v
matplotlib, notebook API
```

- **Engine** does the linear algebra it already has the libraries for: sparse
  reduction with KLU, dense eigensolve with LAPACK. No new dependency.
- **Python** owns the analysis and presentation layer, plus the large-system
  sparse path that the engine deliberately does not implement.
- **Java** consumes a results file. No new dependency, no payload growth.

### Decisions taken and their alternatives

| Decision | Alternative rejected | Reason |
|---|---|---|
| Numerics in the engine | Standalone native tool bundled like DYNGRAPH | Engine already assembles the matrix and links KLU and LAPACK; a new tool means a new repo, three-platform CI and a new payload |
| Java limited to the dense path | Sparse path in Fortran for both editions | Needs vendored ARPACK plus complex KLU or a 2n real formulation, and touches all three release bundles and the public URAMSES kit |
| Sparse path in Python only | Duplicate it in the engine | GUI use is small and medium systems; large-system work is Python-edition work |
| Columnar text results | JSON | java-ui has no JSON parser and adding one is a new dependency |

## 5. Component: RAMSES engine

### 5.1 Refactor

Split the existing decomposed `dump_jacobian`
(`src/core/simul_decomp.f90:2699-3310`) into three routines:

- `assemble_dae_jacobian` : the current lines 2708-2842, unchanged in effect.
  Calls `comp_factor_Jac(.false.,.false.)` and `adjust_Jac_TC`, leaving the
  unreduced DAE Jacobian in the `NET_JACOB` COO arrays.
- `write_jacobian_files` : the existing four-file dump, unchanged.
- `solve_small_signal` : new, described below.

Apply the same split to the integrated-scheme dump
(`src/core/simul_integr.f90:1427-1794`) so both schemes reach the new routine.

### 5.2 `solve_small_signal`

Work from the engine's **internal** gamma information, not from the exported
`jac_eqs.dat`. This is a correctness requirement, not a convenience: the
integrated scheme's dump writes only five columns (`simul_integr.f90:1475`) and
omits the gamma column that the decomposed scheme writes
(`simul_decomp.f90:2935`), which is why the MATLAB tool only ever worked under
`$SCHEME DE`.

Steps:

1. Build the differential and algebraic index sets, and the row permutation
   that puts each differential equation on the row matching the state it
   differentiates, so `E` is diagonal 0/1.
2. Extract the four blocks `fx` (Nx by Nx), `fy` (Nx by Na), `gx` (Na by Nx),
   `gy` (Na by Na) from the assembled COO arrays.
3. Factor `gy` **once** through the existing `sparse_solvers.f90` dispatch
   (`ana_jacob`, `fac_jacob`), then perform `Nx` back-substitutions
   (`subs_rhs`) to form `gy^-1 gx` as a dense Na by Nx array. If the
   factorization reports singularity, abort with an error naming the index-1
   assumption rather than returning garbage.
4. Form `A_sys = fx - fy * (gy^-1 gx)` as a dense Nx by Nx array.
5. Call `dgeev('V','V', ...)`, which returns eigenvalues plus **both** right and
   left eigenvectors in a single call with consistent ordering. This is the
   direct equivalent of MATLAB's three-output `eig`, which is what the working
   QZ path uses.
6. Unpack LAPACK's conjugate-pair column packing (a complex pair occupies two
   consecutive real columns of `VL` and `VR`) into complex eigenvectors.
7. Compute per-mode damping ratio `zeta = -Re(lambda) / |lambda|`, frequency
   `f = |Im(lambda)| / 2*pi` in Hz, and participation factors
   `p_ki = |w_ki * v_ki|` over differential states `k`, normalized so the
   largest entry of each mode is 1. This normalization matches the current
   MATLAB behaviour and makes the arbitrary per-vector scaling of `dgeev`
   irrelevant.
8. Write the results files of section 6.

### 5.3 Size guard

Dense memory is `Nx^2 * 8` bytes for `A_sys`, the same again for each of `VL`
and `VR`, plus `Na * Nx * 8` for `gy^-1 gx`. At `Nx = 5000` that is roughly
600 to 800 MB.

Add a settings keyword `$EIG_MAX_STATES <n>` parsed in `get_settings.f90`
alongside the existing `$SPARSE_SOLVER`, defaulting to **5000**. Above it,
`solve_small_signal` refuses with a message naming the state count, the limit,
and the Python shift-invert path as the route for larger systems.

### 5.4 Triggers

**Disturbance.** A new `EIG` command parallel to the existing `JAC`, parsed in
`apply_disturb` (`src/io/disturb.f90:272-289`):

```
1.000 EIG 'ssa'
```

Guarded on `$OMEGA_REF SYN` exactly as `JAC` is (`disturb.f90:273-278`), with
the same warning and early return under COI. Documented in
`stepss-ramses/docs/INPUT_FORMAT.md` beside the `JAC` entry at line 489.

**C API.** New entries in `src/api/c_interface.f90` and `libs/ramses.h`,
following the existing naming and out-parameter style:

```c
int run_ssa(char *basename, double real_limit, double pf_threshold);
int get_state_matrix_size(int *nx);
int get_state_matrix(int nx, double *a_sys);
```

`run_ssa` follows the `get_Jac()` precedent (`c_interface.f90:214-229`): set the
basename, advance `pause_time` by 1 ms, re-enter `ramses()`, write the results
files. `get_state_matrix` returns `A_sys` in column-major order for callers who
want the matrix itself; it is not written to disk by default because `Nx^2` in
text is bulky (7.7 MB for the 981-state fixture).

Because `stepss-python-ui` generates its ctypes bindings by parsing
`ramses.h`, these three entries become available in Python with no binding code.

## 6. Component: results file format

Three files per run, sharing a basename, matching the existing multi-file
`jac_val` / `jac_eqs` / `jac_var` convention. Plain fixed-width columnar text,
`#` comment lines. `numpy.loadtxt` skips `#` by default; Java skips them in one
line. Every file opens with a version line so a later format change fails
loudly rather than silently misparsing.

### 6.1 `<base>_modes.dat`

```
# STEPSS SSA modes v1
# nstates <Nx> nalg <Na> time <t> real_limit <r> pf_threshold <p>
#   index                       re                       im                     zeta                  freq_hz dom
```

One line per mode, format `(i8,1x,4(en24.15,1x),i2)`: index, real part,
imaginary part, damping ratio, frequency in Hz, and a dominance flag (1 when
`Re(lambda) > real_limit`). All modes are written; the flag records the filter
rather than applying it, so a front end can narrow the filter without
re-running. Widening it past the `real_limit` used at run time is a different
matter: the mode line is there, but the participation factors and mode shape
for a newly included mode are not, because sections 6.2 and 6.3 are written
only for dominant modes. Widening therefore requires a re-run, and a front end
must say so rather than silently showing an empty participation list.

**Name fields.** Names are written left-justified in their `a20` field and
truncated if longer. RAMSES component names carry no embedded spaces, so
parsers split on whitespace and must not depend on fixed column positions,
which a name at full width would break.

### 6.2 `<base>_pf.dat`

```
# STEPSS SSA participation factors v1
#    mode    state                       pf family device               variable
```

Format `(i8,1x,i8,1x,en24.15,1x,a8,1x,a20,1x,a20)`. Written only for dominant
modes and only for entries above `pf_threshold`, which keeps the file small.
Family, device and variable names are inlined so Java does not need to
cross-reference a separate variable file.

### 6.3 `<base>_ms.dat`

```
# STEPSS SSA mode shapes v1
#    mode    state                magnitude                angle_deg device
```

Format `(i8,1x,i8,1x,2(en24.15,1x),a20)`. Right-eigenvector entries for states
named `omega`, normalized so the largest magnitude within each mode is 1, with
the angle in degrees. This reproduces what `loop_analysis.m` plots as phasors.

## 7. Component: Python `stepss.ssa`

New module `stepss-python-ui/src/stepss/ssa.py`, exported through
`__init__.py`'s `__all__`, sitting flat beside `simulator.py` and
`extractor.py`. No new dependencies.

### 7.1 Entry points

```python
res = ram.runSSA(real_limit=-1.0, pf_threshold=0.05)   # engine path
res = stepss.ssa.read_results('ssa')                   # parse existing files
res = stepss.ssa.from_jacobian(A, E, names,            # pure scipy
                               method='dense')
res = stepss.ssa.from_jacobian(A, E, names,
                               method='shift-invert',
                               sigma=-0.5+3j, k=20)
```

`runSSA` is a method on `sim` and takes camelCase, matching the RAMSES-side
convention the package already follows. Module-level functions in `stepss.ssa`
are snake_case.

`from_jacobian` is the **large-system path**. `method='dense'` mirrors the
engine (factor `gy` once with `scipy.sparse.linalg.splu`, back-substitute,
`scipy.linalg.eig`). `method='shift-invert'` calls
`scipy.sparse.linalg.eigs(S, k=k, M=E, sigma=sigma)`, which performs a proper
generalized shift-invert and returns a matched set, fixing by construction the
independent-solve bug in the MATLAB `ARP` path.

### 7.2 Prerequisite change to `getJac()`

`getJac()` currently returns `(A, E)` and discards variable names: the Python
route writes only `py_val.dat` and `py_eqs.dat`, never the `jac_var.dat`
equivalent. Participation factors are unreadable without names, so `getJac()`
gains the ability to return family, device and variable name per column.

The two-value return stays the **default**, so existing user scripts and the
documented `A, E = ram.getJac()` form keep working unchanged.
`getJac(names=True)` returns the three-value form. This requires RAMSES to
write the variable-description file on the `get_Jac()` path, which today it
does only on the `JAC` disturbance path.

### 7.3 `SSAResult`

A dataclass holding numpy arrays and name lists, with:

- `.dominant(real_limit=None, damping_max=None)` returning a filtered view
- `.participation(mode, threshold=0.0)`
- `.mode_shape(mode)`
- `.plot_splane(damp_ratio=None)` drawing the scatter, the real-part limit line
  and the damping-ratio rays
- `.plot_mode_shape(mode)` drawing the phasor plot
- `.table()` returning the printable mode table

No blocking REPL. The interactive `loop_analysis.m` menu becomes an ordinary
API, which is what a notebook workflow wants.

## 8. Component: Java UI

### 8.1 Flow

The Analysis tab currently has two buttons, "Extract Jacobian matrix"
(`RamsesUI.java:1880`) and "Perform small signal stability analysis"
(`:1895`, disabled until the first has run). Collapse them into a single
"Run small-signal analysis" action. "Select Working Directory" (`:1907`) stays.

The action writes a bundled `.dst` resource (replacing
`src/my/ramses/dampJac.dst`) that mirrors the existing one with `EIG` in place
of `JAC`:

```
0.000 CONTINUE SOLVER TR 0.020 0.001 0. ALL
0.000 EIG 'ssa'
0.010 STOP
```

then runs RAMSES through the existing commons-exec path
(`RamsesUI.java:3720-3754`) and parses the three results files.

### 8.2 New classes

- `my.ramses.ssa.SsaResults` : parser for the three files, version-checked
  against the header line.
- `my.ramses.ssa.SsaPanel` : `JTable` of modes with a `TableRowSorter`, a
  Java2D s-plane scatter with the damping-ratio rays and the real-part limit
  line, and on row selection a participation-factor table and a Java2D
  mode-shape phasor plot.

### 8.3 Removals

Delete `src/my/ramses/ssa.p`, the MATLAB `ProcessBuilder`
(`RamsesUI.java:3117-3121`) and `ssaButton1ActionPerformed`
(`:3095-3101`). No dependency is added, and `versions.properties` does not
change because RAMSES is already pinned there.

## 9. Component: `stepss-eigenanalysis` becomes the validation suite

The single real risk in this design is two implementations, engine Fortran and
Python scipy, silently diverging. Rather than archiving the MATLAB repository,
repurpose it as the cross-implementation validation suite. It keeps its
Apache-2.0 licence and its place in `.gitmodules`.

```
stepss-eigenanalysis/
  legacy/      the .m sources, kept for provenance
  fixtures/    the three example input sets, unchanged
  golden/      reference outputs captured from the MATLAB QZ path
  tests/       pytest suite
```

### 9.1 Golden capture, which must happen first

The goldens can only be produced while MATLAB is still available. For each of
the three fixtures, run the current `ssa(...)` with `real_limit = -inf` and
export to text:

- `A_sys` (the `Jdyn` variable), which isolates a reduction error from an
  eigensolve error
- eigenvalues (`eigenvalsQZ`)
- right and left eigenvectors (`V_QZ`, `W_QZ`)
- participation factors as computed by `analyze_results.m`

**No implementation work starts before this is done and committed.**

### 9.2 Tests

- engine `A_sys` against golden `A_sys`
- Python dense `A_sys` against golden `A_sys`
- engine and Python eigenvalues against golden eigenvalues, sorted by
  descending real part then imaginary part
- participation factors against golden, after max-normalization
- Python shift-invert against Python dense, on the subset of modes within a
  radius of sigma
- residual check `||A_sys v - lambda v||` for every returned pair, which is an
  implementation-independent correctness check that does not depend on the
  goldens at all

Tolerances: eigenvalues `rtol=1e-8`, participation factors `atol=1e-6`,
residuals below `1e-10 * ||A_sys||`. These are stated as starting values to be
confirmed against the fixtures during implementation; the residual check is the
one that must hold unconditionally.

## 10. Documentation

- `stepss-docs/src/content/docs/user-guide/eigenanalysis.md` : full rewrite. It
  currently opens "a MATLAB-based tool" and documents `QZ`, `ARP` and `JDQR`.
- `stepss-docs/src/content/docs/python/api-reference.md` : add `runSSA` and the
  `stepss.ssa` surface, update `getJac()` for the third return value.
- `stepss-docs/src/content/docs/python/examples.md` : the "Eigenanalysis
  Workflow" section currently ends in a MATLAB call.
- `stepss-docs/src/content/docs/user-guide/disturbances.md` : add `EIG` beside
  the existing Jacobian export entry.
- `stepss-ramses/docs/INPUT_FORMAT.md` : `EIG` beside `JAC` at line 489.
- `stepss-eigenanalysis/README.md` : rewrite as the validation suite.

House style applies: no em-dashes in any of these repositories.

## 11. Sequencing

1. Capture and commit the MATLAB goldens (section 9.1). Blocking.
2. Engine: refactor `dump_jacobian`, add `solve_small_signal`, the `EIG`
   disturbance, the three C entries and `$EIG_MAX_STATES`. Validate `A_sys`
   against golden before touching the eigensolve.
3. Python: `getJac()` names, `stepss.ssa`, `runSSA`. Validate against golden and
   against the engine.
4. Java: parser, panel, removal of the MATLAB path.
5. Validation suite wired into CI in `stepss-eigenanalysis`.
6. Documentation.

Steps 3 and 4 are independent of each other once step 2 lands. Each of steps 2,
3 and 4 is a separate implementation plan against its own component repository;
this document is the shared contract between them, and the results format of
section 6 is the interface they agree on.

## 12. Risks

| Risk | Mitigation |
|---|---|
| `dgeev` conjugate-pair unpacking of `VL` produces plausible but wrong participation factors | Golden participation factors plus the residual check; validate `A_sys` separately first so a reduction bug cannot masquerade as an unpacking bug |
| Engine and Python implementations diverge over time | Shared fixtures and goldens in CI (section 9) |
| `gy` singular, DAE not index-1 | Detect the KLU factorization failure and abort with a message naming the cause |
| Dense memory blowup | `$EIG_MAX_STATES`, default 5000, with the memory formula documented |
| `mxnzel` (3,000,000, `src/utils/DIMENSIONS.f90:106-107`) exceeded on very large systems | Pre-existing limit, unchanged by this work; the size guard trips first in practice |
| Analysis changes now require an engine release | Engine emits raw quantities only; all filtering, ranking and presentation stay in Python and Java, which release independently |
| COI reference frame | `EIG` carries the same `$OMEGA_REF SYN` guard as `JAC` |

## 13. Non-goals

- Sparse eigensolving inside the engine. No ARPACK is vendored, no complex KLU
  path is wired.
- Large-system eigenanalysis from the Java GUI.
- The `JDQR` and `DPS` paths. Neither works today.
- The commented-out `IRA` sigma-sweep as a built-in feature. `from_jacobian`
  accepts a sigma, so a user who wants a sweep writes a loop.
- Bit-for-bit reproduction of MATLAB behaviour. Where the MATLAB tool is wrong,
  as in the `ARP` left and right pairing, the replacement is correct instead.
