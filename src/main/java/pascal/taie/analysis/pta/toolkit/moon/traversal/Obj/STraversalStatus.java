package pascal.taie.analysis.pta.toolkit.moon.traversal.Obj;



import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;

import java.util.Map;
import java.util.Objects;
import java.util.Stack;

public class STraversalStatus {
    public Node pointer;
    public int allocatorLevel;
    public int callStackLevel;
    private Stack<JMethod> staticCallStack;
    private Map<FlowKind, Integer> visitedFlowKinds;
    public boolean needCheckFieldRelationWhenMeetNew = true;
    public STraversalStatus(Node pointer, int allocatorLevel, int callStackLevel, Node retPtrOfStaticMethod,
                            STraversalStatus predTrace, FlowKind flowKind) {
        this(pointer, allocatorLevel, callStackLevel, predTrace, flowKind);
        if(retPtrOfStaticMethod instanceof VarNode varNode){
            if(staticCallStack == null){
                staticCallStack = new Stack<>();
            }
            staticCallStack.push(varNode.getVar().getMethod());
        }
    }


    public STraversalStatus(Node pointer, int allocatorLevel, int callStackLevel, STraversalStatus predTrace, FlowKind flowKind) {
        this(pointer, allocatorLevel, callStackLevel);
        if(this.visitedFlowKinds == null){
            visitedFlowKinds = Maps.newMap();
        }
        if(predTrace.visitedFlowKinds != null && !predTrace.visitedFlowKinds.isEmpty()){
            this.visitedFlowKinds.putAll(predTrace.visitedFlowKinds);
        }
        int i = this.visitedFlowKinds.computeIfAbsent(flowKind, __ -> 0);
        i += 1;
        this.visitedFlowKinds.put(flowKind, i);
        if(predTrace.staticCallStack != null && !predTrace.staticCallStack.isEmpty()){
            if(staticCallStack == null){
                staticCallStack = new Stack<>();
            }
            staticCallStack.addAll(predTrace.staticCallStack);
        }

        this.needCheckFieldRelationWhenMeetNew = predTrace.needCheckFieldRelationWhenMeetNew;
    }

    public STraversalStatus(Node pointer, int allocatorLevel, int callStackLevel) {
        this.pointer = pointer;
        this.allocatorLevel = allocatorLevel;
        this.callStackLevel = callStackLevel;
    }

    public JMethod getStaticCallStackTop(){
        if(staticCallStack != null && !staticCallStack.isEmpty()){
            return staticCallStack.peek();
        }
        return null;
    }

    public int getVisitedFlowCounter(FlowKind flowKind){
        if(this.visitedFlowKinds == null) return 0;
        return this.visitedFlowKinds.getOrDefault(flowKind, 0);
    }


    @Override
    public int hashCode() {
        return Objects.hash(pointer, callStackLevel, allocatorLevel);
    }

    @Override
    public boolean equals(Object o) {
        if(o instanceof STraversalStatus other){
            if(o == this) return true;
            return this.pointer.equals(other.pointer) &&
                    this.allocatorLevel == other.allocatorLevel &&
                    this.callStackLevel == other.callStackLevel;
        }else {
            return false;
        }
    }
}
