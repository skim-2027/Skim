package pascal.taie.analysis.pta.toolkit.moon.traversal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraFlowGraph;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.FieldRecorder;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.FieldWrapper;
import pascal.taie.analysis.pta.toolkit.moon.support.FieldReachability;
import pascal.taie.analysis.pta.toolkit.moon.support.MoonInfra;
import pascal.taie.analysis.pta.toolkit.moon.traversal.Obj.STraversalResult;
import pascal.taie.analysis.pta.toolkit.moon.traversal.Obj.StoredVarTraverser;
import pascal.taie.analysis.pta.toolkit.skim.traverse.Traversal;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.Monitor;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.graph.Graph;

import java.util.ArrayList;
import java.util.List;

public class VFGTravForObj {
    private static final Logger logger = LoggerFactory.getLogger(VFGTravForObj.class);
    private final int objCtxLen;
    private final MoonInfra moonInfra;
    private final TravHelper helper;
    public VFGTravForObj(int objCtxLen, MoonInfra moonInfra) {
        this.objCtxLen = objCtxLen;
        this.moonInfra = moonInfra;
        this.helper = new TravHelper(moonInfra, objCtxLen);
    }



    public List<MultiMap<Obj, STraversalResult>> traverse(MultiMap<Obj, VarNode> objToBaseVar){
        return Monitor.runAndCount(() -> doTraversalForObj(objToBaseVar), "#VFG Traversal for Object Selection", Level.INFO);
    }




    private List<MultiMap<Obj, STraversalResult>> doTraversalForObj(MultiMap<Obj, VarNode> objToBaseVar){
        List<MultiMap<Obj, STraversalResult>> objToMatchRets = new ArrayList<>(objCtxLen);
        for (int i = 0; i < objCtxLen; i++) {
            objToMatchRets.add(Maps.newConcurrentMultiMap());
        }


        logger.info("Number of container objects after filtering: {}", objToBaseVar.keySet().size());
        FieldRecorder fieldRecorder = moonInfra.getFieldRecorder();
        FieldReachability fieldReachability = moonInfra.getFieldReachability();
        Graph<Obj> oag = moonInfra.getOag();

        StoredVarTraverser storedVarTraverser = new StoredVarTraverser(moonInfra, objCtxLen, helper);


        ExtraFlowGraph extraFlowGraph = moonInfra.getExtraFlowGraph();
        for (Obj baseObj : objToBaseVar.keySet()) {

            for (VarNode var : objToBaseVar.get(baseObj)) {
                extraFlowGraph.getInEdgesOf(var).stream().filter(extraFlowEdge -> {
                    FlowKind kind = extraFlowEdge.flowKind();
                    return kind.equals(FlowKind.FIELD_STORE);
                }).forEach(extraFlowEdge -> {
                    VarNode storedVar = (VarNode) extraFlowEdge.source();
                    AbstractField absF = extraFlowEdge.field();
                    if(isIgnoredField(baseObj, absF) ||
                            !fieldReachability.isConnceredField(baseObj, absF))
                        return;
                    // perform VFG traversal from storedVar
                    int toBeCheckedLen = determineToBeCheckedLen(baseObj, oag);
                    STraversalResult STraversalResult = storedVarTraverser.findSourceOfVarStoredIn(baseObj, storedVar, objCtxLen, absF);
                    objToMatchRets.get(toBeCheckedLen - 1).put(baseObj, STraversalResult);
                });
            }
        }
        return objToMatchRets;
    }

    private int determineToBeCheckedLen(Obj obj, Graph<Obj> oag){
        int toBeCheckedLen;
        if(this.objCtxLen == 1){
            toBeCheckedLen = 1;
        }else if (this.objCtxLen >= 2){
            if(!(obj.getType() instanceof ArrayType) && oag.getPredsOf(obj).size() == 1)
                toBeCheckedLen = 2;
            else
                toBeCheckedLen = 1;
        }else{
            throw new RuntimeException("Unsupported objCtxLen: " + this.objCtxLen);
        }
        return toBeCheckedLen;
    }



    private boolean isIgnoredField(Obj obj, AbstractField field){
        boolean isClassType;

        if(obj.getType() instanceof ArrayType arrayType) {
            isClassType = arrayType.elementType() instanceof ClassType;
        }else{
            Type type = field.getType();
            if(type instanceof PrimitiveType) isClassType = false;
            else if(type instanceof ClassType) isClassType = true;
            else if(type instanceof ArrayType arrayType){
                isClassType = arrayType.elementType() instanceof ClassType;
            }else {
                throw new RuntimeException("Unexpected type: " + type);
            }
        }
        return !isClassType;
    }
}
