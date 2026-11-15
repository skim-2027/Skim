package pascal.taie.analysis.pta.core.cs.element;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.language.classes.JField;

public class ContextField extends InstanceField{
    private final Context context;
    public ContextField(Context context, JField field, int index, CSObj representBase) {
        super(representBase, field, index);
        this.context = context;
    }


    public Context getContext() {
        return context;
    }
}
