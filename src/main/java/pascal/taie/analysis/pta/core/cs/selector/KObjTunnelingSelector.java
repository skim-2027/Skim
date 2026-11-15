package pascal.taie.analysis.pta.core.cs.selector;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.toolkit.tunneling.TunnelingModel;
import pascal.taie.language.classes.JMethod;

/**
 * "Precision-guided Context Sensitivity for Pointer Analysis"
 * (OOPSLA 2018).
 */
class KObjTunnelingSelector extends KObjSelector {

    private final TunnelingModel heuristic = new TunnelingModel();

    KObjTunnelingSelector(int k, int hk) {
        super(k, hk);
    }

    @Override
    public Context selectContext(
            CSCallSite callSite, CSObj recv, JMethod callee) {
        boolean tunnelsThroughChild = heuristic.isChildMethod(callee);
        boolean tunnelsThroughParent = recv.getObject().getContainerMethod()
                .map(heuristic::isParentMethod)
                .orElse(false);
        if (tunnelsThroughParent || tunnelsThroughChild) {
            return factory.makeLastK(recv.getContext(), limit);
        }
        return super.selectContext(callSite, recv, callee);
    }
}
