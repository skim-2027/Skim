package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.ir.exp.Var;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.TwoKeyMap;
import pascal.taie.util.collection.TwoKeyMultiMap;

import java.util.Set;

public class CallDetails {

    private final TwoKeyMultiMap<Var, Var, Var> recvVarMap = Maps.newTwoKeyMultiMap();


    public void putRecvVar(Var toVar, Var fromVar, Var recvVar){
        recvVarMap.put(toVar, fromVar, recvVar);
    }


    public Set<Var> getRecvVar(Var toVar, Var fromVar){
        return recvVarMap.get(toVar, fromVar);
    }
}
