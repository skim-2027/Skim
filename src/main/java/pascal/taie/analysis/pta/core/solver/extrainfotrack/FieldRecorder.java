package pascal.taie.analysis.pta.core.solver.extrainfotrack;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.toolkit.skim.preanalysis.KeyTypeCollector;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.TwoKeyMap;
import pascal.taie.util.collection.TwoKeyMultiMap;

import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FieldRecorder {


    public final TwoKeyMultiMap<Var, AbstractField, Var> varToFieldToStoreFromVar = Maps.newConcurrentTwoKeyMultiMap();
    public final TwoKeyMultiMap<Var, AbstractField, Var> varToFieldToLoadedToVar = Maps.newConcurrentTwoKeyMultiMap();

    public final MultiMap<Obj, AbstractField> objToFields = Maps.newConcurrentMultiMap();
    public final MultiMap<Type, AbstractField> typeToFields = Maps.newConcurrentMultiMap();

    public final MultiMap<AbstractField, Obj> fieldToBaseObjs = Maps.newConcurrentMultiMap();

    public final TwoKeyMap<JMethod, Var, AbstractField> instanceStores = Maps.newTwoKeyMap();
    public final TwoKeyMap<JMethod, Var, AbstractField> instanceLoads = Maps.newTwoKeyMap();
//    private final MultiMap<AbstractField, SootMethod> fieldToInMethods = Maps.newConcurrentMultiMap();
    public void putStore(Var var, AbstractField field, Var storeFromVar){
        varToFieldToStoreFromVar.put(var, field, storeFromVar);
        instanceStores.put(var.getMethod(), var, field);
    }

    public void putLoad(Var var, AbstractField field, Var loadedToVar){
        varToFieldToLoadedToVar.put(var, field, loadedToVar);
        instanceLoads.put(var.getMethod(), var, field);
    }
    public boolean hasStore(Var var, Obj obj){
        return hasUsage(varToFieldToStoreFromVar, var, obj);
    }

    public boolean hasLoad(Var var, Obj obj){
        return hasUsage(varToFieldToLoadedToVar, var, obj);
    }

    private boolean hasUsage(TwoKeyMultiMap<Var, AbstractField, Var> usageMap, Var var, Obj obj){
        if(obj.getType() instanceof ArrayType){
            return usageMap.containsKey(var);
        }
        if(!usageMap.containsKey(var)) return false;
        Set<JField> usageFields = usageMap.get(var).keySet().stream().map(sf -> ((FieldWrapper) sf).field()).collect(Collectors.toSet());
        if(usageFields.isEmpty()) throw new RuntimeException("fields usage is empty where it should not be.");
        return !usageFields.isEmpty();
    }


    public Set<Var> getStoredFromVars(Var var, AbstractField field){
        return varToFieldToStoreFromVar.get(var, field);
    }

    public Set<Var> arrGetStoredFromVars(Var var){
        return getStoredFromVars(var, ArrIdx.get());
    }

    public Set<Var> getLoadedToVars(Var var, AbstractField field){
        return varToFieldToLoadedToVar.get(var, field);
    }

    public Set<AbstractField> getLoadedFields(Var var){
        return varToFieldToLoadedToVar.get(var).keySet();
    }

    public Set<AbstractField> getStoredFields(Var var){
        return varToFieldToStoreFromVar.get(var).keySet();
    }

    public MultiMap<AbstractField, Var> getFieldAndStoredFromVars(Var var){
        return varToFieldToStoreFromVar.get(var);
    }


//    public Set<SootMethod> getInMethodsOf(AbstractField field){
//        return fieldToInMethods.get(field);
//    }

    public Set<AbstractField> allFields(Predicate<Type> isConcernedType){
        return objToFields.values().parallelStream().filter(f -> isConcernedType.test(f.getType())).collect(Collectors.toSet());
    }

    public void recordObjToField(Obj obj, AbstractField field){
        objToFields.put(obj, field);
        typeToFields.put(obj.getType(), field);
        fieldToBaseObjs.put(field, obj);
    }



}
