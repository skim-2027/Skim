package pascal.taie.analysis.pta.toolkit.moon;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.toolkit.moon.objCollection.ObjCollector;
import pascal.taie.analysis.pta.toolkit.moon.support.MoonInfra;
import pascal.taie.analysis.pta.toolkit.moon.traversal.Obj.TraversalInitializer;
import pascal.taie.analysis.pta.toolkit.moon.traversal.VFGTravForObj;
import pascal.taie.util.Monitor;

import java.util.Set;


public class Moon {
    public static boolean enableRecursivePRObjs = true;

    private final PointerAnalysisResult pta;
    private final int maxMatchLayer;


    public static Set<Obj> run(PointerAnalysisResult pta, int maxMatchLayer){
        if (maxMatchLayer > 3 || maxMatchLayer <= 0) {
            throw new RuntimeException("Moon does not support 5-obj or higher.");
        }
        Moon moon = new Moon(pta, maxMatchLayer);
        return moon.analyze();
    }

    public Moon(PointerAnalysisResult pta, int maxMatchLayer){
        this.pta = pta;
        this.maxMatchLayer = maxMatchLayer;
    }

    public Set<Obj> analyze() {
        System.out.println("Moon starts with object context limit = " + maxMatchLayer);
        MoonInfra moonInfra = Monitor.runAndCount(() -> new MoonInfra(pta), "#Moon Data Construction", Level.INFO);

        TraversalInitializer traversalInitializer = new TraversalInitializer(moonInfra, maxMatchLayer);
        var objToBaseVar = traversalInitializer.initializeObjToVarMap();
        VFGTravForObj traversal = new VFGTravForObj(maxMatchLayer, moonInfra);
        var traversalRet = traversal.traverse(objToBaseVar);

        ObjCollector objCollector = new ObjCollector(maxMatchLayer, moonInfra);
        var collectionDetails = objCollector.analyze(traversalRet);
        return collectionDetails.prObjs();
    }

}
