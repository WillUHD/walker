// Copyright WillUHD 2025. All Rights Reserved.

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Main {

    public static Path srcPath;
    public static Queue<Path> toCopy = new LinkedList<>();
    public static Set<Path> analyzed = new HashSet<>();

    public static void main(String[] args) {
        while(true) {
            Scanner scanner = new Scanner(System.in);
            System.out.println("""
                
                `Will'     A     `UHD'       ,cOP  ,cWa                      \s
                  UHD     ,vA     ,V           YR    LK                      \s
                   WA:   ,JVM:   ,V  ,6"Yb.    IG    v2  ,MP' .gP"Ya `7walker\s
                    LK.  M' WA.  M '8)   MM    HT    WA ;Y   ,M'   Yb  WA' "'\s
                    `ER A'  `LK A'   walker    WI    LK;v2   20""\"""\"  LK    \s
                     JAVA    :ER;   IS   BY    LL    ER `Mb. 25.    ,  v2    \s
                      21      VF    `willuhd`.wUHD..wALk. v2. `Walker.WILL.  \s
                
                    ---------============== walker ==============---------
                                           version 2
                                    copyright WillUHD 2025
                                       --help if needed
                """);

            Path depPath;
            boolean verbose;
            while (true) {
                System.out.println("Enter the path of the dependency: ");

                // whitespace removal is needed because of flags
                var s = scanner.nextLine().trim();

                // could use a switch statement with flags but there are really only 2 here
                if(s.startsWith("--help") || s.startsWith("-h")){
                    System.out.println("""
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
                    System.out.println("Path verification failed. --help for help. ");
                    continue;
                } break; // break the loop only if these conditions are met
            }

            var startTime = System.nanoTime();

            // makes the proper name for naming the folder (can't contain ".")
            srcPath = depPath.getParent().resolve(
                    depPath.getFileName().toString()
                            .replace(".dylib", "")
                            .replace(".", ""));
            System.out.println("\nSource dependency detected as: " + srcPath);

            try {Files.createDirectories(srcPath);}
            catch (IOException e) {
                System.err.println("""
                        Can't create the target folder.
                        Make sure filesystem is Read + Write.
                        Auto-exiting. Peace""");
                System.exit(1);
            }

            System.out.println("Target directory created. Starting to patch...");

            toCopy.add(depPath);
            int count = 0;

            if(verbose){
                while (!toCopy.isEmpty()) {
                    var current = toCopy.poll();
                    Walker.otool(current.toString());
                    count++;
                }

                System.out.println("Patching completed. Reverifying: ");
                try (var stream = Files.list(srcPath)) {
                    stream.forEach(bin -> System.out.println(send("otool -L \"" + bin.toString() + "\"")));
                } catch (IOException e) {System.err.println(Arrays.toString(e.getStackTrace()));}
            } else {
                while (!toCopy.isEmpty()) {
                    var current = toCopy.poll();
                    SilentWalker.otool(current.toString());
                    count++;
                }
                System.out.println("Patching completed.");
            }

            var endTime = System.nanoTime();
            var time = (endTime - startTime) / 1000000000f;

            System.out.println("\nProcess Completed. Walker: \n" +
                    "  - finished in " + time + " seconds\n" +
                    "  - analyzed " + count + " dependencies\n" +
                    "  - performance is " + (count / time) + " dependencies per second\n" +
                    "  - saved in source " + srcPath + "\n" +
                    "\nMain binary is " + depPath.getFileName());

            toCopy = new LinkedList<>();
            analyzed = new HashSet<>();
        }
    }

    public static String send(String line) {
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
}
