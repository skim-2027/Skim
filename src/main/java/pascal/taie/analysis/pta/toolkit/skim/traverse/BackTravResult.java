package pascal.taie.analysis.pta.toolkit.skim.traverse;

import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraEdgeLabel;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabelEdge;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabeledGraph;
import pascal.taie.ir.exp.Var;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.collection.TwoKeyMultiMap;
import pascal.taie.util.graph.Graph;

import java.util.*;

public class BackTravResult {
    public enum Relation {
        PARAM,
        THIS,
        TRANSFER
    }

    private final Graph<Obj> oag;
    private final Obj obj;
    private TwoKeyMultiMap<Integer, Obj, Var> allocatorsByParam;
    private TwoKeyMultiMap<Integer, Obj, Var> allocatorsByThis;
    private TwoKeyMultiMap<Integer, Obj, Obj> newObjAndSharedAllocators;
    private TwoKeyMultiMap<Integer, Obj, List<ExtraEdgeLabel>> newObjToEdgeLabels;
    private LabeledGraph<Obj, Relation> localOAG;

    public BackTravResult(Graph<Obj> oag, Obj obj) {
        this.oag = oag;
        this.obj = obj;
    }

    public BackTravResult(BackTravResult toBeCloned) {
        this(toBeCloned.oag, toBeCloned.obj);
        if (toBeCloned.allocatorsByParam != null) {
            allocatorsByParam = Maps.newTwoKeyMultiMap();
            toBeCloned.allocatorsByParam.forEach((i, o1, o2) ->
                    allocatorsByParam.put(i, o1, o2));
        }
        if (toBeCloned.allocatorsByThis != null) {
            allocatorsByThis = Maps.newTwoKeyMultiMap();
            toBeCloned.allocatorsByThis.forEach((i, o1, o2) ->
                    allocatorsByThis.put(i, o1, o2));
        }
        if (toBeCloned.newObjAndSharedAllocators != null) {
            newObjAndSharedAllocators = Maps.newTwoKeyMultiMap();
            toBeCloned.newObjAndSharedAllocators.forEach((i, o1, o2) ->
                    newObjAndSharedAllocators.put(i, o1, o2));
        }
        if (toBeCloned.newObjToEdgeLabels != null) {
            newObjToEdgeLabels = Maps.newTwoKeyMultiMap();
            toBeCloned.newObjToEdgeLabels.forEach((i, o1, o2) ->
                    newObjToEdgeLabels.put(i, o1, o2));
        }
        if (toBeCloned.localOAG != null) {
            this.localOAG = new LabeledGraph<>(toBeCloned.localOAG);
        }
    }

    public void addNewObjToEdgeLabels(int layer, Obj obj, List<ExtraEdgeLabel> edgeLabels) {
        if (newObjToEdgeLabels == null) {
            newObjToEdgeLabels = Maps.newTwoKeyMultiMap();
        }
        newObjToEdgeLabels.put(layer, obj, edgeLabels);
    }

    public TwoKeyMultiMap<Integer, Obj, List<ExtraEdgeLabel>> getNewObjToEdgeLabels() {
        return newObjToEdgeLabels;
    }

    private void addToLocalOAG(Obj from, Obj to, Relation relation) {
        if (localOAG == null) localOAG = new LabeledGraph<>();

        if (oag.hasEdge(from, to))
            localOAG.addEdge(from, to, relation);
    }

    public void addAllocatorsByParam(Set<Obj> crtAllocators, Set<Obj> newAllocators, int crtLayer, int newLayer, Node nextNode) {
        if (newLayer == crtLayer && crtLayer == 1) {
            for (Obj newAllocator : newAllocators) {
                addToLocalOAG(newAllocator, obj, Relation.PARAM);
            }
        } else if (newLayer == crtLayer + 1) {
            for (Obj crtAllocator : crtAllocators) {
                for (Obj newAllocator : newAllocators) {
                    addToLocalOAG(newAllocator, crtAllocator, Relation.PARAM);
                }
            }
        }

        if (allocatorsByParam == null) allocatorsByParam = Maps.newTwoKeyMultiMap();
        Var newVar = ((VarNode) nextNode).getVar();
        for (Obj allocator : newAllocators) {
            allocatorsByParam.put(newLayer, allocator, newVar);
        }
    }

    public void checkBeforeAddAllocatorsByThis(Integer allocatorLayer, Obj allocatorByThis,
                                               TraversalState crtState, Set<Var> vars) {
        int crtLayer = crtState.getAllocationLayer();
        if (!(crtState instanceof AllocTraversalState)) return;

        Set<Obj> crtAllocators = crtState.getCtxObjs();

        if (allocatorLayer == crtLayer) {
            if (crtAllocators.contains(allocatorByThis)) {
                Set<LabelEdge<Obj, Relation>> existingEdges = null;
                if (localOAG != null) {
                    existingEdges = Sets.newHybridSet(localOAG.getOutEdgesOf(allocatorByThis));
                }

                if (existingEdges != null && !existingEdges.isEmpty()) {
                    for (LabelEdge<Obj, Relation> existingEdge : existingEdges) {
                        addToLocalOAG(allocatorByThis, existingEdge.to(), Relation.THIS);
                    }
                    if (allocatorsByThis == null) {
                        allocatorsByThis = Maps.newTwoKeyMultiMap();
                    }
                    vars.forEach(var -> allocatorsByThis.put(allocatorLayer, allocatorByThis, var));
                } else {
                    if (crtLayer == 1) {
                        addToLocalOAG(allocatorByThis, obj, Relation.THIS);
                        if (allocatorsByThis == null) {
                            allocatorsByThis = Maps.newTwoKeyMultiMap();
                        }
                        vars.forEach(var -> allocatorsByThis.put(allocatorLayer, allocatorByThis, var));
                    }
                }
            }
        } else if (allocatorLayer == crtLayer + 1) {
            for (Obj crtAllocator : crtAllocators) {
                if (oag.hasEdge(allocatorByThis, crtAllocator)) {
                    addToLocalOAG(allocatorByThis, crtAllocator, Relation.THIS);
                    if (allocatorsByThis == null) {
                        allocatorsByThis = Maps.newTwoKeyMultiMap();
                    }
                    vars.forEach(var -> allocatorsByThis.put(allocatorLayer, allocatorByThis, var));
                }
            }
        } else {
        }
    }

    public void addNewObjAndSharedAllocators(int layer, Obj newObj, Obj sharedAllocator) {
        if (newObjAndSharedAllocators == null) {
            newObjAndSharedAllocators = Maps.newTwoKeyMultiMap();
        }
        newObjAndSharedAllocators.put(layer, newObj, sharedAllocator);
    }

    public Optional<TwoKeyMultiMap<Integer, Obj, Obj>> getNewObjAndSharedAllocators() {
        return Optional.ofNullable(newObjAndSharedAllocators);
    }

    public TwoKeyMultiMap<Integer, Obj, Var> getAllocatorsByParam() {
        return allocatorsByParam;
    }

    public TwoKeyMultiMap<Integer, Obj, Var> getAllocatorsByThis() {
        return allocatorsByThis;
    }

    public LabeledGraph<Obj, Relation> getLocalOAG() {
        return localOAG;
    }

    public boolean hasResult() {
        return (allocatorsByParam != null && !allocatorsByParam.isEmpty()) ||
                (allocatorsByThis != null && !allocatorsByThis.isEmpty()) ||
                (newObjAndSharedAllocators != null && !newObjAndSharedAllocators.isEmpty()) ||
                (newObjToEdgeLabels != null && !newObjToEdgeLabels.isEmpty()) ||
                (localOAG != null && !localOAG.getNodes().isEmpty());
    }

    public void mergeAllocatorsFromSameObj(BackTravResult other) {
        if (!this.obj.equals(other.obj))
            throw new RuntimeException("Should only merge allocators from the same object. Check this.");
        LabeledGraph<Obj, Relation> otherLocalOAG = other.localOAG;
        if (otherLocalOAG == null || otherLocalOAG.getNodes().isEmpty()) ;
        else if (this.localOAG == null || this.localOAG.getNodes().isEmpty()) {
            this.localOAG = new LabeledGraph<>(otherLocalOAG);
        } else {
            int oldSize = this.localOAG.edgeSize();
            for (Obj o : otherLocalOAG) {
                for (LabelEdge<Obj, Relation> otherOutEdge : otherLocalOAG.getOutEdgesOf(o)) {
                    this.localOAG.addEdge(otherOutEdge);
                }
            }
        }

        if (other.allocatorsByParam != null) {
            if (this.allocatorsByParam == null) this.allocatorsByParam = Maps.newTwoKeyMultiMap();
            int oldSize = this.allocatorsByParam.size();
            other.allocatorsByParam.forEach((i, o1, o2) -> this.allocatorsByParam.put(i, o1, o2));
        }

        if (other.allocatorsByThis != null) {
            if (this.allocatorsByThis == null) this.allocatorsByThis = Maps.newTwoKeyMultiMap();
            int oldSize = this.allocatorsByThis.size();
            other.allocatorsByThis.forEach((i, o1, o2) -> this.allocatorsByThis.put(i, o1, o2));
        }

        if (other.newObjAndSharedAllocators != null) {
            if (this.newObjAndSharedAllocators == null)
                this.newObjAndSharedAllocators = Maps.newTwoKeyMultiMap();
            int oldSize = this.newObjAndSharedAllocators.size();
            other.newObjAndSharedAllocators.forEach((i, o1, o2) -> this.newObjAndSharedAllocators.put(i, o1, o2));
        }

        if (other.newObjToEdgeLabels != null) {
            if (this.newObjToEdgeLabels == null) this.newObjToEdgeLabels = Maps.newTwoKeyMultiMap();
            int oldSize = this.newObjToEdgeLabels.size();
            other.newObjToEdgeLabels.forEach((i, o1, o2) -> this.newObjToEdgeLabels.put(i, o1, o2));
        }
    }
}
