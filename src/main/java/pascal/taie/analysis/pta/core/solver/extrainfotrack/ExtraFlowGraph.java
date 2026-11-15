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
import pascal.taie.util.collection.*;
import pascal.taie.util.graph.Edge;
import pascal.taie.util.graph.Graph;

import javax.annotation.Nullable;
import java.util.Set;


/**
 * Basically the ObjectFlowGraph with extra flows.
 */
public class ExtraFlowGraph
        implements Graph<Node> {
    private final MultiMap<Node, ExtraFlowEdge> inEdges;
    private final MultiMap<Node, ExtraFlowEdge> outEdges;

    private final NodeManager nodeManager;
    public ExtraFlowGraph(NodeManager nodeManager) {
        inEdges = Maps.newMultiMap(
                new IndexMap<>(nodeManager, 4096));
        outEdges = Maps.newMultiMap(
                new IndexMap<>(nodeManager, 4096));
        this.nodeManager = nodeManager;
    }




    public void addEdge(Node srcPtr, Node tgtPtr, FlowKind flowKind, AbstractField field){
        ExtraFlowEdge edge = new ExtraFlowEdge(srcPtr, tgtPtr, flowKind, field);
        outEdges.put(srcPtr, edge);
        inEdges.put(tgtPtr, edge);
    }





    @Override
    public Set<ExtraFlowEdge> getInEdgesOf(Node node) {
        return inEdges.get(node);
    }


    @Override
    public Set<Node> getPredsOf(Node node) {
        return Views.toMappedSet(getInEdgesOf(node), ExtraFlowEdge::src);
    }

    @Override
    public Set<Node> getSuccsOf(Node node) {
        return Views.toMappedSet(getOutEdgesOf(node), ExtraFlowEdge::target);
    }

    @Override
    public Set<ExtraFlowEdge> getOutEdgesOf(Node node) {
        return outEdges.get(node);
    }

    @Override
    public Set<Node> getNodes() {
        return nodeManager.getNodes();
    }


    public record ExtraFlowEdge(Node src, Node dest, FlowKind flowKind, @Nullable AbstractField field) implements Edge<Node> {
        @Override
        public Node source() {
            return src;
        }

        @Override
        public Node target() {
            return dest;
        }
    }
}
