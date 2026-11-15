package pascal.taie.analysis.pta.toolkit.moon.objCollection;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.toolkit.moon.Moon;
import pascal.taie.analysis.pta.toolkit.moon.support.MoonInfra;
import pascal.taie.analysis.pta.toolkit.moon.traversal.Obj.STraversalResult;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabelEdge;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabeledGraph;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.*;

public class ObjCollector {
    public static final Logger logger = LoggerFactory.getLogger(ObjCollector.class);
    private final int maxCtxLayer;
    private final MoonInfra moonData;
    private final PartialChecker partialChecker;

    public static final Object placeCtxObj = new Object();
    public ObjCollector(int maxCtxLayer, MoonInfra moonData) {
        this.maxCtxLayer = maxCtxLayer;
        this.moonData = moonData;
        this.partialChecker = new PartialChecker(moonData);
    }

    public record ObjCollectionDetails(Set<Obj> prObjs, Set<Obj> basePRObjs,
                                       Set<Obj> recurPRObjs,
                                       List<MultiMap<Obj, STraversalResult>> filteredTravResults){}
    public ObjCollectionDetails analyze(List<MultiMap<Obj, STraversalResult>> traversalResults) {
        List<MultiMap<Obj, STraversalResult>> filteredTravResults = new ArrayList<>();
        for (int i = 0; i < traversalResults.size(); i++) {
            filteredTravResults.add(Maps.newMultiMap());
        }


        Set<Obj> basePRObjs = Sets.newSet();

        Set<Obj> potentialPRObjs = Sets.newSet();

        collectbasePRObjs(traversalResults, basePRObjs, potentialPRObjs, filteredTravResults);
        var prObjDepGraph = buildPRObjDepGraph(filteredTravResults, potentialPRObjs);
        var recurPRObjs = collectRecurPRObjs(filteredTravResults, basePRObjs, prObjDepGraph);


        Set<Obj> prObjs = Sets.newSet();
        prObjs.addAll(basePRObjs);
        prObjs.addAll(recurPRObjs);
        logger.info("[ObjCollector] Collected {} PR objects.", prObjs.size());
        logger.info("[ObjCollector] Base PR objects: {}.", basePRObjs.size());
        logger.info("[ObjCollector] Recursive PR objects: {}.", recurPRObjs.size());
        return new ObjCollectionDetails(prObjs, basePRObjs, recurPRObjs, filteredTravResults);

    }



        private Set<Obj> collectRecurPRObjs(List<MultiMap<Obj, STraversalResult>> traversalResults,
                                                  Set<Obj> basePRObjs,
                                                  LabeledGraph<Obj, AbstractField> prObjDepGraph) {
        if(Moon.enableRecursivePRObjs){
            Set<Obj> recurPRObjs = Sets.newSet();

            Set<Obj> wrapperObjs = collectWrapperContainerPRObjs(prObjDepGraph, basePRObjs);
            recurPRObjs.addAll(wrapperObjs);
            if(maxCtxLayer >= 2){
                var allocatorObjs = collectAllocatorContainersForHigherObj(basePRObjs, recurPRObjs, traversalResults);
                var wrapperOfAllocObjs = collectWrapperContainerPRObjs(prObjDepGraph, allocatorObjs);
                recurPRObjs.addAll(allocatorObjs);
                recurPRObjs.addAll(wrapperOfAllocObjs);
            }
            return recurPRObjs;
        }else{
            return Sets.newSet();
        }
    }

    private Set<Obj> collectAllocatorContainersForHigherObj(Set<Obj> basePRObjs, Set<Obj> recurPRObjs,
                                                            List<MultiMap<Obj, STraversalResult>> traversalResults) {
        Set<Obj> allocPRObjs = Sets.newSet();
        for (Obj basePRObj : basePRObjs) {
            for (MultiMap<Obj, STraversalResult> traversalResult : traversalResults) {
                for (STraversalResult resultOfVarTrace : traversalResult.get(basePRObj)) {
                    Set<Obj> firstLayerCtxObjs = Sets.newSet(resultOfVarTrace.getMatchedCtxObjsOfParam(1));
                    Set<Obj> secondLayerCtxObjs = Sets.newSet(resultOfVarTrace.getMatchedCtxObjsOfParam(2));
                    if (firstLayerCtxObjs.isEmpty() || secondLayerCtxObjs.isEmpty()) {
                        continue;
                    }
                    for (Obj firstLayerCtxObj : firstLayerCtxObjs) {
                        Set<Obj> allocatorsOfFirstLayerCtxObjs = moonData.getOag().getPredsOf(firstLayerCtxObj);
                        boolean l1StaticallyAllocated = firstLayerCtxObj.getContainerMethod().isPresent() && firstLayerCtxObj.getContainerMethod().get().isStatic();
                        boolean encapsulatedPattern = !Collections.disjoint(allocatorsOfFirstLayerCtxObjs, secondLayerCtxObjs);
                        if (l1StaticallyAllocated || encapsulatedPattern) {
                            if(partialChecker.check(firstLayerCtxObj, true)){
                                allocPRObjs.add(firstLayerCtxObj);
                            }

                            if (maxCtxLayer > 2) {
                                for (Obj secondLayerCtxObj : secondLayerCtxObjs) {
                                    if (allocPRObjs.contains(secondLayerCtxObj)) {
                                        continue;
                                    }
                                    if (partialChecker.check(secondLayerCtxObj, true)) {
                                        allocPRObjs.add(secondLayerCtxObj);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return allocPRObjs;
    }

    private Set<Obj> collectWrapperContainerPRObjs(LabeledGraph<Obj, AbstractField> containerGraph,
                                                   Set<Obj> basePRObjs) {
        Set<Obj> wrapperPRObjs = Sets.newSet();
        Set<Obj> nodes = Sets.newSet(containerGraph.getNodes());
        nodes.retainAll(basePRObjs);
        Queue<Obj> queue = new ArrayDeque<>(nodes);
        int size = queue.size();
        Queue<Integer> depthQueue = new ArrayDeque<>(Collections.nCopies(size, 0));
        Set<Obj> visited = Sets.newSet();
        while (!queue.isEmpty()) {
            Obj current = queue.remove();
            int depth = depthQueue.element();
            if (!visited.contains(current)) {
                visited.add(current);
                if (depth < maxCtxLayer) {
                    // Push unvisited successors onto the queue
                    for (LabelEdge<Obj, AbstractField> outEdge : containerGraph.getOutEdgesOf(current)) {
                        Obj neighbor = outEdge.target();
                        if (!visited.contains(neighbor) && !basePRObjs.contains(neighbor) && !wrapperPRObjs.contains(neighbor)) {
                            boolean added;
                            if(current.getContainerMethod().get().isStatic()){
                                added = moonData.hasEmptyCtx(current);
                            }else{
                                added = true;
                            }
                            if(added){
                                queue.add(neighbor);
                                depthQueue.add(depth + 1);
                                wrapperPRObjs.add(neighbor);
                            }

                        }
                    }
                }
            }
        }
        return wrapperPRObjs;
    }

    private LabeledGraph<Obj, AbstractField> buildPRObjDepGraph(List<MultiMap<Obj, STraversalResult>> traversalResults, Set<Obj> potentialPRObjs) {
        var oag = moonData.getOag();
        LabeledGraph<Obj, AbstractField> objDepGraph = new LabeledGraph<>();
        for (Obj nonInnerContainerObj : potentialPRObjs) {

            for (MultiMap<Obj, STraversalResult> traversalResult : traversalResults) {
                for (STraversalResult resultOfVarTrace : traversalResult.get(nonInnerContainerObj)) {
                    for (int layer = 0; layer < resultOfVarTrace.getRecordSize(); layer++) {
                        if (layer > maxCtxLayer) break;
                        Set<Obj> newlyAllocObjs = resultOfVarTrace.getNewlyAllocObjs(layer);
                        for (Obj newlyAllocObj : newlyAllocObjs) {
                            if (newlyAllocObj.equals(nonInnerContainerObj)) continue;
                            Set<Obj> allocatorOfNewlyAlloc = oag.getPredsOf(newlyAllocObj);
                            Set<Obj> allocatorOfNonInner = oag.getPredsOf(nonInnerContainerObj);
                            if (!Collections.disjoint(allocatorOfNonInner, allocatorOfNewlyAlloc)
                                    &&
                                    !allocatorOfNewlyAlloc.contains(nonInnerContainerObj)
                            ) {
                                objDepGraph.addEdge(newlyAllocObj, nonInnerContainerObj, resultOfVarTrace.getField());
                            }
                        }
                    }
                }
            }
        }
        return LabeledGraph.unmodifiableGraph(objDepGraph);
    }

    private void collectbasePRObjs(List<MultiMap<Obj, STraversalResult>> traversalResults, Set<Obj> basePRObjs,
                                   Set<Obj> potentialPRObjs,
                                    List<MultiMap<Obj, STraversalResult>> filteredTravRetsPerLayer) {
        for (int idx = 0; idx < traversalResults.size(); idx++) {
            var filteredTravRets = filteredTravRetsPerLayer.get(idx);
            int checkLayer = idx + 1;

            var travResultLayer = traversalResults.get(idx);
            for (Obj obj : travResultLayer.keySet()) {
                if (!moonData.isConcernedContainerObj(obj)) {
                    continue;
                }
                boolean metParamWhenAllocated = travResultLayer.get(obj).stream().anyMatch(STraversalResult::isMetParamOfAllocatedMethod);
                if (!partialChecker.check(obj, metParamWhenAllocated)) continue;
                for (STraversalResult STraversalResult : travResultLayer.get(obj)) {

                    var ctxObjs = STraversalResult.ctxObjsOnLayerOf(checkLayer);
                    if (!ctxObjs.isEmpty()) {
                        filteredTravRets.put(obj, STraversalResult);
                        basePRObjs.add(obj);
                    } else if (Moon.enableRecursivePRObjs && STraversalResult.hasNewlyAllocObjs()) {
                        potentialPRObjs.add(obj);
                        filteredTravRets.put(obj, STraversalResult);
                    }
                }
            }
        }

    }
}
