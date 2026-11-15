package pascal.taie.analysis.pta.toolkit.moon.traversal;

import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.StaticFieldNode;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.toolkit.moon.support.MoonInfra;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;

import java.util.*;
import java.util.stream.Collectors;

public class TravHelper {
    private final MoonInfra moonInfra;
    private final Map<Obj, Map<Obj, Integer>> ctxObjsCache = Maps.newConcurrentMap();
    private final int maxCtxLayer;
    public TravHelper(MoonInfra moonInfra, int maxCtxLayer){
        this.moonInfra = moonInfra;
        this.maxCtxLayer = maxCtxLayer;
    }



    public boolean notInAllocatorMethod(Obj obj, VarNode var){
        JMethod inMethod = var.getVar().getMethod();

        var oag = moonInfra.getOag();


        if (!moonInfra.getMethodsInvokedOn(obj).contains(inMethod)) return false;
        Set<Obj> allocators = oag.getPredsOf(obj);
        return allocators.stream().noneMatch(o -> moonInfra.getMethodsInvokedOn(o).contains(inMethod));
    }

    public boolean checkIfThis(Var var) {
        Var aThis = var.getMethod().getIR().getThis();
        return var.equals(aThis);
    }

    public List<Set<Obj>> collectCtxObjCalledOn(JMethod crtMethod, Obj obj) {
        Map<Obj, Integer> ctxs = getCtxObjsOf(obj);
        Set<Obj> ctxObjs = ctxs.keySet();
        List<Set<Obj>> result = new ArrayList<>(this.maxCtxLayer + 1);
        for (int i = 0; i < this.maxCtxLayer + 1; i ++){
            result.add(Collections.emptySet());
        }
        int maxLayer = -1;
        for (Obj ctxObj : ctxObjs) {
            if( moonInfra.getMethodsInvokedOn(ctxObj).contains(crtMethod)){
                int layer = ctxs.get(ctxObj);
                maxLayer = Math.max(layer,maxLayer);
                if (result.get(layer).isEmpty()) {
                    result.set(layer, Sets.newHybridSet());
                }
                result.get(layer).add(ctxObj);
            }
        }
        if(maxLayer == -1) return List.of();
        return result;
    }

    public Map<Obj, Integer> getCtxObjsOf(Obj obj) {
        var oag = moonInfra.getOag();
        return ctxObjsCache.computeIfAbsent(obj, k -> {
            Map<Obj, Integer> result = Maps.newMap();
            List<Set<Obj>> layerOfCtxObjs = new ArrayList<>();
            layerOfCtxObjs.add(Sets.newSet(Set.of(obj))); // index 0 for current obj.
            layerOfCtxObjs.add(Sets.newSet(oag.getPredsOf(obj))); // index 1 for first allocators of current obj.

            for (int i = 2; i <= maxCtxLayer; i++) {
                layerOfCtxObjs.add(Sets.newSet(layerOfCtxObjs.get(i - 1).stream().map(oag::getPredsOf).flatMap(Set::stream).collect(Collectors.toSet())));
            }
            Set<Obj> alreadyIn = Sets.newSet();
            for (int i = 0; i < layerOfCtxObjs.size(); i++) {
                Set<Obj> layerCtx = layerOfCtxObjs.get(i);
                for (Obj ctx : layerCtx) {
                    if (alreadyIn.contains(ctx)) continue;
                    alreadyIn.add(ctx);
                    result.put(ctx, i);
                }
            }
            return result;
        });
    }



    public Set<Obj> reachingPTS(Node node) {
        if (node instanceof VarNode varNode) {
            return moonInfra.getPta().getPointsToSet(varNode.getVar());
        } else if (node instanceof StaticFieldNode staticFieldNode) {
            return moonInfra.getPta().getPointsToSet(staticFieldNode.getField());
        }
        throw new RuntimeException("Unsupported node type: " + node.getClass().getName());
    }
}
