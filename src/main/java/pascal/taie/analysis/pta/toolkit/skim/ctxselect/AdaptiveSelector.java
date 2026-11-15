package pascal.taie.analysis.pta.toolkit.skim.ctxselect;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.context.ContextFactory;
import pascal.taie.analysis.pta.core.cs.context.TrieContext;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.FieldWrapper;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabelEdge;
import pascal.taie.analysis.pta.toolkit.skim.graph.LabeledGraph;
import pascal.taie.analysis.pta.toolkit.skim.graph.SuperOAG;
import pascal.taie.analysis.pta.toolkit.skim.preanalysis.SkimInfra;
import pascal.taie.analysis.pta.toolkit.skim.traverse.BackTravResult;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.TwoKeyMultiMap;
import pascal.taie.util.graph.Graph;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;

public class AdaptiveSelector {
    private final int maxMethodCtxDepth;

    private final SkimInfra infra;
    private final LabeledGraph<Obj, BackTravResult.Relation> globalOAG;
    private final ContextFactory<Obj> ctxFactory = new TrieContext.Factory<>();

    public AdaptiveSelector(SkimInfra infra, LabeledGraph<Obj, BackTravResult.Relation> globalOAG, int maxMethodCtxDepth) {
        this.infra = infra;
        this.globalOAG = globalOAG;

        this.maxMethodCtxDepth = maxMethodCtxDepth;
    }

    public AdaptiveCtxSelectResult select(TwoKeyMultiMap<Obj, AbstractField, BackTravResult> backTravResults) {
        MultiMap<Obj, JField> concernedInsFs = Maps.newMultiMap();
        for (Pair<Obj, AbstractField> p : backTravResults.twoKeySet()) {
            if (p.second() instanceof FieldWrapper fieldWrapper) {
                concernedInsFs.put(p.first(), fieldWrapper.field());
            }
        }

        SuperOAG superOAG = new SuperOAG();

        for (Obj obj : globalOAG.getNodes()) {
            for (LabelEdge<Obj, BackTravResult.Relation> inEdge : globalOAG.getInEdgesOf(obj)) {
                Obj source = inEdge.source();
                Obj target = inEdge.target();
                if (!infra.isPartialAndHaveConcernedField(target)) continue;
                if (infra.hasSingleCtx(target)) continue;
                superOAG.addEdge(source, target, inEdge.label().name());
            }
        }

        for (Obj obj : globalOAG.getNodes()) {
            if (infra.isPartialAndHaveConcernedField((obj)) && hasSpecialCtx(obj)) {
                superOAG.addRootNode(obj);
            }
        }
        return new AdaptiveCtxSelectResult(selectContext(superOAG), ctxFactory, concernedInsFs);
    }

    public record AdaptiveCtxSelectResult(MultiMap<Obj, Context> objToCtxs,
                                          ContextFactory<Obj> ctxFactory,
                                          MultiMap<Obj, JField> concernedInsFs) {
    }

    private MultiMap<Obj, Context> selectContext(SuperOAG superOAG) {
        MultiMap<Obj, Context> contextMap = Maps.newMultiMap();
        Queue<Pair<SuperOAG.Node, Context>> worklist = new ArrayDeque<>();
        Context emptyCtx = ctxFactory.getEmptyContext();

        for (SuperOAG.Node rootNode : superOAG.getRootNodes()) {
            worklist.add(new Pair<>(rootNode, emptyCtx));
        }

        while (!worklist.isEmpty()) {
            Pair<SuperOAG.Node, Context> current = worklist.poll();
            SuperOAG.Node node = current.first();
            Context ctx = current.second();
            Obj obj = ((SuperOAG.Node.ObjNode) node).obj();
            if (contextMap.put(obj, ctx)) {
                Set<SuperOAG.Node> reachSuccs = superOAG.getSuccsOf(node);
                for (SuperOAG.Node succ : reachSuccs) {
                    Context newCtx = ctxFactory.append(ctx, obj, maxMethodCtxDepth);
                    Obj succObj = ((SuperOAG.Node.ObjNode) succ).obj();
                    if (!contextMap.get(succObj).contains(newCtx)) {
                        worklist.add(new Pair<>(succ, newCtx));
                    }
                }
            }
        }

        MultiMap<Obj, Context> ctxMapWithoutEmptyCtx = Maps.newMultiMap();
        for (Obj obj : contextMap.keySet()) {
            Set<Context> contexts = contextMap.get(obj);
            for (Context ctx : contexts) {
                if (!ctx.equals(emptyCtx)) {
                    ctxMapWithoutEmptyCtx.put(obj, ctx);
                }
            }
        }

        return ctxMapWithoutEmptyCtx;
    }

    private boolean hasSpecialCtx(Obj obj) {
        Graph<Obj> fullOAG = infra.getFullOAG();
        if (obj.getContainerMethod().isPresent()) {
            var inMethod = obj.getContainerMethod().get();
            if (inMethod.isStatic()) {
                var hasStaticInit = infra.bfsReachable(inMethod, true, true).stream().anyMatch(JMethod::isStaticInitializer);
                if (hasStaticInit) return true;
            }

            for (Obj predInProgramOAG : fullOAG.getPredsOf(obj)) {
                if (!globalOAG.hasEdge(predInProgramOAG, obj)) {
                    return true;
                }
            }
        }
        return false;
    }
}
