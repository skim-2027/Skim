package pascal.taie.analysis.pta.toolkit.skim.graph;

import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.graph.Graph;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public class LabeledGraph<N, L> implements Graph<N> {
    private MultiMap<N, LabelEdge<N, L>> outEdges;
    private MultiMap<N, LabelEdge<N, L>> inEdges;

    private Set<N> nodes;

    public LabeledGraph(){
        this.outEdges = Maps.newMultiMap(Maps.newHybridMap(), Sets::newHybridSet);
        this.inEdges = Maps.newMultiMap(Maps.newHybridMap(), Sets::newHybridSet);
        this.nodes = Sets.newHybridSet();
    }

    public LabeledGraph(LabeledGraph<N,L> other){
        this.outEdges = Maps.newMultiMap();
        this.outEdges.putAll(other.outEdges);
        this.inEdges = Maps.newMultiMap();
        this.inEdges.putAll(other.inEdges);
        this.nodes = Sets.newHybridSet(other.nodes);
    }

    public boolean addEdge(N from, N to, L label){
        LabelEdge<N,L> labelEdge = new LabelEdge<>(from, to, label);
        return addEdge(labelEdge);
    }

    public boolean addEdge(LabelEdge<N,L> edge){
        boolean changed;
        N from = edge.from();
        N to = edge.to();
        changed = outEdges.put(from, edge);

        boolean c1 = inEdges.put(to, edge);
        boolean c2 = nodes.add(from);
        boolean c3 = nodes.add(to);
        if (changed != c1 ) {
            throw new RuntimeException("Inconsistent state in LabeledGraph: " + edge);
        }
        return changed;
    }

    public void removeNode(N node) {
        if (!nodes.contains(node)) {
            return;
        }
        nodes.remove(node);
        for (LabelEdge<N, L> outEdge : getOutEdgesOf(node)) {
            inEdges.remove(outEdge.to(), outEdge);
        }
        outEdges.removeAll(node);
        for (LabelEdge<N, L> inEdge : getInEdgesOf(node)) {
            outEdges.remove(inEdge.from(), inEdge);
        }
        inEdges.removeAll(node);
    }

    public static <N, L> LabeledGraph<N,L> unmodifiableGraph (LabeledGraph<N,L> g){
        LabeledGraph<N,L> newG = new LabeledGraph<>();
        newG.outEdges = Maps.unmodifiableMultiMap(g.outEdges);
        newG.inEdges = Maps.unmodifiableMultiMap(g.inEdges);
        newG.nodes = Collections.unmodifiableSet(g.nodes);
        return newG;
    }

    @Override
    public Set<LabelEdge<N,L>> getOutEdgesOf(N node) {
        return Collections.unmodifiableSet(outEdges.get(node));
    }
    @Override
    public Set<LabelEdge<N,L>> getInEdgesOf(N node) {
        return Collections.unmodifiableSet(inEdges.get(node));
    }

    @Override
    public Set<N> getPredsOf(N node) {
        return getInEdgesOf(node).stream().map(LabelEdge::from).collect(Collectors.toSet());
    }

    @Override
    public Set<N> getSuccsOf(N node) {
        return getOutEdgesOf(node).stream().map(LabelEdge::to).collect(Collectors.toSet());
    }

    public Set<N> getNodes() {
        return Collections.unmodifiableSet(nodes);
    }

    public int edgeSize(){
        return outEdges.values().size();
    }

    public Collection<LabelEdge<N,L>> allEdges(){
        return Collections.unmodifiableCollection(outEdges.values());
    }
}
