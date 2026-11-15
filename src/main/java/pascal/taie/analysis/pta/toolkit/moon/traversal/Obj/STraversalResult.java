package pascal.taie.analysis.pta.toolkit.moon.traversal.Obj;



import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.AbstractField;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import java.util.*;

public class STraversalResult {
    private final List<Set<Obj>> matchedCtxObjsOfThisAsParam;
    private final List<Set<Obj>> matchedCtxObjsOfParamNonStatic;
    private final List<List<MultiMap<Var, Node>>> matchedLayerOfPassingArgsOfStatic;
    private final List<List<MultiMap<Var, Node>>> matchedLayerOfPassingArgsOfNonStatic;
    private final List<Set<Obj>> newlyAllocObjs;
    private final int recordSize;
    private boolean metParamOfAllocatedMethod;
    private final Set<JMethod> visitedMethods = new HashSet<>();
    private final AbstractField field;
    private final VarNode varStored;

    public STraversalResult(int matchLayer, AbstractField field, VarNode varStored) {
        this.varStored = varStored;
        this.field = field;
        matchLayer ++;
        recordSize = matchLayer;
        matchedCtxObjsOfThisAsParam = new ArrayList<>(matchLayer);
        matchedCtxObjsOfParamNonStatic = new ArrayList<>(matchLayer);
        newlyAllocObjs = new ArrayList<>(matchLayer);
        matchedLayerOfPassingArgsOfNonStatic = new ArrayList<>(matchLayer);
        matchedLayerOfPassingArgsOfStatic = new ArrayList<>(matchLayer);
        for (int i = 0; i < matchLayer; i++) {
            matchedCtxObjsOfThisAsParam.add(null);
            matchedCtxObjsOfParamNonStatic.add(null);
            newlyAllocObjs.add(null);
            matchedLayerOfPassingArgsOfNonStatic.add(null);
            matchedLayerOfPassingArgsOfStatic.add(null);
        }
    }

    public AbstractField getField() {
        return field;
    }

    public Set<JMethod> getVisitedMethods(){
        return visitedMethods;
    }
    public void addVisitedMethod(JMethod method) {
        visitedMethods.add(method);
    }

    public void hasMetParamOfAllocatedMethod() {
        this.metParamOfAllocatedMethod = true;
    }
    public boolean isMetParamOfAllocatedMethod() {
        return metParamOfAllocatedMethod;
    }

    public int getRecordSize() {
        return recordSize;
    }



    public void addNewlyAllocObj(Obj newlyAllocObj, int index) {
        if(this.newlyAllocObjs.get(index) == null){
            Set<Obj> newlyAllocObjs = Sets.newSet();
            newlyAllocObjs.add(newlyAllocObj);
            this.newlyAllocObjs.set(index, newlyAllocObjs);
        }else{
            this.newlyAllocObjs.get(index).add(newlyAllocObj);
        }
    }

    public void addMatchedCtxObjsByThisAsParam(Set<Obj> matchedCtxObjsOfThis, int index) {
        if(matchedCtxObjsOfThis.isEmpty()) return;
        if(this.matchedCtxObjsOfThisAsParam.get(index) == null){
            this.matchedCtxObjsOfThisAsParam.set(index, Sets.newSet(matchedCtxObjsOfThis));
        }else{
            this.matchedCtxObjsOfThisAsParam.get(index).addAll(matchedCtxObjsOfThis);
        }
    }

    public void addMatchedCtxObjsOfParam(Set<Obj> matchedCtxObjsOfParam, int index, Var parameter, Set<Node> passingArgs, boolean isInStatic) { // , Set<Node> passingArgs
        if(matchedCtxObjsOfParam.isEmpty()) return;


        if(this.matchedCtxObjsOfParamNonStatic.get(index) == null){
            this.matchedCtxObjsOfParamNonStatic.set(index, Sets.newSet(matchedCtxObjsOfParam));
        }else{
            this.matchedCtxObjsOfParamNonStatic.get(index).addAll(matchedCtxObjsOfParam);
        }



        if(!passingArgs.isEmpty()){

            List<List<MultiMap<Var, Node>>> targetLayerOfPassingArgs = isInStatic ? matchedLayerOfPassingArgsOfStatic : matchedLayerOfPassingArgsOfNonStatic;
//            if(isInStatic){
//                passingArgs = passingArgs.stream().filter(n -> n instanceof LocalVarNode lvn && !lvn.getMethod().isStaticInitializer()).collect(Collectors.toSet());
//            }
            if(targetLayerOfPassingArgs.get(index) == null){
                targetLayerOfPassingArgs.set(index, new ArrayList<>());
            }
            MultiMap<Var, Node> multiMap = Maps.newMultiMap();
            multiMap.putAll(parameter, passingArgs);
            targetLayerOfPassingArgs.get(index).add(multiMap);
        }

    }



    public List<List<MultiMap<Var, Node>>> getMatchedLayerOfPassingArgsOfNonStatic() {
        return matchedLayerOfPassingArgsOfNonStatic;
    }
    public List<List<MultiMap<Var, Node>>> getMatchedLayerOfPassingArgsOfStatic() {
        return matchedLayerOfPassingArgsOfStatic;
    }

    public Set<Obj> getMatchedCtxObjsOfThis(int index) {
        if(index >= recordSize) return Collections.emptySet();
        Set<Obj> matchedCtxObjsOfThis = this.matchedCtxObjsOfThisAsParam.get(index);
        return matchedCtxObjsOfThis == null ? Collections.emptySet() : Set.copyOf(matchedCtxObjsOfThis);
    }
    public Set<Obj> getMatchedCtxObjsOfParam(int index) {
        if(index >= recordSize) return Collections.emptySet();
        Set<Obj> matchedCtxObjsOfParam = this.matchedCtxObjsOfParamNonStatic.get(index);
        return matchedCtxObjsOfParam == null ? Collections.emptySet() : Set.copyOf(matchedCtxObjsOfParam);
    }
    public Set<Obj> getNewlyAllocObjs(int index) {
        if(index >= recordSize) return Collections.emptySet();
        Set<Obj> newlyAllocObjs = this.newlyAllocObjs.get(index);
        return newlyAllocObjs == null ? Collections.emptySet() : Set.copyOf(newlyAllocObjs);
    }


    public boolean hasSpecificCtxObj(Obj ctxObj){
        return this.matchedCtxObjsOfParamNonStatic.stream().anyMatch(s -> s != null && s.contains(ctxObj)) || this.matchedCtxObjsOfThisAsParam.stream().anyMatch(s -> s != null && s.contains(ctxObj));
    }

    public boolean hasSpecificNewlyAllocObj(Obj newlyAllocObj){
        return this.newlyAllocObjs.stream().anyMatch(s -> s != null && s.contains(newlyAllocObj));
    }

    public boolean hasAnyOfNewlyAllocObjs(Set<Obj> newlyAllocObjs){
        return this.newlyAllocObjs.stream().anyMatch(s -> s != null && !Collections.disjoint(s, newlyAllocObjs));
    }

    public boolean hasAnyOfCtxObjsOfThis(){
        return this.matchedCtxObjsOfThisAsParam.stream().anyMatch(s -> s != null && !s.isEmpty());
    }

    public Set<Obj> getAllNewlyAllocObjs(){
        Set<Obj> allNewlyAllocObjs = Sets.newSet();
        this.newlyAllocObjs.stream().filter(s -> s != null).forEach(allNewlyAllocObjs::addAll);
        return allNewlyAllocObjs;
    }

    public Set<Obj> ctxObjsOnLayerOf(int checkLayer){
        Set<Obj> ctxObjs = Sets.newSet();
        ctxObjs.addAll(getMatchedCtxObjsOfParam(checkLayer));
        ctxObjs.addAll(getMatchedCtxObjsOfThis(checkLayer));
        return ctxObjs;
    }

    public boolean hasNewlyAllocObjs(){
        return newlyAllocObjs.stream().anyMatch(allocNodes -> allocNodes != null && !allocNodes.isEmpty());
    }

}
