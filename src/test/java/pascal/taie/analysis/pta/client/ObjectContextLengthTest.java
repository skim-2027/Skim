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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectContextLengthTest {

    @ParameterizedTest
    @ValueSource(strings = {"ci", "2-obj", "3-obj"})
    void countsHeapContexts(String cs) {
        Main.main("-cp", "src/test/resources/pta/contextsensitivity",
                "-m", "ObjectContextLengths", "-a",
                "pta=cs:" + cs + ";implicit-entries:false;only-app:true",
                "-a", ObjectContextLength.ID);
        Map<Integer, Long> counts = World.get().getResult(ObjectContextLength.ID);
        PointerAnalysisResult pta = World.get().getResult(PointerAnalysis.ID);
        assertEquals((long) pta.getCSObjects().size(),
                counts.values().stream().mapToLong(Long::longValue).sum());
        assertTrue(counts.get(0) > 0);
        if (cs.equals("ci")) {
            assertEquals(Set.of(0), counts.keySet());
        } else {
            assertEquals(cs.equals("2-obj") ? 3L : 2L, counts.get(1));
            if (cs.equals("3-obj")) {
                assertEquals(2L, counts.get(2));
            }
        }
        assertThrows(UnsupportedOperationException.class,
                () -> counts.put(99, 1L));
    }
}
