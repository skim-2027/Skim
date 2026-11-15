package pascal.taie.analysis.pta.toolkit.skim.policy;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.context.ContextFactory;
import pascal.taie.analysis.pta.core.heap.Obj;

public interface SelectPolicy {
    boolean retainObjCtx(Context ctx, Obj obj);

    ContextFactory<Obj> getCtxFactory();
}
