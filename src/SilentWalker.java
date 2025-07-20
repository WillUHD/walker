import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class SilentWalker {
    public static void otool(String bin) {
        var current = Path.of(bin);
        if (!Main.analyzed.add(current)) return;
        var binDir = current.getParent();
        var otools = Main.send("otool -L " + bin).split("\n");
        var rPaths = findRPaths(bin);
        var len = rPaths.length;
        if (len > 0)for (var p : rPaths) Main.toCopy.add(Path.of(p));

        for (var i = 1; i < otools.length; i++) {
            var line = otools[i].trim();
            line = line.substring(0, line.indexOf('(')).trim();

            if (line.startsWith("@loader_path/")) {
                var dep = binDir.resolve(line.substring("@loader_path/".length()));
                Main.toCopy.add(dep);
            } else if (line.startsWith("@executable_path/")) {
                var dep = binDir.resolve("Frameworks").resolve(line.substring("@executable_path/".length()));
                Main.toCopy.add(dep);
            } else if (!line.startsWith("/usr/lib") && !line.startsWith("/System/Library")) {
                var dep = Path.of(line);
                Main.toCopy.add(dep);
            }
        }

        copy(current, bin);
    }

    private static String[] findRPaths(String path) {
        var dirsOut = Main.send("otool -l " + path + " | grep -A2 LC_RPATH");
        if (dirsOut.isBlank()) return new String[0];
        var rDirsRaw = dirsOut.trim().split("\\)");

        var pathsOut = Main.send("otool -L " + path + " | grep @rpath");
        if (pathsOut.isBlank()) return new String[0];
        var rPathsRaw = pathsOut.trim().split("\n");

        List<String> rDirs = new ArrayList<>();
        for (var raw : rDirsRaw) {
            var trimmed = raw.trim();
            if (trimmed.contains("path ") && trimmed.contains("(")) {
                var dir = trimmed.substring(trimmed.indexOf("path ") + 5, trimmed.indexOf('(')).trim();
                rDirs.add(dir);
            }
        }

        List<String> rPaths = new ArrayList<>();
        for (var raw : rPathsRaw) {
            var trimmed = raw.trim().replace("@rpath", "");
            if (!trimmed.contains("(")) continue;
            var name = trimmed.substring(0, trimmed.indexOf('(')).trim();
            rPaths.add(name);
        }

        List<String> finalPaths = new ArrayList<>();
        for (var dir : rDirs) {
            for (var name : rPaths) {
                var candidate = dir + name;
                if (Files.exists(Path.of(candidate))) finalPaths.add(candidate);
            }
        }

        return finalPaths.toArray(new String[0]);
    }

    private static void copy(Path execPath, String origPath) {
        var copiedPath = Main.srcPath.resolve(execPath.getFileName());
        try {Files.copy(execPath, copiedPath, StandardCopyOption.REPLACE_EXISTING);}
        catch (IOException e) {System.err.println(e);}

        Main.send("install_name_tool -id \"@loader_path/" + execPath.getFileName() + "\" " + copiedPath);
        Main.send("install_name_tool -change \"" + execPath + "\" \"@loader_path/" + execPath.getFileName() + "\" \"" + origPath + "\"");
    }
}
