package pascal.taie.analysis.pta.toolkit.skim.traverse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.flowgraph.*;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.*;
import pascal.taie.analysis.pta.toolkit.skim.preanalysis.SkimInfra;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.ClassNames;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.*;
import pascal.taie.util.collection.*;
import pascal.taie.util.graph.Graph;

import java.util.*;
import java.util.stream.Collectors;

public class Traversal {
    private static final Logger logger = LogManager.getLogger(Traversal.class);
    private static final int PARALLEL_TRAVERSAL_THRESHOLD = 128;
    private final SkimInfra infra;
    private final int maxRounds;

    private final ExtraFlowGraph extraFlowGraph;
    private final CallDetails callDetails;
    private final Graph<Obj> oag;
    private final PointerAnalysisResult pta;
    private final MultiMap<Obj, BaseObjKeyOfNewObj> newObjToLayerToBaseObj = Maps.newMultiMap();
    private final ExtraInfoTracker extraInfoTracker;
    public Traversal(SkimInfra infra) {
        this(infra, 2);
    }

    public Traversal(SkimInfra infra, int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("skim-max-rounds must be at least 1");
        }
        this.maxRounds = maxRounds;
        this.infra = infra;
        this.pta = infra.getPta();
        extraFlowGraph = infra.getExtraFlowGraph();
        callDetails = infra.getCallDetails();
        oag = infra.getOag();
        extraInfoTracker = infra.getPta().getExtraInfoTracker();
    }


    private record BackTravKey(Obj baseObj, AbstractField field, VarNode storedVar){}
    private record BackTravTaskResult(BackTravKey key, BackTravResult result) {}
    public record BaseObjKeyOfNewObj(Obj baseObj, AbstractField field, Integer layer) {}
    public TwoKeyMultiMap<Obj, AbstractField, BackTravResult> incrementalBackTrav(
            MultiMap<Obj, VarNode> baseObjToVars) {
        Map<BackTravKey, BackTravResult> backTravResults = Maps.newMap();

        MultiMap<Obj, BackTravKey> objToBackTravKeyContainingAsAllocatorByParam = Maps.newMultiMap();

        MultiMap<Obj, BaseObjKeyOfNewObj> incrementalNewObjToLayerToBaseObj;

        List<BackTravKey> keysToTraverse = getAllKeysToTraverse(baseObjToVars);

        int count = 0;
        do {
            logger.info("Back traversal round " + count + ", keysToTraverse size: " + keysToTraverse.size() + ", newObjToLayerToBaseObj size: " + newObjToLayerToBaseObj.size());

            if (maxRounds > 2) {
                this.wrapperObjCache.clear();
            }

            incrementalNewObjToLayerToBaseObj = Maps.newMultiMap();

            var keyStream = keysToTraverse.size() >= PARALLEL_TRAVERSAL_THRESHOLD
                    ? keysToTraverse.parallelStream()
                    : keysToTraverse.stream();
            List<BackTravTaskResult> roundResults = keyStream
                    .map(key -> new BackTravTaskResult(
                            key, backTraverse(key.baseObj(), key.storedVar())))
                    .toList();

            for (BackTravTaskResult taskResult : roundResults) {
                BackTravKey backTravKey = taskResult.key();
                Obj baseObj = backTravKey.baseObj();
                AbstractField field = backTravKey.field();

                BackTravResult travResult = taskResult.result();
                if(travResult.hasResult()){
                    backTravResults.put(backTravKey, travResult);

                    if (travResult.getNewObjAndSharedAllocators().isPresent()) {
                        for (Pair<Integer, Obj> integerObjPair : travResult.getNewObjAndSharedAllocators().get().twoKeySet()) {
                            int layer = integerObjPair.first();
                            Obj newObj = integerObjPair.second();

                            if (maxRounds <= 2 && !infra.isPartialAndHaveConcernedField(newObj)) {
                                continue;
                            }
                            BaseObjKeyOfNewObj value = new BaseObjKeyOfNewObj(baseObj, field, layer);
                            if (!newObjToLayerToBaseObj.contains(newObj, value)) {
                                incrementalNewObjToLayerToBaseObj.put(newObj, value);
                            }
                        }
                    }

                    TwoKeyMultiMap<Integer, Obj, Var> allocatorsByParam = travResult.getAllocatorsByParam();
                    if (allocatorsByParam != null) {
                        for (Pair<Integer, Obj> integerObjPair : allocatorsByParam.twoKeySet()) {
                            Obj allocator = integerObjPair.second();

                            objToBackTravKeyContainingAsAllocatorByParam.put(allocator, backTravKey);
                        }
                    }
                }
            }

            newObjToLayerToBaseObj.putAll(incrementalNewObjToLayerToBaseObj);

            keysToTraverse = getNextKeysToTraverse(incrementalNewObjToLayerToBaseObj, objToBackTravKeyContainingAsAllocatorByParam,
                    backTravResults);

            count++;
            if (count >= maxRounds) break;
        } while (!incrementalNewObjToLayerToBaseObj.isEmpty());

        if (!incrementalNewObjToLayerToBaseObj.isEmpty()) {
            logger.warn("Stopping back traversal after {} rounds (skim-max-rounds={}) with omitted incremental new objects {}.",
                    count, maxRounds, incrementalNewObjToLayerToBaseObj.keySet().size());
        }

        TwoKeyMultiMap<Obj, AbstractField, BackTravResult> objToFieldToRet = Maps.newTwoKeyMultiMap();

        backTravResults.forEach((key, travResult) ->
                objToFieldToRet.put(key.baseObj, key.field, travResult));

        return objToFieldToRet;
    }

    private List<BackTravKey> getNextKeysToTraverse(MultiMap<Obj, BaseObjKeyOfNewObj> incrementalNewObjToLayerToBaseObj,
                                                   MultiMap<Obj, BackTravKey> objToBackTravKeyContainingAsAllocatorByParam,
                                                   Map<BackTravKey, BackTravResult> backTravResults) {
        Set<BackTravKey> nextKeysToTraverse = Sets.newHybridSet();
        for (Obj newObj : incrementalNewObjToLayerToBaseObj.keySet()) {
            Set<BackTravKey> backTravKeys = objToBackTravKeyContainingAsAllocatorByParam.get(newObj);
            nextKeysToTraverse.addAll(backTravKeys);
        }

        for (BackTravKey backTravKey : backTravResults.keySet()) {
            Obj baseObj = backTravKey.baseObj();
            if (incrementalNewObjToLayerToBaseObj.containsKey(baseObj)) {
                nextKeysToTraverse.add(backTravKey);
            }
        }
        return nextKeysToTraverse.stream().sorted(Comparator.comparing((BackTravKey key) -> key.baseObj().getIndex())).toList();
    }

    private List<BackTravKey> getAllKeysToTraverse(MultiMap<Obj, VarNode> baseObjToVars) {
        Set<BackTravKey> keysToTraverse = Sets.newConcurrentSet();

        baseObjToVars.keySet().parallelStream().forEach(baseObj -> {
            for (VarNode var : baseObjToVars.get(baseObj)) {
                extraFlowGraph.getInEdgesOf(var).stream().filter(extraFlowEdge -> {
                    FlowKind kind = extraFlowEdge.flowKind();
                    return kind.equals(FlowKind.FIELD_STORE);
                }).forEach(extraFlowEdge -> {
                    VarNode storedVar = (VarNode) extraFlowEdge.source();
                    AbstractField absF = extraFlowEdge.field();
                    if(absF instanceof FieldWrapper fieldWrapper){
                        Type fieldType = fieldWrapper.field().getType();
                        if(!infra.isConcernedFieldType(baseObj, fieldType))
                            return;
                    }
                    BackTravKey key = new BackTravKey(baseObj, absF, storedVar);
                    keysToTraverse.add(key);
                });
            }
        });

        return keysToTraverse.stream().sorted(Comparator.comparing((BackTravKey key) -> key.baseObj().getIndex())).toList();
    }

    private BackTravResult backTraverse(Obj baseObj, VarNode storedVar) {
        BackTravResult travResult = new BackTravResult(oag, baseObj);
        TraversalWorkList workList = new TraversalWorkList(maxRounds);
        Map<TraversalWorkList.Key, Set<Obj>> visited = Maps.newMap();

        Map<Obj, Integer> objIntegerMap = infra.ctxObjsIvkOn(storedVar.getVar().getMethod(), baseObj);
        MultiMap<Integer, Obj> ctxObjToLayer = objIntegerMap.keySet().stream().collect(MultiMapCollector.get(objIntegerMap::get, o -> o));
        Optional<Integer> first = ctxObjToLayer.keySet().stream().sorted().findFirst();
        first.ifPresent(minLayer -> workList.addEntry(
                new AllocTraversalState(storedVar, minLayer,
                        ctxObjToLayer.get(minLayer))));

        while(!workList.isEmpty()){
            TraversalState crtState = workList.pollEntry();
            TraversalWorkList.Key stateKey = TraversalWorkList.keyOf(crtState);
            Set<Obj> processedRecvObjs = visited.get(stateKey);
            if (processedRecvObjs == null) {
                visited.put(stateKey, Sets.newHybridSet(crtState.getRecvObjs()));
            } else {
                Set<Obj> recvObjDelta = Sets.newHybridSet(crtState.getRecvObjs());
                recvObjDelta.removeAll(processedRecvObjs);
                if (recvObjDelta.isEmpty()) {
                    continue;
                }
                processedRecvObjs.addAll(recvObjDelta);
                crtState = crtState.withRecvObjs(recvObjDelta);
            }
            Node node = crtState.node;
            if (node instanceof VarNode varNode &&
                !infra.isConcernedFieldType(baseObj, varNode.getVar().getType())) {
                continue;
            }
            if(node instanceof VarNode varNode && !extraInfoTracker.getNewObj(varNode.getVar()).isEmpty()){
                addNewObjs(baseObj, travResult, crtState);
                continue;
            }

            List<Node> passedArgs = new ArrayList<>();
            for (ExtraFlowGraph.ExtraFlowEdge inEdge : extraFlowGraph.getInEdgesOf(node)) {
                switch (inEdge.flowKind()) {
                    case FIELD_LOAD -> handleFieldLoad(inEdge, crtState).forEach(workList::addEntry);
                    case CALL_LOAD -> handleCallLoad(inEdge, crtState).forEach(workList::addEntry);
                    case PARAMETER_PASSING -> passedArgs.add(inEdge.src());
                    case THIS_PASSING -> {
                        TwoKeyMultiMap<Integer, Obj, Var> allocators = handleThisPassing(baseObj, inEdge, crtState);
                        addAllocatorsByThis(baseObj, allocators, travResult, crtState);
                    }
                    case CAST, LOCAL_ASSIGN ->
                            workList.addEntry(crtState.nextState(inEdge.src()));
                    case RETURN -> handleReturn(inEdge, crtState).ifPresent(workList::addEntry);

                    case INSTANCE_LOAD, ARRAY_LOAD, INSTANCE_STORE, ARRAY_STORE -> {
                    }
                    case STATIC_LOAD, STATIC_STORE -> {
                    }
                    case FIELD_STORE, CALL_STORE -> {
                    }
                }
            }

            if(!passedArgs.isEmpty()){
                handleParamPassing(baseObj, passedArgs, crtState, travResult, workList);
            }
        }

        return travResult;
    }

    private void addAllocatorsByThis(Obj baseObj, TwoKeyMultiMap<Integer, Obj, Var> allocators, BackTravResult travResult, TraversalState crtState) {
        int crtLayer = crtState.getAllocationLayer();
        for (Pair<Integer, Obj> integerObjPair : allocators.twoKeySet()) {
            Integer allocatorLayer = integerObjPair.first();
            Obj allocator = integerObjPair.second();
            if(allocatorLayer >= crtLayer){
                travResult.checkBeforeAddAllocatorsByThis(allocatorLayer, allocator, crtState, allocators.get(allocatorLayer, allocator));
            }
        }
    }

    private void addNewObjs(Obj baseObj, BackTravResult travResult, TraversalState crtState) {
        Var var = ((VarNode) crtState.node).getVar();
        Set<Obj> newObjs = extraInfoTracker.getNewObj(var);
        JMethod inMethod = var.getMethod();

        Map<Obj, Integer> ctxObjsIvkOn = infra.ctxObjsIvkOn(inMethod, baseObj);

        if (crtState.getAllocationLayer() == 1 && newObjs.size() == 1) {
            Obj newObj = newObjs.iterator().next();
            if (crtState.getExtraEdgeLabels().stream().anyMatch(label ->
                    label instanceof FieldWrapper fieldWrapper
                            && newObj.getType().equals(fieldWrapper.field().getType()))) {
                Set<Obj> sameTypeNewObjs = Sets.newHybridSet(newObjs);
                oag.getPredsOf(newObj).stream().filter(allocator -> ctxObjsIvkOn.getOrDefault(allocator, -1) == 1).forEach(allocator -> {
                    oag.getSuccsOf(allocator).stream().filter(succ -> succ.getType().equals(newObj.getType())).forEach(sameTypeNewObjs::add);
                });
                newObjs = sameTypeNewObjs;
            }
        }
        if (crtState instanceof AllocTraversalState || checkCrtStateIfWasOutOfAllocChain(crtState)) {
            int crtLayer = crtState.getAllocationLayer();
            Set<Obj> crtRecvObjs = crtState.getCtxObjs();

            for (Obj ctxObj : ctxObjsIvkOn.keySet()) {
                int layer = ctxObjsIvkOn.get(ctxObj);
                if( (layer == crtLayer && crtRecvObjs.contains(ctxObj))
                        ||
                        (layer == crtLayer + 1 && crtRecvObjs.stream().anyMatch(crtObj -> oag.hasEdge(ctxObj, crtObj)))
                ){
                    newObjs.forEach(newObj -> {
                        travResult.addNewObjAndSharedAllocators(layer, newObj, ctxObj);
                        travResult.addNewObjToEdgeLabels(layer, newObj, crtState.getExtraEdgeLabels());
                    });
                }
            }
        }
    }

    private TwoKeyMultiMap<Integer, Obj, Var> handleThisPassing(Obj obj, ExtraFlowGraph.ExtraFlowEdge inEdge, TraversalState crtState) {
        VarNode tgt = (VarNode) inEdge.target();
        if(!isThis(tgt)) {
            throw new RuntimeException("Should be a this var.");
        }

        TwoKeyMultiMap<Integer, Obj, Var> ret = Maps.newTwoKeyMultiMap();
        Set<Obj> thisPTS = infra.getPointsToSet(tgt);
        infra.allocatorsMiniLayerOf(obj).forEach( (allocator, layer) ->{
            if(thisPTS.contains(allocator)) {
                ret.put(layer, allocator, tgt.getVar());
            }
        });

        return ret;
    }

    private List<TraversalState> handleCallLoad(ExtraFlowGraph.ExtraFlowEdge inEdge, TraversalState crtState) {
        VarNode srcVar = (VarNode) inEdge.src();
        if(isThis(srcVar)){
            Set<VarNode> returnValues = getReturnVarOfThisCall((VarNode) inEdge.target(), srcVar);
            return returnValues.stream().map(crtState::nextState).toList();
        }
        TraversalState newState = crtState.nextState(srcVar);
        newState.addExtraEdgeLabel(CallLabel.get());
        return List.of(newState);
    }

    private Set<VarNode> getReturnVarOfThisCall(VarNode resultVar, VarNode thisVar) {
        Set<VarNode> ret = Sets.newSet();
        Var result = resultVar.getVar();
        Var thisV =  thisVar.getVar();
        for (ExtraFlowGraph.ExtraFlowEdge edge : extraFlowGraph.getInEdgesOf(resultVar)) {
            if(edge.flowKind().equals(FlowKind.RETURN)){
                VarNode source = (VarNode) edge.source();
                if(callDetails.getRecvVar(result, source.getVar()).contains(thisV)){
                    ret.add(source);
                }
            }
        }
        return ret;
    }

    private List<TraversalState> handleFieldLoad(ExtraFlowGraph.ExtraFlowEdge inEdge, TraversalState crtState) {
        VarNode srcVar = (VarNode) inEdge.src();
        if(isThis(srcVar)){
            Set<VarNode> fieldStoredVarOfThis = getFieldStoredVarOfThis((VarNode) inEdge.target(), srcVar);
            return fieldStoredVarOfThis.stream().map(crtState::nextState).toList();
        }
        if(pta.getPointsToSet(srcVar.getVar()).stream().noneMatch(o -> infra.isConcernedFieldOf(o, inEdge.field())))
            return List.of();
        TraversalState newState = crtState.nextState(srcVar);
        newState.addExtraEdgeLabel(inEdge.field());
        return List.of(newState);
    }

    private Set<VarNode> getFieldStoredVarOfThis(VarNode loadedVar, VarNode thisVar){
        Set<VarNode> ret =  Sets.newSet();
        Set<Obj> thisPts = infra.getPointsToSet(thisVar);
        extraFlowGraph.getInEdgesOf(loadedVar).stream()
                .filter(e ->
                        e.flowKind().equals(FlowKind.INSTANCE_LOAD)
                ).forEach(
                        e -> {
                            InstanceFieldNode insFSrc = (InstanceFieldNode) e.source();
                            Obj base = insFSrc.getBase();
                            if(!thisPts.contains(base)) return;
                            for (ExtraFlowGraph.ExtraFlowEdge extraFlowEdge : extraFlowGraph.getInEdgesOf(insFSrc)) {
                                VarNode source = (VarNode) extraFlowEdge.source();
                                if(infra.getReceiverObjectsOf(methodOf(source)).contains(base)){
                                    ret.add(source);
                                }
                            }
                        }
                );
        return ret;
    }

    private void handleParamPassing(Obj obj, List<Node> passedArgs, TraversalState crtState, BackTravResult travResult, TraversalWorkList workList) {
        List<TraversalState> nextStates = new ArrayList<>();

        Node currentNode = crtState.node;
        Var paramVar = ((VarNode) currentNode).getVar();
        JMethod inMethod = methodOf(currentNode);
        if(inMethod.isStatic()){
            Optional<JMethod> opAllocMtd = obj.getContainerMethod();
            handleStaticParamPassing(obj, passedArgs, crtState, opAllocMtd.isPresent() && inMethod.equals(opAllocMtd.get()), travResult, workList);
            return;
        }

        Map<Obj, Integer> ctxObjsOfInMethod = infra.ctxObjsIvkOn(inMethod, obj);
        TwoKeyMultiMap<Obj, Integer, Integer> wrapperObjIvkOn = null;
        if(ctxObjsOfInMethod.isEmpty()) {
            wrapperObjIvkOn = getWrapperObjIvkOn(inMethod, obj);
            if (wrapperObjIvkOn.isEmpty()) {
                return;
            }
        }

        if (!ctxObjsOfInMethod.isEmpty()) {
            for (Node passedArg : passedArgs) {
                if (passedArg instanceof VarNode passedArgVarNode) {
                    Var passedArgVar = passedArgVarNode.getVar();
                    Set<Var> recvVars = callDetails.getRecvVar(paramVar, passedArgVar);

                    Set<Obj> recvObjs = recvVars.stream().map(pta::getPointsToSet)
                            .flatMap(Set::stream).collect(Collectors.toSet());

                    MultiMap<Integer, Obj> recvObjsToAllocationLayer = Maps.newMultiMap();
                    ctxObjsOfInMethod.forEach((ctxObj, layer) -> {
                        if (recvObjs.contains(ctxObj))
                            recvObjsToAllocationLayer.put(layer, ctxObj);
                    });

                    recvObjsToAllocationLayer.forEachSet((layer, recvAlsoCtxObjs) ->
                            nextStates.add(new AllocTraversalState(passedArgVarNode, layer, recvAlsoCtxObjs, crtState))
                    );
                }
            }

            List<TraversalState> validStates = checkBeforeAddAllocatorByParam(crtState, travResult, nextStates);
            validStates.forEach(workList::addEntry);
        } else if (!wrapperObjIvkOn.isEmpty()) {
            boolean crtInAllocChain = crtState instanceof AllocTraversalState;

            if (crtInAllocChain) {
                MultiMap<Obj, BaseObjKeyOfNewObj> potentialWrappingObjs = crtState.getCtxObjs().stream()
                        .map(newObjToLayerToBaseObj::get).flatMap(Set::stream).collect(MultiMapCollector.get(
                        BaseObjKeyOfNewObj::baseObj, b -> b));

                for (Node passedArg : passedArgs) {
                    if (passedArg instanceof VarNode passedArgVarNode) {
                        Var passedArgVar = passedArgVarNode.getVar();
                        Set<Var> recvVars = callDetails.getRecvVar(paramVar, passedArgVar);
                        Set<Obj> recvObjs = recvVars.stream().map(pta::getPointsToSet)
                                .flatMap(Set::stream).collect(Collectors.toCollection(Sets::newHybridSet));

                        MultiMap<Integer, Obj> wrappingLayerToWrappingObjs = Maps.newMultiMap();

                        for (Obj recvObj : recvObjs) {
                            if (!potentialWrappingObjs.containsKey(recvObj)) continue;
                            Set<BaseObjKeyOfNewObj> wrappingObjKeys = potentialWrappingObjs.get(recvObj);

                            wrappingObjKeys.forEach(wrappingObjKey ->
                                    wrappingLayerToWrappingObjs.put(wrappingObjKey.layer(), wrappingObjKey.baseObj()));
                        }

                        wrappingLayerToWrappingObjs.forEachSet((layer, wrappingObjs) -> {
                            nextStates.add(new OutOfAllocTravState(passedArgVarNode, crtState.getAllocationLayer(), wrappingObjs, layer, crtState.getCtxObjs(), crtState));
                        });
                    }
                }

                nextStates.forEach(workList::addEntry);
            } else {
                OutOfAllocTravState crtOutOfState = (OutOfAllocTravState)crtState;
                int wrapperLayer = crtOutOfState.getWrapperLayer();
                if (wrapperLayer <= 1) {
                    return;
                }

                Set<Obj> crtRecvObjs = crtOutOfState.getRecvObjs();
                Set<Obj> crtCtxObjs = crtOutOfState.getCtxObjs();
                for (Node passedArg : passedArgs) {
                    if (passedArg instanceof VarNode passedArgVarNode) {
                        Var passedArgVar = passedArgVarNode.getVar();
                        Set<Var> recvVars = callDetails.getRecvVar(paramVar, passedArgVar);
                        Set<Obj> recvObjs = recvVars.stream().map(pta::getPointsToSet)
                                .flatMap(Set::stream)
                                .filter(o -> crtRecvObjs.stream().anyMatch(recvObj -> oag.hasEdge(o, recvObj)))
                                .collect(Collectors.toCollection(Sets::newHybridSet));
                        nextStates.add(new OutOfAllocTravState(passedArgVarNode, crtOutOfState.getAllocationLayer(), recvObjs, wrapperLayer - 1, crtCtxObjs, crtState));
                    }
                }
                nextStates.forEach(workList::addEntry);
            }
        }
    }

    private void handleStaticParamPassing(Obj obj, List<Node> passedArgs, TraversalState crtState,
                                          boolean isInAllocMtd, BackTravResult travResult, TraversalWorkList workList) {
        List<TraversalState> nextStates = new ArrayList<>();

        TwoKeyMultiMap<Var, Integer, Obj> argToAllocators = Maps.newTwoKeyMultiMap();

        if(!obj.getContainerMethod().isPresent()){
            throw new RuntimeException(obj + "Method not present");
        }

        JMethod inMethod = methodOf(crtState.node);
        Map<Obj, Integer> ctxObjsOfInMethod = infra.ctxObjsIvkOn(inMethod, obj);
        TwoKeyMultiMap<Obj, Integer, Integer> wrapperObjIvkOn = null;

        if(ctxObjsOfInMethod.isEmpty()) {
            wrapperObjIvkOn = getWrapperObjIvkOn(inMethod, obj);
            if (wrapperObjIvkOn.isEmpty()) {
                return;
            }
        }

        if (!ctxObjsOfInMethod.isEmpty()) {
            int crtAllocLayer = crtState.getAllocationLayer();
            for (Node passedArg : passedArgs) {
                if (passedArg instanceof VarNode passedArgVarNode) {
                    Var passedArgVar = passedArgVarNode.getVar();
                    Set<Obj> recvObjsOfCaller = infra.getReceiverObjectsOf(passedArgVar.getMethod());
                    ctxObjsOfInMethod.forEach((o, i) -> {
                        if (i == crtAllocLayer || i == crtAllocLayer + 1) {
                            if (recvObjsOfCaller.contains(o)) {
                                argToAllocators.put(passedArgVar, i, o);
                            }
                        }
                    });
                }
            }

            Pair<TraversalState, Optional<JMethod>> p = crtState.popStaticCallWithNextState();
            Optional<JMethod> caller = p.second();
            TraversalState stateAfterStaticCall = p.first();
            for (Node passedArg : passedArgs) {
                if (passedArg instanceof VarNode passedArgVarNode) {
                    if(caller.isPresent() && !caller.get().equals(methodOf(passedArg))){
                        continue;
                    }
                    if(inMethod.equals(methodOf(passedArg))){
                        continue;
                    }
                    Var argVar = passedArgVarNode.getVar();
                    argToAllocators.get(argVar).forEachSet((layer, allocators) ->
                            nextStates.add(new AllocTraversalState(passedArg, layer, allocators, stateAfterStaticCall))
                    );
                }
            }

            if (caller.isPresent()) {
                nextStates.forEach(workList::addEntry);
            }
            else {
                List<TraversalState> validStates = checkBeforeAddAllocatorByParam(stateAfterStaticCall, travResult, nextStates);
                validStates.forEach(workList::addEntry);
            }
        } else if (!wrapperObjIvkOn.isEmpty()) {
            boolean crtInAllocChain = crtState instanceof AllocTraversalState;
            if (crtInAllocChain) {
                MultiMap<Obj, BaseObjKeyOfNewObj> potentialWrappingObjs = crtState.getCtxObjs().stream()
                        .map(newObjToLayerToBaseObj::get).flatMap(Set::stream).collect(MultiMapCollector.get(
                                BaseObjKeyOfNewObj::baseObj, b -> b));

                for (Node passedArg : passedArgs) {
                    if (passedArg instanceof VarNode passedArgVarNode) {
                        Var passedArgVar = passedArgVarNode.getVar();
                        Set<Obj> recvObjsOfCaller = infra.getReceiverObjectsOf(passedArgVar.getMethod());

                        MultiMap<Integer, Obj> wrappingLayerToWrappingObjs = Maps.newMultiMap();
                        for (Obj recvObj : recvObjsOfCaller) {
                            if (!potentialWrappingObjs.containsKey(recvObj)) continue;
                            Set<BaseObjKeyOfNewObj> wrappingObjKeys = potentialWrappingObjs.get(recvObj);
                            wrappingObjKeys.forEach(wrappingObjKey ->
                                    wrappingLayerToWrappingObjs.put(wrappingObjKey.layer(), wrappingObjKey.baseObj()));
                        }

                        wrappingLayerToWrappingObjs.forEachSet((layer, wrappingObjs) -> {
                            nextStates.add(new OutOfAllocTravState(passedArgVarNode, crtState.getAllocationLayer(), wrappingObjs, layer, crtState.getCtxObjs(), crtState));
                        });
                    }
                }

                nextStates.forEach(workList::addEntry);
            } else {
                OutOfAllocTravState crtOutOfState = (OutOfAllocTravState)crtState;
                int wrapperLayer = crtOutOfState.getWrapperLayer();
                if (wrapperLayer <= 1) {
                    return;
                }

                Set<Obj> crtRecvObjs = crtOutOfState.getRecvObjs();
                Set<Obj> crtCtxObjs = crtOutOfState.getCtxObjs();
                for (Node passedArg : passedArgs) {
                    if (passedArg instanceof VarNode passedArgVarNode) {
                        Var passedArgVar = passedArgVarNode.getVar();
                        Set<Obj> recvObjsOfCaller = infra.getReceiverObjectsOf(passedArgVar.getMethod()).stream()
                                .filter(o -> crtRecvObjs.stream().anyMatch(recvObj -> oag.hasEdge(o, recvObj)))
                                .collect(Collectors.toCollection(Sets::newHybridSet));
                        nextStates.add(new OutOfAllocTravState(passedArgVarNode, crtOutOfState.getAllocationLayer(), recvObjsOfCaller, wrapperLayer - 1, crtCtxObjs, crtState));
                    }
                }
                nextStates.forEach(workList::addEntry);
            }
        }

        nextStates.forEach(workList::addEntry);
    }

    private List<TraversalState> checkBeforeAddAllocatorByParam(TraversalState crtState, BackTravResult travResult, List<TraversalState> nextStates) {
        if (!checkCrtStateIfWasOutOfAllocChain(crtState)) return List.of();
        List<TraversalState> validNextStates = new ArrayList<>();

        int crtLayer = crtState.getAllocationLayer();
        for (TraversalState nextState : nextStates) {
            int nextLayer = nextState.getAllocationLayer();
            if(nextLayer == crtLayer || nextLayer == crtLayer + 1) {
                boolean valid = true;
                Set<Obj> crtAllocators = crtState.getCtxObjs();
                Set<Obj> newAllocators = Sets.newHybridSet(nextState.getCtxObjs());

                if(nextLayer == crtLayer) {
                    newAllocators.retainAll(crtAllocators);
                    valid = !newAllocators.isEmpty();
                }
                else if( nextLayer == crtLayer + 1 ){
                    if(crtAllocators.isEmpty()){
                        throw new RuntimeException("Check this.");
                    }

                    Set<Obj> filteredNewAllocators = Sets.newHybridSet();
                    for (Obj crtAllocator : crtAllocators) {
                        for (Obj newAllocator : newAllocators) {
                            if(oag.hasEdge(newAllocator, crtAllocator)){
                                filteredNewAllocators.add(newAllocator);
                            }
                        }
                    }
                    newAllocators.retainAll(filteredNewAllocators);
                    valid = !newAllocators.isEmpty();
                }
                if (valid) {
                    travResult.addAllocatorsByParam(crtAllocators, newAllocators, crtLayer, nextLayer, nextState.node);
                    validNextStates.add(nextState);
                }
            }
        }
        return validNextStates;
    }

    private Map<Obj, TwoKeyMultiMap<Obj, Integer, Integer>> wrapperObjCache = Maps.newConcurrentMap();

    private TwoKeyMultiMap<Obj, Integer, Integer> computeWrapperObjs(Obj obj) {
        TwoKeyMultiMap<Obj, Integer, Integer> ret = Maps.newTwoKeyMultiMap();
        Map<Obj, Integer> ctxObjToLayer = infra.allocatorsMiniLayerOf(obj);
        for (Obj ctxObj : ctxObjToLayer.keySet()) {
            Set<Obj> parentCtxObjs = oag.getPredsOf(ctxObj).stream().filter(ctxObjToLayer::containsKey).collect(Collectors.toSet());
            for (BaseObjKeyOfNewObj baseObjKeyOfNewObj : newObjToLayerToBaseObj.get(ctxObj)) {
                Obj wrappingObj = baseObjKeyOfNewObj.baseObj();

                boolean wrappingObjSharingParentCtxObj = false;
                for (Obj parentCtxObj : parentCtxObjs) {
                    if (infra.allocateesMiniTwoLayersOf(parentCtxObj).containsKey(wrappingObj)) {
                        wrappingObjSharingParentCtxObj = true;
                        break;
                    }
                }
                if (!wrappingObjSharingParentCtxObj) continue;
                Integer wrappingLayer = baseObjKeyOfNewObj.layer();
                if (wrappingLayer == 0) {
                    continue;
                }else if (wrappingLayer == 1) {
                    ret.put(wrappingObj, wrappingLayer, ctxObjToLayer.get(ctxObj));
                } else if (wrappingLayer == 2) {
                    ret.put(wrappingObj, wrappingLayer, ctxObjToLayer.get(ctxObj));

                    Set<Obj> wrappingObjParentCtxObjs = oag.getPredsOf(wrappingObj).stream()
                            .filter(o1 -> !Collections.disjoint(oag.getPredsOf(o1), parentCtxObjs))
                            .collect(Collectors.toSet());
                    for (Obj wrappingObjParentCtxObj : wrappingObjParentCtxObjs) {
                        ret.put(wrappingObjParentCtxObj, 1, ctxObjToLayer.get(ctxObj));
                    }
                }
            }
        }

        return ret;
    }
    private TwoKeyMultiMap<Obj, Integer, Integer> getWrapperObjIvkOn(JMethod method, Obj o){
        TwoKeyMultiMap<Obj, Integer, Integer> cached = wrapperObjCache.computeIfAbsent(o, this::computeWrapperObjs);

        Set<Obj> recvObjs = infra.getReceiverObjectsOf(method);
        TwoKeyMultiMap<Obj, Integer, Integer> filtered =
                Maps.newTwoKeyMultiMap();

        cached.forEach((wrapperObj, wrapperLayer, ctxLayer) -> {
            if (recvObjs.contains(wrapperObj)) {
                filtered.put(wrapperObj, wrapperLayer, ctxLayer);
            }
        });

        return filtered;
    }

    private Optional<TraversalState> handleReturn(ExtraFlowGraph.ExtraFlowEdge inEdge, TraversalState crtState) {
        Node src = inEdge.src();
        JMethod srcMtd = methodOf(src);

        if(srcMtd.isStatic()){
            VarNode crtNode = (VarNode) inEdge.target();
            TraversalState state = crtState.nextState(src);
            return Optional.of(state.pushStaticCallStack(
                    crtNode.getVar().getMethod()));
        }

        return Optional.empty();
    }

    private boolean checkCrtStateIfWasOutOfAllocChain(TraversalState crtState) {
        if (crtState instanceof OutOfAllocTravState outOfAllocTravState) {
            return outOfAllocTravState.getWrapperLayer() == 1;
        }
        return true;
    }

    private boolean isThis(VarNode varNode){
        Var var = varNode.getVar();
        return isThis(var);
    }

    private boolean isThis(Var var){
        if(var == null){
            throw new  NullPointerException("Var is null");
        }
        return var.equals(var.getMethod().getIR().getThis());
    }

    private JMethod methodOf(Node n){
        if(n instanceof VarNode varNode){
            return varNode.getVar().getMethod();
        }
        throw new RuntimeException("Unknown Type!");
    }
}
