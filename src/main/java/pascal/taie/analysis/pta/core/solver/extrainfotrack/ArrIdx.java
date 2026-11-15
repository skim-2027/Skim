package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.World;
import pascal.taie.language.type.Type;

public enum ArrIdx implements AbstractField {
    INSTANCE;

    private static Type objType;
    public static ArrIdx get(){
        return INSTANCE;
    }

    @Override
    public Type getType() {
        if (objType == null) {
            objType = World.get().getTypeSystem().getType("java.lang.Object");
        }
        return objType;
    }
}
