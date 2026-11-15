package pascal.taie.analysis.pta.core.cs.selector;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.toolkit.skim.policy.SelectPolicy;
import pascal.taie.language.classes.JMethod;

public class KObjElemSelector extends KObjSelector {
    private final SelectPolicy policy;

    public KObjElemSelector(int k, int hk, SelectPolicy policy) {
        super(k, hk);
        this.policy = policy;
        this.factory = policy.getCtxFactory();
    }


    @Override
    protected Context selectNewObjContext(CSMethod method, NewObj obj) {
        return selectObjContext(method.getContext(), obj);
    }

    public Context selectObjContext(Context context, Obj obj) {
        Context c;
        if (context.getLength() == 0) c = context;
        else {
            c = policy.retainObjCtx(context, obj) ?
                    factory.makeLastK(context, limit-1) :
                    factory.makeLastK(context, hLimit);
        }

        return c;
    }


    @Override
    public Context selectContext(CSCallSite callSite, CSObj recv, JMethod callee) {
        Context ctx = recv.getContext();
        return factory.append(ctx, recv.getObject(), this.limit);
    }

}
