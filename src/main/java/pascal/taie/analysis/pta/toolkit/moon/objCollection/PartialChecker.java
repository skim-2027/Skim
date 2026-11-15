package pascal.taie.analysis.pta.toolkit.moon.objCollection;



import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraFlowGraph;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraInfoTracker;
import pascal.taie.analysis.pta.toolkit.moon.support.FieldReachability;
import pascal.taie.analysis.pta.toolkit.moon.support.KeyTypeCollector;
import pascal.taie.analysis.pta.toolkit.moon.support.MoonInfra;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Sets;

import java.util.*;

public class PartialChecker {
    private static final int fieldTypeThreshold = 3;
    private final ExtraFlowGraph VFGForObj;
    private final KeyTypeCollector keyTypeCollector;
    private final FieldReachability fieldReachability;
    private final MoonInfra moonInfra;
    private final ExtraInfoTracker extraInfoTracker;
    public PartialChecker(MoonInfra moonInfra){
        this.keyTypeCollector = moonInfra.getKeyTypeCollector();
        this.fieldReachability = moonInfra.getFieldReachability();
        extraInfoTracker = moonInfra.getPta().getExtraInfoTracker();
        this.VFGForObj = extraInfoTracker.getExtraObjFlowGraph();
        this.moonInfra = moonInfra;
    }


    public boolean check(Obj obj, boolean metParamWhenAllocated){
        Optional<JMethod> optionalJMethod = obj.getContainerMethod();
        if (optionalJMethod.isEmpty()) return false;
        JMethod inMethod = optionalJMethod.get();


        if(canReturnOut(obj)){
            if(metParamWhenAllocated || inMethod.isStatic() || obj.getType() instanceof ArrayType){
                return true;
            }
        }

        if (inMethod.isStatic()) {
            return false;
        }

        Var aThis = inMethod.getIR().getThis();
        if (aThis == null) return false;
        VarNode thisVar = extraInfoTracker.getVarNode(aThis);
        if (thisVar == null) return false;
        Set<JMethod> traversalMethods = Sets.newSet();
        Set<Node> thisAlias = getAliasOf(thisVar, traversalMethods);
        // first check if the obj flows into this field
        // then check if the obj flows out of the allocated method.

        Deque<CheckTrace> stack = new ArrayDeque<>();
        Set<CheckTrace> visited = Sets.newSet();


        if (obj.getContainerMethod().isEmpty() ||
                !(obj.getAllocation() instanceof DefinitionStmt<?,?> definitionStmt) ||
                definitionStmt.getDef().isEmpty() ||
                !(definitionStmt.getDef().get() instanceof Var newVar)
        ) {
            return false;
        }

        VarNode newVarNode = extraInfoTracker.getVarNode(newVar);
        if(newVarNode == null){
            return false;
        }

//        stack.push(new CheckTrace(obj, CheckStatus.Allocation));
        stack.push(new CheckTrace(newVarNode, CheckStatus.DirectVar));

        Set<Type> fieldTypes = Sets.newSet();
        while(!stack.isEmpty()){
            CheckTrace crtTrace = stack.pop();
            if(visited.contains(crtTrace)){
                continue;
            }
            visited.add(crtTrace);
            Node crtNode = crtTrace.getNode();
            CheckStatus crtState = crtTrace.getState();

            for (ExtraFlowGraph.ExtraFlowEdge outEdge : VFGForObj.getOutEdgesOf(crtNode)) {
                FlowKind flowKind = outEdge.flowKind();
                CheckStatus nextState = moveToNode(crtState, flowKind, true);
                if(nextState == CheckStatus.UnDef) continue;
                if(flowKind == FlowKind.FIELD_STORE && thisAlias.contains(outEdge.target())){

                    fieldTypes.add(outEdge.field().getType());
                    if(isContextAwareField(inMethod, outEdge.field())){
                        return fieldTypes.size() <= fieldTypeThreshold;
                    }
                }else{
                    if(flowKind != FlowKind.FIELD_STORE || keyTypeCollector.isConcernedType(outEdge.field().getType())){
                        if(outEdge.target() instanceof VarNode varNode) {
                        if (traversalMethods.contains(varNode.getVar().getMethod())) {
                            if(flowKind == FlowKind.FIELD_STORE){
                                fieldTypes.add(outEdge.field().getType());
                            }
                            stack.push(new CheckTrace(varNode, nextState));
                        }
                        }else{
                            stack.push(new CheckTrace(outEdge.target(), nextState));
                        }
                    }
                }
            }

            for (ExtraFlowGraph.ExtraFlowEdge inEdge : VFGForObj.getInEdgesOf(crtNode)) {
                FlowKind flowKind = inEdge.flowKind();
                CheckStatus nextState = moveToNode(crtState, flowKind, false);
                if(nextState == CheckStatus.UnDef) continue;
                if(flowKind == FlowKind.FIELD_LOAD && thisAlias.contains(inEdge.source())){

                    fieldTypes.add(inEdge.field().getType());
                    if(isContextAwareField(inMethod, inEdge.field())) {
                        return fieldTypes.size() <= fieldTypeThreshold;
                    }
                }else{
                    if(flowKind != FlowKind.FIELD_LOAD || keyTypeCollector.isConcernedType(inEdge.field().getType())){
                        if(inEdge.source() instanceof VarNode varNode) {
                        if (traversalMethods.contains(varNode.getVar().getMethod())) {
                            if(flowKind == FlowKind.FIELD_LOAD){
                                fieldTypes.add(inEdge.field().getType());
                            }
                            stack.push(new CheckTrace(varNode, nextState));
                        }
                        }else{
                            stack.push(new CheckTrace(inEdge.source(), nextState));
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isContextAwareField(JMethod method, AbstractField field){
        if(!keyTypeCollector.isConcernedType(field.getType())) return false;
        Set<Obj> recvObjs = moonInfra.getReceiverObjectsOf(method);
        for (Obj recvObj : recvObjs) {
            if(recvObj.getType() instanceof ClassType){
                if(fieldReachability.isConnceredField(recvObj, field)) return true;
            }
        }
        return false;
    }

    private CheckStatus moveToNode(CheckStatus crtState, FlowKind flowKind, boolean forward){
        switch (crtState){
            case Allocation -> {
                if(forward && flowKind == FlowKind.NEW){
                    return CheckStatus.DirectVar;
                }
            }

            case DirectVar -> {
                if(forward && flowKind == FlowKind.LOCAL_ASSIGN){
                    return CheckStatus.DirectVar;
                }else if(forward && flowKind == FlowKind.FIELD_STORE){
                    return CheckStatus.StoredInVar;
                }
            }

            case StoredInVar -> {
                if(forward){
                    // Forward
                    if(flowKind == FlowKind.FIELD_STORE){
                        return CheckStatus.StoredInVar;
                    }else if(flowKind == FlowKind.LOCAL_ASSIGN){
                        return CheckStatus.StoredInVar;
                    }
                }
                else{
                    // backForward
                    if(flowKind == FlowKind.LOCAL_ASSIGN){
                        return CheckStatus.StoredInVar;
                    }else if(flowKind == FlowKind.FIELD_LOAD){
                        return CheckStatus.StoredInVar;
                    }
                }
            }
        }
        return CheckStatus.UnDef;
    }


    private boolean canReturnOut(Obj obj){
        Stack<Node> stack = new Stack<>();
        if (obj.getContainerMethod().isEmpty() ||
            !(obj.getAllocation() instanceof DefinitionStmt<?,?> definitionStmt) ||
            definitionStmt.getDef().isEmpty() ||
            !(definitionStmt.getDef().get() instanceof Var newVar)
                ) {
            return false;
        }


        VarNode newVarNode = extraInfoTracker.getVarNode(newVar);
        if(newVarNode == null){
            return false;
        }

        for (Node succ : VFGForObj.getSuccsOf(newVarNode)) {
            stack.push(succ);
        }
        for (Node pred : VFGForObj.getPredsOf(newVarNode)) {
            stack.push(pred);
        }
        Set<Node> visited = Sets.newSet();
        while(!stack.isEmpty()){
            Node crtNode = stack.pop();
            visited.add(crtNode);
            for (ExtraFlowGraph.ExtraFlowEdge outEdge : VFGForObj.getOutEdgesOf(crtNode)) {
                Node succ = outEdge.target();
                if(succ instanceof VarNode varNode && varNode.getVar().getMethod().equals(obj.getContainerMethod().get()) && isReturnVar(varNode.getVar())){
                    return true;
                }
                if(outEdge.flowKind() == FlowKind.LOCAL_ASSIGN && !visited.contains(succ)){
                    stack.push(outEdge.target());
                }
            }

        }
        return false;

    }

    private boolean isReturnVar(Var var){
        List<Var> returnVars = var.getMethod().getIR().getReturnVars();
        return returnVars.contains(var);
    }


    private Set<Node> getAliasOf(Node node, Set<JMethod> traversalMethods){
        Stack<Node> stack = new Stack<>();
        stack.push(node);
        Set<Node> visited = Sets.newSet();
        while(!stack.isEmpty()){
            Node crtNode = stack.pop();
            visited.add(crtNode);
            if(crtNode instanceof VarNode varNode){
                JMethod method = varNode.getVar().getMethod();;
                if(method != null){
                    traversalMethods.add(method);
                }

            }
            for (ExtraFlowGraph.ExtraFlowEdge outEdge : VFGForObj.getOutEdgesOf(crtNode)) {
                if(outEdge.flowKind() == FlowKind.LOCAL_ASSIGN && !visited.contains(outEdge.target())){
                    stack.push(outEdge.target());
                }
            }
        }
        return visited;
    }


    private record CheckTrace(Node node, CheckStatus state){
        public Node getNode(){
            return node;
        }
        public CheckStatus getState(){
            return state;
        }
    }

    private enum CheckStatus {
        DirectVar, StoredInVar, Allocation, ThisAlias, THIS, End, UnDef
    }
}
