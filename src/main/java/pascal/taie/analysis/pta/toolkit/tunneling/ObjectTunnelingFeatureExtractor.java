package pascal.taie.analysis.pta.toolkit.tunneling;

import pascal.taie.World;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ClassLiteral;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.NewMultiArray;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.stmt.AssignLiteral;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.PhiStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ReferenceType;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A1;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A10;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A2;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A3;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A4;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A5;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A6;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A7;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A8;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.A9;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B1;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B10;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B11;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B12;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B13;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B2;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B3;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B4;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B5;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B6;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B7;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B8;
import static pascal.taie.analysis.pta.toolkit.tunneling.ObjectTunnelingFeature.B9;

/**
 * Computes the 23 method properties used by the OOPSLA'18 artifact.
 */
public class ObjectTunnelingFeatureExtractor {

    private ObjectTunnelingFeatureExtractor() {
    }

    public static Map<JMethod, Integer> extract() {
        List<JMethod> methods = World.get().getClassHierarchy()
                .allClasses()
                .flatMap(clazz -> clazz.getDeclaredMethods().stream())
                .toList();
        Set<JMethod> staticInvocationTargets = Sets.newSet();
        Map<JMethod, Integer> result = Maps.newMap(methods.size());
        for (JMethod method : methods) {
            result.put(method,
                    computeLocalFeatures(method, staticInvocationTargets));
        }
        for (JMethod method : staticInvocationTargets) {
            result.computeIfPresent(method,
                    (ignored, features) -> features | B9.mask());
        }
        return result;
    }

    private static int computeLocalFeatures(
            JMethod method, Set<JMethod> staticInvocationTargets) {
        String signature = method.getSignature();
        int features = 0;
        features = set(features, A1, signature.contains("java"));
        features = set(features, A2, signature.contains("lang"));
        features = set(features, A3, signature.contains("sun"));
        features = set(features, A4, signature.contains("()"));
        features = set(features, A5, signature.contains("void"));
        features = set(features, A6, signature.contains("security"));
        features = set(features, A7, signature.contains("int"));
        features = set(features, A8, signature.contains("util"));
        features = set(features, A9, signature.contains("String"));
        features = set(features, A10, signature.contains("init"));
        features = set(features, B1, isInNestedClass(signature));
        features = set(features, B2, method.getParamCount() > 1);
        features = set(features, B11,
                extractParameters(signature).contains("Object"));
        features = set(features, B13,
                method.getDeclaringClass().getDeclaredMethods().size() > 20);

        boolean hasReferenceParam = method.getParamTypes().stream()
                .anyMatch(ReferenceType.class::isInstance);
        boolean hasReferenceVar = !method.isStatic() || hasReferenceParam ||
                method.isNative() &&
                        method.getReturnType() instanceof ReferenceType;
        boolean hasAssignLocal = !method.isAbstract() && !method.isNative() &&
                (!method.isStatic() || hasReferenceParam);
        boolean hasLoadArray = false;
        boolean hasStoreInstanceField = false;
        boolean hasStaticInvocation = false;
        boolean hasVirtualInvocation = false;
        int heapCount = 0;

        if (!method.isAbstract() && !method.isNative() &&
                !method.getDeclaringClass().isPhantom()) {
            IR ir = method.getIR();
            hasReferenceVar |= ir.getVars().stream()
                    .anyMatch(var -> var.getType() instanceof ReferenceType);
            for (Stmt stmt : ir) {
                hasLoadArray |= stmt instanceof LoadArray load &&
                        load.getLValue().getType() instanceof ReferenceType;
                hasAssignLocal |= isReferenceAssignment(stmt);
                hasStoreInstanceField |= stmt instanceof StoreField store &&
                        store.getFieldAccess() instanceof InstanceFieldAccess &&
                        store.getRValue().getType() instanceof ReferenceType;
                if (stmt instanceof Invoke invoke) {
                    hasStaticInvocation |= invoke.isStatic();
                    hasVirtualInvocation |= invoke.isVirtual() ||
                            invoke.isInterface();
                    if (invoke.isStatic()) {
                        JMethod target = invoke.getMethodRef().resolveNullable();
                        if (target != null) {
                            staticInvocationTargets.add(target);
                        }
                    }
                }
                heapCount += heapAllocationCount(stmt);
            }
        }

        features = set(features, B3, hasLoadArray);
        features = set(features, B4, hasAssignLocal);
        features = set(features, B5, hasReferenceVar);
        features = set(features, B6, hasStoreInstanceField);
        features = set(features, B7, hasStaticInvocation);
        features = set(features, B8, hasVirtualInvocation);
        features = set(features, B10, heapCount == 1);
        return set(features, B12, heapCount > 0);
    }

    private static boolean isReferenceAssignment(Stmt stmt) {
        if (stmt instanceof Copy copy) {
            return copy.getLValue().getType() instanceof ReferenceType;
        }
        if (stmt instanceof PhiStmt phi) {
            return phi.getLValue().getType() instanceof ReferenceType;
        }
        return false;
    }

    private static int heapAllocationCount(Stmt stmt) {
        if (stmt instanceof New allocation) {
            return allocation.getRValue() instanceof NewMultiArray multiArray
                    ? multiArray.getType().dimensions()
                    : 1;
        }
        if (stmt instanceof AssignLiteral assign) {
            return assign.getRValue() instanceof StringLiteral || assign.getRValue() instanceof ClassLiteral
                    ? 1
                    : 0;
        }
        return 0;
    }

    private static boolean isInNestedClass(String signature) {
        int colon = signature.indexOf(':');
        return colon > 0 && signature.lastIndexOf('$', colon) >= 0;
    }

    private static String extractParameters(String signature) {
        int start = signature.indexOf('(');
        int end = signature.indexOf(')', start);
        return start >= 0 && end > start
                ? signature.substring(start + 1, end)
                : "";
    }

    private static int set(
            int features, ObjectTunnelingFeature feature, boolean present) {
        return present ? features | feature.mask() : features;
    }
}
