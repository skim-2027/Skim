package pascal.taie.analysis.pta.toolkit.skim.graph;

import pascal.taie.util.Hashes;
import pascal.taie.util.graph.Edge;

public record LabelEdge<N, L>(N from, N to, L label) implements Edge<N> {
        @Override
        public int hashCode() {
            return Hashes.hash(from, to, label);
        }

        @Override
        public boolean equals(Object obj) {
            if(this == obj) return true;
            if(obj == null || getClass() != obj.getClass()) return false;
            LabelEdge other = (LabelEdge) obj;
            return from.equals(other.from) && to.equals(other.to) && label.equals(other.label);
        }

        @Override
        public N source() {
            return from;
        }

        @Override
        public N target() {
            return to;
        }

        public L label(){
            return label;
        }
    }
