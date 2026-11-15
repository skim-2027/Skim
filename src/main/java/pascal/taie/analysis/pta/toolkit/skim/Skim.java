package pascal.taie.analysis.pta.toolkit.skim;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.event.Level;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ArbitraryField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraEdgeLabel;
import pascal.taie.analysis.pta.toolkit.skim.ctxselect.AdaptiveSelector;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabelEdge;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabeledGraph;
import pascal.taie.analysis.pta.toolkit.skim.policy.AdaptivePolicy;
import pascal.taie.analysis.pta.toolkit.skim.policy.SelectPolicy;
import pascal.taie.analysis.pta.toolkit.skim.preanalysis.SkimInfra;
import pascal.taie.analysis.pta.toolkit.skim.traverse.BackTravResult;
import pascal.taie.analysis.pta.toolkit.skim.traverse.Traversal;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JField;
import pascal.taie.language.type.ArrayType;
import pascal.taie.util.Monitor;
import pascal.taie.util.collection.*;
import pascal.taie.util.graph.SimpleGraph;

import java.util.*;

public class Skim {
    private static final Logger logger = LogManager.getLogger(Skim.class);
    private final PointerAnalysisResult pta;
    private final int maxCtxLen;
    private final int maxRounds;

    public static SelectPolicy run(PointerAnalysisResult pta, int maxCtxLen) {
        return run(pta, maxCtxLen, 2);
    }

    public static SelectPolicy run(PointerAnalysisResult pta, int maxCtxLen, int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("skim-max-rounds must be at least 1");
        }
        return new Skim(pta, maxCtxLen, maxRounds).analysis();
    }

    private Skim(PointerAnalysisResult pta, int maxCtxLen, int maxRounds) {
        this.pta = pta;
        this.maxCtxLen = maxCtxLen;
        this.maxRounds = maxRounds;
    }

    public SelectPolicy analysis() {
        SkimInfra infra = Monitor.runAndCount(() -> new SkimInfra(pta, maxCtxLen), "SkimInfra Construction", Level.INFO);

        MultiMap<Obj, VarNode> baseObjToVars = Maps.newConcurrentMultiMap();
        infra.getPta().getExtraInfoTracker().getFieldBaseVars().parallelStream()
                .forEach(baseV ->
                        infra.getPointsToSet(baseV).forEach(o -> {
                            if ((o instanceof NewObj || o instanceof MockObj) && o.getContainerMethod().isPresent())
                            {
                                if (!infra.isPartialAndHaveConcernedField(o)) {
                                    return;
                                }
                                baseObjToVars.put(o, baseV);
                            }
                        }));

        Traversal traversal = new Traversal(infra, maxRounds);

        TwoKeyMultiMap<Obj, AbstractField, BackTravResult> backTravResults = Monitor.runAndCount(() ->
                        traversal.incrementalBackTrav(baseObjToVars),
                "Traversal",
                Level.INFO);

        SimpleGraph<Pair<Obj, AbstractField>> connectedObjToFields = new SimpleGraph<>();
        MultiMap<Obj, BackTravResult> objAsAllocatorToTravResults = Maps.newMultiMap();
        TwoKeyMap<Obj, AbstractField, BackTravResult> mergedTravResults = Maps.newTwoKeyMap();

        mergeTravResults(backTravResults, connectedObjToFields, objAsAllocatorToTravResults, mergedTravResults);

        var globalOAG = collectPureGlobalOAG(connectedObjToFields, mergedTravResults,
                objAsAllocatorToTravResults, infra);

        AdaptiveSelector.AdaptiveCtxSelectResult result = new AdaptiveSelector(infra, globalOAG, this.maxCtxLen).select(backTravResults);

        MultiMap<Obj, Context> objContextMultiMap = result.objToCtxs();
        MultiMap<Obj, JField> objJFieldMultiMap = result.concernedInsFs();
        logger.info("Size of Objs with contexts: {}", objContextMultiMap.keySet().size());
        logger.info("Size of Objs with concerned fields: {}", objJFieldMultiMap.keySet().size());

        return new AdaptivePolicy(result);
    }

    private void mergeTravResults(TwoKeyMultiMap<Obj, AbstractField, BackTravResult> backTravResults,
                                  SimpleGraph<Pair<Obj, AbstractField>> connectedObjToFields,
                                  MultiMap<Obj, BackTravResult> objAsAllocatorToTravResults,
                                  TwoKeyMap<Obj, AbstractField, BackTravResult> mergedTravResults) {
        for (Obj obj : backTravResults.keySet()) {
            MultiMap<AbstractField, BackTravResult> fldToTravRets = backTravResults.get(obj);
            for (AbstractField absF : fldToTravRets.keySet()) {
                BackTravResult mergedAllF = null;
                for (BackTravResult travRet : fldToTravRets.get(absF)) {
                    collectConnectedObjField(connectedObjToFields, travRet, obj, absF, backTravResults);

                    if (mergedAllF == null) mergedAllF = new BackTravResult(travRet);
                    else {
                        mergedAllF.mergeAllocatorsFromSameObj(travRet);
                    }
                }
                if (mergedAllF != null) {
                    mergedTravResults.put(obj, absF, mergedAllF);
                    if (mergedAllF.getLocalOAG() != null) {
                        for (Obj node : mergedAllF.getLocalOAG().getNodes()) {
                            if (obj.equals(node)) continue;
                            objAsAllocatorToTravResults.put(node, mergedAllF);
                        }
                    }
                }
            }
        }
    }

    private LabeledGraph<Obj, BackTravResult.Relation> collectPureGlobalOAG(SimpleGraph<Pair<Obj, AbstractField>> connectedObjToFields,
                                                                            TwoKeyMap<Obj, AbstractField, BackTravResult> mergedTravResults,
                                                                            MultiMap<Obj, BackTravResult> objAsAllocatorToTravResults,
                                                                            SkimInfra infra) {
        var oag = infra.getOag();
        LabeledGraph<Obj, BackTravResult.Relation> globalOAG = new LabeledGraph<>();
        mergedTravResults.forEach((obj, absF, backTravResult) -> {
            var localOAG = backTravResult.getLocalOAG();
            if (localOAG != null) {
                for (LabelEdge<Obj, BackTravResult.Relation> edge : localOAG.allEdges()) {
                    globalOAG.addEdge(edge);
                }
            }
        });

        if (this.maxRounds == 1) return globalOAG;

        MultiMap<Obj, Pair<Obj, AbstractField>> objToConnectedNodes = Maps.newMultiMap();
        connectedObjToFields.getNodes().forEach(p -> objToConnectedNodes.put(p.first(), p));
        Set<Pair<Obj, AbstractField>> workList = Sets.newHybridSet(connectedObjToFields.getNodes());
        while (!workList.isEmpty()) {
            var it = workList.iterator();
            Pair<Obj, AbstractField> currentPair = it.next();
            Obj propagatingObj = currentPair.first();
            AbstractField absF = currentPair.second();
            it.remove();
            List<BackTravResult> concernedPropagatingTravResults = new ArrayList<>();
            if (absF instanceof ArbitraryField) {
                var fieldToTravResults = mergedTravResults.get(propagatingObj);
                if (fieldToTravResults != null)
                    concernedPropagatingTravResults.addAll(fieldToTravResults.values());

                concernedPropagatingTravResults.addAll(objAsAllocatorToTravResults.get(propagatingObj));
            } else {
                BackTravResult travResultPerField = mergedTravResults.get(propagatingObj, absF);
                if (travResultPerField != null) {
                    concernedPropagatingTravResults.add(travResultPerField);
                }
            }
            if (concernedPropagatingTravResults.isEmpty()) continue;

            LabeledGraph<Obj, BackTravResult.Relation> allocatorGraphFromPropagatingObj = new LabeledGraph<>();
            for (BackTravResult travRet : concernedPropagatingTravResults) {
                var localOAG = travRet.getLocalOAG();
                if (localOAG == null) continue;

                Deque<Obj> wl = new ArrayDeque<>();
                Set<Obj> visited = new HashSet<>();

                wl.add(propagatingObj);

                while (!wl.isEmpty()) {
                    Obj target = wl.poll();
                    if (!visited.add(target)) continue;

                    for (LabelEdge<Obj, BackTravResult.Relation> edge : localOAG.getInEdgesOf(target)) {
                        Obj source = edge.source();
                        allocatorGraphFromPropagatingObj.addEdge(
                                source, target, edge.label());
                        if (!visited.contains(source)) {
                            wl.add(source);
                        }
                    }
                }
            }

            for (Pair<Obj, AbstractField> succPair : connectedObjToFields.getSuccsOf(currentPair)) {
                Obj propagatedObj = succPair.first();
                AbstractField propagatedField = succPair.second();
                if (propagatedObj.equals(currentPair.first())) continue;
                BackTravResult propagatedResult = mergedTravResults.get(propagatedObj, propagatedField);
                if (propagatedResult == null)
                    throw new RuntimeException("propagatedResult should not be null.");
                Optional<TwoKeyMultiMap<Integer, Obj, Obj>> opNewObjAndSharedAllocators = propagatedResult.getNewObjAndSharedAllocators();
                if (opNewObjAndSharedAllocators.isEmpty())
                    throw new RuntimeException("opNewObjAndSharedAllocators should not be empty.");

                var newObjAndSharedAllocators = opNewObjAndSharedAllocators.get();
                TwoKeyMultiMap<Integer, Obj, Var> allocatorsByParam = propagatedResult.getAllocatorsByParam();
                for (Pair<Integer, Obj> integerObjPair : newObjAndSharedAllocators.twoKeySet()) {
                    Obj newObj = integerObjPair.second();
                    if (!newObj.equals(propagatingObj)) continue;
                    int layer = integerObjPair.first();

                    if (layer > 1) continue;

                    Set<Obj> targetedAllocators;
                    if (layer - 1 == 0 &&
                            (propagatedObj.getType() instanceof ArrayType || propagatingObj.getType().equals(propagatedObj.getType()))
                    ) {
                        targetedAllocators = Set.of(propagatedObj);
                    } else {
                        if (allocatorsByParam == null) continue;
                        MultiMap<Obj, Var> allocatorsByParamToArg = allocatorsByParam.get(layer - 1);
                        if (allocatorsByParamToArg == null || allocatorsByParamToArg.isEmpty())
                            continue;
                        targetedAllocators = allocatorsByParamToArg.keySet();
                    }

                    for (Obj sharedAllocator : newObjAndSharedAllocators.get(integerObjPair.first(), newObj)) {
                        if (globalOAG.hasEdge(sharedAllocator, propagatingObj)) {
                            for (Obj targetedAllocator : targetedAllocators) {
                                if (oag.hasEdge(sharedAllocator, targetedAllocator)) {
                                    if (!globalOAG.hasEdge(sharedAllocator, targetedAllocator)) {
                                        globalOAG.addEdge(sharedAllocator, targetedAllocator, BackTravResult.Relation.TRANSFER);
                                    }
                                } else {
                                }
                            }
                        }
                    }
                }
            }
        }

        return globalOAG;
    }

    private void collectConnectedObjField(SimpleGraph<Pair<Obj, AbstractField>> connectedGraphs,
                                          BackTravResult travRet, Obj obj, AbstractField absF,
                                          TwoKeyMultiMap<Obj, AbstractField, BackTravResult> backTravResults) {
        TwoKeyMultiMap<Integer, Obj, List<ExtraEdgeLabel>> layeredNewObjToEdgeLabels = travRet.getNewObjToEdgeLabels();
        if (layeredNewObjToEdgeLabels == null) return;
        for (Integer layer : layeredNewObjToEdgeLabels.keySet().stream().sorted().toList()) {
            if (layer == 0) continue;
            MultiMap<Obj, List<ExtraEdgeLabel>> thisLayerNewObjToEdgeLabels = layeredNewObjToEdgeLabels.get(layer);

            for (Obj newObj : thisLayerNewObjToEdgeLabels.keySet()) {
                connectedGraphs.addEdge(new Pair<>(newObj, ArbitraryField.get()), new Pair<>(obj, absF));
            }
        }
    }
}
