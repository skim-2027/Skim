package pascal.taie.analysis.pta.toolkit.tunneling;

import pascal.taie.language.classes.JMethod;

import java.util.Map;

public class TunnelingModel {

    private static final int[][] CHILD_CONDITIONS = {
            {20, 28, 37, 26, 11, 17, 0, 5},
            {22, 28, 25, 26, 0, 37, 39, 41, 7, 15, 13, 8, 34, 2, 18, 42, 44, 32, 30, 21, 16, 4, 10}
    };

    private static final int[][] PARENT_CONDITIONS = {
            {30, 28, 26, 37, 41, 25, 8, 7, 15, 11, 33, 17, 3, 13, 23, 5, 21, 42, 38, 45, 18, 35},
            {6, 41, 23, 28, 25, 37, 31, 17, 3, 33, 11, 26, 19, 9, 21, 5, 0, 13, 35, 45, 38},
            {22, 7, 28, 25, 26, 41, 11, 0, 37, 21, 33, 5, 17, 3, 12, 8, 35, 45, 18, 42, 38, 30, 14},
            {40, 28, 0, 7, 25, 19, 11, 31, 2, 33, 26, 13, 21, 9, 23, 5, 44, 35, 14}
    };

    private final Map<JMethod, Integer> methodFeatures =
            ObjectTunnelingFeatureExtractor.extract();

    public boolean isParentMethod(JMethod method) {
        return matches(PARENT_CONDITIONS,
                methodFeatures.getOrDefault(method, 0));
    }

    public boolean isChildMethod(JMethod method) {
        return matches(CHILD_CONDITIONS,
                methodFeatures.getOrDefault(method, 0));
    }

    private static boolean matches(int[][] formula, int methodFeatures) {
        for (int[] clause : formula) {
            boolean matches = true;
            for (int literal : clause) {
                int feature = 1 << (literal / 2);
                boolean present = (methodFeatures & feature) != 0;
                if (present == (literal % 2 != 0)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
