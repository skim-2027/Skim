package pascal.taie.analysis.pta.toolkit.skim.graph;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

public final class SuperOAG {
    private Set<Node> rootNodes;

    private Set<Node> artificialRootNodes;

    public sealed interface Node {
        record ObjNode(Obj obj) implements Node {}
        record FieldNode(JField field) implements Node {}
        record MethodNode(JMethod method) implements Node {}
    }

    private final Map<Object, Node> nodeMap = Maps.newMap();

    public enum EdgeLabel {
        FIELD, METHOD, PARAM, THIS, TRANSFER
    }

    private final LabeledGraph<Node, EdgeLabel> graph = new LabeledGraph<>();

    public void addRootNode(Obj obj) {
        if (artificialRootNodes == null) {
            artificialRootNodes = Sets.newHybridSet();
        }
        Node node = getNode(obj);
        artificialRootNodes.add(node);
    }

    private Node getNode(Object obj) {
        return nodeMap.computeIfAbsent(obj, o -> {
            if (o instanceof Obj) {
                return new Node.ObjNode((Obj) o);
            } else if (o instanceof JField) {
                return new Node.FieldNode((JField) o);
            } else if (o instanceof JMethod) {
                return new Node.MethodNode((JMethod) o);
            } else {
                throw new IllegalArgumentException("Unsupported object type: " + o.getClass());
            }
        });
    }
    public void addEdge(Obj baseObj, JField field) {
        graph.addEdge(getNode(baseObj), getNode(field), EdgeLabel.FIELD);
    }

    public void addEdge(Obj allocator, Obj allocatee, String label) {
        graph.addEdge(getNode(allocator), getNode(allocatee), EdgeLabel.valueOf(label));
    }

    public void addEdge(Obj baseObj, JMethod method) {
        graph.addEdge(getNode(baseObj), getNode(method), EdgeLabel.METHOD);
    }

    public Set<Node> getRootNodes() {
        if (rootNodes == null) {
            rootNodes = Sets.newHybridSet();
            for (Node node : graph.getNodes()) {
                if (graph.getInEdgesOf(node).isEmpty()) {
                    rootNodes.add(node);
                }
            }
            if (artificialRootNodes != null) {
                rootNodes.addAll(artificialRootNodes);
            }
        }
        return Collections.unmodifiableSet(rootNodes);
    }

    public Set<Node> getNodes() {
        return graph.getNodes();
    }

    public Set<Node> getPredsOf(Node node) {
        return graph.getPredsOf(node);
    }

    public Set<Node> getSuccsOf(Node node) {
        return graph.getSuccsOf(node);
    }

    public int getInDegreeOf(Node node) {
        return graph.getPredsOf(node).size();
    }
}
