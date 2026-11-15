package pascal.taie.analysis.pta.toolkit.moon.support;

import pascal.taie.World;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.FieldRecorder;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.FieldWrapper;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Sets;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class KeyTypeCollector {

    private static final Type OBJECT_TYPE = World.get().getTypeSystem().getType("java.lang.Object");
    private final Set<Type> polyTypes = Sets.newHybridSet();
    private final FieldRecorder fieldRecorder;
    public KeyTypeCollector(FieldRecorder fieldRecorder){
        this.fieldRecorder = fieldRecorder;
    }

    private boolean isPolyType(Type type){
        if(type.equals(OBJECT_TYPE)){
            return true;
        }
        if(type instanceof ClassType classType){
            JClass jClass = classType.getJClass();
            return jClass.isAbstract() || jClass.isInterface() || jClass.getName().startsWith("Abstract");
        }
        return false;
    }

    public boolean isConcernedType(Type type){
        if(type instanceof ArrayType arrayType){
            type = arrayType.elementType();
        }
        return isPolyType(type) || this.polyTypes.contains(type);
    }


    private Set<AbstractField> fieldsOf(Obj node) {
        return fieldRecorder.objToFields.get(node);
    }


    private final Set<Type> metType = Sets.newHybridSet();
    private Set<AbstractField> fieldsOf(Type type) {
        if (type instanceof ClassType refType) {
            if(!fieldRecorder.typeToFields.containsKey(type) && !metType.contains(type)) {
                metType.add(type);
                for (Obj heap : fieldRecorder.objToFields.keySet()) {
                    if (World.get().getTypeSystem().isSubtype(refType, heap.getType())) {
                        for (AbstractField sparkField : fieldRecorder.objToFields.get(heap)) {
                            if (sparkField instanceof FieldWrapper f) {
                                JField jField = f.field();
                                Type declType = jField.getDeclaringClass().getType();
                                if (World.get().getTypeSystem().isSubtype(declType, type)) {
                                    fieldRecorder.typeToFields.put(type, sparkField);
                                }
                            } else {
                                throw new RuntimeException(sparkField + ";" + sparkField.getClass());
                            }
                        }
                    }
                }
            }
            return fieldRecorder.typeToFields.get(type);
        } else {
            return Collections.emptySet();
        }
    }

    public void run(Collection<Obj> allObjs) {
        Set<Type> types = Sets.newHybridSet();
        for (Obj heap : allObjs) {
            if(heap.getContainerMethod().isEmpty()) continue;
            Type type = heap.getType();
            if (type instanceof ArrayType at) {
                Type et = at.elementType();
                if (isPolyType(et)) {
                    polyTypes.add(et);
                } else {
                    types.add(et);
                }
            } else {
                for (AbstractField field : fieldsOf(heap)) {
                    Type ft = field.getType();
                    if (ft instanceof ArrayType fat) {
                        ft = fat.elementType();
                    }
                    if (isPolyType(ft)) {
                        polyTypes.add(ft);
                        polyTypes.add(type);
                    } else {
                        types.add(type);
                        types.add(ft);
                    }
                }
            }
        }
        boolean continueUpdating = true;
        while (continueUpdating) {
            continueUpdating = false;
            for (Type type : types) {
                for (AbstractField field : fieldsOf(type)) {
                    Type ft = field.getType();
                    if (isConcernedType(ft)) {
                        if (polyTypes.add(type)) {
                            continueUpdating = true;
                        }
                    }
                }
            }
        }
    }
}
