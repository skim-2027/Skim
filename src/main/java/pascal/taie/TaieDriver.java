package pascal.taie;

import java.io.File;
import java.util.Arrays;

public class TaieDriver {


    public static void main0(String[] args) {
//        implicit-entries

        String cmd = "-wc -java 6 " + "-cp exploration/hashmap/Main.jar -m Main" +
                " -a poly-call -a may-fail-cast" +
                " -a pta=implicit-entries:false;" +
                "cs:" + "ci" + ";";

        System.out.println(cmd);
        pascal.taie.Main.main(cmd.split(" "));

    }
    public static void main(String[] args) {
//        runOptionalPta("bloat", "2-obj", "call-vs-obj");
//        runOptionalPta("antlr", "3-obj", "zipper-e");
        if(args.length == 0){
//            runOptionalPta("bloat", "1-obj", "sun");
//            runOptionalPta("bloat", "2-obj", null);
//                        runOptionalPta("bloat", "1-obj", "sun");
//            runOptionalPta("cha", "2-obj", null);
//            runOptionalPta("bloat", "1-obj", "sunnew");
//            runOptionalPta("bloat", "2-obj", "sunAttempt");
//            runOptionalPta("antlr", "1-obj", null);
//            runOptionalPta("chart", "1-obj", "sun");
//            runOptionalPta("antlr", "1-obj", "sun");
//            runOptionalPta("entry:transfer", "1-obj", "sun");
//            runOptionalPta("entry:transfer", "1-obj", null);
//            runOptionalPta("entry:WrapperPattern", "1-obj", "sun");
//            runOptionalPta("entry:WrapperPattern", "2-obj", null);
//            runOptionalPta("entry:WrapperPatternWithStatic", "1-obj", "sun");
//            runOptionalPta("entry:TransferWithWrapperPattern", "1-obj", "sun");
//            runOptionalPta("entry:TransferWithWrapperPattern", "1-obj", null);
//            runOptionalPta("entry:TwoLayersOfWrapperPatterns", "1-obj", "sun"); // 这个暂时还没有解决。
//              runOptionalPta("entry:TwoAllocLayersOfWrapperPatterns", "1-obj", "sun"); // 已解决，代码之前有bug。
//            runOptionalPta("entry:TwoAllocLayersOfWrapperPatterns", "2-obj", null);
        }else{
            if(args.length == 2){
                runOptionalPta(args[0], args[1], null);
            }else if(args.length == 3){
                runOptionalPta(args[0], args[1], args[2]);
            }
        }

    }
    public static void main1(String[] args) {
        if(args.length != 2){
            System.out.println(Arrays.toString(args));
            System.out.println("Usage: java -cp <taie-jar-with-dependencies.jar> pascal.taie.TaieDriver <app> <sensitivity>");
            System.out.println("Example: java -cp <taie-jar-with-dependencies.jar> pascal.taie.TaieDriver antlr 2-obj");
            System.exit(1);
        }
        String app = args[0];
        String sens = args[1];
        runOptionalPta(app, sens, null);
    }

    public static void runOptionalPta(String app, String sens, String advanced) {

        if(app.startsWith("entry")){
            String dir = app.split(":")[1];
            String cmd = "-wc -java 6 " + "-cp explorationPLDI27/" + dir + "/Main.jar -m Main" +
                    " -a poly-call -a may-fail-cast" +
                    " -a pta=implicit-entries:false;" + (advanced == null ? "" : "advanced:" + advanced + ";")
                    + "cs:" + sens + ";";

            System.out.println(cmd);
            pascal.taie.Main.main(cmd.split(" "));
        }else{
            String cmd = "-wc --allow-phantom -java 6 " + getCpAndMain(app) +
                    " -a poly-call -a may-fail-cast" +
                    " -a pta=" + (advanced == null ? "" : "advanced:" + advanced + ";")
                    + "cs:" + sens + ";"
                    + getReflectionlog(app);

            System.out.println(cmd);
            pascal.taie.Main.main(cmd.split(" "));
        }

    }
    private static String getReflectionlog(String app){
        File file = new File("java-benchmarks/dacapo-2006/" + app + "-refl.log");
        if(file.exists()){
            return "reflection-log:java-benchmarks/dacapo-2006/" + app + "-refl.log;";
        }else{
            throw new RuntimeException("Reflection log not found for app:" + app);
        }
//        return "";
    }

    private static String getCpAndMain(String app) {
        File file = new File("java-benchmarks/dacapo-2006/" + app + ".jar");
        if (file.exists()) {
            return "-cp java-benchmarks/dacapo-2006/" + app + ".jar:java-benchmarks/dacapo-2006/" + app
                    + "-deps.jar -m Harness";//"dacapo." + app + ".Main";
        }
        if (app.equals("checkstyle")) {
            String cp = "datasets/benchmarks/applications/checkstyle/checkstyle-5.7-all.jar";
            return "-cp " + cp + " -m com.puppycrawl.tools.checkstyle.Main";
        } else if (app.equals("jpc")) {
            String cp = "datasets/benchmarks/applications/JPC/JPCApplication.jar";
            return "-cp " + cp + " -m org.jpc.j2se.JPCApplication";
        } else if(app.equals("findbugs")){
            if(true) throw new RuntimeException("not implemented.");
            String cp = "datasets/applications/findbugs/findbugs.jar:";
            return "-cp " + cp + " -m edu.umd.cs.findbugs.FindBugs";
        }
        throw new RuntimeException("APP not found:" + app);

    }



}
