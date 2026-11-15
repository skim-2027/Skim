package pascal.taie.analysis.pta.toolkit.skim.traverse;

import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraEdgeLabel;
import pascal.taie.util.collection.Sets;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class OutOfAllocTravState extends TraversalState {
    private final int wrapperLayer;
    private final Set<Obj> matchedCtxObjs;

    public OutOfAllocTravState(Node node, int allocationLayer, Set<Obj> recvObjs, int wrapperLayer, Set<Obj> matchedCtxObjs, TraversalState preState) {
        this(node, allocationLayer, recvObjs, wrapperLayer, matchedCtxObjs,
                preState.getExtraEdgeLabels(), preState.getStaticCallStack());
    }

    private OutOfAllocTravState(Node node, int allocationLayer, Set<Obj> recvObjs,
                                int wrapperLayer, Set<Obj> matchedCtxObjs,
                                List<ExtraEdgeLabel> extraEdgeLabels,
                                StaticCallStack staticCallStack) {
        super(node, allocationLayer, recvObjs, extraEdgeLabels, staticCallStack);
        this.wrapperLayer = wrapperLayer;
        this.matchedCtxObjs = Sets.newHybridSet(matchedCtxObjs);
    }

    public int getWrapperLayer() {
        return wrapperLayer;
    }

    @Override
    protected TraversalState copy(Node node, int allocationLayer, Set<Obj> recvObjs,
                                  List<ExtraEdgeLabel> extraEdgeLabels,
                                  StaticCallStack staticCallStack) {
        return new OutOfAllocTravState(node, allocationLayer, recvObjs,
                wrapperLayer, matchedCtxObjs, extraEdgeLabels, staticCallStack);
    }

    @Override
    public Set<Obj> getCtxObjs() {
        return Collections.unmodifiableSet(matchedCtxObjs);
    }

    @Override
    protected int hashCodeImpl() {
        return Objects.hash(super.hashCodeImpl(), wrapperLayer, matchedCtxObjs);
    }

    @Override
    protected boolean equalsImpl(TraversalState other) {
        if (!(other instanceof OutOfAllocTravState that)) return false;
        return super.equalsImpl(other) && this.wrapperLayer == that.wrapperLayer
                && Objects.equals(this.matchedCtxObjs, that.matchedCtxObjs);
    }
}
