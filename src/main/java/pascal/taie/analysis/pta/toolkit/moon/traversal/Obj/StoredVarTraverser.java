package pascal.taie.analysis.pta.toolkit.moon.traversal.Obj;



import pascal.taie.analysis.graph.flowgraph.*;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.*;
import pascal.taie.analysis.pta.toolkit.moon.support.MoonInfra;
import pascal.taie.analysis.pta.toolkit.moon.traversal.TravHelper;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.graph.Graph;

import java.util.*;
import java.util.stream.Collectors;

public class StoredVarTraverser {

    private final ExtraFlowGraph VFGForField;
    private final CallDetails methodCallDetail;
    private final Graph<Obj> oag;
    private final int maxCtxLayer;
    private final ExtraInfoTracker extraInfoTracker;
    private final TravHelper helper;
    private final MoonInfra moonInfra;
    public StoredVarTraverser(MoonInfra moonInfra, int maxCtxLayer, TravHelper helper) {
        this.moonInfra = moonInfra;
        this.maxCtxLayer = maxCtxLayer;
        this.extraInfoTracker = moonInfra.getPta().getExtraInfoTracker();
        this.methodCallDetail = moonInfra.getCallDetails();
        this.VFGForField = moonInfra.getExtraFlowGraph();
        this.oag = moonInfra.getOag();
        this.helper = helper;
    }
    public STraversalResult findSourceOfVarStoredIn(Obj obj, VarNode varStored, int maxAllocLayerToTrace, AbstractField __storedField) {
        JMethod allocatedMethod = obj.getContainerMethod().get();
        Deque<STraversalStatus> stack = new ArrayDeque<>(); // dfs
        Set<STraversalStatus> visited = Sets.newSet();
        STraversalResult traversalResult = new STraversalResult(maxAllocLayerToTrace, __storedField, varStored);
        traversalResult.addVisitedMethod(varStored.getVar().getMethod());
        int maxAllocatorLayer = getMinAllocLayer(varStored, obj) - 1;
        if (maxAllocatorLayer == -1 - 1) return traversalResult; // -1 means var not in methods of the ctx obj tree.
        stack.push(new STraversalStatus(varStored, maxAllocatorLayer, 0));


        if(maxCtxLayer == 1 && obj.getType().toString().contains("javax.swing.event.EventListenerList")){
            // This is a special case for EventListenerList, since it cannot be precisely handled by the results of a context-insensitive pointer analysis.
            traversalResult.addMatchedCtxObjsOfParam(oag.getPredsOf(obj), 1, varStored.getVar(), Set.of(varStored), true);
        }
        while (!stack.isEmpty()) {
            STraversalStatus crtTrace = stack.pop();
            if (visited.contains(crtTrace)) continue;
            visited.add(crtTrace);
            Node crtPtr = crtTrace.pointer;
            int crtCallStackLevel = crtTrace.callStackLevel;
            if (crtPtr == null || crtCallStackLevel > 10) continue;
            if (helper.reachingPTS(crtPtr).isEmpty()) continue;
            int crtAllocatorLevel = crtTrace.allocatorLevel;
            if(crtPtr instanceof VarNode varNode && varNode.getVar().getMethod().equals(allocatedMethod)){
                traversalResult.hasMetParamOfAllocatedMethod();
            }
            if (crtPtr instanceof VarNode varNode && !extraInfoTracker.getNewObj(varNode.getVar()).isEmpty()) {
                Set<Obj> pts = extraInfoTracker.getNewObj(varNode.getVar());

                for (Obj newObj : pts) {
                    int allocatorLayer = -1;
                    if(newObj.equals(obj) || oag.getSuccsOf(obj).contains(newObj)){
                        allocatorLayer = 0;
                    }else{
                        Map<Obj, Integer> ctxObjs = helper.getCtxObjsOf(obj);
                        for (Obj allocator : ctxObjs.keySet()) {
                            if(oag.getSuccsOf(allocator).contains(newObj)){
                                allocatorLayer = ctxObjs.get(allocator);
                                break;
                            }
                        }
                    }
                    if(allocatorLayer != -1){
                        traversalResult.addNewlyAllocObj(newObj, allocatorLayer);
                    }
                }
                continue;
            }

            Set<Node> predOfParamPassing = Sets.newSet();
            for (ExtraFlowGraph.ExtraFlowEdge inEdge : VFGForField.getInEdgesOf(crtPtr)) {
                FlowKind flowKind = inEdge.flowKind();
                Node predNode = inEdge.source();
                switch (flowKind) {
                    case INSTANCE_LOAD, CALL_STORE, FIELD_STORE, ARRAY_LOAD -> {
                    }
                    case STATIC_LOAD, STATIC_STORE -> {
                        STraversalStatus newPtrTrace = new STraversalStatus(predNode, crtAllocatorLevel, crtCallStackLevel, crtTrace, flowKind);
                        if (!visited.contains(newPtrTrace)) {
                            stack.push(newPtrTrace);
                            if(predNode instanceof VarNode varNode){
                                traversalResult.addVisitedMethod(varNode.getVar().getMethod());
                            }
                        }
                    }
                    case RETURN -> {
                        if(predNode instanceof VarNode localVarNode && localVarNode.getVar().getMethod().isStatic()){
                            STraversalStatus newPtrTrace = new STraversalStatus(predNode, crtAllocatorLevel, crtCallStackLevel, crtPtr, crtTrace, FlowKind.RETURN);
                            if (!visited.contains(newPtrTrace)) {
                                stack.push(newPtrTrace);
                                traversalResult.addVisitedMethod(localVarNode.getVar().getMethod());
                            }
                        }
                    }
                    case THIS_PASSING -> {
                        JMethod inMethod = ((VarNode)crtPtr).getVar().getMethod();
                        List<Set<Obj>> ctxObjs = helper.collectCtxObjCalledOn(inMethod, obj);
                        for (int allocatorLayer = 0; allocatorLayer < ctxObjs.size(); allocatorLayer++) {
                            Set<Obj> ctxObjsOfLayer = ctxObjs.get(allocatorLayer);
                            if (ctxObjsOfLayer.isEmpty()) continue;
                            if (allocatorLayer == maxAllocLayerToTrace) {
                                // match finished.

                                    traversalResult.addMatchedCtxObjsByThisAsParam(ctxObjsOfLayer, allocatorLayer);


                            } else {
                                if (allocatorLayer > 0 && allocatorLayer >= crtAllocatorLevel) {

                                    traversalResult.addMatchedCtxObjsByThisAsParam(ctxObjsOfLayer, allocatorLayer);

                                }
                            }
                        }
                    }

                    case CALL_LOAD -> {
                        Var predVar = ((VarNode) predNode).getVar();
                        if (helper.checkIfThis(predVar)) {
                            Set<Node> returnVars = getReturnVarOfThisMethodCall(predNode, crtPtr);
                            for (Node varReturnFrom : returnVars) {
                                STraversalStatus newPtrTrace = new STraversalStatus(varReturnFrom, crtAllocatorLevel, crtCallStackLevel, crtTrace, FlowKind.RETURN);
                                if (!visited.contains(newPtrTrace)) {
                                    stack.push(newPtrTrace);
                                }
                            }

                        } else {
                            if (hasSpecComplexFlow(crtTrace, FlowKind.CALL_LOAD)) continue;
                            STraversalStatus newPtrTrace = new STraversalStatus(predNode, crtAllocatorLevel, crtCallStackLevel, crtTrace, FlowKind.CALL_LOAD);
                            if (!visited.contains(newPtrTrace)) {
                                stack.push(newPtrTrace);
                            }
                        }

                    }
                    case FIELD_LOAD -> {
                        Var predVar = ((VarNode) predNode).getVar();
                        if (helper.checkIfThis(predVar)) {

                            AbstractField __labeledField = inEdge.field();
                            if(__labeledField instanceof FieldWrapper labeledField && __storedField instanceof JField storedField){
                                if(labeledField.field().getDeclaringClass().equals(storedField.getDeclaringClass()))
                                    continue;
                            }

                            Set<Node> fieldStoredVar = getFieldStoredVar(predNode, crtPtr);
                            for (Node pointer : fieldStoredVar) {
                                STraversalStatus newPtrTrace = new STraversalStatus(pointer, crtAllocatorLevel, crtCallStackLevel, crtTrace, FlowKind.LOCAL_ASSIGN);
                                newPtrTrace.needCheckFieldRelationWhenMeetNew = false;
                                if (!visited.contains(newPtrTrace)) {
                                    stack.push(newPtrTrace);
                                }
                            }

                        } else {
                            if (hasSpecComplexFlow(crtTrace, FlowKind.FIELD_LOAD)) continue;
                            STraversalStatus newPtrTrace = new STraversalStatus(predNode, crtAllocatorLevel, crtCallStackLevel, crtTrace, FlowKind.FIELD_LOAD);
                            if (!visited.contains(newPtrTrace)) {
                                stack.push(newPtrTrace);
                            }
                        }

                    }
                    case LOCAL_ASSIGN, CAST -> {
                        STraversalStatus newPtrTrace = new STraversalStatus(predNode, crtAllocatorLevel, crtCallStackLevel, crtTrace, FlowKind.LOCAL_ASSIGN);
                        if (!visited.contains(newPtrTrace)) {
                            stack.push(newPtrTrace);
                        }
                    }

                    case NEW ->  {
                    }

                    case PARAMETER_PASSING ->  {
                        // leave for below.
                        predOfParamPassing.add(predNode);
                    }
                    default -> {
                        throw new RuntimeException("Unexpected flowKind: " + flowKind);
                    }
                }
            }

            if(!predOfParamPassing.isEmpty()) {
                JMethod crtInMethod = getMethodOfPointer(crtPtr);
                if (crtInMethod.isStatic()) {
                    // in static method.
                    Set<Obj> layerOneCtxObjs = oag.getPredsOf(obj);
                    if (crtInMethod.equals(obj.getContainerMethod().get())) {
                        // in static allocated method of obj.
                        int objCtxSize = layerOneCtxObjs.size();
                        if (moonInfra.hasEmptyCtx(obj)) objCtxSize ++;
                        if (objCtxSize > 1 && 1 >= crtAllocatorLevel) {
                                    traversalResult.addMatchedCtxObjsOfParam(layerOneCtxObjs, 1, ((VarNode) crtPtr).getVar(), predOfParamPassing, true);
                        }
                    }else if(maxAllocLayerToTrace >= 2 && crtAllocatorLevel >= 1){
                        // in another static method.


                        if(ctxUnderLimited(obj, layerOneCtxObjs)){
                            JMethod inMethod = getMethodOfPointer(crtPtr);
                            List<Set<Obj>> ctxObjs = helper.collectCtxObjCalledOn(inMethod, obj);
                            if(ctxObjs.size() >= 3){
                                Set<Obj> layerTwoCtxObjs = ctxObjs.get(2);
                                traversalResult.addMatchedCtxObjsOfParam(layerTwoCtxObjs, 2, ((VarNode) crtPtr).getVar(), predOfParamPassing, true);
                            }
                        }
                    }

                    JMethod callerOfStatic = crtTrace.getStaticCallStackTop();
                    for (Node pred : predOfParamPassing) { // static
                        if (pred instanceof VarNode && crtPtr instanceof VarNode) {
                            if (callerOfStatic != null && !callerOfStatic.equals(getMethodOfPointer(pred)))
                                continue; // skip suspicious caller of static method.
                            if (getMethodOfPointer(crtPtr).equals(getMethodOfPointer(pred)))
                                continue; // skip static recursive call.
                            STraversalStatus newPtrTrace = new STraversalStatus(pred, crtAllocatorLevel, crtCallStackLevel, null, crtTrace, FlowKind.PARAMETER_PASSING);
                            traversalResult.addVisitedMethod(getMethodOfPointer(pred));
                            if (!visited.contains(newPtrTrace)) {
                                stack.push(newPtrTrace);
                            }
                        }

                    }

                    // end of static methods.
                }
                else {
                    // non-static
                    if (predOfParamPassing.stream().allMatch(pred -> checkIfThisCallOnArg(pred, crtPtr))) {
                        // all called by this variable.
                        for (Node pred : predOfParamPassing) {
                            if (falseParamPassingFlow(crtPtr, pred, obj)) continue;
                            List<Set<Obj>> ctxObjs = helper.collectCtxObjCalledOn(getMethodOfPointer(pred), obj);
                            if(ctxObjs.stream().anyMatch(s -> !s.isEmpty())){
                                STraversalStatus newPtrTrace = new STraversalStatus(pred, crtAllocatorLevel, crtCallStackLevel, crtTrace, FlowKind.LOCAL_ASSIGN);
                                if (!visited.contains(newPtrTrace)) {
                                    stack.push(newPtrTrace);
                                    traversalResult.addVisitedMethod(getMethodOfPointer(pred));
                                }
                            }
                        }
                    }
                    else {

                        List<Set<Obj>> ctxObjs = helper.collectCtxObjCalledOn(getMethodOfPointer(crtPtr), obj);
                        for (int allocatorLayer = 0; allocatorLayer < ctxObjs.size(); allocatorLayer++) {
                            Set<Obj> ctxObjsOfLayer = ctxObjs.get(allocatorLayer);
                            if (ctxObjsOfLayer.isEmpty()) continue;
                            if (allocatorLayer >= maxAllocLayerToTrace) {
                                // match finished.
                                traversalResult.addMatchedCtxObjsOfParam(ctxObjsOfLayer, allocatorLayer, ((VarNode) crtPtr).getVar(), predOfParamPassing, false);
                                break;
                            }
                            else {
                                boolean toBreak = false;
                                if (allocatorLayer >= crtAllocatorLevel) {
                                    traversalResult.addMatchedCtxObjsOfParam(ctxObjsOfLayer, allocatorLayer, ((VarNode) crtPtr).getVar(), predOfParamPassing, false);
                                    toBreak = true;

                                }
                                // continue match more layer.
                                for (Node pred : predOfParamPassing) {
                                    if (falseParamPassingFlow(crtPtr, pred, ctxObjsOfLayer)) continue;
                                    STraversalStatus newPtrTrace = new STraversalStatus(pred, allocatorLayer, crtCallStackLevel + 1, crtTrace, FlowKind.PARAMETER_PASSING);
                                    if (!visited.contains(newPtrTrace)) {
                                        stack.push(newPtrTrace);
                                        traversalResult.addVisitedMethod(getMethodOfPointer(pred));
                                    }
                                }
                                if (toBreak) break;
                            }
                        }

                    }
                }
            }
        }

        return traversalResult;
    }

    private boolean ctxUnderLimited(Obj obj, Set<Obj> layerOneCtxObjs) {
        return layerOneCtxObjs.stream().mapToInt(o -> oag.getPredsOf(o).size()).sum() < 300;
    }

    private boolean checkIfThisCallOnArg(Node arg, Node param) {
        if (arg instanceof VarNode argVarNode && param instanceof VarNode paramVarNode) {
            Var argVar = argVarNode.getVar();
            Var paramVar = paramVarNode.getVar();
            Set<Var> recvVars = methodCallDetail.getRecvVar(paramVar, argVar);
            return recvVars.stream().allMatch(helper::checkIfThis);
        }
        return false;
    }





    private int getMinAllocLayer(Node crtPtr, Obj obj) {
        List<Set<Obj>> sets = helper.collectCtxObjCalledOn(getMethodOfPointer(crtPtr), obj);
        for (int i = 0; i < sets.size(); i++) {
            if (!sets.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }







    private boolean hasSpecComplexFlow(STraversalStatus crtTrace, FlowKind flowKind) {
        switch (flowKind) {
            case CALL_LOAD -> {
                int callLoadFreq = crtTrace.getVisitedFlowCounter(FlowKind.CALL_LOAD);
                int wrappedFlowFreq = crtTrace.getVisitedFlowCounter(FlowKind.FIELD_STORE);
                int unwrappedFlowFreq = crtTrace.getVisitedFlowCounter(FlowKind.FIELD_LOAD);
                return callLoadFreq + wrappedFlowFreq + unwrappedFlowFreq > 2;
            }
            case FIELD_LOAD -> {

            }
        }
        return false;
    }

    private Set<Node> getReturnVarOfThisMethodCall(Node predAkaThis, Node crtPtr) {
        Set<Obj> thisPts = helper.reachingPTS(predAkaThis);
        Set<Node> preds = Sets.newSet();
        for (ExtraFlowGraph.ExtraFlowEdge inEdge : VFGForField.getInEdgesOf(crtPtr)) {
            if(inEdge.flowKind().equals(FlowKind.RETURN)){
                JMethod inMethod = getMethodOfPointer(inEdge.source());
                for (Obj thisObj : thisPts) {
                    if (moonInfra.getMethodsInvokedOn(thisObj).contains(inMethod)) {
                        preds.add(inEdge.source());
                        break;
                    }
                }
            }
        }
        return preds;
    }

    private Set<Node> getFieldStoredVar(Node pred, Node crtPtr) {
        Set<Node> ptrStoredIn = Sets.newSet();
        Set<Obj> thisPts = helper.reachingPTS(pred);
        Obj thisObj;

        for (ExtraFlowGraph.ExtraFlowEdge edge : VFGForField.getInEdgesOf(crtPtr)) {
            if(edge.flowKind().equals(FlowKind.INSTANCE_LOAD) || edge.flowKind().equals(FlowKind.ARRAY_LOAD)){
                Node source = edge.source();

                Obj baseObj = null;
                if (source instanceof InstanceFieldNode instanceField) {
                    baseObj = instanceField.getBase();
                } else if (source instanceof ArrayIndexNode arrayIndexNode) {
                    baseObj = arrayIndexNode.getBase();
                } else {
                    throw new RuntimeException("Unexpected node type: " + source.getClass().getName());
                }


                if (thisPts.contains(baseObj) && !VFGForField.getPredsOf(source).isEmpty()) {
                    thisObj = baseObj;


                    for (ExtraFlowGraph.ExtraFlowEdge inEdge : VFGForField.getInEdgesOf(source)) {
                        JMethod inMethod = getMethodOfPointer(inEdge.source());
                        if (moonInfra.getMethodsInvokedOn(thisObj).contains(inMethod)) {
                            ptrStoredIn.add(inEdge.source());
                        }
                    }
                }
            }
        }
        return ptrStoredIn;
    }




    private boolean falseParamPassingFlow(Node current, Node pred, Set<Obj> objs) {
        if (pred instanceof VarNode predVn && current instanceof VarNode currentVn) {
            Var predVar = predVn.getVar();
            Var currentVar = currentVn.getVar();
            Set<Var> recvVars = methodCallDetail.getRecvVar(currentVar, predVar);
            var pta = moonInfra.getPta();
            return recvVars.stream().noneMatch(v -> {
                Set<Obj> pointsToSet = pta.getPointsToSet(v);
                return !Collections.disjoint(objs, pointsToSet);
            });
        }
        return true;
    }
    private boolean falseParamPassingFlow(Node current, Node pred, Obj obj) {
        if (moonInfra.getMethodsInvokedOn(obj).contains(getMethodOfPointer(current))) {
            return falseParamPassingFlow(current, pred, Set.of(obj));
        }else{
            JMethod inMethod = getMethodOfPointer(current);
            if(inMethod.isStatic()){
                return false;
            }
            Var aThis = inMethod.getIR().getThis();
            if (aThis == null) {
                return false;
            }
            return falseParamPassingFlow(current, pred, moonInfra.getPta().getPointsToSet(aThis));
        }
    }


    private JMethod getMethodOfPointer(Node n){
        if(n instanceof VarNode va){
            return va.getVar().getMethod();
        }
        throw new RuntimeException("Unexpected node type: " + n.getClass().getName());
    }

    private enum FieldRelation {
        NONE,
        POINT_TO,
        POINT_FROM,
        SAME
    }
}
