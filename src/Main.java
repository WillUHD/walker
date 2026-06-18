import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main{
    static Terminal t = new Terminal();

    static boolean logging = true;
    static Path srcPath;
    static final Set<Path> analyzed = ConcurrentHashMap.newKeySet();
    static final AtomicInteger count = new AtomicInteger(0);

    void main() {
        while(true) {
            var scanner = new Scanner(System.in);
            t.print("\nWalker 3 ", Terminal.Text.bold, Terminal.Colors.brightBlue);
            t.print("by willuhd ", Terminal.Text.italic, Terminal.Colors.blue);
            IO.print("—— Enter the library path ");
            t.print("(-h for help)", Terminal.Text.dim);
            IO.println(": ");
            t.set(Terminal.Text.italic, Terminal.Colors.green);
            IO.print(">> ");

            Path depPath;
            boolean verbose;
            while (true) {

                var s = scanner.nextLine().trim();
                t.restore();

                if(s.startsWith("--help") || s.startsWith("-h")){
                    t.print("\n          Walker ", Terminal.Text.italic, Terminal.Colors.blue);
                    t.println("help", Terminal.Text.bold, Terminal.Colors.blue);
                    t.print("  print this help menu: ", Terminal.Text.dim);
                    t.println("--help", Terminal.Colors.brightBlue);
                    t.print("  maximum verbosity: ", Terminal.Text.dim);
                    t.println("--verbose", Terminal.Colors.brightBlue);
                    t.print("  shut down Walker: ", Terminal.Text.dim);
                    t.println("--shutdown\n", Terminal.Colors.brightBlue);
                    continue;
                } else if(s.startsWith("--shutdown") || s.startsWith("-s")) System.exit(0);

                verbose = true;

                if(s.endsWith("-v")) s = s.substring(0, s.length() - 2).trim();
                else if(s.endsWith("--verbose")) s = s.substring(0, s.length() - 9).trim();
                else verbose = false;

                if (s.equals("~")) s = System.getProperty("user.home");
                else if (s.startsWith("~/")) s = System.getProperty("user.home") + s.substring(1);

                depPath = Path.of(s);

                if (!Files.exists(depPath) || !depPath.toString().endsWith(".dylib")) {
                    t.error("Path is invalid");
                    continue;
                } break;
            }

            var startTime = System.nanoTime();

            srcPath = depPath.getParent().resolve(
                    depPath.getFileName().toString()
                            .replace(".dylib", "")
                            .replace(".", ""));

            try { Files.createDirectories(srcPath); }
            catch (IOException e) {
                t.error("Can't create the target folder, make sure the file system is writable.");
                System.exit(1);
            }

            IO.println("Target directory created. Patching...\u001B[?7l");

            // reset state for new run
            analyzed.clear();
            count.set(0);
            logging = verbose;

            // use virtual threads
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var phaser = new Phaser(1);
                submitDependency(depPath, executor, phaser);
                phaser.arriveAndAwaitAdvance();
            }

            if(verbose){
                IO.println("Patching completed. Reverifying: ");
                try (var stream = Files.list(srcPath)) {
                    stream.forEach(bin -> IO.println(send("otool -L \"" + bin + "\"")));
                } catch (IOException e) { t.error(Arrays.toString(e.getStackTrace())); }
            } else IO.println("\r\rPatching completed.\u001B[K\n");

            var endTime = System.nanoTime();
            var time = (endTime - startTime) / 1_000_000_000f;
            var totalAnalyzed = count.get();

            IO.println("""
                    Summary:
                      - finished in %.3f seconds
                      - analyzed %d dependencies
                      - saved in %s
                    Main library is %s
                    """.formatted(time, totalAnalyzed,
                    t.format(String.valueOf(srcPath), Terminal.Text.italic, Terminal.Colors.green),
                    depPath.getFileName()));
        }
    }

    static void submitDependency(Path dep, ExecutorService executor, Phaser phaser) {
        // only proceeds if we haven't seen this path yet
        if (!analyzed.add(dep)) return;

        phaser.register();
        executor.submit(() -> {
            try {otool(dep, executor, phaser);}
            finally {phaser.arriveAndDeregister();}
        });
    }

    static synchronized void log(String msg) {
        if (logging) IO.println(msg);
        else IO.print(("\r" + msg + "\u001B[K").translateEscapes());
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
        } catch (IOException | InterruptedException e) {t.error(Arrays.toString(e.getStackTrace()));}
        return sb.toString().trim();
    }

    public static void otool(Path current, ExecutorService executor, Phaser phaser) {
        count.incrementAndGet();
        log("Analyzing current file " + current);

        var bin = current.toString();
        var binDir = current.getParent();
        var otools = send("otool -L " + bin).split("\n");

        // copy original file to the target location
        var copiedPath = srcPath.resolve(current.getFileName());
        log("Copying path " + current + " to " + copiedPath);
        try {
            Files.copy(current, copiedPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            t.error(Arrays.toString(e.getStackTrace()));
        }

        // set the library ID inside the copied binary
        send("install_name_tool -id \"@loader_path/" + current.getFileName() + "\" " + copiedPath);

        // identify @rpaths and submit them
        var rPaths = findRPaths(bin);
        var len = rPaths.length;
        if (len > 0){
            for (var p : rPaths) {
                var rpathDep = Path.of(p);
                submitDependency(rpathDep, executor, phaser);
                log("Detected @rpath dependency: " + p);

                // patch the copied binary's rpath reference to use @loader_path
                send("install_name_tool -change \"@rpath/" + rpathDep.getFileName() + "\" \"@loader_path/" + rpathDep.getFileName() + "\" \"" + copiedPath + "\"");
            }
            log("@rpath detection complete: " + len + " rpaths. ");
        } else log("@rpath clear.");

        // parse and submit general dependencies
        for (var i = 1; i < otools.length; i++) {
            var line = otools[i].trim();
            if (!line.contains("(")) continue;
            line = line.substring(0, line.indexOf('(')).trim();
            log("Resolving current line: " + line);

            Path dep = null;
            if (line.startsWith("@loader_path/")) {
                dep = binDir.resolve(line.substring("@loader_path/".length()));
            } else if (line.startsWith("@executable_path/")) {
                dep = binDir.resolve("Frameworks").resolve(line.substring("@executable_path/".length()));
            } else if (!line.startsWith("/usr/lib") && !line.startsWith("/System/Library")) {
                dep = Path.of(line);
            }

            if (dep != null) {
                submitDependency(dep, executor, phaser);
                log("Queued dependency: " + dep);

                // patch the copied binary's dependency path to point to @loader_path
                send("install_name_tool -change \"" + line + "\" \"@loader_path/" + dep.getFileName() + "\" \"" + copiedPath + "\"");
            }
        }

        log("Dependency " + bin + " finished analyzation. ");
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
        for (var dir : rDirs)
            for (var name : rPaths) {
                var candidate = dir + name;
                if (Files.exists(Path.of(candidate))) {
                    finalPaths.add(candidate);
                    log("Resolved @rpath for " + candidate);
                }
            }

        log("@rpath analysis for " + path + " complete.");
        return finalPaths.toArray(new String[0]);
    }
}