package pascal.taie.analysis.pta.toolkit.skim.policy;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.context.ContextFactory;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.toolkit.skim.ctxselect.AdaptiveSelector;
import pascal.taie.util.collection.MultiMap;

public class AdaptivePolicy implements SelectPolicy {
    private final ContextFactory<Obj> ctxFactory;
    private final MultiMap<Obj, Context> objToCtxs;
    public AdaptivePolicy(AdaptiveSelector.AdaptiveCtxSelectResult result) {
        this.ctxFactory = result.ctxFactory();
        this.objToCtxs = result.objToCtxs();
    }

    @Override
    public boolean retainObjCtx(Context ctx, Obj obj) {
        return objToCtxs.get(obj).contains(ctx);
    }

    @Override
    public ContextFactory<Obj> getCtxFactory() {
        return ctxFactory;
    }
}
