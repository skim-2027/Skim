package pascal.taie.analysis.pta.toolkit.skim.traverse;

import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraEdgeLabel;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

final class TraversalWorkList {
    private final int extraEdgeLabelLimit;
    public TraversalWorkList(int maxRounds) {
        if (maxRounds <= 2) {
            extraEdgeLabelLimit = 3;
        } else {
            extraEdgeLabelLimit = 8;
        }
    }
    private final Map<Key, TraversalState> entries =
            Maps.newLinkedHashMap();

    void addEntry(TraversalState state) {
        if (state.extraEdgeLabelSize() > extraEdgeLabelLimit) {
            return;
        }
        Key key = keyOf(state);
        TraversalState existing = entries.get(key);
        if (existing == null) {
            entries.put(key, state);
            return;
        }
        Set<Obj> recvObjs = Sets.newHybridSet(existing.getRecvObjs());
        if (recvObjs.addAll(state.getRecvObjs())) {
            entries.put(key, existing.withRecvObjs(recvObjs));
        }
    }

    TraversalState pollEntry() {
        if (entries.isEmpty()) {
            throw new NoSuchElementException();
        }
        var iterator = entries.entrySet().iterator();
        TraversalState state = iterator.next().getValue();
        iterator.remove();
        return state;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    static Key keyOf(TraversalState state) {
        Integer wrapperLayer = null;
        Set<Obj> matchedCtxObjs = Set.of();
        if (state instanceof OutOfAllocTravState outOfState) {
            wrapperLayer = outOfState.getWrapperLayer();
            matchedCtxObjs = outOfState.getCtxObjs();
        }
        return new Key(state.getClass(), state.node,
                state.getAllocationLayer(), state.getStaticCallStack(),
                Set.copyOf(state.getExtraEdgeLabels()), wrapperLayer,
                matchedCtxObjs);
    }

    record Key(
            Class<? extends TraversalState> stateType,
            Node node,
            int allocationLayer,
            StaticCallStack staticCallStack,
            Set<ExtraEdgeLabel> extraEdgeLabels,
            Integer wrapperLayer,
            Set<Obj> matchedCtxObjs) {
        Key {
            extraEdgeLabels = Set.copyOf(extraEdgeLabels);
            matchedCtxObjs = Set.copyOf(matchedCtxObjs);
        }
    }
}
