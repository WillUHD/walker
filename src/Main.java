import java.io.*;
import java.nio.file.*;
import java.util.*;

static Terminal t = new Terminal();

void main(String[] args) {
    while(true) {
        var scanner = new Scanner(System.in);
        IO.println("""
            
            `Will'     A     `UHD'       ,cOP  ,cWa                      \s
              UHD     ,vA     ,V           YR    LK                      \s
               WA:   ,JVM:   ,V  ,6"Yb.    IG    v2  ,MP' .gP"Ya `7walker\s
                LK.  M' WA.  M '8)   MM    HT    WA ;Y   ,M'   Yb  WA' "'\s
                `ER A'  `LK A'   walker    WI    LK;v2   20""\"""\"  LK    \s
                 JAVA    :ER;   IS   BY    LL    ER `Mb. 25.    ,  v2    \s
                  21      VF    `willuhd`.wUHD..wALk. v2. `WILL.  \s
            
                ---------============== walker ==============---------
                                       version 2
                                copyright WillUHD 2025
                                   --help if needed
            """);

        Path depPath;
        boolean verbose;
        while (true) {
            IO.println("Enter the path of the dependency: ");

            // whitespace removal is needed because of flags
            var s = scanner.nextLine().trim();

            // could use a switch statement with flags but there are really only 2 here
            if(s.startsWith("--help") || s.startsWith("-h")){
                IO.println("""
                    =================================================
                                  _                            _    \s
                         ,___,   //  /,  _   ,_      /_   _   // ,_ \s
                    _/_/_/(_/(__(/__/(__(/__/ (_   _/ (__(/__(/__/_)_
                                                                /   \s
                               print the help guide: --help    /    \s
                               maximum verbosity: --verbose
                               shut down walker: --shutdown
                    
                      to use walker, start by entering the path of a
                          native library with C entry points.
                        on macOS, native libraries end with .dylib
                    
                     for performance/code issues, contact me @WillUHD
                    
                     =================================================
                    """);
                continue;
            } else if(s.startsWith("--shutdown") || s.startsWith("-s")) System.exit(0);

            verbose = true;

            // magic numbers: "-v".length() == 2, "--verbose".length() == 9
            if(s.endsWith("-v")) s = s.substring(0, s.length() - 2).trim();
            else if(s.endsWith("--verbose"))s  = s.substring(0, s.length() - 9).trim();
            else verbose = false; // flag false if not verbose

            depPath = Path.of(s);

            // verify dylib information
            // is basic because otool won't break on a broken dylib
            if (!Files.exists(depPath) || !depPath.toString().endsWith(".dylib")) {
                IO.println("Path verification failed. --help for help. ");
                continue;
            } break; // break the loop only if these conditions are met
        }

        var startTime = System.nanoTime();

        // makes the proper name for naming the folder (can't contain ".")
        srcPath = depPath.getParent().resolve(
                depPath.getFileName().toString()
                        .replace(".dylib", "")
                        .replace(".", ""));
        IO.println("\nSource dependency detected as: " + srcPath);

        try {Files.createDirectories(srcPath);}
        catch (IOException e) {
            System.err.println("Can't create the target folder, make sure the file system is writable.");
            System.exit(1);
        }

        IO.println("Target directory created. Patching...");

        toCopy.add(depPath);
        int count = 0;

        logging = verbose;
        while (!toCopy.isEmpty()) {
            var current = toCopy.poll();
            otool(current.toString());
            count++;
        }

        if(verbose){
            IO.println("Patching completed. Reverifying: ");
            try (var stream = Files.list(srcPath)) {
                stream.forEach(bin -> IO.println(send("otool -L \"" + bin + "\"")));
            } catch (IOException e) {System.err.println(Arrays.toString(e.getStackTrace()));}
        } else {
            IO.println("Patching completed.");
        }

        var endTime = System.nanoTime();
        var time = (endTime - startTime) / 1000000000f;

        IO.println("\nProcess Completed. Walker: \n" +
                "  - finished in " + time + " seconds\n" +
                "  - analyzed " + count + " dependencies\n" +
                "  - performance is " + (count / time) + " dependencies per second\n" +
                "  - saved in source " + srcPath + "\n" +
                "\nMain binary is " + depPath.getFileName());

        toCopy = new LinkedList<>();
        analyzed = new HashSet<>();
    }
}

static boolean logging = true;
static Path srcPath;
static Queue<Path> toCopy = new LinkedList<>();
static Set<Path> analyzed = new HashSet<>();

static void log(String msg) {
    if (logging) IO.println(msg);
}

static String send(String line) {
    var pb = new ProcessBuilder("zsh", "-c", line);
    pb.redirectErrorStream(true);
    var sb = new StringBuilder();
    try {
        var p = pb.start();
        var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String output;
        while ((output = reader.readLine()) != null) sb.append(output).append('\n');
        p.waitFor();
    } catch (IOException | InterruptedException e) {System.err.println(Arrays.toString(e.getStackTrace()));}
    return sb.toString().trim();
}

public static void otool(String bin) {
    var current = Path.of(bin);
    if (!analyzed.add(current)) return;

    log("Analyzing current file " + bin);
    var binDir = current.getParent();
    var otools = send("otool -L " + bin).split("\n");

    var rPaths = findRPaths(bin);
    var len = rPaths.length;
    if (len > 0){
        for (var p : rPaths) {
            toCopy.add(Path.of(p));
            log("Detected @rpath dependency: " + p);
        }
        log("@rpath detection complete: " + len + " rpaths. ");
    } else log("@rpath clear.");

    for (var i = 1; i < otools.length; i++) {
        var line = otools[i].trim();
        line = line.substring(0, line.indexOf('(')).trim();
        log("Resolving current line: " + line);

        if (line.startsWith("@loader_path/")) {
            var dep = binDir.resolve(line.substring("@loader_path/".length()));
            toCopy.add(dep);
            log("Queued @loader_path dependency: " + dep);
        } else if (line.startsWith("@executable_path/")) {
            var dep = binDir.resolve("Frameworks").resolve(line.substring("@executable_path/".length()));
            toCopy.add(dep);
            log("Queued @executable_path dependency: " + dep);
        } else if (!line.startsWith("/usr/lib") && !line.startsWith("/System/Library")) {
            var dep = Path.of(line);
            toCopy.add(dep);
            log("Queued dependency: " + dep);
        }
    }

    log("Dependency " + bin + " finished analyzation. ");

    copy(current, bin);
    log("Current process for " + bin + " completed. ");
}

private static String[] findRPaths(String path) {
    log("Finding @rpaths for " + path);

    var dirsOut = send("otool -l " + path + " | grep -A2 LC_RPATH");
    if (dirsOut.isBlank()) return new String[0];
    var rDirsRaw = dirsOut.trim().split("\\)");

    var pathsOut = send("otool -L " + path + " | grep @rpath");
    if (pathsOut.isBlank()) return new String[0];
    var rPathsRaw = pathsOut.trim().split("\n");

    List<String> rDirs = new ArrayList<>();
    for (var raw : rDirsRaw) {
        var trimmed = raw.trim();
        if (trimmed.contains("path ") && trimmed.contains("(")) {
            var dir = trimmed.substring(trimmed.indexOf("path ") + 5, trimmed.indexOf('(')).trim();
            rDirs.add(dir);
            log("Dependency LC_RPATH path found: " + dir);
        }
    }

    List<String> rPaths = new ArrayList<>();
    for (var raw : rPathsRaw) {
        var trimmed = raw.trim().replace("@rpath", "");
        if (!trimmed.contains("(")) continue;
        var name = trimmed.substring(0, trimmed.indexOf('(')).trim();
        rPaths.add(name);
        log("Dependency @rpath dylib found: " + name);
    }

    List<String> finalPaths = new ArrayList<>();
    for (var dir : rDirs) {
        for (var name : rPaths) {
            var candidate = dir + name;
            if (Files.exists(Path.of(candidate))) {
                finalPaths.add(candidate);
                log("Resolved @rpath for " + candidate);
            }
        }
    }

    log("@rpath analysis for " + path + " complete.");
    return finalPaths.toArray(new String[0]);
}

private static void copy(Path execPath, String origPath) {
    var copiedPath = srcPath.resolve(execPath.getFileName());
    log("Copying path " + execPath + " to " + copiedPath);
    try {Files.copy(execPath, copiedPath, StandardCopyOption.REPLACE_EXISTING);}
    catch (IOException e) {System.err.println(Arrays.toString(e.getStackTrace()));}
    log("Patching path linking using install_name_tool. ");

    send("install_name_tool -id \"@loader_path/" + execPath.getFileName() + "\" " + copiedPath);
    send("install_name_tool -change \"" + execPath + "\" \"@loader_path/" + execPath.getFileName() + "\" \"" + origPath + "\"");
}
