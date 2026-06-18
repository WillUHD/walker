import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class Walker {
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
}
