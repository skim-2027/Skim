package pascal.taie.analysis.pta.core.cs.element;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.Type;

public class ContextArrIdx extends ArrayIndex{
    private final Context context;
    private final Type type;
    public ContextArrIdx(Context context, Type type, int index) {
        super(null, index);
        this.context = context;
        this.type = type;
    }

    public Context getContext() {
        return context;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "[ContextArrIdx]" + context.toString();
    }
}
