# In-Engine Small-Signal Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give RAMSES the ability to reduce its DAE Jacobian to a state matrix, solve the dense eigenproblem and write eigenvalues, damping, participation factors and mode shapes to file, so that both STEPSS editions get small-signal analysis without MATLAB.

**Architecture:** `dump_jacobian` already assembles the full unreduced DAE Jacobian into the `NET_JACOB` COO arrays before it writes anything. That assembly is split out and reused: a new module factors `gy` once with KLU, forms `A_sys = fx - fy*(gy^-1*gx)`, calls LAPACK `dgeev` for eigenvalues and both eigenvector sets, and writes three text files. No new third-party library: KLU is vendored and LAPACK is already a hard dependency.

**Tech Stack:** Fortran (gfortran, `build/Makefile.gfortran`), vendored SuiteSparse KLU via `src/linalg/i_KLU.f90`, OpenBLAS/MKL LAPACK, bash + python3 regression gates following `tools/nordic_gate.sh`.

**Source of truth:** `docs/superpowers/specs/2026-08-12-matlab-free-eigenanalysis-design.md`. Read it before starting.

## Global Constraints

- **Use `double precision`, not `real(kind=dp)`.** Both `CLAUDE.md` files mandate `real(kind=dp)`, but `dp` is defined nowhere in the tree and `real(kind=dp)` appears **0** times against **414** uses of `double precision`. Writing the documented form will not compile. Follow the code.
- `implicit none` in every program unit; all dummy arguments carry `intent`; all procedures live in modules; lowercase constructs; underscores for multi-word names; units in variable comments.
- Must compile cleanly with: `gfortran -O0 -fmax-errors=1 -Wall -Werror=unused-parameter -Werror=unused-variable -Werror=unused-function -Wno-maybe-uninitialized -Wno-surprising -fbounds-check -static -g`
- **No new third-party library.** Adding one would touch all three release bundles plus the public URAMSES kit.
- **Nothing proprietary enters `libramses`.** It ships compiled inside `uramses-modules_*`, which is committed to the public SPS-L/stepss-uramses. `tools/check_kit_no_proprietary.sh` is the backstop.
- No em-dashes in any file in any `stepss-*` repo. Verify with `grep -rnP '\x{2014}'`.
- Build with `make -f build/Makefile.gfortran all`; output lands in `build/Release_gnu_l/` on Linux.
- The `EIG` trigger keeps the `$OMEGA_REF SYN` guard that `JAC` has.

---

## File Structure

**stepss-eigenanalysis** (Phase 1)
- Create `fixtures/kundur_pss/`, `fixtures/kundur_nopss/`: exported `jac_*.dat` for both variants
- Create `golden/`: `*_Asys.txt`, `*_eigs.txt`, `*_pf.txt`, `*_states.txt` per fixture
- Create `tests/test_golden.py`: residual and self-consistency checks that need no RAMSES licence
- Modify `README.md`

**stepss-ramses** (Phase 2)
- Modify `src/utils/DIMENSIONS.f90`: reserve a KLU instance for SSA
- Modify `src/linalg/i_KLU.f90`: general-purpose COO factor/solve entry, because `ana_jacob` takes its dimension and COO slice from global network state and cannot factor an arbitrary matrix
- Modify `src/utils/MODULES.f90`: `eig_max_states` in `SETTINGS`
- Modify `src/io/get_settings.f90`: parse `$EIG_MAX_STATES`
- Modify `src/core/simul_decomp.f90`: split `dump_jacobian`; add `dumpeig` flag
- Create `src/core/ssa.f90`: `solve_small_signal`, the whole reduction, eigensolve and writer
- Modify `src/io/disturb.f90`: `EIG` case
- Modify `src/api/c_interface.f90`: `run_ssa`, `get_state_matrix_size`, `get_state_matrix`
- Modify `build/Makefile.gfortran`: compile `src/core/ssa.f90`
- Modify `docs/INPUT_FORMAT.md`
- Create `tools/compare_ssa.py`, `tools/kundur_ssa_gate.sh`
- Modify `.github/workflows/release.yml`: run the gate

**stepss-python-ui** (one step only, in Task 9)
- Modify `src/stepss/libs/ramses.h`: this file is **maintained by hand here**, not synced from stepss-ramses, whose `src/api/dllramses.h` is only a one-line Windows import stub. New C entries are invisible to Python until this file lists them.

---

## Phase 1: Reference data

### Task 1: Capture goldens for Kundur and the secondary fixtures

**Files:**
- Create: `stepss-eigenanalysis/fixtures/{kundur_pss,kundur_nopss}/jac_{val,eqs,var,struc}.dat`
- Create: `stepss-eigenanalysis/fixtures/{1link_island,test}/` (copied from `example/`)
- Create: `stepss-eigenanalysis/golden/*.txt`
- Create: `stepss-eigenanalysis/tests/test_golden.py`
- Modify: `stepss-eigenanalysis/README.md`

**Interfaces:**
- Consumes: `capture_golden.m` (already committed on branch `octave-compatibility`)
- Produces: `golden/<name>_Asys.txt` (`i j value` triplets, 1-based), `golden/<name>_eigs.txt` (`re im`, sorted by descending real then descending imaginary), `golden/<name>_pf.txt` (dense, rows = differential states, columns = modes, each column peaking at 1), `golden/<name>_states.txt` (`index family device variable`). Task 7 and every later validation step compare against these.

- [ ] **Step 1: Export both Kundur variants**

Requires a working `stepss` install. Run from a scratch directory:

```python
import stepss, shutil, os
for tag, dyn in (('kundur_pss', 'dyn.dat'), ('kundur_nopss', 'dyn_noPSS.dat')):
    case = stepss.cfg()
    for f in ('lf.dat', dyn, 'solveroptions.dat'):
        case.addData(f)
    case.addDst('jac.dst'); case.addObs('obs.dat'); case.addTrj(f'{tag}.trj')
    ram = stepss.sim()
    ram.execSim(case, 0.0)
    ram.contSim(0.01)
    ram.endSim()
    os.makedirs(tag, exist_ok=True)
    for suffix in ('val', 'eqs', 'var', 'struc'):
        shutil.move(f'jac_{suffix}.dat', f'{tag}/jac_{suffix}.dat')
```

with `jac.dst` containing exactly:

```
  0.000 CONTINUE SOLVER BD 0.01 0.0001 0. ALL
  0.001 JAC 'jac'
  0.010 STOP
```

Copy `lf.dat`, `dyn.dat`, `dyn_noPSS.dat`, `solveroptions.dat` and `obs.dat` from
`stepss-test-systems/stepss-Kundur-Two-Area-System`. Do not copy them into
`stepss-eigenanalysis`: only the exported `jac_*.dat` belong there, so the test
suite runs without a RAMSES licence.

- [ ] **Step 2: Verify the exports are usable**

Run: `grep -c NaN */jac_val.dat`
Expected: `0` for both. A nonzero count means the export is defective, as
`example/py_val.dat` is; stop and investigate rather than capturing a bad golden.

- [ ] **Step 3: Capture the goldens**

```sh
octave --no-window-system --quiet --eval \
  "addpath('.','scripts'); \
   capture_golden('fixtures/kundur_pss/jac',   'golden'); \
   capture_golden('fixtures/kundur_nopss/jac', 'golden'); \
   capture_golden('fixtures/test/test',        'golden'); \
   capture_golden('fixtures/1link_island/1link_island', 'golden', \
                  'fixtures/1link_island/1link_island_struc.dat')"
```

Expected: four `Nx=..., relative eigenpair residual ...` lines, all residuals
below `1e-10`, and `wrote 4 golden files` each time. `capture_golden` aborts on
a bad residual, so a silent pass is a real pass.

Rename the two Kundur outputs from `jac_*` to `kundur_pss_*` and
`kundur_nopss_*` so the four sets do not collide.

- [ ] **Step 4: Write the licence-free consistency test**

```python
"""Checks that hold on the goldens alone, with no RAMSES and no MATLAB."""
import numpy as np, pathlib, pytest

GOLDEN = pathlib.Path(__file__).parent.parent / "golden"
CASES = ["kundur_pss", "kundur_nopss", "test", "1link_island"]


def load_asys(name):
    t = np.loadtxt(GOLDEN / f"{name}_Asys.txt")
    n = int(max(t[:, 0].max(), t[:, 1].max()))
    a = np.zeros((n, n))
    a[t[:, 0].astype(int) - 1, t[:, 1].astype(int) - 1] = t[:, 2]
    return a


def load_eigs(name):
    e = np.loadtxt(GOLDEN / f"{name}_eigs.txt")
    return e[:, 0] + 1j * e[:, 1]


@pytest.mark.parametrize("name", CASES)
def test_eigenvalues_solve_the_golden_state_matrix(name):
    """The captured spectrum must be the spectrum of the captured matrix."""
    a, w_gold = load_asys(name), load_eigs(name)
    w = np.linalg.eigvals(a)
    order = lambda z: np.lexsort((z.imag, z.real))
    err = np.abs(w[order(w)] - w_gold[order(w_gold)]).max()
    assert err / np.abs(w_gold).max() < 1e-12, f"{name}: {err:.3e}"


@pytest.mark.parametrize("name", CASES)
def test_participation_columns_peak_at_one(name):
    p = np.loadtxt(GOLDEN / f"{name}_pf.txt")
    assert np.allclose(p.max(axis=0), 1.0)
    assert (p >= 0).all()


@pytest.mark.parametrize("name", CASES)
def test_state_labels_match_participation_rows(name):
    p = np.loadtxt(GOLDEN / f"{name}_pf.txt")
    labels = (GOLDEN / f"{name}_states.txt").read_text().splitlines()
    assert len(labels) == p.shape[0]


def test_pss_flips_the_interarea_damping_sign():
    """Kundur Example 12.6: the inter-area mode is unstable without the PSS.

    This is the assertion that a numerical regression cannot satisfy by
    accident, so it is the one worth stating loudest.
    """
    def interarea(name):
        w = load_eigs(name)
        f = np.abs(w.imag) / (2 * np.pi)
        band = w[(f > 0.4) & (f < 0.9) & (w.imag > 0)]
        assert len(band) == 1, f"{name}: expected one inter-area mode, got {len(band)}"
        return -band[0].real / abs(band[0])

    assert interarea("kundur_nopss") < 0, "no-PSS inter-area mode should be unstable"
    assert interarea("kundur_pss") > 0.05, "PSS should damp the inter-area mode"
```

- [ ] **Step 5: Run the test suite**

Run: `python3 -m pytest tests/test_golden.py -v`
Expected: all pass. `test_pss_flips_the_interarea_damping_sign` is the one to
watch: it should report roughly `-0.023` and `+0.109`.

- [ ] **Step 6: Document and commit**

Add a `## Fixtures and goldens` section to `README.md` naming the four cases,
stating that `example/py_*` is quarantined for holding 3,819 NaN entries, and
recording that goldens are captured with Octave via `capture_golden.m`.

```sh
git add fixtures golden tests README.md
git commit -m "Capture reference spectra for Kundur and the secondary fixtures"
```

---

## Phase 2: Engine

### Task 2: General-purpose sparse factor and solve

`ana_jacob` reads `scheme`, `adjacsubnet` and `nbnzel` from global state to size
its matrix, so it can only ever factor the network Jacobian. The reduction needs
to factor `gy`, an arbitrary submatrix. The C layer is already general: every
`KLU_dll_*` entry takes an instance index plus explicit CSC arrays. Only the
Fortran wrapper needs widening.

**Files:**
- Modify: `src/utils/DIMENSIONS.f90:109-110`
- Modify: `src/linalg/i_KLU.f90` (public list at line 103, new procedures at end of `contains`)

**Interfaces:**
- Produces, both in `KLU_mod`:
  - `subroutine factor_coo(nb, n, nnz, irow, icol, vals, diagno)` with
    `integer, intent(in) :: nb, n, nnz`, `integer, intent(in) :: irow(nnz), icol(nnz)`,
    `double precision, intent(in) :: vals(nnz)`, `integer, intent(out) :: diagno`.
    1-based `irow`/`icol`. Duplicates are summed, matching `spconvert`.
  - `subroutine solve_coo(nb, rhs, transpos, diagno)` with
    `double precision, intent(inout) :: rhs(:)`, `logical, intent(in) :: transpos`.
  - `integer, parameter :: ssa_klu_instance` exported from `DIMENSIONS`.

- [ ] **Step 1: Reserve an instance index**

In `src/utils/DIMENSIONS.f90`, directly after the `mxsubnet` parameter:

```fortran
   integer :: ssa_klu_instance    !< KLU instance reserved for small-signal analysis
   parameter (ssa_klu_instance=mxsubnet+1)
```

and widen the instance array in `src/linalg/i_KLU.f90` from

```fortran
   type (KLUdatatype), dimension(0:mxsubnet) :: KLUdata
```

to

```fortran
   type (KLUdatatype), dimension(0:mxsubnet+1) :: KLUdata
```

updating its `use DIMENSIONS, only: mxsubnet` to also import `ssa_klu_instance`.

- [ ] **Step 2: Add the two procedures**

Append inside `contains` in `src/linalg/i_KLU.f90`, and add both names to the
`public` statement:

```fortran
   !> @brief Factorize an arbitrary sparse matrix given in COO form
   !! @details Unlike ana_jacob/fac_jacob, which size themselves from the global
   !!          network state, this pair accepts any square matrix. It is used by
   !!          the small-signal reduction to factor the algebraic block gy once
   !!          and then back-substitute for every differential column.
   !> @param[in]  nb     solver instance index; use ssa_klu_instance
   !> @param[in]  n      matrix dimension
   !> @param[in]  nnz    number of COO entries (duplicates allowed, they are summed)
   !> @param[in]  irow   1-based row indices
   !> @param[in]  icol   1-based column indices
   !> @param[in]  vals   entry values
   !> @param[out] diagno error flag (0 = success)
   subroutine factor_coo(nb, n, nnz, irow, icol, vals, diagno)
      use SETTINGS, only: write_msg_and_stop

      implicit none

      integer, intent(in) :: nb, n, nnz
      integer, intent(in) :: irow(nnz), icol(nnz)
      double precision, intent(in) :: vals(nnz)
      integer, intent(out) :: diagno

      integer(C_INT) :: flag, nbc
      integer :: mapflag
      character(len=120) :: errorMsg

      diagno = 0
      nbc = nb

      KLUdata(nb)%nbvar = n
      KLUdata(nb)%nonzelem = nnz
      KLUdata(nb)%lower = 1
      KLUdata(nb)%upper = nnz
      KLUdata(nb)%nrhs = 1
      KLUdata(nb)%first_call_factor = .true.
      KLUdata(nb)%first_call_cootocsc = .true.

      call calc_map_optimized(KLUdata(nb)%map, irow, icol, &
                              KLUdata(nb)%nodup_row, KLUdata(nb)%nodup_col, mapflag)
      if (mapflag /= 0) then
         call write_msg_and_stop('factor_coo: ', 'could not merge duplicate entries')
         diagno = mapflag
         return
      endif

      call build_csc_pattern(nb)
      call fill_matrix_optimized(KLUdata(nb)%map, vals, KLUdata(nb)%nodup_val, mapflag)
      call gather_csc_values(nb)

      flag = KLU_init(nbc)
      if (flag /= 0) then
         write(errorMsg,"('KLU_init returned flag=',i3)") flag
         call write_msg_and_stop('factor_coo: ', trim(errorMsg))
         diagno = flag
         return
      endif

      flag = KLU_analyze(nbc, KLUdata(nb)%nbvar, KLUdata(nb)%iacsc, KLUdata(nb)%jacsc)
      if (flag /= 0) then
         write(errorMsg,"('KLU_analyze returned flag=',i3)") flag
         call write_msg_and_stop('factor_coo: ', trim(errorMsg))
         diagno = flag
         return
      endif

      flag = KLU_factor(nbc, KLUdata(nb)%iacsc, KLUdata(nb)%jacsc, KLUdata(nb)%acsc)
      if (flag /= 0) then
         write(errorMsg,"('KLU_factor returned flag=',i3,'; the algebraic block is singular, so the DAE is not index 1')") flag
         call write_msg_and_stop('factor_coo: ', trim(errorMsg))
         diagno = flag
         return
      endif

   end subroutine factor_coo

   !> @brief Solve against a matrix previously factorized by factor_coo
   !> @param[in]    nb       solver instance index used in factor_coo
   !> @param[inout] rhs      right-hand side on entry, solution on exit
   !> @param[in]    transpos .true. to solve with the transpose
   !> @param[out]   diagno   error flag (0 = success)
   subroutine solve_coo(nb, rhs, transpos, diagno)

      implicit none

      integer, intent(in) :: nb
      double precision, intent(inout) :: rhs(:)
      logical, intent(in) :: transpos
      integer, intent(out) :: diagno

      integer(C_INT) :: flag, nbc, transp

      nbc = nb
      transp = 0
      if (transpos) transp = 1

      flag = KLU_solve(nbc, rhs, KLUdata(nb)%nbvar, KLUdata(nb)%nrhs, transp)
      diagno = flag

   end subroutine solve_coo
```

**Before writing this, read the existing `cootocsc`, `build_csc_pattern`,
`calc_map_optimized`, `fill_matrix_optimized` and the CSC value gather in
`src/linalg/i_KLU.f90:309-420`.** The four helper calls above must match their
real signatures and the real name of the gather step; adjust the calls to fit
rather than adding wrappers. If `cootocsc(nb)` already performs the whole
map-build-gather sequence off the module-level `row`/`col`/`jac` arrays, prefer
refactoring it to take the triplets as arguments and call it from both places,
which is DRY and avoids a second copy of the conversion logic.

- [ ] **Step 3: Build**

Run: `make -f build/Makefile.gfortran all`
Expected: clean build, no new warnings. This step has no runtime test of its
own; Task 5 exercises it against the golden `A_sys`.

- [ ] **Step 4: Commit**

```sh
git add src/utils/DIMENSIONS.f90 src/linalg/i_KLU.f90
git commit -m "Let the KLU wrapper factor an arbitrary COO matrix"
```

---

### Task 3: Split assembly out of dump_jacobian

**Files:**
- Modify: `src/core/simul_decomp.f90:2699-3310`

**Interfaces:**
- Produces: `subroutine assemble_dae_jacobian()`, a contained subroutine of the
  decomposed solver that leaves the unreduced DAE Jacobian in the `NET_JACOB`
  arrays `row`, `col`, `jac(1:nbnzel)` and performs no I/O. Tasks 5 and 7 rely
  on it. Behaviour must be bit-identical to what `dump_jacobian` does today.

- [ ] **Step 1: Extract the assembly**

Move lines 2708 to 2842 of `dump_jacobian` verbatim into a new contained
subroutine `assemble_dae_jacobian`, keeping the `jac(1:nbnzel)=0.d0`,
`comp_factor_Jac(.false.,.false.)`, `adjust_Jac_TC` calls and the
`STOP 'Error in formulating Jac'` consistency check. Replace the extracted body
in `dump_jacobian` with a single `call assemble_dae_jacobian`.

Do not change the COI finite-difference block or the `dumpjac=.false.` reset at
the end; they stay in `dump_jacobian`.

- [ ] **Step 2: Verify byte-identical export**

```sh
make -f build/Makefile.gfortran all
cd $(mktemp -d) && cp <kundur files> . && <run with jac.dst>
```

Compare each of `jac_val.dat`, `jac_eqs.dat`, `jac_var.dat`, `jac_struc.dat`
against the Task 1 fixtures:

Run: `for f in val eqs var struc; do cmp jac_$f.dat <fixtures>/kundur_pss/jac_$f.dat && echo "$f OK"; done`
Expected: four `OK` lines. Any difference means the refactor changed behaviour;
fix it before continuing, because every later task validates against goldens
derived from this exact output.

- [ ] **Step 3: Commit**

```sh
git add src/core/simul_decomp.f90
git commit -m "Separate DAE Jacobian assembly from the file dump"
```

---

### Task 4: `$EIG_MAX_STATES` setting

**Files:**
- Modify: `src/utils/MODULES.f90` (`SETTINGS`, near `sparse_solver` at line 75)
- Modify: `src/io/get_settings.f90` (new case beside `$SPARSE_SOLVER` at line 268)
- Modify: `docs/INPUT_FORMAT.md`

**Interfaces:**
- Produces: `integer :: eig_max_states` in `SETTINGS`, default `5000`. Task 5 reads it.

- [ ] **Step 1: Declare the setting**

In the `SETTINGS` module, beside `sparse_solver`:

```fortran
   integer :: eig_max_states = 5000 !< largest state count accepted by small-signal analysis (states)
```

The dense workspace is roughly `3*Nx^2 + Na*Nx` doubles, so 5000 states is
already 600 to 800 MB. Document that in the comment block.

- [ ] **Step 2: Parse it**

In `src/io/get_settings.f90`, immediately after the `$SPARSE_SOLVER` case:

```fortran
         case('$EIG_MAX_STATES')
            if(adrec(i+1)-adrec(i).ne.1)then
               call write_msg_and_stop('Get Settings','')
               write(log,"('$EIG_MAX_STATES record has not 1 field')")
            endif
            read(field(findex),*,iostat=readstatus,err=10)eig_max_states
```

- [ ] **Step 3: Build and check the default survives**

Run: `make -f build/Makefile.gfortran all` then run the Kundur case with no
`$EIG_MAX_STATES` record.
Expected: clean build, simulation unaffected.

- [ ] **Step 4: Document and commit**

Add `$EIG_MAX_STATES` to `docs/INPUT_FORMAT.md` beside the other settings records.

```sh
git add src/utils/MODULES.f90 src/io/get_settings.f90 docs/INPUT_FORMAT.md
git commit -m "Add the \$EIG_MAX_STATES limit for small-signal analysis"
```

---

### Task 5: Reduction to the state matrix

This is the task most likely to contain a bug, so it is validated on its own
against the golden `A_sys` before any eigensolve exists.

**Files:**
- Create: `src/core/ssa.f90`
- Modify: `build/Makefile.gfortran`
- Create: `tools/compare_ssa.py`

**Interfaces:**
- Produces, in new module `ssa_mod`:
  - `subroutine build_state_matrix(a_sys, nx, diagno)` with
    `double precision, allocatable, intent(out) :: a_sys(:,:)`,
    `integer, intent(out) :: nx, diagno`.
  - `subroutine ssa_index_sets(dif_eq, dif_var, alg_eq, alg_var, nx, na)`, which
    derives the differential and algebraic index sets from the engine's internal
    `eqtyp_*` arrays and `injbr`/`twopbr`, **not** from `jac_eqs.dat`. The
    integrated scheme's dump omits the gamma column, which is exactly why the
    MATLAB tool only worked under `$SCHEME DE`.
  - Task 6 consumes `build_state_matrix`.

- [ ] **Step 1: Write the comparator first**

Create `tools/compare_ssa.py`:

```python
#!/usr/bin/env python3
"""Compare an engine-written SSA artefact against a captured golden.

  compare_ssa.py asys <engine_Asys.txt> <golden_Asys.txt> [--rtol 1e-14]
  compare_ssa.py eigs <engine_modes.dat> <golden_eigs.txt> [--rtol 1e-12]

Exit status 0 on agreement, 1 on mismatch, 2 on bad usage.
"""
import sys
import numpy as np


def load_triplets(path):
    t = np.loadtxt(path)
    n = int(max(t[:, 0].max(), t[:, 1].max()))
    a = np.zeros((n, n))
    a[t[:, 0].astype(int) - 1, t[:, 1].astype(int) - 1] = t[:, 2]
    return a


def main(argv):
    if len(argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    mode, engine, golden = argv[1], argv[2], argv[3]
    rtol = float(argv[argv.index("--rtol") + 1]) if "--rtol" in argv else None

    if mode == "asys":
        a, g = load_triplets(engine), load_triplets(golden)
        if a.shape != g.shape:
            print(f"FAIL: shape {a.shape} vs golden {g.shape}")
            return 1
        err = np.abs(a - g).max() / np.abs(g).max()
        tol = rtol if rtol is not None else 1e-14
        print(f"A_sys relative error {err:.3e} (tol {tol:.1e})")
        return 0 if err < tol else 1

    if mode == "eigs":
        w = np.loadtxt(engine, comments="#", usecols=(1, 2))
        w = w[:, 0] + 1j * w[:, 1]
        e = np.loadtxt(golden)
        g = e[:, 0] + 1j * e[:, 1]
        if len(w) != len(g):
            print(f"FAIL: {len(w)} modes vs golden {len(g)}")
            return 1
        order = lambda z: np.lexsort((z.imag, z.real))
        err = np.abs(w[order(w)] - g[order(g)]).max() / np.abs(g).max()
        tol = rtol if rtol is not None else 1e-12
        print(f"eigenvalue relative error {err:.3e} (tol {tol:.1e})")
        return 0 if err < tol else 1

    print(f"unknown mode {mode}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
```

- [ ] **Step 2: Verify the comparator rejects a wrong matrix**

```sh
python3 tools/compare_ssa.py asys golden/kundur_pss_Asys.txt golden/kundur_nopss_Asys.txt
```

Expected: nonzero exit and a large relative error. The two variants differ only
in the PSS gain, so this also proves the comparator is sensitive enough to
notice that difference. Then compare a golden against itself and expect exit 0
with error `0.000e+00`.

- [ ] **Step 3: Implement the reduction**

Create `src/core/ssa.f90` with module `ssa_mod`. `build_state_matrix` must:

1. call `ssa_index_sets` to get `dif_eq`, `dif_var`, `alg_eq`, `alg_var`, `nx`, `na`.
   Follow the block walk in `src/core/simul_decomp.f90:2940-2990`: an equation is
   differential when `eqtyp_sync(shift+j-1) > 0 .and. injbr(i) == 1`, and its
   state column is `jacindex + eqtyp_sync(shift+j-1)` shifted by `nbparkeq` and
   `nbxexc(i)` for the EXC and TOR sub-blocks. Mirror the same pattern for
   `nbinj` and `nbtwop`;
2. abort via `write_msg_and_stop` when `nx > eig_max_states`, naming `nx`, the
   limit, and the Python shift-invert path as the route for larger systems;
3. walk `row`, `col`, `jac(1:nbnzel)` once, routing each entry into one of four
   COO lists `fx`, `fy`, `gx`, `gy` by whether its row is differential and its
   column is a differential variable;
4. `call factor_coo(ssa_klu_instance, na, nnz_gy, gy_row, gy_col, gy_val, diagno)`
   once, then loop the `nx` columns of `gx`, expanding each into a dense length
   `na` right-hand side and calling `solve_coo`, accumulating the dense
   `gygx(na,nx)`;
5. form `a_sys = fx - matmul(fy_dense, gygx)`, building `fy` dense since it is
   `nx` by `na`;
6. `call finalize` on the SSA instance.

Add an optional debug writer `write_state_matrix(a_sys, nx, filename)` emitting
`i j value` triplets with format `(i16,1x,i16,1x,en30.17)` for entries whose
magnitude exceeds `tiny`, so this task can be validated before Task 6 exists.

Register `src/core/ssa.f90` in `build/Makefile.gfortran` after `simul_decomp.f90`
in the object list, since it uses that module.

- [ ] **Step 4: Validate against the golden state matrix**

Temporarily call `build_state_matrix` and `write_state_matrix` from
`dump_jacobian` right after `assemble_dae_jacobian`, rebuild, run both Kundur
variants, then:

```sh
python3 tools/compare_ssa.py asys kundur_pss_Asys_engine.txt   golden/kundur_pss_Asys.txt
python3 tools/compare_ssa.py asys kundur_nopss_Asys_engine.txt golden/kundur_nopss_Asys.txt
```

Expected: both exit 0 with relative error below `1e-14`.

**If this does not pass, do not proceed.** Every later task is validated through
this matrix, and a reduction bug would masquerade as an eigensolve bug.

- [ ] **Step 5: Commit**

```sh
git add src/core/ssa.f90 build/Makefile.gfortran tools/compare_ssa.py
git commit -m "Reduce the DAE Jacobian to the state matrix in the engine"
```

---

### Task 6: Dense eigensolve and participation factors

**Files:**
- Modify: `src/core/ssa.f90`

**Interfaces:**
- Produces: `subroutine solve_state_matrix(a_sys, nx, lambda, vr, vl, diagno)` with
  `double precision, intent(inout) :: a_sys(nx,nx)` (dgeev destroys it),
  `complex(kind(1.d0)), allocatable, intent(out) :: lambda(:), vr(:,:), vl(:,:)`,
  and `subroutine participation(vr, vl, nx, pf)` producing
  `double precision, allocatable, intent(out) :: pf(:,:)` normalised so each
  column peaks at 1. Task 7 consumes both.

- [ ] **Step 1: Call dgeev and unpack the conjugate pairs**

`dgeev('V','V',n,a,lda,wr,wi,vl,ldvl,vr,ldvr,work,lwork,info)` returns real
arrays in which a complex conjugate pair occupies **two consecutive columns**:
column `j` holds the real part and column `j+1` the imaginary part, giving
eigenvectors `v_j = col_j + i*col_{j+1}` and `v_{j+1} = col_j - i*col_{j+1}`.
Detect a pair by `wi(j) > 0`, consume two columns, and advance by two. This
unpacking is the single most likely source of a subtle bug in the whole plan.

Query the optimal workspace with `lwork = -1` first, then allocate.

Check `info`: a nonzero value means the QR iteration failed to converge, which
must abort rather than return partial results.

- [ ] **Step 2: Compute participation factors**

`pf(k,i) = abs(vl(k,i) * vr(k,i))`, then divide each column by its own maximum
so the largest entry is 1. The per-column maximum cancels the arbitrary scaling
of each eigenvector, which is why this normalisation is used rather than a
biorthogonal one.

- [ ] **Step 3: Validate the spectrum and the physics**

Write the eigenvalues from the temporary hook and compare:

```sh
python3 tools/compare_ssa.py eigs kundur_pss_modes.dat   golden/kundur_pss_eigs.txt
python3 tools/compare_ssa.py eigs kundur_nopss_modes.dat golden/kundur_nopss_eigs.txt
```

Expected: both exit 0, relative error below `1e-12`.

Then assert the physics by hand, which catches an eigenvector bug that the
eigenvalue comparison cannot see. For the no-PSS case the 0.625 Hz mode must
have `zeta` near `-0.0233`, and its participation over the four `omega` states
must be spread across all four machines, while the 1.085 Hz mode must
concentrate on G1 and G2 (roughly `G2:0.99 G1:0.84 G3:0.04 G4:0.02`) and the
1.116 Hz mode on G3 and G4. If the local modes do not separate this way, the
`dgeev` left-eigenvector unpacking is wrong.

- [ ] **Step 4: Commit**

```sh
git add src/core/ssa.f90
git commit -m "Solve the dense state-matrix eigenproblem with LAPACK"
```

---

### Task 7: Results files

**Files:**
- Modify: `src/core/ssa.f90`

**Interfaces:**
- Produces: `subroutine solve_small_signal(basename, real_limit, pf_threshold, diagno)`,
  the single entry point Tasks 8 and 9 call. Writes `<basename>_modes.dat`,
  `<basename>_pf.dat`, `<basename>_ms.dat` exactly as specified in section 6 of
  the spec. Read that section before writing this task.

- [ ] **Step 1: Write the three files**

Formats, copied from the spec:

- `_modes.dat`: header `# STEPSS SSA modes v1`, then a metadata comment line, then
  `(i8,1x,4(en24.15,1x),i2)` giving index, real part, imaginary part,
  `zeta = -real/abs(lambda)`, `f = abs(imag)/(2*pi)` in Hz, and a dominance flag
  set when `real > real_limit`. Every mode is written.
- `_pf.dat`: header `# STEPSS SSA participation factors v1`, then
  `(i8,1x,i8,1x,en24.15,1x,a8,1x,a20,1x,a20)`, only for dominant modes and only
  entries above `pf_threshold`, with family, device and variable names inlined.
- `_ms.dat`: header `# STEPSS SSA mode shapes v1`, then
  `(i8,1x,i8,1x,2(en24.15,1x),a20)`, right-eigenvector entries for states named
  `omega`, normalised so the largest magnitude within each mode is 1, angle in
  degrees.

Names are written left-justified in their `a20` field and truncated if longer.
RAMSES component names have no embedded spaces, so consumers split on whitespace.

- [ ] **Step 2: Verify the files parse and agree**

```sh
python3 tools/compare_ssa.py eigs ssa_modes.dat golden/kundur_pss_eigs.txt
python3 -c "
import numpy as np
m = np.loadtxt('ssa_modes.dat', comments='#')
assert np.allclose(m[:,3], -m[:,1]/np.hypot(m[:,1], m[:,2]), atol=1e-12), 'zeta column wrong'
assert np.allclose(m[:,4], np.abs(m[:,2])/(2*np.pi), atol=1e-12), 'frequency column wrong'
print('derived columns OK')
"
```

Expected: exit 0 and `derived columns OK`.

- [ ] **Step 3: Commit**

```sh
git add src/core/ssa.f90
git commit -m "Write the small-signal results files"
```

---

### Task 8: `EIG` disturbance trigger

**Files:**
- Modify: `src/core/simul_decomp.f90` (add `dumpeig`, beside `dumpjac`)
- Modify: `src/io/disturb.f90` (new case beside `JAC` at line 272)
- Modify: `docs/INPUT_FORMAT.md`
- Create: `tools/kundur_ssa_gate.sh`
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Produces: the `.dst` command `t EIG '<basename>'`, which Task 9 and the Java UI
  both rely on.

- [ ] **Step 1: Add the disturbance case**

Mirror the `JAC` case exactly, including the `$OMEGA_REF SYN` guard and its
warning, setting `dumpeig=.true.` and `forcejac=.true.` and storing the basename
in `jacfile`. In the decomposed solver, where `if(dumpjac)call dump_jacobian` sits
at line 610, add `if(dumpeig)` calling `assemble_dae_jacobian` then
`solve_small_signal`, and reset `dumpeig=.false.` afterwards exactly as
`dump_jacobian` resets `dumpjac`.

- [ ] **Step 2: Write the regression gate**

Create `tools/kundur_ssa_gate.sh` following the structure of
`tools/nordic_gate.sh` (read it first). It takes
`<ramses-binary> <repo-root> <golden-dir>`, runs both Kundur variants with an
`EIG` disturbance in a temp dir, and asserts:

1. each run produces the three results files;
2. `tools/compare_ssa.py eigs` passes against both goldens;
3. the inter-area damping sign flips, computed from `_modes.dat` alone:

```sh
"$PY" - "$RUN/nopss_modes.dat" "$RUN/pss_modes.dat" <<'EOF'
import sys, numpy as np
def interarea(path):
    m = np.loadtxt(path, comments='#')
    sel = m[(m[:,4] > 0.4) & (m[:,4] < 0.9) & (m[:,2] > 0)]
    assert len(sel) == 1, f"{path}: expected one inter-area mode, got {len(sel)}"
    return sel[0,3]
z_no, z_pss = interarea(sys.argv[1]), interarea(sys.argv[2])
assert z_no < 0, f"no-PSS inter-area mode should be unstable, got zeta={z_no}"
assert z_pss > 0.05, f"PSS should damp the inter-area mode, got zeta={z_pss}"
print(f"PASS: zeta {z_no:+.4f} without PSS, {z_pss:+.4f} with PSS")
EOF
```

The Kundur inputs the gate needs must be copied into `examples/Kundur/` in this
repo, mirroring `examples/Nordic/`, so the gate has no cross-repo dependency.

- [ ] **Step 3: Run the gate**

Run: `bash tools/kundur_ssa_gate.sh build/Release_gnu_l/ramses . <golden-dir>`
Expected: `PASS: zeta -0.0233 without PSS, +0.1087 with PSS`

- [ ] **Step 4: Wire the gate into CI**

Add the gate beside the three existing `nordic_gate.sh` invocations in
`.github/workflows/release.yml` (lines 50, 142 and 214), one per platform.

- [ ] **Step 5: Document and commit**

Add `EIG` to `docs/INPUT_FORMAT.md` beside the `JAC` entry at line 489.

```sh
git add src/core/simul_decomp.f90 src/io/disturb.f90 docs/INPUT_FORMAT.md \
        tools/kundur_ssa_gate.sh examples/Kundur .github/workflows/release.yml
git commit -m "Add the EIG disturbance and its Kundur regression gate"
```

---

### Task 9: C API entries

**Files:**
- Modify: `src/api/c_interface.f90` (follow `get_Jac` at lines 208-229)
- Modify: `stepss-python-ui/src/stepss/libs/ramses.h`

**Interfaces:**
- Produces, callable from Python once the header lists them:
  - `int run_ssa(char *basename, double real_limit, double pf_threshold);`
  - `int get_state_matrix_size(int *nx);`
  - `int get_state_matrix(int nx, double *a_sys);` returning column-major order.

- [ ] **Step 1: Add the three entries**

Follow the `get_Jac` pattern exactly: set the basename, set `dumpeig=.true.` and
`forcejac=.true.`, advance `pause_time` by 1 ms, clear `error_flag`, return
`ramses()`. `get_Jac` deliberately bypasses the `$OMEGA_REF SYN` guard that the
disturbance path enforces, so `run_ssa` should match that behaviour for
consistency and say so in its doc comment.

`get_state_matrix` returns the matrix retained from the last `run_ssa` call, so
`ssa_mod` must keep it in a module-level allocatable. `get_state_matrix_size`
lets the caller allocate before the copy.

- [ ] **Step 2: Declare them in the Python header**

Append to `stepss-python-ui/src/stepss/libs/ramses.h`:

```c
int run_ssa(char *basename, double real_limit, double pf_threshold);
int get_state_matrix_size(int *nx);
int get_state_matrix(int nx, double *a_sys);
```

This file is hand-maintained in that repo and is **not** generated from
stepss-ramses; `src/api/dllramses.h` here is only a one-line Windows import
stub. Python parses this header at import to build its ctypes bindings, so the
entries are invisible until it lists them.

- [ ] **Step 3: Verify from Python**

```python
import stepss
case = stepss.cfg()
for f in ('lf.dat', 'dyn.dat', 'solveroptions.dat'):
    case.addData(f)
case.addDst('nothing.dst'); case.addObs('obs.dat'); case.addTrj('out.trj')
ram = stepss.sim()
ram.execSim(case, 0.0)
print(ram.ramses.run_ssa(b'ssa', -1.0, 0.05))
ram.endSim()
```

Expected: return code 0 and the three `ssa_*.dat` files in the working directory.

- [ ] **Step 4: Commit both repos**

```sh
# in stepss-ramses
git add src/api/c_interface.f90 src/core/ssa.f90
git commit -m "Expose small-signal analysis through the C API"
# in stepss-python-ui
git add src/stepss/libs/ramses.h
git commit -m "Declare the small-signal C entries"
```

---

## Out of scope

Deliberately not in this plan, per the spec:

- Sparse shift-invert in the engine. No ARPACK is vendored, and Java is limited
  to small and medium systems by decision.
- The `stepss.ssa` Python module and the Java Swing panel. Each gets its own
  plan once this one lands; they depend only on the section 6 file format.
- Fixing the exciter NaN defect in `example/py_val.dat`. Separate bug report.

## Self-Review

**Spec coverage.** Section 5.1 is Task 3; 5.2 is Tasks 5 to 7; 5.3 is Task 4;
5.4 is Tasks 8 and 9; section 6 is Task 7; section 9 is Task 1. Sections 7
(Python) and 8 (Java) are deliberately deferred and listed under Out of scope.

**One gap found and closed.** The spec says the reduction factors `gy` "through
the existing `sparse_solvers.f90` KLU dispatch", which is not possible: both
`ana_jacob` and `fac_jacob` size themselves from `scheme`, `adjacsubnet` and
`nbnzel`, so they can only factor the network Jacobian. Task 2 was added to widen
the wrapper, and the spec should be amended to match.

**Type consistency.** `build_state_matrix` produces `a_sys`/`nx`, consumed by
`solve_state_matrix` in Task 6, whose `vr`/`vl` feed `participation` and then
Task 7. `ssa_klu_instance` is defined in Task 2 and used in Task 5.
`solve_small_signal` is defined in Task 7 and called from Tasks 8 and 9.
`dumpeig` is introduced in Task 8 and referenced by Task 9.
