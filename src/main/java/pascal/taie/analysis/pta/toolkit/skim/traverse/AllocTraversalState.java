package pascal.taie.analysis.pta.toolkit.skim.traverse;

import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraEdgeLabel;

import java.util.*;

public class AllocTraversalState extends TraversalState {
    public AllocTraversalState(Node node, int allocationLayer, Set<Obj> recvObjs) {
        this(node, allocationLayer, recvObjs, List.of(), StaticCallStack.empty());
    }

    public AllocTraversalState(Node node, int allocationLayer, Set<Obj> recvObjs, TraversalState preState){
        this(node, allocationLayer, recvObjs, preState.getExtraEdgeLabels(), preState.getStaticCallStack());
    }

    private AllocTraversalState(Node node, int allocationLayer, Set<Obj> recvObjs,
                                List<ExtraEdgeLabel> extraEdgeLabels,
                                StaticCallStack staticCallStack) {
        super(node, allocationLayer, recvObjs, extraEdgeLabels, staticCallStack);
    }

    @Override
    protected TraversalState copy(Node node, int allocationLayer, Set<Obj> recvObjs, List<ExtraEdgeLabel> extraEdgeLabels, StaticCallStack staticCallStack) {
        return new AllocTraversalState(node, allocationLayer, recvObjs,
                extraEdgeLabels, staticCallStack);
    }

    @Override
    public Set<Obj> getCtxObjs() {
        return this.getRecvObjs();
    }

    @Override
    protected boolean equalsImpl(TraversalState o) {
        if (!(o instanceof AllocTraversalState)) return false;
        return super.equalsImpl(o);
    }
}
