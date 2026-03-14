# JavaUtil

A Java source code analysis tool that uses [Tree-sitter](https://tree-sitter.github.io/) and [JavaParser](https://javaparser.org/) to search and inspect method declarations across codebases.

## Features

- **Fast method search** using Tree-sitter's incremental parsing
- **Hybrid analysis** combining Tree-sitter's speed with JavaParser's semantic richness
- Supports overload detection with parameter-type filtering
- Scans entire directory trees for `.java` files
- Java 21 records for clean, immutable result types

## Requirements

- Java 21+
- Maven 3.6.3+

## Build

```bash
mvn clean compile
```

## Run

```bash
mvn exec:java -Dexec.args="<source-root> <method-name>"
```

Example:

```bash
mvn exec:java -Dexec.args="src findMethods"
```

## Test

```bash
mvn test
```

## Project Structure

```
src/main/java/com/javautil/app/
├── App.java                              CLI entry point
├── codebase/
│   ├── CodeBase.java                     Interface for codebase abstraction
│   ├── JavaCodeBase.java                 Java-specific implementation
│   └── CodeBaseLoader.java               Stateless file discovery
└── parser/
    ├── CodeParser.java                   Parser interface (returns data)
    ├── TreeSitterParser.java             Tree-sitter implementation
    ├── HybridJavaParser.java             Tree-sitter + JavaParser hybrid
    ├── TreeSitterLanguageFactory.java    Native library loading & caching
    └── model/
        ├── MethodInfo.java               Method metadata (record)
        ├── ClassInfo.java                Class metadata (record)
        └── ParseResult.java              Search result wrapper (record)
```

## Architecture

The project follows a layered design:

1. **Model layer** (`parser.model`) — immutable Java records that represent parsed code structures
2. **Parser layer** (`parser`) — `CodeParser` interface with two implementations:
   - `TreeSitterParser`: fast syntax-level search via Tree-sitter
   - `HybridJavaParser`: combines Tree-sitter discovery with JavaParser enrichment
3. **Codebase layer** (`codebase`) — file discovery and project abstraction
4. **App layer** — CLI entry point wiring everything together

## License

Licensed under the MIT License.
