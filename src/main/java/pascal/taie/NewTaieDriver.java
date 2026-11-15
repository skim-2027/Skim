package pascal.taie;

import picocli.CommandLine;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command
public class NewTaieDriver {

    private static final String BENCHMARK_HOME = "java-benchmarks";

    private static final String BENCHMARK_INFO = "java-benchmarks/benchmark-info.yml";

    private static final Map<String, BenchmarkInfo> benchmarkInfos =
            BenchmarkInfo.load(BENCHMARK_INFO);

    @CommandLine.Option(names = "-cs", defaultValue = "ci")
    private String cs;

    @CommandLine.Option(names = "-java", defaultValue = "0")
    private int jdk;

    @CommandLine.Option(names = "-advanced", defaultValue = "null")
    private String advanced;

    @CommandLine.Parameters
    private List<String> benchmarks;

    public static void main(String[] args) {
        NewTaieDriver runner = CommandLine.populateCommand(new NewTaieDriver(), args);
        runner.runAll();
    }

    private void runAll() {
        if (benchmarks == null) {
            benchmarks = benchmarkInfos.keySet().stream().toList();
        }
        benchmarks.forEach(this::run);
    }

    private void run(String benchmark) {
        System.out.println("\nAnalyzing " + benchmark);
        Main.main(composeArgs(benchmark));
    }

    private String[] composeArgs(String benchmark) {
        BenchmarkInfo info = benchmarkInfos.get(benchmark);
        List<String> args = new ArrayList<>();
        int jdkVersion = jdk != 0 ? jdk : info.jdk();
        Collections.addAll(args,
                "-java", Integer.toString(jdkVersion),
                "-acp", buildClassPath(info.apps()),
                "-cp", buildClassPath(info.libs()),
                "-wc",
                "-m", info.main());
        if (info.allowPhantom()) {
            args.add("--allow-phantom");
        }
        Map<String, String> ptaArgs = Map.of(
                "distinguish-string-constants", "null",
                "merge-string-objects", "false",
                "cs", cs,
                "advanced", advanced,
                "reflection-inference", "null",
                "reflection-log", new File(BENCHMARK_HOME, info.reflectionLog()).toString());
        Collections.addAll(args,
                "-a", "pta=" + ptaArgs.entrySet()
                        .stream()
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(Collectors.joining(";")),
                "-a", "may-fail-cast",
                "-a", "poly-call");
        return args.toArray(new String[0]);
    }

    private String buildClassPath(List<String> paths) {
        return paths.stream()
                .map(this::extendCP)
                .flatMap(List::stream)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private List<String> extendCP(String path) {
        File file = new File(BENCHMARK_HOME, path);
        List<String> paths = new ArrayList<>();
        if (isJar(file)) {
            paths.add(file.toString());
        } else if (file.isDirectory()) {
            paths.add(file.toString());
            for (File item : Objects.requireNonNull(file.listFiles())) {
                if (isJar(item)) {
                    paths.add(item.toString());
                }
            }
        } else {
            throw new RuntimeException(path + " is neither a directory nor a JAR");
        }
        return paths;
    }

    private static boolean isJar(File file) {
        return file.isFile() && file.getName().endsWith(".jar");
    }
}
