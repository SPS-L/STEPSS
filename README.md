# STEPSS

**Umbrella repository for the STEPSS power system simulation platform.**

This repository pulls in every component of the [STEPSS](https://stepss.sps-lab.org/) power system simulation platform from the [SPS-L](https://github.com/SPS-L) organization as a git submodule, each tracking its default branch.

## Components

| Submodule | Access | Description |
|---|---|---|
| [stepss-ramses](https://github.com/SPS-L/stepss-ramses) | private | RAMSES dynamic power system simulator (core engine) |
| [stepss-uramses](https://github.com/SPS-L/stepss-uramses) | public | User-defined device models framework (custom Fortran model compilation) |
| [stepss-python-ui](https://github.com/SPS-L/stepss-python-ui) | public | Python API/wrapper for RAMSES, published as the `stepss` PyPI package |
| [stepss-java-ui](https://github.com/SPS-L/stepss-java-ui) | public | Java-based GUI front-end |
| [stepss-helios](https://github.com/SPS-L/stepss-helios) | private | Modern C++ power-flow engine (successor to PFC; TUI + C API) |
| [stepss-pfc](https://github.com/SPS-L/stepss-pfc) | private | Power flow computation |
| [stepss-Codegen](https://github.com/SPS-L/stepss-Codegen) | private | CODEGEN model code generation |
| [stepss-cg-studio](https://github.com/SPS-L/stepss-cg-studio) | public | Visual block diagram editor for CODEGEN models |
| [stepss-dyngraph](https://github.com/SPS-L/stepss-dyngraph) | private | Post-processing tool for extracting and plotting RAMSES simulation curves |
| [stepss-eigenanalysis](https://github.com/SPS-L/stepss-eigenanalysis) | public | Small-signal / eigenvalue analysis tools (MATLAB) |
| [stepss-RamsesNN](https://github.com/SPS-L/stepss-RamsesNN) | public | Neural networks embedded in dynamic power system simulators |
| [stepss-docs](https://github.com/SPS-L/stepss-docs) | public | Documentation website (Astro/Starlight) |
| [stepss-userguide](https://github.com/SPS-L/stepss-userguide) | public | LaTeX user documentation and models reference |
| [stepss-license-gen](https://github.com/SPS-L/stepss-license-gen) | private | License generation tooling for STEPSS components |
| [stepss-test-systems](https://github.com/SPS-L/stepss-test-systems) | private | Power system test cases and network models |

## Getting everything

```sh
git clone --recurse-submodules git@github.com:SPS-L/stepss.git
```

or over HTTPS:

```sh
git clone --recurse-submodules https://github.com/SPS-L/stepss.git
```

Submodule URLs are relative, so they use the same protocol you cloned the umbrella with. Private components require access to the SPS-L organization; without it they simply fail to fetch; initialize just the public ones with:

```sh
git submodule update --init stepss-uramses stepss-python-ui \
  stepss-java-ui stepss-cg-studio stepss-eigenanalysis stepss-RamsesNN \
  stepss-docs stepss-userguide
```

## Updating to the latest components

Each submodule tracks its default branch. To move everything to the latest tips:

```sh
./update.sh
```

then review and commit the bumped submodule pointers:

```sh
git commit -am "Update components to latest"
git push
```

If you already cloned and someone else bumped the pointers:

```sh
git pull
git submodule update --init --recursive
```

## Documentation

Platform-wide documentation lives at [stepss.sps-lab.org](https://stepss.sps-lab.org/). Each component repository carries its own README with build and usage instructions.

## License

This umbrella repository contains only submodule pointers and helper scripts. Each component is licensed individually. See the `LICENSE` file in each component repository (a mix of Apache-2.0, MIT, CC BY 4.0, and the STEPSS Academic Public License; private components are internal to SPS-L).

## Authors

Developed and maintained by the [Sustainable Power Systems Laboratory (SPS-L)](https://sps-lab.org/) at the Cyprus University of Technology, under the direction of Dr. Petros Aristidou.
