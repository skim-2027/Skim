package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.language.type.Type;

public interface AbstractField extends ExtraEdgeLabel {

    Type getType();
}
