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

package pascal.taie.analysis.pta.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.config.AnalysisConfig;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts context-sensitive objects by heap-context length, including length zero.
 * Each CSObj in the pointer analysis result is counted once, regardless of how
 * many points-to sets contain it. Only observed lengths appear in the result.
 */
public class ObjectContextLength extends ProgramAnalysis<Map<Integer, Long>> {

    public static final String ID = "object-context-length";

    private static final Logger logger =
            LoggerFactory.getLogger(ObjectContextLength.class);

    public ObjectContextLength(AnalysisConfig config) {
        super(config);
    }

    @Override
    public Map<Integer, Long> analyze() {
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);
        Map<Integer, Long> counts = new TreeMap<>();
        for (CSObj obj : result.getCSObjects()) {
            counts.merge(obj.getContext().getLength(), 1L, Long::sum);
        }
        logger.info("Object context length distribution (all CSObjs):");
        counts.forEach((length, count) -> logger.info(
                "Context length {}: {} CSObjs", length, count));
        logger.info("Total: {} CSObjs", result.getCSObjects().size());
        return Collections.unmodifiableMap(counts);
    }
}
