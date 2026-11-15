package pascal.taie.analysis.pta.toolkit.skim.preanalysis;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ArrIdx;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.FieldRecorder;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.Collection;
import java.util.Set;

public class ContainerCollector {
    private final FieldRecorder fieldRecorder;
    private final FieldReachability fieldReachabilityRecorder;
    protected final MultiMap<Obj, AbstractField> containerToFields = Maps.newConcurrentMultiMap();
    private final KeyTypeCollector keyTypeCollector;
    public ContainerCollector(SkimInfra skimInfra){
        this.fieldRecorder = skimInfra.extraInfoTracker.getFieldRecorder();
        this.fieldReachabilityRecorder = skimInfra.fieldReachability;
        this.keyTypeCollector = skimInfra.keyTypeCollector;
    }
    public void collect(Collection<Obj> allObjs){
        Set<Obj> toBeDetermined = Sets.newConcurrentSet();
        allObjs.parallelStream().forEach(
        heap -> {
            Type type = heap.getType();
            if(type instanceof ArrayType arrayType){
                if(arrayType.dimensions() > 0){
                        containerToFields.put(heap, ArrIdx.get());
                }
            }else if(type instanceof ClassType classType){
                if(keyTypeCollector.isConcernedType(classType) && heap.getContainerMethod().isPresent()){
                    toBeDetermined.add(heap);
                }
            }
        });

        toBeDetermined.parallelStream()
                .forEach(heap -> {
            Set<AbstractField> fields = fieldRecorder.objToFields.get(heap);
            for (AbstractField field : fields) {
                if(!keyTypeCollector.isConcernedType(field.getType())) continue;
                if(fieldReachabilityRecorder.isConnceredField(heap, field)){
                    containerToFields.put(heap, field);
                }
            }
        });
    }
}
