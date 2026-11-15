package pascal.taie.analysis.pta.toolkit.skim.traverse;

import pascal.taie.language.classes.JMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class StaticCallStack {
    private static final StaticCallStack EMPTY =
            new StaticCallStack(List.of(), false);

    private final List<JMethod> callers;

    private final boolean hasSummarizedPrefix;

    private StaticCallStack(List<JMethod> callers,
                            boolean hasSummarizedPrefix) {
        this.callers = List.copyOf(callers);
        this.hasSummarizedPrefix = hasSummarizedPrefix;
    }

    public static StaticCallStack empty() {
        return EMPTY;
    }

    public StaticCallStack push(JMethod caller) {
        Objects.requireNonNull(caller);
        int firstIdx = callers.indexOf(caller);
        int lastIdx = callers.lastIndexOf(caller);

        if (firstIdx != -1 && lastIdx != -1 && firstIdx != lastIdx ) {
            return this;
        } else {
            List<JMethod> newCallers = new ArrayList<>(callers);
            newCallers.add(caller);
            return new StaticCallStack(newCallers, false);
        }
    }

    public Optional<JMethod> peek() {
        return callers.isEmpty()
                ? Optional.empty()
                : Optional.of(callers.get(callers.size() - 1));
    }

    public StaticCallStack pop() {
        if (callers.isEmpty()) {
            return this;
        }
        return new StaticCallStack(
                callers.subList(0, callers.size() - 1),
                hasSummarizedPrefix);
    }

    public int size() {
        return callers.size();
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof StaticCallStack other
                && hasSummarizedPrefix == other.hasSummarizedPrefix
                && callers.size() == other.callers.size();
    }

    @Override
    public int hashCode() {
        return Objects.hash(callers.size(), hasSummarizedPrefix);
    }

    @Override
    public String toString() {
        return (hasSummarizedPrefix ? "[...] " : "") + callers;
    }
}
