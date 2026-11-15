package pascal.taie.analysis.pta.core.solver.extrainfotrack;


import pascal.taie.language.type.Type;

/**
 * EmptyField is a singleton class that represents any field of an object, used in Connected Obj Field in CtxTunneling.java
 * It comes when an object plays the role of "allocator", which means all the methods and fields share the same ctx tunneling policy.
 */
public enum ArbitraryField implements AbstractField {
    INSTANCE;

    public static ArbitraryField get() {
        return INSTANCE;
    }

    @Override
    public Type getType() {
        throw new RuntimeException("ArbitraryField does not have a specific type.");
    }
}
