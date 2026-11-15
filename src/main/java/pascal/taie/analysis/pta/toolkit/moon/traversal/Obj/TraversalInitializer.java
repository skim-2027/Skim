package pascal.taie.analysis.pta.toolkit.moon.traversal.Obj;

import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.FieldRecorder;
import pascal.taie.analysis.pta.toolkit.moon.support.MoonInfra;
import pascal.taie.analysis.pta.toolkit.moon.support.MultiCtxFilter;
import pascal.taie.ir.exp.Var;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class TraversalInitializer {

    private final MoonInfra moonInfra;
    private final int objCtxLen;

    public TraversalInitializer(MoonInfra moonInfra, int objCtxLen){
        this.moonInfra = moonInfra;
        this.objCtxLen = objCtxLen;
    }
    public MultiMap<Obj, VarNode> initializeObjToVarMap() {
        PointerAnalysisResult pta = moonInfra.getPta();
        Set<VarNode> fieldBaseVars = pta.getExtraInfoTracker().getFieldBaseVars();

        MultiMap<Obj, VarNode> containerObjToBaseVar = Maps.newConcurrentMultiMap();
        MultiCtxFilter objFilter = new MultiCtxFilter(this.objCtxLen, moonInfra);

        fieldBaseVars.parallelStream().forEach(varNode -> {
            Var var = varNode.getVar();
            Set<Obj> pts = pta.getPointsToSet(var);
            for (Obj o : pts) {
                if (!moonInfra.isConcernedContainerObj(o)) continue;
                if (!objFilter.filterBeforeMatch(o)) continue;
                if (o instanceof NewObj && o.getContainerMethod().isPresent()){
                    containerObjToBaseVar.put(o, varNode);
                }
            }
        });

        return containerObjToBaseVar;
    }
}
