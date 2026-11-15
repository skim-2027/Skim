package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.NodeManager;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.cs.element.*;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.Var;
import pascal.taie.util.Indexer;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.Set;

public class ExtraInfoTracker extends NodeManager implements Indexer<Node> {
    private final ExtraFlowGraph extraFlowGraph = new ExtraFlowGraph(this);
    private final ExtraFlowGraph extraObjFlowGraph = new ExtraFlowGraph(this);
    private final CallDetails callDetails = new CallDetails();

    private final FieldRecorder fieldRecorder = new FieldRecorder();

    private final Set<VarNode> fieldBaseVars = Sets.newSet();
    private final MultiMap<Var, Obj> varToNewObj = Maps.newMultiMap();
    private final Set<VarNode> thisVars = Sets.newSet();

    public Set<Obj> getNewObj(Var var) {
        return varToNewObj.get(var);
    }

    public void recordThisVar(CSVar csThis){
        thisVars.add(getVarNode(csThis.getVar()));
    }

    public Set<VarNode> getThisVars() {
        return thisVars;
    }

    public FieldRecorder getFieldRecorder() {
        return fieldRecorder;
    }

    public void trackFlowEdge(Pointer srcPtr, Pointer tgtPtr, FlowKind flowKind, AbstractField abstractField) {
        Node src =  toNode(srcPtr);
        Node tgt =  toNode(tgtPtr);
        extraFlowGraph.addEdge(src, tgt, flowKind, abstractField);

        if(flowKind.equals(FlowKind.FIELD_LOAD)){
            fieldBaseVars.add((VarNode)src);
            if (srcPtr instanceof CSVar csBase && tgtPtr instanceof CSVar csTo) {
                fieldRecorder.putLoad(csBase.getVar(), abstractField, csTo.getVar());
            } else {
                throw new RuntimeException("should be two csVars");
            }
        }else if(flowKind.equals(FlowKind.FIELD_STORE)){
            fieldBaseVars.add((VarNode) tgt);
            if (srcPtr instanceof CSVar csFrom && tgtPtr instanceof CSVar csBase) {
                fieldRecorder.putStore(csBase.getVar(), abstractField, csFrom.getVar());
            } else {
                throw new RuntimeException("should be two csVars");
            }
        }
    }

    public void trackObjEdge(Pointer srcPtr, Pointer tgtPtr, FlowKind flowKind, AbstractField abstractField) {
        Node src = toNode(srcPtr);
        Node tgt = toNode(tgtPtr);
        extraObjFlowGraph.addEdge(src, tgt, flowKind, abstractField);
    }

    public void trackRecvVar(Var fromVar, Var toVar, Var recvVar){
        callDetails.putRecvVar(toVar, fromVar, recvVar);
    }

    public ExtraFlowGraph getExtraFlowGraph() {
        return extraFlowGraph;
    }

    public ExtraFlowGraph getExtraObjFlowGraph() {
        return extraObjFlowGraph;
    }

    public CallDetails getCallDetails() {
        return callDetails;
    }

    public void trackVarOfNewObj(Var var, Obj obj) {
        if(obj instanceof NewObj || obj instanceof MockObj)
            varToNewObj.put(var, obj);
    }

    public Set<VarNode> getFieldBaseVars() {
        return fieldBaseVars;
    }


    /**
     * Converts given pointer to a node in this OFG.
     */
    private Node toNode(Pointer pointer) {
        if (pointer instanceof CSVar csVar) {
            return getOrCreateVarNode(csVar.getVar());
        } else if (pointer instanceof InstanceField iField) {
            return getOrCreateInstanceFieldNode(
                    iField.getBase().getObject(), iField.getField());
        } else if (pointer instanceof ArrayIndex arrayIndex) {
            return getOrCreateArrayIndexNode(
                    arrayIndex.getArray().getObject());
        } else {
            return getOrCreateStaticFieldNode(
                    ((StaticField) pointer).getField());
        }
    }
}
