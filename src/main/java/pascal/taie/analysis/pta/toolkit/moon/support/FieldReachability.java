package pascal.taie.analysis.pta.toolkit.moon.support;


import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraFlowGraph;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

public class FieldReachability {


    protected final MultiMap<AbstractField, Var> fieldToInParams = Maps.newConcurrentMultiMap();
    protected final MultiMap<AbstractField, Var> fieldToOutParams = Maps.newConcurrentMultiMap();


    protected final TwoKeyMultiMap<Obj, AbstractField, VarNode> objToNonThisFieldStore = Maps.newConcurrentTwoKeyMultiMap();
    protected final TwoKeyMultiMap<Obj, AbstractField, VarNode> objToNonThisFieldLoad = Maps.newConcurrentTwoKeyMultiMap();
    protected final Set<AbstractField> hasNonThisFieldLoad = Sets.newConcurrentSet();
    protected final MultiMap<Obj, Var> objToArgOfInvokeMethods = Maps.newConcurrentMultiMap();
    private final ExtraFlowGraph VFGForHeap;
    private final MoonInfra moonInfra;
    public FieldReachability(MoonInfra moonInfra){
//        this.pta = pta;
//        this.fieldRecorder = fieldRecorder;
//        this.VFGForHeap = VFGForHeap;
//        this.objToInvokeMethods = objToInvokeMethods;
        this.moonInfra = moonInfra;
        this.VFGForHeap = moonInfra.extraInfoTracker.getExtraObjFlowGraph();
    }

    public void build(KeyTypeCollector openTypeCollector){

//        System.err.println("TODO: parallelStream() for field reachability");
            moonInfra.extraInfoTracker.getFieldRecorder().allFields(openTypeCollector::isConcernedType)
                     .parallelStream()
                    .forEach(field -> {
                        boolean needInCheck = false;
                        Set<VarNode> retOrParams = traversal( field, false);
                        if (!retOrParams.isEmpty()) {
                            needInCheck = true;
                            for (VarNode varNode : retOrParams) {
                                fieldToOutParams.put(field, varNode.getVar());
                            }
                        }
                        needInCheck |= hasNonThisFieldLoad.contains(field);
                        if(needInCheck){
                            Set<VarNode> paramsOrThis = traversal( field, true);
                            if (!paramsOrThis.isEmpty()) {
                                for (VarNode vn: paramsOrThis) {
                                    fieldToInParams.put(field, vn.getVar());
                                }
                            }
                        }
                    });

            var pta = moonInfra.getPta();
            for (Obj obj : pta.getObjects()) {
                Set<JMethod> invokeMethods  = moonInfra.getMethodsInvokedOn(obj);
                invokeMethods.forEach(m -> {
                    for (Var param : m.getIR().getParams()) {
                        if (!pta.getPointsToSet(param).isEmpty()) {
                            objToArgOfInvokeMethods.put(obj, param);
                        }
                    }
                    Var aThis = m.getIR().getThis();
                    if (aThis != null)
                        objToArgOfInvokeMethods.put(obj, aThis);
                });
            }

    }


    private boolean hasInflow(Obj heap, AbstractField field){
        if(objToNonThisFieldStore.containsKey(heap, field)) return true;
        return !Collections.disjoint(fieldToInParams.get(field), objToArgOfInvokeMethods.get(heap));
    }

    private boolean hasOutflow(Obj heap, AbstractField field) {
        if (objToNonThisFieldLoad.containsKey(heap, field)) return true;
        Set<JMethod> invokedMethods = moonInfra.getMethodsInvokedOn(heap);
        return fieldToOutParams.get(field).stream().anyMatch(v -> invokedMethods.contains(v.getMethod()));
    }

    public boolean isConnceredField(Obj heap, AbstractField field){
        return hasInflow(heap, field) && hasOutflow(heap, field);
    }

    private Set<VarNode> traversal(AbstractField field, boolean isInflow) {
        Set<VarNode> ret = new HashSet<>();
        MultiMap<Traversal, Node> state2nodes = Maps.newMultiMap();
        Stack<Pair<Node, Traversal>> stack = new Stack<>();

        Set<JMethod> inMethods = moonInfra.extraInfoTracker.getFieldRecorder().fieldToBaseObjs.get(field).stream().map(moonInfra::getMethodsInvokedOn).flatMap(Set::stream).collect(Collectors.toSet());

        moonInfra.extraInfoTracker.getThisVars().stream().filter(thisVar -> {
            JMethod method = thisVar.getVar().getMethod();
            return inMethods.contains(method);
        }).forEach(thisVar -> stack.push(new Pair<>(thisVar, Traversal.ThisAlias)));

        while (!stack.isEmpty()) {
            Pair<Node, Traversal> front = stack.pop();
            if (front.second() == Traversal.Finish) {
                if (front.first() instanceof VarNode lvn) {
                    ret.add(lvn);
                }
            }
            state2nodes.put(front.second(), front.first());
            Set<Pair<Node, Traversal>> nexts = move(front, field, isInflow);
            for (Pair<Node, Traversal> nodeState : nexts) {
                if (!state2nodes.get(nodeState.second()).contains(nodeState.first())) {
                    stack.add(nodeState);
                }
            }
        }
        return ret;
    }


    private Set<Pair<Node, Traversal>> move(Pair<Node, Traversal> nodeState, AbstractField field, boolean in) {
        Node node = nodeState.first();
        Traversal state = nodeState.second();
        Set<Pair<Node, Traversal>> ret = new HashSet<>();

        for (ExtraFlowGraph.ExtraFlowEdge outEdge : VFGForHeap.getOutEdgesOf(node)) {
            boolean metTargetField = false;
            AbstractField fieldOnEdge = outEdge.field();
            if(fieldOnEdge != null){
                metTargetField = fieldOnEdge.equals(field);
            }

            Traversal next;
            if (in) {
                next = moveForIn(state, outEdge.flowKind(), metTargetField, true);
            } else {
                next = moveForOut(state, outEdge.flowKind(), metTargetField, true);
            }

            if (next != Traversal.Undef){
                ret.add(new Pair<>(outEdge.target(), next));
            }

        }

        for (ExtraFlowGraph.ExtraFlowEdge inEdge : VFGForHeap.getInEdgesOf(node)) {

            boolean metTargetField = false;
            AbstractField fieldOnEdge = inEdge.field();
            if(fieldOnEdge != null){
                metTargetField = fieldOnEdge.equals(field);
            }
            Traversal nextState;
            if (in) {
                nextState = moveForIn(state, inEdge.flowKind(), metTargetField, false);
            } else {
                nextState = moveForOut(state, inEdge.flowKind(), metTargetField, false);
            }
            if (nextState != Traversal.Undef){
                ret.add(new Pair<>(inEdge.source(), nextState));
            }

        }

        return ret;
    }

    private Traversal moveForOut(Traversal currState, FlowKind kind, boolean fieldMatch, boolean isForward) {
        switch (currState) {
//            case THIS -> {
//                if(!isForward && kind == FlowKind.THIS_CONNECT){
//                    return Traversal.ThisAlias;
//                }
//            }
            case ThisAlias -> {
                if(isForward) {
                    if (kind == FlowKind.LOCAL_ASSIGN) {
                        return Traversal.ThisAlias;
                    } else if (kind == FlowKind.FIELD_LOAD) {
                        if (fieldMatch) {
                            return Traversal.DirectVar;
                        }
                    }
                }
            }
            case DirectVar -> {
                if(isForward){
                    if(kind == FlowKind.LOCAL_ASSIGN || kind == FlowKind.FIELD_LOAD || kind == FlowKind.CALL_LOAD ){
                        return Traversal.DirectVar;
                    }else if(kind == FlowKind.RETURN) {
                        return Traversal.Finish;
                    }else if(kind == FlowKind.FIELD_STORE || kind == FlowKind.CALL_STORE){
                        return Traversal.StoredInVar;
                    }
                }else{
                    if(kind == FlowKind.FIELD_STORE || kind == FlowKind.CALL_STORE ){
                        return Traversal.StoredInVar;
                    }
                }
            }
            case StoredInVar -> {
                if(!isForward){
                    if(kind == FlowKind.LOCAL_ASSIGN || kind == FlowKind.FIELD_LOAD || kind == FlowKind.CALL_LOAD){
                        return Traversal.StoredInVar;
                    }else if(kind == FlowKind.NEW) {
                        return Traversal.Allocation;
                    }
                }else{
                    if(kind == FlowKind.PARAMETER_PASSING){
                        return Traversal.Finish;
                    }
                }
            }
            case Allocation -> {
                if(isForward && kind == FlowKind.NEW){
                    return Traversal.DirectVar;
                }
            }
        }
        return Traversal.Undef;
    }

    private Traversal moveForIn(Traversal currState, FlowKind kind, boolean fieldMatch, boolean isForward) {
        switch (currState) {
            case Allocation -> {
                if(isForward && kind == FlowKind.NEW){
                    return Traversal.DirectVar;
                }
            }

//            case THIS -> {
//                if(!isForward && kind == FlowKind.THIS_CONNECT){
//                    return Traversal.ThisAlias;
//                }
//            }
            case DirectVar -> {
                if(isForward){
                    if(kind == FlowKind.LOCAL_ASSIGN || kind == FlowKind.FIELD_LOAD || kind == FlowKind.CALL_LOAD ){
                        return Traversal.DirectVar;
                    }else if(kind == FlowKind.FIELD_STORE || kind == FlowKind.CALL_STORE) {
                        return Traversal.StoredInVar;
                    }
                }else{
                    if(kind == FlowKind.FIELD_STORE || kind == FlowKind.CALL_STORE){
                        return Traversal.StoredInVar;
                    }
                }
            }
            case StoredInVar -> {

                if(!isForward){
                    if(kind == FlowKind.LOCAL_ASSIGN || kind == FlowKind.FIELD_LOAD || kind == FlowKind.CALL_LOAD){
                        return Traversal.StoredInVar;
                    }else if(kind == FlowKind.NEW){
                        return Traversal.Allocation;
                    }
                }else{
                    if(kind == FlowKind.PARAMETER_PASSING){
                        return Traversal.Finish;
                    }
                }
            }
            case ThisAlias -> {

                if(isForward){
                    if(kind == FlowKind.LOCAL_ASSIGN){
                        return Traversal.ThisAlias;
                    }else if(kind == FlowKind.FIELD_LOAD){
                        if(fieldMatch){
                            return Traversal.DirectVar;
                        }
                    }
                }else{
                    if(kind == FlowKind.FIELD_STORE){
                        if(fieldMatch) return Traversal.StoredInVar;
                    }
                }
            }
        }
        return Traversal.Undef;
    }


    private enum Traversal {
        DirectVar, StoredInVar, Allocation, ThisAlias, THIS, Finish, Undef
    }

}
