import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class Main {
    static Terminal t = new Terminal();
    static boolean logging = true;
    static Path srcPath;

    static final Map<String, DepNode> stateNodes = new ConcurrentHashMap<>();
    static final AtomicBoolean isScanning = new AtomicBoolean(false);
    static final AtomicBoolean cancelScan = new AtomicBoolean(false);
    static String currentRootPath = "";

    static class DepNode {
        String id; // Absolute path or "missing:<parentId>|<ref>"
        String fileName;
        String absolutePath;
        boolean isMissing;
        boolean isSystem;
        String architecture;
        List<String> rpaths = new CopyOnWriteArrayList<>();
        Set<String> dyldFlags = Collections.synchronizedSet(new LinkedHashSet<>());
        Map<String, Edge> children = Collections.synchronizedMap(new LinkedHashMap<>());
        int incomingEdgesCount = 0;
    }

    record Edge(String originalReference, String resolvedMechanism, String loadCommand) {}
    record PathResolution(String path, String mechanism) {}
    record DependencyRef(String path, String cmd) {}

    void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            return;
        }

        Path depPath = null;
        Path outPath = null;
        var verbose = false;
        var web = false;
        var port = 2013;

        for (var v = 0; v < args.length; v++) {
            var arg = args[v];
            switch (arg) {
                case "-h", "--help" -> {
                    printHelp();
                    return;
                }
                case "-v", "--verbose" -> verbose = true;
                case "-w", "--web" -> web = true;
                case "-p", "--port" -> {
                    if (v + 1 < args.length) {
                        try { port = Integer.parseInt(args[++v]); }
                        catch (NumberFormatException e) {
                            t.error("Invalid port: " + args[v]);
                            System.exit(1);
                        }
                    } else {
                        t.error("Missing port number");
                        System.exit(1);
                    }
                }
                case "-o", "--output" -> {
                    if (v + 1 < args.length) {
                        var outStr = args[++v];
                        if (outStr.equals("~"))
                            outStr = System.getProperty("user.home");
                        else if (outStr.startsWith("~/"))
                            outStr = System.getProperty("user.home") + outStr.substring(1);
                        outPath = Path.of(outStr);
                    } else {
                        t.error("Missing output folder path");
                        System.exit(1);
                    }
                }
                default -> {
                    var s = arg;
                    if (s.equals("~"))
                        s = System.getProperty("user.home");
                    else if (s.startsWith("~/"))
                        s = System.getProperty("user.home") + s.substring(1);
                    depPath = Path.of(s);
                }
            }
        }

        if (web) {
            try {
                startWebServer(port);
                synchronized (Main.class) {
                    Main.class.wait();
                }
            } catch (Exception e) {
                t.error("Failed to start web server: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        if (depPath == null || !Files.exists(depPath) || !depPath.toString().endsWith(".dylib")) {
            t.error("Path is invalid");
            System.exit(1);
        }

        var success = new Main().runPatch(depPath, outPath, verbose);
        if (!success) {
            System.exit(1);
        }
    }

    static void printHelp() {
        t.print("\nWalker 3.1 ", Terminal.Text.bold, Terminal.Colors.brightBlue);
        t.print("by willuhd ", Terminal.Text.italic, Terminal.Colors.blue);
        t.print("\u001B]8;;https://github.com/willuhd/walker\u0007(https://github.com/willuhd/walker)\u001B]8;;\u0007",
                Terminal.Text.underline, Terminal.Colors.brightBlack);

        t.print("\n- ", Terminal.Text.dim);
        t.println("Walker is a dependency patcher for Mach-O libraries (.dylib files).", Terminal.Text.normal);
        t.print("- ", Terminal.Text.dim);
        t.println("It recursively copies and patches non-system libraries into a single folder.", Terminal.Text.normal);
        t.print("- ", Terminal.Text.dim);
        t.println("For advanced custom path configurations, use the local web server option.", Terminal.Text.normal);

        t.print("\nTo show the help menu: ", Terminal.Text.bold, Terminal.Colors.brightCyan);
        t.print("walker ", Terminal.Colors.brightBlue);
        t.println("[--help | -h]", Terminal.Colors.white);

        t.print("\nPatch a library (cmd): ", Terminal.Text.bold, Terminal.Colors.brightCyan);
        t.print("walker ", Terminal.Colors.brightBlue);
        t.print("[path/to/library.dylib] ", Terminal.Colors.white);
        t.println("[flags]", Terminal.Text.normal, Terminal.Colors.brightCyan);
        t.print("  --output  (-o)", Terminal.Colors.blue);
        t.println(": specify a custom output folder to patch to", Terminal.Text.dim);
        t.print("  --verbose (-v)", Terminal.Colors.blue);
        t.println(": print all logs during the patching process", Terminal.Text.dim);

        t.print("\nPatch a library (web): ", Terminal.Text.bold, Terminal.Colors.brightCyan);
        t.print("walker ", Terminal.Colors.brightBlue);
        t.print("[--web | -w] ", Terminal.Colors.white);
        t.println("[flags]", Terminal.Text.normal, Terminal.Colors.brightCyan);
        t.print("  --port    (-p)", Terminal.Colors.blue);
        t.println(": specify a custom port for the server", Terminal.Text.dim);
        t.print("  --verbose (-v)", Terminal.Colors.blue);
        t.println(": print all logs for the server and patches run", Terminal.Text.dim);

        t.print("\nWalker supports entering relative paths (like ", Terminal.Text.dim);
        t.print("./", Terminal.Text.bold, Terminal.Colors.brightGreen);
        t.print(", ", Terminal.Text.dim);
        t.print("../", Terminal.Text.bold, Terminal.Colors.brightGreen);
        t.print(", ", Terminal.Text.dim);
        t.print("~/", Terminal.Text.bold, Terminal.Colors.brightGreen);
        t.print(").", Terminal.Text.dim);
        t.print("\nBy default, the server port is ", Terminal.Text.dim);
        t.println("2013\n", Terminal.Text.bold, Terminal.Colors.green);
    }

    boolean runPatch(Path depPath, Path outPath, boolean verbose) {
        var startTime = System.nanoTime();
        logging = verbose;

        var parentDir = (outPath != null) ? outPath : depPath.getParent();
        srcPath = parentDir.resolve(
                depPath.getFileName().toString()
                        .replace(".dylib", "")
                        .replace(".", ""));

        try { Files.createDirectories(srcPath); }
        catch (IOException e) {
            t.error("Can't create the target folder, make sure the file system is writable.");
            return false;
        }

        // Disable line-wrap during execution to keep progress output clean
        IO.println("Target directory created. Scanning dependencies...\u001B[?7l");

        try {
            stateNodes.clear();
            currentRootPath = depPath.toAbsolutePath().toString();

            // Parallel scan using Virtual Threads
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var phaser = new Phaser(1);
                submitScan(currentRootPath, new HashSet<>(), executor, phaser);
                phaser.arriveAndAwaitAdvance();
            }

            calculateIncomingEdges();

            IO.println("Copying and patching binaries in parallel...");

            List<DepNode> localNodes = stateNodes.values().stream()
                    .filter(n -> !n.isSystem && !n.isMissing)
                    .toList();

            // Parallel patching and signing using Virtual Threads
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var node : localNodes) {
                    executor.submit(() -> {
                        Path original = Path.of(node.absolutePath);
                        Path target = srcPath.resolve(node.fileName);
                        log("Copying " + original + " to " + target);
                        try {
                            Files.copy(original, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            t.error("Failed to copy " + original + ": " + e.getMessage());
                            return;
                        }

                        exec("install_name_tool", "-id", "@loader_path/" + node.fileName, target.toString());

                        synchronized (node.children) {
                            for (var entry : node.children.entrySet()) {
                                var childId = entry.getKey();
                                var edge = entry.getValue();
                                var childNode = stateNodes.get(childId);
                                if (childNode != null && !childNode.isSystem) {
                                    String newRef = "@loader_path/" + childNode.fileName;
                                    log("Updating ref in " + node.fileName + ": " + edge.originalReference() + " -> " + newRef);
                                    exec("install_name_tool", "-change", edge.originalReference(), newRef, target.toString());
                                }
                            }
                        }

                        exec("codesign", "-f", "-s", "-", target.toString());
                    });
                }
            }

            if (verbose) {
                IO.println("Patching completed. Reverifying: ");
                try (var stream = Files.list(srcPath)) {
                    stream.forEach(bin -> IO.println(exec("otool", "-L", bin.toString())));
                } catch (IOException e) {
                    t.error(e.getMessage());
                    cleanup(srcPath);
                    return false;
                }
            } else {
                IO.println("\r\rPatching completed.\u001B[K\n");
            }

            var endTime = System.nanoTime();
            var time = (endTime - startTime) / 1_000_000_000f;
            var totalAnalyzed = localNodes.size();

            IO.println("""
                    Summary:
                      - finished in %.3f seconds
                      - analyzed %d dependencies
                      - saved in %s
                    Main library is %s
                    """.formatted(time, totalAnalyzed,
                    t.format(String.valueOf(srcPath), Terminal.Text.italic, Terminal.Colors.green),
                    depPath.getFileName()));
            return true;
        } finally {IO.print("\u001B[?7h");}
    }

    static synchronized void log(String msg) {
        if (logging) IO.println(msg);
        else IO.print(("\r" + msg + "\u001B[K").translateEscapes());
    }

    static String exec(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var p = pb.start();
            var sb = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    static void startDiscovery(String rootPath) {
        isScanning.set(true);
        cancelScan.set(false);
        stateNodes.clear();

        String resolvedPath = rootPath;
        if (resolvedPath.equals("~")) {
            resolvedPath = System.getProperty("user.home");
        } else if (resolvedPath.startsWith("~/")) {
            resolvedPath = System.getProperty("user.home") + resolvedPath.substring(1);
        }
        Path targetPathObj = Path.of(resolvedPath).toAbsolutePath().normalize();
        currentRootPath = targetPathObj.toString();

        Thread.startVirtualThread(() -> {
            try {
                if (Files.exists(targetPathObj)) {
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var phaser = new Phaser(1);
                        submitScan(currentRootPath, new HashSet<>(), executor, phaser);
                        phaser.arriveAndAwaitAdvance();
                    }
                    calculateIncomingEdges();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isScanning.set(false);
            }
        });
    }

    static void calculateIncomingEdges() {
        for (var n : stateNodes.values()) n.incomingEdgesCount = 0;
        for (var parent : stateNodes.values()) {
            Map<String, Edge> childrenCopy;
            synchronized (parent.children) {
                childrenCopy = new LinkedHashMap<>(parent.children);
            }
            for (var childId : childrenCopy.keySet()) {
                var child = stateNodes.get(childId);
                if (child != null) child.incomingEdgesCount++;
            }
        }
    }

    static void submitScan(String absPath, Set<String> accumulatedRpaths, ExecutorService executor, Phaser phaser) {
        if (cancelScan.get()) return;

        var placeholder = new DepNode();
        placeholder.id = absPath;
        placeholder.absolutePath = absPath;
        placeholder.fileName = Path.of(absPath).getFileName().toString();
        placeholder.isSystem = absPath.startsWith("/usr/lib") || absPath.startsWith("/System/Library");
        placeholder.isMissing = false;

        if (stateNodes.putIfAbsent(absPath, placeholder) != null) {
            return;
        }

        phaser.register();
        executor.submit(() -> {
            try {
                scanNodeTask(placeholder, accumulatedRpaths, executor, phaser);
            } finally {
                phaser.arriveAndDeregister();
            }
        });
    }

    static void scanNodeTask(DepNode node, Set<String> accumulatedRpaths, ExecutorService executor, Phaser phaser) {
        String absPath = node.id;

        if (isScanning.get()) {
            node.architecture = exec("file", "-b", absPath)
                    .replace("Mach-O 64-bit dynamically linked shared library ", "")
                    .trim();
        } else {
            node.architecture = "Unknown";
        }

        List<String> rpaths = new ArrayList<>();
        List<DependencyRef> deps = new ArrayList<>();
        parseMachO(absPath, rpaths, deps);

        node.rpaths.addAll(rpaths);
        for (var d : deps) {
            node.dyldFlags.add(d.cmd());
        }

        var nextRpaths = new LinkedHashSet<>(accumulatedRpaths);
        nextRpaths.addAll(node.rpaths);

        for (var depRef : deps) {
            if (cancelScan.get()) return;
            var ref = depRef.path();
            var res = resolveReference(ref, absPath, nextRpaths);
            var resolvedPath = res != null ? res.path() : null;
            var mechanism = res != null ? res.mechanism() : "unresolved";

            var edge = new Edge(ref, mechanism, depRef.cmd());

            if (resolvedPath != null && resolvedPath.equals(absPath)) continue;

            var isSysRef = ref.startsWith("/usr/lib") || ref.startsWith("/System/Library");

            if (resolvedPath != null && (isSysRef || Files.exists(Path.of(resolvedPath)))) {
                synchronized (node.children) {
                    node.children.put(resolvedPath, edge);
                }
                if (!isSysRef) {
                    submitScan(resolvedPath, nextRpaths, executor, phaser);
                } else if (!stateNodes.containsKey(resolvedPath)) {
                    var sysNode = new DepNode();
                    sysNode.id = resolvedPath;
                    sysNode.absolutePath = resolvedPath;
                    sysNode.fileName = Path.of(resolvedPath).getFileName().toString();
                    sysNode.isSystem = true;
                    sysNode.isMissing = false;
                    sysNode.architecture = "macOS Dynamic Shared Cache";
                    stateNodes.put(resolvedPath, sysNode);
                }
            } else {
                var missingId = "missing:" + absPath + "|" + ref;
                synchronized (node.children) {
                    node.children.put(missingId, edge);
                }

                var mNode = new DepNode();
                mNode.id = missingId;
                mNode.fileName = Path.of(ref).getFileName().toString();
                mNode.isMissing = true;
                mNode.isSystem = false;
                mNode.absolutePath = null;
                stateNodes.putIfAbsent(missingId, mNode);
            }
        }
    }

    static void parseMachO(String absPath, List<String> rpaths, List<DependencyRef> deps) {
        String output = exec("otool", "-l", absPath);
        String[] lines = output.split("\n");
        String currentCmd = null;
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("cmd ")) {
                currentCmd = line.substring(4).trim();
            } else if (line.startsWith("path ") && "LC_RPATH".equals(currentCmd)) {
                int openParen = line.indexOf('(');
                String val = openParen > 5 ? line.substring(5, openParen).trim() : line.substring(5).trim();
                rpaths.add(val);
            } else if (line.startsWith("name ") && currentCmd != null && currentCmd.endsWith("DYLIB")) {
                int openParen = line.indexOf('(');
                String val = openParen > 5 ? line.substring(5, openParen).trim() : line.substring(5).trim();
                deps.add(new DependencyRef(val, currentCmd));
            }
        }
    }

    static PathResolution resolveReference(String ref, String parentAbs, Set<String> rpaths) {
        var parentDir = Path.of(parentAbs).getParent();
        if (parentDir == null) return null;

        if (ref.startsWith("@loader_path/")) {
            return new PathResolution(parentDir.resolve(ref.substring(13)).normalize().toString(), "loader_path");
        } else if (ref.startsWith("@executable_path/")) {
            var execBase = parentDir.endsWith("Frameworks") ? parentDir.getParent().resolve("MacOS") : parentDir;
            return new PathResolution(execBase.resolve(ref.substring(17)).normalize().toString(), "executable_path");
        } else if (ref.startsWith("@rpath/")) {
            var suffix = ref.substring(7);
            for (var rp : rpaths) {
                var testRp = rp.replace("@loader_path", parentDir.toString())
                        .replace("@executable_path", parentDir.toString());
                var candidate = Path.of(testRp).resolve(suffix).normalize();
                if (Files.exists(candidate)) return new PathResolution(candidate.toString(), "rpath (" + rp + ")");
            }
            return new PathResolution(null, "rpath (Not Found)");
        } else {
            return new PathResolution(ref, "absolute");
        }
    }

    static void cleanup(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    static void startWebServer(int port) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new WebHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        IO.println("Walker 3 Web Server running at http://localhost:" + port);
    }

    static class WebHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            var path = ex.getRequestURI().getPath();
            var method = ex.getRequestMethod();

            try {
                if (path.equals("/") && method.equals("GET")) {
                    serveHtml(ex);
                } else if (path.equals("/dylib.png") && method.equals("GET")) {
                    serveIcon(ex);
                } else if (path.equals("/folder.png") && method.equals("GET")) {
                    serveFolderIcon(ex);
                } else if (path.equals("/api/discover") && method.equals("POST")) {
                    var bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                    String reqPath = "";
                    try {
                        @SuppressWarnings("unchecked")
                        var parsed = (Map<String, Object>) new JSONParser(bodyStr).parse();
                        if (parsed != null && parsed.get("path") != null) {
                            reqPath = parsed.get("path").toString();
                        }
                    } catch (Exception e) {
                        reqPath = bodyStr;
                    }
                    startDiscovery(reqPath);
                    sendJSON(ex, 200, Map.of("status", "started"));
                } else if (path.equals("/api/status") && method.equals("GET")) {
                    sendJSON(ex, 200, buildStatus());
                } else if (path.equals("/api/clear") && method.equals("POST")) {
                    cancelScan.set(true);
                    stateNodes.clear();
                    currentRootPath = "";
                    sendJSON(ex, 200, Map.of("status", "cleared"));
                } else if (path.equals("/api/fs-list") && method.equals("GET")) {
                    handleFileSystemList(ex);
                } else if (path.equals("/api/apply") && method.equals("POST")) {
                    var bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    var req = (Map<String, Object>) new JSONParser(bodyStr).parse();
                    var res = applyPatchBatch(req);
                    if (Boolean.TRUE.equals(res.get("success"))) startDiscovery(currentRootPath);
                    sendJSON(ex, 200, res);
                } else {
                    ex.sendResponseHeaders(404, -1);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendJSON(ex, 500, Map.of("error", e.getMessage()));
            }
        }

        private void serveHtml(HttpExchange ex) throws IOException {
            try (var is = Main.class.getResourceAsStream("/index.html")) {
                if (is == null) {
                    var err = "Error: Embedded 'index.html' resource not found in build.".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(404, err.length);
                    ex.getResponseBody().write(err);
                } else {
                    var b = is.readAllBytes();
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                }
            } finally {
                ex.getResponseBody().close();
            }
        }

        private void serveIcon(HttpExchange ex) throws IOException {
            try (var is = Main.class.getResourceAsStream("/dylib.png")) {
                if (is != null) {
                    byte[] b = is.readAllBytes();
                    ex.getResponseHeaders().set("Content-Type", "image/png");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                } else {
                    ex.sendResponseHeaders(404, -1);
                }
            } finally {
                ex.getResponseBody().close();
            }
        }

        private void serveFolderIcon(HttpExchange ex) throws IOException {
            try (var is = Main.class.getResourceAsStream("/folder.png")) {
                if (is != null) {
                    byte[] b = is.readAllBytes();
                    ex.getResponseHeaders().set("Content-Type", "image/png");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                } else {
                    ex.sendResponseHeaders(404, -1);
                }
            } finally {
                ex.getResponseBody().close();
            }
        }

        private void handleFileSystemList(HttpExchange ex) throws IOException {
            var query = ex.getRequestURI().getQuery();
            var pathParam = "";
            if (query != null && query.contains("path=")) {
                var parts = query.split("path=");
                if (parts.length > 1) pathParam = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }

            var p = pathParam.isEmpty() ? Path.of(System.getProperty("user.home")) : Path.of(pathParam);
            if (!Files.exists(p)) p = Path.of("/");
            if (Files.isRegularFile(p)) {
                p = p.getParent();
                if (p == null) p = Path.of("/");
            }

            var response = new LinkedHashMap<String, Object>();
            response.put("currentPath", p.toAbsolutePath().toString());
            response.put("parentPath", p.getParent() != null ? p.getParent().toAbsolutePath().toString() : "");

            var filesList = new ArrayList<Map<String, Object>>();
            try {
                Files.list(p).forEach(file -> {
                    var fData = new LinkedHashMap<String, Object>();
                    fData.put("name", file.getFileName().toString());
                    fData.put("isDir", Files.isDirectory(file));
                    fData.put("absPath", file.toAbsolutePath().toString());
                    filesList.add(fData);
                });
            } catch (Exception ignored) {}
            response.put("files", filesList);
            sendJSON(ex, 200, response);
        }

        private void sendJSON(HttpExchange ex, int code, Object data) throws IOException {
            var b = serializeJSON(data).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> applyPatchBatch(Map<String, Object> payload) {
        IO.println("\n--- BEGIN BATCH PATCH OPERATION ---");
        var ops = (List<Map<String, Object>>) payload.get("ops");
        var affectedTargets = new LinkedHashSet<String>();

        for (var op : ops) {
            if ("refactor_rpath".equals(op.get("type"))) {
                var conflict = checkRefactorConflicts(op);
                if (conflict != null && !op.containsKey("conflict_resolution")) {
                    return Map.of("success", false, "conflict", conflict, "op", op);
                }
            } else if ("change_dep_with_file".equals(op.get("type"))) {
                var actionType = (String) op.get("file_action");
                var childId = (String) op.get("childId");
                var childNode = stateNodes.get(childId);

                if ("move".equals(actionType) && childNode != null && childNode.incomingEdgesCount > 1 && !op.containsKey("force")) {
                    return Map.of("success", false, "shared_warning", true, "childName", childNode.fileName, "incomingCount", childNode.incomingEdgesCount, "op", op);
                }
            }
        }

        for (var op : ops) {
            var type = (String) op.get("type");
            var target = (String) op.get("target");

            if (target != null) affectedTargets.add(target);

            switch (type) {
                case "add_rpath" -> {
                    var val = (String) op.get("val");
                    IO.println("Adding rpath to " + target + ": " + val);
                    exec("install_name_tool", "-add_rpath", val, target);
                }
                case "delete_rpath" -> {
                    var val = (String) op.get("val");
                    IO.println("Deleting rpath from " + target + ": " + val);
                    exec("install_name_tool", "-delete_rpath", val, target);
                }
                case "rename_rpath" -> {
                    var oldV = (String) op.get("oldVal");
                    var newV = (String) op.get("newVal");
                    IO.println("Renaming rpath in " + target + " from " + oldV + " to " + newV);
                    exec("install_name_tool", "-rpath", oldV, newV, target);
                }
                case "refactor_rpath" -> {
                    var oldV = (String) op.get("oldVal");
                    var newV = (String) op.get("newVal");
                    var res = (String) op.get("conflict_resolution");
                    IO.println("Refactoring rpath in " + target + " from " + oldV + " to " + newV);

                    var copied = performRefactorCopy(target, oldV, newV, res);
                    affectedTargets.addAll(copied);
                    exec("install_name_tool", "-rpath", oldV, newV, target);
                }
                case "change_dep" -> {
                    var oldRef = (String) op.get("oldRef");
                    var newRef = (String) op.get("newRef");
                    IO.println("Changing reference string in " + target + " from " + oldRef + " to " + newRef);
                    exec("install_name_tool", "-change", oldRef, newRef, target);
                }
                case "change_dep_with_file" -> {
                    var dest = executeChangeDepWithFile(op);
                    if (dest != null) affectedTargets.add(dest);
                }
                default -> {}
            }
        }

        for (var tgt : affectedTargets) {
            if (tgt != null && Files.exists(Path.of(tgt))) {
                IO.println("Re-signing altered binary: " + tgt);
                exec("codesign", "-f", "-s", "-", tgt);
            }
        }

        IO.println("--- BATCH PATCH OPERATION COMPLETE ---\n");
        return Map.of("success", true);
    }

    static String executeChangeDepWithFile(Map<String, Object> op) {
        var targetParent = (String) op.get("target");
        var oldRef = (String) op.get("oldRef");
        var newRef = (String) op.get("newRef");
        var srcPathVal = (String) op.get("srcPath");
        var destPathVal = (String) op.get("destPath");
        var action = (String) op.get("file_action");

        try {
            var src = Path.of(srcPathVal);
            var dest = Path.of(destPathVal);

            if (!"none".equals(action) && Files.exists(src) && !src.toRealPath().equals(dest.toAbsolutePath())) {
                Files.createDirectories(dest.getParent());
                if ("move".equals(action)) {
                    IO.println("Moving physical binary: " + src + " -> " + dest);
                    Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    IO.println("Copying physical binary: " + src + " -> " + dest);
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return destPathVal;
            }

            IO.println("Updating Mach-O reference in: " + targetParent);
            exec("install_name_tool", "-change", oldRef, newRef, targetParent);
        } catch (Exception e) {
            t.error("Relocation Error: " + e.getMessage());
        }
        return null;
    }

    static String checkRefactorConflicts(Map<String, Object> op) {
        var target = (String) op.get("target");
        var oldV = (String) op.get("oldVal");
        var newV = (String) op.get("newVal");

        var node = stateNodes.get(target);
        if (node == null) return null;

        var baseDir = Path.of(target).getParent();
        if (baseDir == null) return null;
        var destDir = resolveAbstractPath(newV, baseDir);

        Map<String, Edge> childrenCopy;
        synchronized (node.children) {
            childrenCopy = new LinkedHashMap<>(node.children);
        }

        for (var child : childrenCopy.entrySet()) {
            if (child.getValue().resolvedMechanism().contains(oldV)) {
                var src = Path.of(child.getKey());
                if (!Files.exists(src)) continue;
                var dest = destDir.resolve(src.getFileName());
                if (Files.exists(dest)) return dest.toString();
            }
        }
        return null;
    }

    static List<String> performRefactorCopy(String target, String oldV, String newV, String resolution) {
        var copiedPaths = new ArrayList<String>();
        var node = stateNodes.get(target);
        if (node == null) return copiedPaths;

        var baseDir = Path.of(target).getParent();
        if (baseDir == null) return copiedPaths;
        var destDir = resolveAbstractPath(newV, baseDir);

        try {
            Files.createDirectories(destDir);
            Map<String, Edge> childrenCopy;
            synchronized (node.children) {
                childrenCopy = new LinkedHashMap<>(node.children);
            }
            for (var child : childrenCopy.entrySet()) {
                if (child.getValue().resolvedMechanism().contains(oldV)) {
                    var src = Path.of(child.getKey());
                    if (!Files.exists(src)) continue;

                    var dest = destDir.resolve(src.getFileName());
                    if (Files.exists(dest)) {
                        if ("skip".equals(resolution)) {
                            IO.println("Skipping copy: " + dest);
                            continue;
                        } else if ("replace".equals(resolution)) {
                            IO.println("Renaming existing conflict destination to " + dest + "-old");
                            Files.move(dest, Path.of(dest.toString() + "-old"), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    IO.println("Copying refactored file: " + src + " -> " + dest);
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    copiedPaths.add(dest.toString());
                }
            }
        } catch (IOException e) {
            t.error("Refactor error: " + e.getMessage());
        }
        return copiedPaths;
    }

    static Path resolveAbstractPath(String rp, Path baseDir) {
        var testRp = rp.replace("@loader_path", baseDir.toString())
                .replace("@executable_path", baseDir.toString());
        return Path.of(testRp).normalize();
    }

    static Map<String, Object> buildStatus() {
        var nodesMap = new LinkedHashMap<String, Object>();
        for (var n : stateNodes.values()) {
            var nData = new LinkedHashMap<String, Object>();
            nData.put("id", n.id);
            nData.put("fileName", n.fileName);
            nData.put("absolutePath", n.absolutePath == null ? "" : n.absolutePath);
            nData.put("isMissing", n.isMissing);
            nData.put("isSystem", n.isSystem);
            nData.put("architecture", n.architecture == null ? "" : n.architecture);
            nData.put("rpaths", new ArrayList<>(n.rpaths));
            synchronized (n.dyldFlags) {
                nData.put("dyldFlags", new ArrayList<>(n.dyldFlags));
            }
            nData.put("incomingEdgesCount", n.incomingEdgesCount);

            var children = new LinkedHashMap<String, Object>();
            Map<String, Edge> childrenCopy;
            synchronized (n.children) {
                childrenCopy = new LinkedHashMap<>(n.children);
            }
            for (var e : childrenCopy.entrySet()) {
                var edgeInfo = new LinkedHashMap<String, Object>();
                edgeInfo.put("originalReference", e.getValue().originalReference());
                edgeInfo.put("resolvedMechanism", e.getValue().resolvedMechanism());
                edgeInfo.put("loadCommand", e.getValue().loadCommand());
                children.put(e.getKey(), edgeInfo);
            }
            nData.put("children", children);
            nodesMap.put(n.id, nData);
        }
        return Map.of("isScanning", isScanning.get(), "rootPath", currentRootPath, "nodes", nodesMap);
    }

    static String serializeJSON(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        if (obj instanceof List<?> l) {
            var sj = new StringJoiner(",", "[", "]");
            for (var o : l) sj.add(serializeJSON(o));
            return sj.toString();
        }
        if (obj instanceof Map<?, ?> m) {
            var sj = new StringJoiner(",", "{", "}");
            for (var e : m.entrySet()) sj.add("\"" + e.getKey() + "\":" + serializeJSON(e.getValue()));
            return sj.toString();
        }
        return "\"\"";
    }

    static class JSONParser {
        private final String src;
        private int ptr = 0;
        JSONParser(String src) { this.src = src; }

        Object parse() {
            skip();
            if (ptr >= src.length()) return null;
            char c = src.charAt(ptr);
            if (c == '"') return readString();
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == 't' || c == 'f') return readBool();
            return readNum();
        }

        private void skip() {
            while (ptr < src.length() && Character.isWhitespace(src.charAt(ptr))) ptr++;
        }

        private String readString() {
            ptr++;
            var sb = new StringBuilder();
            while (ptr < src.length()) {
                char c = src.charAt(ptr++);
                if (c == '"') break;
                if (c == '\\' && ptr < src.length()) sb.append(src.charAt(ptr++));
                else sb.append(c);
            }
            return sb.toString();
        }

        private Map<String, Object> readObject() {
            var map = new LinkedHashMap<String, Object>();
            ptr++;
            while (ptr < src.length()) {
                skip();
                if (src.charAt(ptr) == '}') { ptr++; break; }
                String key = readString();
                skip();
                ptr++; // skip ':'
                map.put(key, parse());
                skip();
                if (src.charAt(ptr) == ',') ptr++;
            }
            return map;
        }

        private List<Object> readArray() {
            var list = new ArrayList<>();
            ptr++;
            while (ptr < src.length()) {
                skip();
                if (src.charAt(ptr) == ']') { ptr++; break; }
                list.add(parse());
                skip();
                if (src.charAt(ptr) == ',') ptr++;
            }
            return list;
        }

        private boolean readBool() {
            boolean b = src.startsWith("true", ptr);
            ptr += b ? 4 : 5;
            return b;
        }

        private Number readNum() {
            int start = ptr;
            while (ptr < src.length() && "-0123456789.eE".indexOf(src.charAt(ptr)) >= 0) ptr++;
            try { return Double.parseDouble(src.substring(start, ptr)); }
            catch (Exception e) { return 0; }
        }
    }
}