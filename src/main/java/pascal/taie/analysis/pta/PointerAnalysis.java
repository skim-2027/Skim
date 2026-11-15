/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta;

import org.slf4j.event.Level;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelectorFactory;
import pascal.taie.analysis.pta.core.cs.selector.KObjElemSelector;
import pascal.taie.analysis.pta.core.heap.AllocationSiteBasedModel;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.DefaultSolver;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.core.solver.extrainfotrack.ExtraTrackSolver;
import pascal.taie.analysis.pta.plugin.AnalysisTimer;
import pascal.taie.analysis.pta.plugin.ClassInitializer;
import pascal.taie.analysis.pta.plugin.CompositePlugin;
import pascal.taie.analysis.pta.plugin.EntryPointHandler;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.ReferenceHandler;
import pascal.taie.analysis.pta.plugin.ResultProcessor;
import pascal.taie.analysis.pta.plugin.ThreadHandler;
import pascal.taie.analysis.pta.plugin.android.AndroidAnalysis;
import pascal.taie.analysis.pta.plugin.exception.ExceptionAnalysis;
import pascal.taie.analysis.pta.plugin.invokedynamic.InvokeDynamicAnalysis;
import pascal.taie.analysis.pta.plugin.invokedynamic.Java9StringConcatHandler;
import pascal.taie.analysis.pta.plugin.invokedynamic.LambdaAnalysis;
import pascal.taie.analysis.pta.plugin.natives.NativeModeller;
import pascal.taie.analysis.pta.plugin.reflection.ReflectionAnalysis;
import pascal.taie.analysis.pta.plugin.spring.SpringAnalysis;
import pascal.taie.analysis.pta.plugin.taint.TaintAnalysis;
import pascal.taie.analysis.pta.toolkit.CollectionMethods;
import pascal.taie.analysis.pta.toolkit.mahjong.Mahjong;
import pascal.taie.analysis.pta.toolkit.moon.Moon;
import pascal.taie.analysis.pta.toolkit.scaler.Scaler;
import pascal.taie.analysis.pta.toolkit.skim.AdaptiveCSManager;
import pascal.taie.analysis.pta.toolkit.skim.Skim;
import pascal.taie.analysis.pta.toolkit.zipper.Zipper;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.config.ConfigException;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.Monitor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class PointerAnalysis extends ProgramAnalysis<PointerAnalysisResult> {

    public static final String ID = "pta";

    public PointerAnalysis(AnalysisConfig config) {
        super(config);
    }

    @Override
    public PointerAnalysisResult analyze() {
        AnalysisOptions options = getOptions();

        HeapModel heapModel = new AllocationSiteBasedModel(options);
        ContextSelector selector = null;
        CSManager csManager = null;
        String advanced = options.getString("advanced");
        String cs = options.getString("cs");
        boolean preAnalysisOnly = options.getBoolean("pre-analysis-only");
        if (preAnalysisOnly && !"moon".equals(advanced) && !"skim".equals(advanced)) {
            throw new ConfigException("pre-analysis-only requires moon or skim");
        }
        int skimMaxRounds = options.getInt("skim-max-rounds");
        if ("skim".equals(advanced) && skimMaxRounds < 1) {
            throw new ConfigException("skim-max-rounds must be at least 1");
        }
        if (advanced != null) {
            if (advanced.equals("collection")) {
                selector = ContextSelectorFactory.makeSelectiveSelector(cs,
                        new CollectionMethods(World.get().getClassHierarchy()).get());
            } else if (advanced.equals("tunneling")) {
                selector = ContextSelectorFactory.makeObjectTunnelingSelector(
                        cs);
            } else {
                // run context-insensitive analysis as pre-analysis
                PointerAnalysisResult preResult = runAnalysis(heapModel,
                        ContextSelectorFactory.makeCISelector(),
                        advanced.startsWith("skim") || advanced.startsWith("moon"),
                        false);
                if (advanced.startsWith("scaler")) {
                    selector = Monitor.runAndCount(() -> ContextSelectorFactory
                                    .makeGuidedSelector(Scaler.run(preResult, advanced)),
                            "Scaler", Level.INFO);
                } else if (advanced.startsWith("zipper")) {
                    selector = Monitor.runAndCount(() -> ContextSelectorFactory
                                    .makeSelectiveSelector(cs, Zipper.run(preResult, advanced)),
                            "Zipper", Level.INFO);
                } else if (advanced.equals("mahjong")) {
                    heapModel = Monitor.runAndCount(() -> Mahjong.run(preResult, options),
                            "Mahjong", Level.INFO);
                } else if (advanced.equals("skim")) {
                    Object[] contextInfo = ContextSelectorFactory.getContextInfo(cs);
                    int k = (int) contextInfo[1];
                    if(!"Obj".equals(contextInfo[0])) {
                        throw new ConfigException("Has to be object sensitivity");
                    }
                    var policy = Monitor.runAndCount(() -> Skim.run(preResult, k, skimMaxRounds),
                            "Skim", Level.INFO);
                    selector = ContextSelectorFactory.makeObjElemSelector(k, policy);
                    csManager = new AdaptiveCSManager((KObjElemSelector) selector);
                } else if (advanced.equals("moon")) {
                    Object[] contextInfo = ContextSelectorFactory.getContextInfo(cs);
                    int k = (int) contextInfo[1];
                    int hk = (int) contextInfo[2];
                    if (k != hk + 1) {
                        throw new ConfigException("Has to be KObj-K-1Heap sensitivity");
                    }
                    selector = Monitor.runAndCount(() -> ContextSelectorFactory
                                    .makeObjSelectiveSelector(cs, Moon.run(preResult, hk)),
                            "Moon", Level.INFO);
                }
                else {
                    throw new IllegalArgumentException(
                            "Illegal advanced analysis argument: " + advanced);
                }
                if (preAnalysisOnly) {
                    return preResult;
                }
            }
        }
        if (selector == null) {
            selector = ContextSelectorFactory.makePlainSelector(cs);
        }
        if (csManager == null) {
            csManager = new MapBasedCSManager();
        }
        return runAnalysis(heapModel, selector, csManager);
    }


    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector, CSManager csManager) {
        return runAnalysis(heapModel, selector, false, csManager, true);
    }

    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector, boolean trackExtraFlow,
                                              boolean allowDumpCI) {
        return runAnalysis(heapModel, selector, trackExtraFlow,
                new MapBasedCSManager(), allowDumpCI);
    }

    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector, boolean trackExtraFlow,
                                              CSManager csManager, boolean allowDumpCI) {
        AnalysisOptions options = getOptions();

        Solver solver =
                trackExtraFlow ?
                        new ExtraTrackSolver(options, heapModel, selector, csManager)
                        :
                        new DefaultSolver(options,
                                heapModel, selector, csManager);
        // The initialization of some Plugins may read the fields in solver,
        // e.g., contextSelector or csManager, thus we initialize Plugins
        // after setting all other fields of solver.
        setPlugin(solver, options, allowDumpCI);
        solver.solve();
        return solver.getResult();
    }

    private static void setPlugin(Solver solver, AnalysisOptions options,
                                  boolean allowDumpCI) {
        CompositePlugin plugin = new CompositePlugin();
        // add builtin plugins
        // To record elapsed time precisely, AnalysisTimer should be added at first.
        plugin.addPlugin(
                new AnalysisTimer(),
                new EntryPointHandler(),
                new ClassInitializer(),
                new ThreadHandler(),
                new NativeModeller(),
                new ExceptionAnalysis()
        );
        if (World.get().getOptions().isAndroidMode()) {
            plugin.addPlugin(new AndroidAnalysis());
        }
        int javaVersion = World.get().getOptions().getJavaVersion();
        if (javaVersion < 9) {
            // current reference handler doesn't support Java 9+
            plugin.addPlugin(new ReferenceHandler());
        }
        if (javaVersion >= 8) {
            plugin.addPlugin(new LambdaAnalysis());
        }
        if (javaVersion >= 9) {
            plugin.addPlugin(new Java9StringConcatHandler());
        }
        if (options.getString("reflection-inference") != null ||
                options.getString("reflection-log") != null) {
            plugin.addPlugin(new ReflectionAnalysis());
        }
        if (options.getBoolean("handle-invokedynamic") &&
                InvokeDynamicAnalysis.useMethodHandle()) {
            plugin.addPlugin(new InvokeDynamicAnalysis());
        }
        if (options.getString("taint-config") != null
                || !((List<String>) options.get("taint-config-providers")).isEmpty()) {
            plugin.addPlugin(new TaintAnalysis());
        }
        if (options.getBoolean("spring")) {
            plugin.addPlugin(new SpringAnalysis());
        }
        plugin.addPlugin(new ResultProcessor(allowDumpCI));
        // add plugins specified in options
        // noinspection unchecked
        addPlugins(plugin, (List<String>) options.get("plugins"));
        // connects plugins and solver
        plugin.setSolver(solver);
        solver.setPlugin(plugin);
    }

    private static void addPlugins(CompositePlugin plugin,
                                   List<String> pluginClasses) {
        for (String pluginClass : pluginClasses) {
            try {
                Class<?> clazz = Class.forName(pluginClass);
                Constructor<?> ctor = clazz.getConstructor();
                Plugin newPlugin = (Plugin) ctor.newInstance();
                plugin.addPlugin(newPlugin);
            } catch (ClassNotFoundException e) {
                throw new ConfigException(
                        "Plugin class " + pluginClass + " is not found");
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new AnalysisException("Failed to get constructor of " +
                        pluginClass + ", does the plugin class" +
                        " provide a public non-arg constructor?");
            } catch (InvocationTargetException | InstantiationException e) {
                throw new AnalysisException(
                        "Failed to create plugin instance for " + pluginClass, e);
            }
        }
    }
}
