package pascal.taie.util.collection;

import pascal.taie.util.TriFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UnmodifiableTwoKeyMap<K1, K2, V> implements
        TwoKeyMap<K1, K2, V>, Serializable {

    private final TwoKeyMap<K1, K2, V> m;

    UnmodifiableTwoKeyMap(@Nonnull TwoKeyMap<K1, K2, V> m) {
        this.m = m;
    }

    @Override
    public boolean containsKey(K1 key1, K2 key2) {
        return m.containsKey(key1, key2);
    }

    @Override
    public boolean containsKey(K1 key1) {
        return m.containsKey(key1);
    }

    @Override
    public boolean containsValue(V value) {
        return m.containsValue(value);
    }

    @Nullable
    @Override
    public V get(K1 key1, K2 key2) {
        return m.get(key1, key2);
    }

    @Nullable
    @Override
    public Map<K2, V> get(K1 key1) {
        return m.get(key1);
    }

    @Nullable
    @Override
    public V put(@Nonnull K1 key1, @Nonnull K2 key2, @Nonnull V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(@Nonnull K1 key1, @Nonnull Map<K2, V> map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(@Nonnull TwoKeyMap<K1, K2, V> twoKeyMap) {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public V remove(K1 key1, K2 key2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(K1 key1) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void replaceALl(TriFunction<? super K1, ? super K2, ? super V, ? extends V> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Pair<K1, K2>> twoKeySet() {
        return m.twoKeySet();
    }

    @Override
    public Set<K1> keySet() {
        return m.keySet();
    }

    @Override
    public Collection<V> values() {
        return m.values();
    }

    @Override
    public Set<Entry<K1, K2, V>> entrySet() {
        return m.entrySet();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        return m.isEmpty();
    }

    @Override
    public int size() {
        return m.size();
    }
}
