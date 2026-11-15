package pascal.taie.analysis.pta.toolkit.skim;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.selector.KObjElemSelector;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.plugin.invokedynamic.LambdaAnalysis;

public class AdaptiveCSManager extends MapBasedCSManager {
    private final KObjElemSelector selector;

    public AdaptiveCSManager(KObjElemSelector selector) {
        this.selector = selector;
    }

    @Override
    public CSObj getCSObj(Context heapContext, Obj obj) {
        if (heapContext.getLength() == 0 || obj instanceof NewObj) {
            return super.getCSObj(heapContext, obj);
        } else if (obj instanceof MockObj mockObj) {
            if (LambdaAnalysis.isLambdaObj(mockObj)) {
                return super.getCSObj(heapContext, obj);
            }
            else {
                Context context = selector.selectObjContext(heapContext, obj);
                return super.getCSObj(context, obj);
            }
        }
        throw new RuntimeException("AdaptiveManager: the object should be either NewObj or MockObj.");
    }
}
