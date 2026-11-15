package pascal.taie.analysis.pta.core.solver.extrainfotrack;

public enum CallLabel implements ExtraEdgeLabel{

    INSTANCE;

    public static CallLabel get(){
        return INSTANCE;
    }
}
