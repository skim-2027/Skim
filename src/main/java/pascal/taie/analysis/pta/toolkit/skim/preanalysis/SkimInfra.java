package pascal.taie.analysis.pta.toolkit.skim.preanalysis;

import pascal.taie.World;
import pascal.taie.analysis.graph.flowgraph.*;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.*;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.*;
import pascal.taie.analysis.pta.toolkit.PointerAnalysisResultEx;
import pascal.taie.analysis.pta.toolkit.PointerAnalysisResultExImpl;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassNames;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.*;
import pascal.taie.util.graph.Graph;
import pascal.taie.util.graph.SimpleGraph;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SkimInfra {
    private final int maxObjAllocatorLayer;
    private final PointerAnalysisResult pta;
    private final SimpleGraph<Obj> oag;
    private final SimpleGraph<Obj> fullOAG;
    protected final ExtraInfoTracker extraInfoTracker;
    private final PointerAnalysisResultEx ptaEx;
    private final Map<Obj, Map<Obj, Integer>> allocatorsCache = Maps.newConcurrentMap();
    private final Map<Obj, Set<JMethod>> objToInvokeMtds = Maps.newConcurrentMap();
    private final Map<Obj, Set<JMethod>> fullObjToInvokeMtds = Maps.newConcurrentMap();
    private final MultiMap<JMethod, Obj> mtdToRecvObjs = Maps.newConcurrentMultiMap();

    private final FieldRecorder fieldRecorder;
    protected final KeyTypeCollector keyTypeCollector;
    private final ContainerCollector containerCollector;
    protected final FieldReachability fieldReachability;
    protected final PartialChecker partialChecker;

    private final Set<Type> stringLikeTypes;
    public SkimInfra(PointerAnalysisResult pta, int mtdCtxLen) {
        this.maxObjAllocatorLayer = mtdCtxLen - 1;
        var typeSystem = World.get().getTypeSystem();
        stringLikeTypes = Set.of(
                typeSystem.getClassType(ClassNames.STRING),
                typeSystem.getClassType(ClassNames.STRING_BUILDER),
                typeSystem.getClassType(ClassNames.STRING_BUFFER),
                typeSystem.getClassType(ClassNames.THROWABLE)
        );

        this.pta = pta;
        this.ptaEx = new PointerAnalysisResultExImpl(pta, true);
        this.oag = new SimpleGraph<>();

        this.fullOAG = new SimpleGraph<>();

        this.extraInfoTracker = pta.getExtraInfoTracker();
        this.fieldRecorder = extraInfoTracker.getFieldRecorder();
        this.keyTypeCollector = new KeyTypeCollector(fieldRecorder);
        this.fieldReachability = new FieldReachability(this);
        this.containerCollector = new ContainerCollector(this);
        this.partialChecker = new PartialChecker(this);
        this.buildMtdInvokeAndOAG();
        this.initObjToFields();
        this.initKeyTypes();
        this.initFieldReachability();
        this.collectContainer();
    }

    private void initObjToFields() {
        MultiMap<JMethod, VarNode> thisAliases = Maps.newMultiMap();
        ExtraFlowGraph extraFlowGraph = getExtraFlowGraph();
        Stream<JMethod> storeStream = fieldRecorder.instanceStores.keySet().stream();
        Stream<JMethod> loadStream = fieldRecorder.instanceLoads.keySet().stream();
        Streams.concat(storeStream, loadStream).distinct().forEach(
                jMethod -> {
                    if (jMethod.isStatic()) return;
                    Var aThis = jMethod.getIR().getThis();
                    if (aThis == null) return;
                    VarNode thisNode = extraInfoTracker.getVarNode(aThis);
                    if (thisNode == null) return;
                    Set<VarNode> visited = Sets.newHybridSet();
                    Queue<VarNode> workList = new ArrayDeque<>();
                    workList.add(thisNode);
                    while (!workList.isEmpty()) {
                        VarNode current = workList.poll();
                        visited.add(current);
                        for (ExtraFlowGraph.ExtraFlowEdge extraFlowEdge : extraFlowGraph.getOutEdgesOf(current)) {
                            if (extraFlowEdge.flowKind().equals(FlowKind.LOCAL_ASSIGN)) {
                                VarNode succ = (VarNode) extraFlowEdge.dest();
                                if (!visited.contains(succ)) {
                                    workList.add(succ);
                                }
                            }
                        }
                    }
                    thisAliases.putAll(jMethod, visited);
                }
        );

        doHandleField(thisAliases, fieldRecorder.instanceStores, true);
        doHandleField(thisAliases, fieldRecorder.instanceLoads, false);
    }

    private void doHandleField(MultiMap<JMethod, VarNode> thisAliasMap, TwoKeyMap<JMethod, Var, AbstractField> instanceFieldAccesses, boolean isStore) {
        for (JMethod method : instanceFieldAccesses.keySet()) {
            Set<VarNode> thisAliasVars = thisAliasMap.get(method);

            Map<Var, AbstractField> operations = instanceFieldAccesses.get(method);
            operations.forEach((baseVar, field) -> {
                VarNode baseVarNode = extraInfoTracker.getVarNode(baseVar);
                if (baseVarNode == null) {
                    throw new RuntimeException("baseVarNode is null for baseVar: " + baseVar + " in method: " + method);
                }
                boolean isNonThisBase = !thisAliasVars.contains(baseVarNode);
                for (Obj obj : pta.getPointsToSet(baseVar)) {
                    fieldRecorder.recordObjToField(obj, field);
                    if (isNonThisBase) {
                        if (isStore) {
                            fieldReachability.objToNonThisFieldStore.put(obj, field, baseVarNode);
                        } else {
                            fieldReachability.objToNonThisFieldLoad.put(obj, field, baseVarNode);
                            fieldReachability.hasNonThisFieldLoad.add(field);
                        }
                    }
                }
            });
        }
    }

    private void initKeyTypes() {
        keyTypeCollector.run(pta.getObjects());
    }

    private void initFieldReachability() {
        this.fieldReachability.build(this.keyTypeCollector);
    }

    private void collectContainer() {
        this.containerCollector.collect(pta.getObjects());
    }

    private void buildMtdInvokeAndOAG() {
        pta.getObjects()
                .parallelStream()
                .forEach(obj -> {
                    Set<JMethod> methods = Sets.newHybridSet();
                    Queue<JMethod> workList = new ArrayDeque<>(
                            ptaEx.getMethodsInvokedOn(obj));
                    while (!workList.isEmpty()) {
                        JMethod method = workList.poll();
                        methods.add(method);
                        pta.getCallGraph()
                                .getCalleesOfM(method)
                                .stream()
                                .filter(m -> m.isStatic() && !methods.contains(m))
                                .forEach(workList::add);
                    }

                    boolean specialObj = obj.getType() instanceof ArrayType || obj instanceof ConstantObj || obj instanceof MergedObj;
                    if (!specialObj) {
                        objToInvokeMtds.put(obj, methods);
                        for (JMethod method : methods) {
                            mtdToRecvObjs.put(method, obj);
                        }
                    }
                    fullObjToInvokeMtds.put(obj, methods);
                });
        this.fullObjToInvokeMtds.forEach((obj, methods) -> {
            methods.stream()
                    .map(ptaEx::getObjectsAllocatedIn)
                    .flatMap(Set::stream)
                    .forEach(succ -> {
                        if (!(obj.getType() instanceof ArrayType) && !(succ instanceof ConstantObj) && !(succ instanceof MergedObj)) {
                            this.oag.addEdge(obj, succ);
                        }
                        this.fullOAG.addEdge(obj, succ);
                    });
        });
    }

    public boolean isPartialAndHaveConcernedField(Obj obj) {
        if (!isParial(obj)) return false;
        if (obj instanceof MockObj) return true;
        return !getConcernedFieldsOfContainer(obj).isEmpty();
    }

    public boolean isConcernedFieldOf(Obj obj, AbstractField field) {
        if (obj instanceof MockObj) return true;
        return getConcernedFieldsOfContainer(obj).contains(field);
    }

    public boolean isConcernedFieldType(Obj baseObj, Type fieldType) {
        if (baseObj instanceof MockObj) return true;
        return isConcernedType(fieldType);
    }

    private boolean isConcernedType(Type type){
        if(type instanceof ArrayType arrayType){
            type = arrayType.elementType();
        }
        return type instanceof ClassType && !stringLikeTypes.contains(type);
    }

    private Set<AbstractField> getConcernedFieldsOfContainer(Obj container) {
        return this.containerCollector.containerToFields.get(container);
    }

    private boolean isParial(Obj obj) {
        return this.partialChecker.check(obj, true);
    }

    public Graph<Obj> getOag() {
        return oag;
    }

    public Graph<Obj> getFullOAG() {
        return fullOAG;
    }

    public PointerAnalysisResult getPta() {
        return pta;
    }

    public ExtraFlowGraph getExtraFlowGraph() {
        return extraInfoTracker.getExtraFlowGraph();
    }

    public CallDetails getCallDetails() {
        return extraInfoTracker.getCallDetails();
    }

    public Set<JMethod> getMethodsInvokedOn(Obj obj){
        return objToInvokeMtds.get(obj) == null ?  Collections.emptySet() : objToInvokeMtds.get(obj);
    }

    public Set<Obj> getReceiverObjectsOf(JMethod method){
        return mtdToRecvObjs.get(method);
    }

    public Set<Obj> getPointsToSet(Node node) {
        if (node instanceof VarNode varNode) {
            return pta.getPointsToSet(varNode.getVar());
        } else if (node instanceof InstanceFieldNode ifNode) {
            return pta.getPointsToSet(ifNode.getBase(), ifNode.getField());
        } else if (node instanceof ArrayIndexNode aiNode) {
            return pta.getPointsToSet(aiNode.getBase());
        } else {
            throw new RuntimeException("Unknown node type: " + node);
        }
    }

    public boolean hasSingleCtx(Obj obj) {
        Map<Obj, Integer> allocators = allocatorsMiniLayerOf(obj);
        return allocators.size() <= maxObjAllocatorLayer + 1;
    }

    public Map<Obj, Integer> allocatorsMiniLayerOf(Obj obj){
        return allocatorsCache.computeIfAbsent(obj, o -> {
            Set<Obj> visited = Sets.newSet();
            MultiMap<Integer, Obj> allocators = Maps.newMultiMap();
            allocators.put(0, o);
            visited.add(o);
            Set<Obj> layer1 = Sets.newHybridSet(oag.getPredsOf(o));
            layer1.remove(o);
            allocators.putAll(1, layer1);
            visited.addAll(layer1);

            for (int i = 2; i <= maxObjAllocatorLayer; i++) {
                Set<Obj> layerI = allocators.get(i - 1).stream().map(oag::getPredsOf).flatMap(Set::stream)
                        .filter(o1 -> !visited.contains(o1)).collect(Collectors.toSet());
                allocators.putAll(i, layerI);
                visited.addAll(layerI);
            }

            Map<Obj, Integer> ret = Maps.newMap();
            allocators.forEach((i, o1) -> ret.put(o1, i));
            return ret;
        });
    }

    private final Map<Obj, Map<Obj, Integer>> allocateesCache = Maps.newConcurrentMap();
    public Map<Obj, Integer> allocateesMiniTwoLayersOf(Obj o) {
        return allocateesCache.computeIfAbsent(o, obj -> {
            Set<Obj> visited = Sets.newSet();
            MultiMap<Integer, Obj> allocatees = Maps.newMultiMap();
            allocatees.put(0, obj);
            visited.add(obj);
            Set<Obj> layer1 = Sets.newHybridSet(oag.getSuccsOf(obj));
            layer1.remove(obj);
            allocatees.putAll(1, layer1);
            visited.addAll(layer1);

            for (int i = 2; i <= 2; i++) {
                Set<Obj> layerI = allocatees.get(i - 1).stream().map(oag::getSuccsOf).flatMap(Set::stream)
                        .filter(o1 -> !visited.contains(o1)).collect(Collectors.toSet());
                allocatees.putAll(i, layerI);
                visited.addAll(layerI);
            }

            Map<Obj, Integer> ret = Maps.newMap();
            allocatees.forEach((i, o1) -> ret.put(o1, i));
            return ret;
        });
    }

    public Map<Obj, Integer> ctxObjsIvkOn(JMethod method, Obj obj){
        Map<Obj, Integer> ret = Maps.newMap();
        Map<Obj, Integer> allocators = allocatorsMiniLayerOf(obj);

        Set<Obj> recvObjs = Sets.newSet(getReceiverObjectsOf(method));
        recvObjs.retainAll(allocators.keySet());

        for (Obj recvObj : recvObjs) {
            ret.put(recvObj, allocators.get(recvObj));
        }

        return ret;
    }

    public Set<JMethod> bfsReachable(JMethod start, boolean isCaller, boolean onlyStatic){
        var callGraph = pta.getCallGraph();
        Set<JMethod> ret = Sets.newSet();
        Queue<JMethod> queue = new ArrayDeque<>();
        queue.add(start);
        while(!queue.isEmpty()){
            JMethod current = queue.poll();
            Set<JMethod> neighbors = isCaller ?
                    callGraph.getCallersOf(current).stream().map(Invoke::getContainer).collect(Collectors.toSet()) :
                    callGraph.getCalleesOfM(current);
            for(JMethod neighbor: neighbors){
                if(onlyStatic && !neighbor.isStatic()){
                    continue;
                }
                if(!ret.contains(neighbor)){
                    ret.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return ret;
    }
}
