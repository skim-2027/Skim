package pascal.taie.analysis.pta.toolkit.moon.support;



import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.CallDetails;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.graph.Graph;

import java.util.Set;
import java.util.stream.Collectors;

public class MultiCtxFilter {

    private final int maxMatchLayer;
    private final Graph<Obj> objectAllocationGraphWithArr;
    private final Set<Obj> objsWithoutEnoughCtx = Sets.newSet();
    private final MoonInfra moonInfra;
    public MultiCtxFilter(int maxMatchLayer, MoonInfra moonInfra) {
        this.maxMatchLayer = maxMatchLayer;
        this.objectAllocationGraphWithArr = moonInfra.getOag();
        this.moonInfra = moonInfra;
    }
    public boolean filterBeforeMatch(Obj obj) {
        Set<Obj> crtObjs = Sets.newSet();
        crtObjs.add(obj);
        for (int i = 0; i < this.maxMatchLayer; i++) {
            Set<Obj> preds = crtObjs.stream().map(objectAllocationGraphWithArr::getPredsOf).flatMap(Set::stream).collect(Collectors.toSet());
            int size = preds.size();
            if (crtObjs.stream().anyMatch(moonInfra::hasEmptyCtx)) size ++;
            if(size > 1) return true;
            crtObjs = preds;
        }
        objsWithoutEnoughCtx.add(obj);
        return false;
    }


}
