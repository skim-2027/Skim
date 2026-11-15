package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.language.classes.JField;
import pascal.taie.language.type.Type;

public record FieldWrapper(JField field) implements AbstractField {

    @Override
    public int hashCode() {
        return  field.hashCode();
    }

    @Override
    public Type getType() {
        return field.getType();
    }
}
