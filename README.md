<div align="center">

Walker: extract dependencies with ease
============

<div align="left">

Walker is a commandline tool that helps you manage macOS libraries easily. Specifically, it uses the system tools `otool` and `install_name_tool` to manually configure each nested library (`.dylib` file) to be located in the same parent folder with the main library. 

Managing dependencies can be a pain, sometimes libraries such as `opencv` can have hundreds of nested dependencies, each with their own non-system dependencies. Walker automates this by finding all the dependencies and using BFS to iterate through the dependency chart. 

Because Walker needs to wait for `otool` to give an output and avoid the rare collision between two dependencies pointing to each other, it's not multithreaded. 

## How normal dependency patching works
Regularly, you'd use `otool` to find out which dependencies are where. But it doesn't really give you a complete list of all the absolute locations of the dependencies: 
- `@rpath`: Each `.dylib` file has a "lookup locations" list, given by `otool -l`. macOS's loader, `dyld`, will search for dependencies in each of these locations until something matches. 
- `@loader_path`: In the same parent directory as the original library. This is what we want all the libraries to eventually become. 
- `@executable_path`: In a special path called `/../Frameworks`. Usually only used in macOS app development but can sometimes occur in libraries, so it's best to include that. 
- `/absolute/path`: Sometimes, an absolute path can be found. However, paths beginning with `/usr/lib` are usually system libraries and we don't need to copy them. 

So a typical `otool` output could look like so: 

```shell
/path/to/this.dylib:
    @rpath/inSomeFolder.dylib (compatibility version 1.0.0, current version 1.0.2)
    @loader_path/inTheSameFolder.dylib (compatibility version 1.0.0, current version 1.0.3)
    @executable_path/thisIsInFrameworks.dylib (compatibility version 1.0.0, current version 1.0.4)
    /or/it/can/just/be/a/path.dylib (compatibility version 1.0.0, current version 1.0.5)
    /usr/lib/weIgnoreThis.dylib (compatibility version 2.0.0, current version 2.0.6)
```

`@rpath`s are the hardest to deal with because there can be many `rpath` locations and we need to search each of them against the file's location relative to the `rpath` location. However, with an automated extractor like Walker, it's easy (unlike doing it manually). 

## How Walker patches dependencies
Here's a rough flowchart of Walker's average workflow (you can always refer to the source code later on): 
1. Get the parent dependency's `otool -L`
2. Check if it has `rpath`s or not, using `otool -l`. If it does, see if each `rpath` library exists in each of the `rpath` paths. Finally, we can retrieve the absolute location of each `rpath` dependency. 
3. Check if it has `loader_path`s, `executable_path`s, or absolute paths. These path retrievals are a lot simpler. 
4. Queue all the nested dependencies found. 
5. Copy this dependency to the target resultant folder, and patch it with `install_name_tool` to make it `@loader_path` with the rest. 
6. Loop again for each dependency in queue. 

## Performance
Yes, it's written in Java, but using `native-image`, it can analyze 1085 nested dependencies with highest depth level of 11 in around 25s. The main limitation will almost certainly be IO and cmdline tools. 

## Future Release Plan
- Concurrency support with virtual threads
- Advanced dependency layout manager? (with UI?) so you could custom decide exactly which dependencies go where, etc. 

## Hope this can help some people! 
> by WillUHD
