package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.PointerAnalysisResultImpl;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.*;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.DefaultSolver;
import pascal.taie.analysis.pta.core.solver.PointerFlowEdge;
import pascal.taie.analysis.pta.core.solver.Transfer;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;

import java.util.Map;
import java.util.Set;

/**
 * This is basically all the same as DefaultSolver, only tracking extra information during the analysis.
 */
public class ExtraTrackSolver extends DefaultSolver {

    private final Map<Edge<?,?>, CSVar> receiveVarOfCallEdge = Maps.newMap();
    private final ExtraInfoTracker extraInfoTracker;


    public ExtraTrackSolver(AnalysisOptions options, HeapModel heapModel, ContextSelector contextSelector, CSManager csManager) {
        super(options, heapModel, contextSelector, csManager);
        extraInfoTracker = new ExtraInfoTracker();
    }

    @Override
    protected void processInstanceStore(CSVar baseVar, PointsToSet pts) {
        Context context = baseVar.getContext();
        Var var = baseVar.getVar();
        for (StoreField store : var.getStoreFields()) {
            Var fromVar = store.getRValue();
            if (propTypes.isAllowed(fromVar)) {
                CSVar from = csManager.getCSVar(context, fromVar);
                JField field = store.getFieldRef().resolve();
                pts.forEach(baseObj -> {
                    if (baseObj.getObject().isFunctional()) {
                        InstanceField instField = csManager.getInstanceField(baseObj, field);
                        addPFGEdge(from, instField, FlowKind.INSTANCE_STORE);
                    }
                });
                trackEdge(from, baseVar, FlowKind.FIELD_STORE, new FieldWrapper(field));
                trackObjEdge(from, baseVar, FlowKind.FIELD_STORE, new FieldWrapper(field));
            }
        }
    }

    @Override
    protected void processInstanceLoad(CSVar baseVar, PointsToSet pts) {
        Context context = baseVar.getContext();
        Var var = baseVar.getVar();
        for (LoadField load : var.getLoadFields()) {
            Var toVar = load.getLValue();
            if (propTypes.isAllowed(toVar)) {
                CSVar to = csManager.getCSVar(context, toVar);
                JField field = load.getFieldRef().resolve();
                pts.forEach(baseObj -> {
                    if (baseObj.getObject().isFunctional()) {
                        InstanceField instField = csManager.getInstanceField(baseObj, field);
                        addPFGEdge(instField, to, FlowKind.INSTANCE_LOAD);
                    }
                });
                trackEdge(baseVar, to, FlowKind.FIELD_LOAD, new FieldWrapper(field));
                trackObjEdge(baseVar, to, FlowKind.FIELD_LOAD, new FieldWrapper(field));
            }
        }
    }

    @Override
    protected void processArrayStore(CSVar arrayVar, PointsToSet pts) {
        Context context = arrayVar.getContext();
        Var var = arrayVar.getVar();
        for (StoreArray store : var.getStoreArrays()) {
            Var rvalue = store.getRValue();
            if (propTypes.isAllowed(rvalue)) {
                CSVar from = csManager.getCSVar(context, rvalue);
                pts.forEach(array -> {
                    if (array.getObject().isFunctional()) {
                        ArrayIndex arrayIndex = csManager.getArrayIndex(array);
                        // we need type guard for array stores as Java arrays
                        // are covariant
                        addPFGEdge(new PointerFlowEdge(
                                        FlowKind.ARRAY_STORE, from, arrayIndex),
                                arrayIndex.getType());
                    }
                });

                trackEdge(from, arrayVar, FlowKind.FIELD_STORE, ArrIdx.get());
                trackObjEdge(from, arrayVar, FlowKind.FIELD_STORE, ArrIdx.get());
            }
        }
    }

    @Override
    protected void processArrayLoad(CSVar arrayVar, PointsToSet pts) {
        Context context = arrayVar.getContext();
        Var var = arrayVar.getVar();
        for (LoadArray load : var.getLoadArrays()) {
            Var lvalue = load.getLValue();
            if (propTypes.isAllowed(lvalue)) {
                CSVar to = csManager.getCSVar(context, lvalue);
                pts.forEach(array -> {
                    if (array.getObject().isFunctional()) {
                        ArrayIndex arrayIndex = csManager.getArrayIndex(array);
                        addPFGEdge(arrayIndex, to, FlowKind.ARRAY_LOAD);
                    }
                });

                trackEdge(arrayVar, to, FlowKind.FIELD_LOAD, ArrIdx.get());
                trackObjEdge(arrayVar, to, FlowKind.FIELD_LOAD, ArrIdx.get());
            }
        }
    }

    @Override
    protected void processCall(CSVar recv, PointsToSet pts) {
        Context context = recv.getContext();
        Var var = recv.getVar();
        for (Invoke callSite : var.getInvokes()) {
            pts.forEach(recvObj -> {
                // resolve callee
                JMethod callee = CallGraphs.resolveCallee(
                        recvObj.getObject().getType(), callSite);
                if (callee != null) {
                    // select context
                    CSCallSite csCallSite = csManager.getCSCallSite(context, callSite);
                    Context calleeContext = contextSelector.selectContext(
                            csCallSite, recvObj, callee);
                    // build call edge
                    CSMethod csCallee = csManager.getCSMethod(calleeContext, callee);
                    Edge<CSCallSite, CSMethod> edge = new Edge<>(CallGraphs.getCallKind(callSite),
                            csCallSite, csCallee);
                    addCallEdge(edge);

                    this.receiveVarOfCallEdge.put(edge, recv);
                    // pass receiver object to *this* variable
                    if (!isIgnored(callee)) {
                        addVarPointsTo(calleeContext, callee.getIR().getThis(),
                                recvObj);
                        // track edges
                        CSVar csThis = csManager.getCSVar(calleeContext, callee.getIR().getThis());
                        trackEdge(recv, csThis, FlowKind.THIS_PASSING);
                        extraInfoTracker.recordThisVar(csThis);
                        boolean isSpecialOrStatic = edge.getKind() == CallKind.SPECIAL;
                        if (isSpecialOrStatic) {
                            trackObjEdge(recv, csThis, FlowKind.LOCAL_ASSIGN);
                        }
                        trackObjEdge(csThis, csThis, FlowKind.PARAMETER_PASSING);
                    }
                } else {
                    plugin.onUnresolvedCall(recvObj, context, callSite);
                }
            });
        }
    }

    @Override
    protected void processCallEdge(Edge<CSCallSite, CSMethod> edge) {
        if (callGraph.addEdge(edge)) {
            // process new call edge
            CSMethod csCallee = edge.getCallee();
            addCSMethod(csCallee);
            CallKind callKind = edge.getKind();
            if (callKind != CallKind.OTHER
                    && !isIgnored(csCallee.getMethod())) {
                boolean isSpecialOrStatic = callKind == CallKind.SPECIAL || callKind == CallKind.STATIC;
                Context callerCtx = edge.getCallSite().getContext();
                Invoke callSite = edge.getCallSite().getCallSite();
                Context calleeCtx = csCallee.getContext();
                JMethod callee = csCallee.getMethod();
                InvokeExp invokeExp = callSite.getInvokeExp();

                CSVar csRecvVar = receiveVarOfCallEdge.get(edge);
                // pass arguments to parameters
                for (int i = 0; i < invokeExp.getArgCount(); ++i) {
                    Var arg = invokeExp.getArg(i);
                    if (propTypes.isAllowed(arg)) {
                        Var param = callee.getIR().getParam(i);
                        CSVar argVar = csManager.getCSVar(callerCtx, arg);
                        CSVar paramVar = csManager.getCSVar(calleeCtx, param);
                        addPFGEdge(argVar, paramVar, FlowKind.PARAMETER_PASSING);

                        trackObjEdge(paramVar, paramVar, FlowKind.PARAMETER_PASSING);

                        if (isSpecialOrStatic) {
                            trackObjEdge(argVar, paramVar, FlowKind.LOCAL_ASSIGN);
                        }

                        if(csRecvVar != null) {
                            trackEdge(argVar, csRecvVar, FlowKind.CALL_STORE);
                            trackRecvVarOfParamPassing(arg, param, csRecvVar.getVar());
                            trackObjEdge(argVar, csRecvVar, FlowKind.CALL_STORE);
                        }
                    }
                }
                // pass results to LHS variable
                Var lhs = callSite.getResult();
                if (lhs != null && propTypes.isAllowed(lhs)) {
                    CSVar csLHS = csManager.getCSVar(callerCtx, lhs);
                    for (Var ret : callee.getIR().getReturnVars()) {
                        if (propTypes.isAllowed(ret)) {
                            CSVar csRet = csManager.getCSVar(calleeCtx, ret);
                            addPFGEdge(csRet, csLHS, FlowKind.RETURN);
                            if(csRecvVar != null) {
                                trackRecvVarOfReturn(lhs, ret, csRecvVar.getVar());
                                trackEdge(csRecvVar, csLHS, FlowKind.CALL_LOAD);
                                trackObjEdge(csRecvVar, csLHS, FlowKind.CALL_LOAD);
                            }

                            if (isSpecialOrStatic) {
                                trackObjEdge(csRet, csLHS, FlowKind.LOCAL_ASSIGN);
                            }
                            trackObjEdge(csRet, csRet, FlowKind.RETURN);
                        }
                    }
                }
            }
            plugin.onNewCallEdge(edge);
        }
    }


    Set<FlowKind> objEdgesToTrack = Set.of(FlowKind.LOCAL_ASSIGN, FlowKind.CAST);
    @Override
    public void addPFGEdge(PointerFlowEdge edge, Transfer transfer) {
        edge = pointerFlowGraph.addEdge(edge);
        if (edge != null && edge.addTransfer(transfer)) {

            // track edge
            trackEdge(edge.source(), edge.target(), edge.kind());
            if (objEdgesToTrack.contains(edge.kind())) {
                trackObjEdge(edge.source(), edge.target(), FlowKind.LOCAL_ASSIGN);
            }

            PointsToSet targetSet = transfer.apply(
                    edge, getPointsToSetOf(edge.source()));
            if (!targetSet.isEmpty()) {
                addPointsTo(edge.target(), targetSet);
            }
        }
    }


    private void trackRecvVarOfParamPassing(Var fromVar, Var toVar, Var recvVar){
        extraInfoTracker.trackRecvVar(fromVar, toVar, recvVar);
    }

    private void trackRecvVarOfReturn(Var toVar, Var fromVar, Var recvVar){
        extraInfoTracker.trackRecvVar(fromVar, toVar, recvVar);
    }

    private void trackEdge(Pointer src, Pointer dest, FlowKind flowKind) {
        extraInfoTracker.trackFlowEdge(src, dest, flowKind, null);
    }

    private void trackEdge(Pointer src, Pointer dest, FlowKind flowKind, AbstractField abstractField) {
        extraInfoTracker.trackFlowEdge(src, dest, flowKind, abstractField);
    }

    private void trackObjEdge(Pointer src, Pointer dest, FlowKind flowKind) {
        extraInfoTracker.trackObjEdge(src, dest, flowKind, null);
    }

    private void trackObjEdge(Pointer src, Pointer dest, FlowKind flowKind, AbstractField abstractField) {
        extraInfoTracker.trackObjEdge(src, dest, flowKind, abstractField);
    }

    @Override
    protected void trackNew(Obj obj, CSVar var) {
        extraInfoTracker.trackVarOfNewObj(var.getVar(), obj);
        trackObjEdge(var, var, FlowKind.NEW);
    }

    @Override
    public PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(
                    propTypes, csManager, heapModel,
                    callGraph, pointerFlowGraph, extraInfoTracker);
        }
        return result;
    }
}
