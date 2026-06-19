<div align="center">

Walker: extract dependencies with ease
======================================

<div align="left">

Walker is a tool designed to help manage macOS libraries. It automates the parsing and configuration of nested library (`.dylib`) dependency graphs using system utilities like `otool`, `install_name_tool`, and `codesign`. It also has a web interface to configure and view the full dependency chart in columns:

<img width="1304" height="643" alt="image" src="https://github.com/user-attachments/assets/436a1083-f12f-432c-ad23-c36395131610" />

Deeply nested dependencies (like libraries such as `opencv`) can be hard to resolve manually. Walker traverses the dependency tree recursively in parallel to collect, relocate, and re-link these libraries.

## How normal dependency patching works
A typical Mach-O library loads its dependencies using path variables. When inspecting a binary with `otool`, you will generally see path structures such as:
- `@rpath`: Runpath search paths. `dyld` searches a list of directories defined inside the binary's load commands (`LC_RPATH`) in order until it finds the matching dependency.
- `@loader_path`: The path to the directory containing the entity loading the library. This is the preferred target path structure to keep dependencies self-contained.
- `/absolute/path`: Absolute location on the file system. Paths matching system library locations (e.g., `/usr/lib` or `/System/Library`) are ignored by default.
> - Technically there also is `@executable_path`, but that depends on the executable binary running it, which can only be known at runtime, so support for that has ended.

A typical `otool -L` output looks like this: 

```zsh
/path/to/this.dylib:
    @rpath/inSomeFolder.dylib (compatibility version 1.0.0, current version 1.0.2)
    @loader_path/inTheSameFolder.dylib (compatibility version 1.0.0, current version 1.0.3)
    /or/it/can/just/be/a/path.dylib (compatibility version 1.0.0, current version 1.0.5)
    /usr/lib/weIgnoreThis.dylib (compatibility version 2.0.0, current version 2.0.6)
```

## How Walker's parallel dependency engine works
Walker uses a concurrent graph resolution engine built on virtual threads:
1. Walker uses VTs to scan Mach-O headers concurrently via `otool -l`.
2. As the parser traverses the dependency graph, it propagates a history of parent `@rpath` declarations to child tasks, allowing it to resolve nested references based on the specific parent load context.
3. Nodes are situated in a directed graph (`G = (V, E)` approach implemented via an adjacency map with in-degree tracking), while an async `Phaser` maps the whole tree before proceeding.
4. During a patch, Walker implements collision and shared node detection:
   - Incoming connection counts are calculated for each node to identify shared dependencies without a complex CFG.
   - SHA256 checksums are calculated for non-syste binaries to recognize complete duplicates, in order to solve name collisions in the event of copying to the same location.
5. Relocation, `install_name_tool -id`, `install_name_tool -change`, and codesigning `codesign -f -s -` are executed in parallel across the resolved nodes.

## Usage

- **To show the help menu**: `walker`***`[--help | -h]`***
- **To run Walker CLI**: `walker`***`[path/to/library.dylib]`**`[flags]`*
  - `[--output  | -o]`: specify a custom output folder to patch to
  - `[--verbose | -v]`: print all logs during the patching process
- **To run Walker web interface**: `walker`***`[--web | -w]`**`[flags]`*
  - `[--port    | -p]`: specify a custom port for the localhost server
  - `[--verbose | -v]`: print all logs for the server and patches run
> - Walker supports entering relative paths like `./`, `../`, and `~/`.
> - By default, the localhost port is `2013`.
> - In the CLI, Walker can only do a complete patch of a library (with all of its nested dependencies) into one folder. For more complex patches and dependency graph viewing support, use the web version.

## Performance
Using virtual threads, Walker can resolve, copy, patch, and re-sign a complex 172-dependency library in around 6 seconds.
