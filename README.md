# STEPSS

**Static and Transient Electric Power System Simulator**

STEPSS is a graphical user interface (GUI) application for the RAMSES power system simulator, providing an intuitive interface for power system analysis and simulation.

## About

STEPSS (Static and Transient Electric Power System Simulator) is a Java-based GUI application that interfaces with the RAMSES simulation engine. It provides a user-friendly environment for performing static and transient analysis of electric power systems.

## Features

- Graphical user interface for power system simulation
- Integration with RAMSES simulation engine
- Support for loading and analyzing power system data files
- Visualization and analysis tools
- Cross-platform support (Java-based)

## Requirements

- Java Runtime Environment (JRE) 64-bit
- RAMSES simulation engine

## Installation

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/SPS-L/STEPSS.git
   cd STEPSS
   ```

2. Build the project using the provided build system (Ant/NetBeans project structure):
   ```bash
   ant build
   ```

3. The compiled JAR file will be available in the `dist` folder as `stepss.jar`.

### Running the Application

To run the application from the command line:

```bash
java -jar dist/stepss.jar
```

Or from the dist folder:

```bash
cd dist
java -jar stepss.jar
```

## Usage

1. Launch the STEPSS application
2. Load a power system data file using the "Load File" option
3. Configure simulation parameters as needed
4. Run the simulation
5. Analyze and visualize the results

For detailed usage instructions and documentation, please visit the [STEPSS website](https://stepss.sps-lab.org/).

## Version

Current version: 3.40

## Authors

- Petros Aristidou
- Thierry Van Cutsem

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

## Related Projects

- [PyRAMSES](https://pyramses.sps-lab.org/) - Python interface for RAMSES
- [URAMSES](https://github.com/SPS-L/URAMSES) - User models for PyRAMSES

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Support

For support, questions, or inquiries, please contact:
- Email: info@sps-lab.org
- Website: https://sps-lab.org
- STEPSS Website: https://stepss.sps-lab.org/

---

**Last edited:** 2026-01-13
