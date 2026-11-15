package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.language.type.Type;

public record TypedArrIdx(Type elemType) implements AbstractField {
        @Override
        public int hashCode() {
            return elemType.hashCode();
        }

    @Override
    public Type getType() {
        return elemType;
    }
}
