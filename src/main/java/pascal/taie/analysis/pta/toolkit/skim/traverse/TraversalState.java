package pascal.taie.analysis.pta.toolkit.skim.traverse;

import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraEdgeLabel;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Pair;

import java.util.*;

public abstract class TraversalState {
    protected final Node node;
    protected final int allocationLayer;
    protected final Set<Obj> recvObjs;
    protected List<ExtraEdgeLabel> extraEdgeLabels;
    protected final StaticCallStack staticCallStack;

    protected TraversalState(Node node, int allocationLayer, Set<Obj> recvObjs,
                             List<ExtraEdgeLabel> extraEdgeLabels,
                             StaticCallStack staticCallStack) {
        this.node = Objects.requireNonNull(node);
        this.allocationLayer = allocationLayer;
        this.recvObjs = Set.copyOf(recvObjs);
        if (extraEdgeLabels != null && !extraEdgeLabels.isEmpty())
            this.extraEdgeLabels = new ArrayList<>(extraEdgeLabels);
        this.staticCallStack = Objects.requireNonNull(staticCallStack);
    }

    public void addExtraEdgeLabel(ExtraEdgeLabel label) {
        Objects.requireNonNull(label);
        if (extraEdgeLabels == null) {
            extraEdgeLabels = new ArrayList<>();
        }
        extraEdgeLabels.add(label);
    }

    public List<ExtraEdgeLabel> getExtraEdgeLabels() {
        return extraEdgeLabels == null ? List.of() : Collections.unmodifiableList(extraEdgeLabels);
    }

    public TraversalState pushStaticCallStack(JMethod staticCall) {
        return copy(node, allocationLayer, recvObjs, extraEdgeLabels,
                staticCallStack.push(staticCall));
    }

    public StaticCallStack getStaticCallStack() {
        return staticCallStack;
    }

    public Pair<TraversalState, Optional<JMethod>> popStaticCallWithNextState() {
        if (staticCallStack == null || staticCallStack.size() == 0) {
            return new Pair<>(this, Optional.empty());
        }

        Optional<JMethod> topStack = staticCallStack.peek();
        var nextState = copy(node, allocationLayer, recvObjs, extraEdgeLabels,
                staticCallStack.pop());
        return new Pair<>(nextState, topStack);
    }

    public int getAllocationLayer() {
        return allocationLayer;
    }

    public abstract Set<Obj> getCtxObjs();

    public Set<Obj> getRecvObjs() {
        return Collections.unmodifiableSet(recvObjs);
    }

    TraversalState withRecvObjs(Set<Obj> recvObjs) {
        return copy(node, allocationLayer, recvObjs, extraEdgeLabels,
                staticCallStack);
    }

    protected abstract TraversalState copy(Node node, int allocationLayer,
                                           Set<Obj> recvObjs,
                                           List<ExtraEdgeLabel> extraEdgeLabels,
                                           StaticCallStack staticCallStack);

    public TraversalState nextState(Node nextNode) {
        return copy(nextNode, allocationLayer, recvObjs, extraEdgeLabels, staticCallStack);
    }

    protected int recvObjSize() {
        return recvObjs == null ? 0 : recvObjs.size();
    }

    protected int extraEdgeLabelSize() {
        return extraEdgeLabels == null ? 0 : extraEdgeLabels.size();
    }

    protected int staticCallStackSize() {
        return staticCallStack.size();
    }

    protected boolean equalsImpl(TraversalState o) {
        return this.node.equals(o.node) && this.allocationLayer == o.allocationLayer &&
                Objects.equals(this.recvObjs, o.recvObjs)
                &&
                Objects.equals(this.staticCallStack, o.staticCallStack)
                ;
    }

    protected int hashCodeImpl() {
        return Objects.hash(node, allocationLayer
                , recvObjSize(), staticCallStack
        );
    }

    @Override
    public int hashCode() {
        return hashCodeImpl();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof TraversalState other) {
            return equalsImpl(other);
        }
        return false;
    }
}
