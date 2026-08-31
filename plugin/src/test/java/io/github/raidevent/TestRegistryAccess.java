package io.github.raidevent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

/**
 * テスト用のレジストリ。
 *
 * <p>26.x では {@code Attribute.MAX_HEALTH} のような定数がレジストリ参照になっており、
 * サーバー無しで触ると「No RegistryAccess implementation found」で落ちる。Paper は
 * {@code ServiceLoader} で実装を探すので、テストのクラスパスにだけこれを置いて解決する。
 *
 * <p>返すのは「キーごとに一意なダミー」であって本物ではない。効果の実装は attribute を
 * <b>識別子として</b> しか使わない (どの attribute instance を引くかの鍵) ので、
 * 同じキーに同じインスタンスが返れば足りる。
 *
 * <p>中身は動的プロキシで作る。Mockito ではモックできない型 ({@code ItemType} など) が
 * あるため。振る舞いが要る場合は {@link #override} で個別に足す。
 *
 * <p>{@code src/test/resources/META-INF/services/io.papermc.paper.registry.RegistryAccess}
 * から読まれる。本番の jar には入らない。
 */
public final class TestRegistryAccess implements RegistryAccess {

    /** ダミーごとの「このメソッドはこう返す」の指定。 */
    private static final Map<Object, Map<String, Object>> OVERRIDES = new ConcurrentHashMap<>();

    private final Map<Class<?>, Registry<?>> registries = new ConcurrentHashMap<>();

    /**
     * ダミーの振る舞いを1つ足す。
     *
     * <p>例: {@code override(Material.BREAD.asItemType(), "isEdible", true)}
     *
     * <p>効くのはインターフェースから作ったダミーだけ。抽象クラスのものは Mockito 製
     * なので、そちらは {@code Mockito.when} で直接指定すること。
     */
    public static void override(Object entry, String method, Object value) {
        if (!Proxy.isProxyClass(entry.getClass())) {
            throw new IllegalArgumentException(
                    "Mockito 製のダミーには効かない。Mockito.when を使うこと: " + entry);
        }
        OVERRIDES.computeIfAbsent(entry, key -> new ConcurrentHashMap<>()).put(method, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return (Registry<T>) registries.computeIfAbsent(type, DummyRegistry::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return (Registry<T>) getRegistry(typeOf(key));
    }

    /**
     * レジストリのキーから、そこに入る型を引く。
     *
     * <p>ダミーを作るのに具体型が要る。{@code RegistryKey<Attribute> ATTRIBUTE} のように
     * 定数が型引数を持っているので、宣言から取り出す。個別の対応表を持たずに済む。
     */
    private static Class<? extends Keyed> typeOf(RegistryKey<?> key) {
        for (java.lang.reflect.Field field : RegistryKey.class.getFields()) {
            if (!RegistryKey.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                if (!key.equals(field.get(null))) {
                    continue;
                }
            } catch (IllegalAccessException e) {
                continue;
            }
            Type generic = field.getGenericType();
            if (generic instanceof ParameterizedType parameterized) {
                Type argument = parameterized.getActualTypeArguments()[0];
                // GameRule<?> のように型引数自身が入れ子になっている場合がある
                if (argument instanceof ParameterizedType nested) {
                    argument = nested.getRawType();
                }
                if (argument instanceof Class<?> type && Keyed.class.isAssignableFrom(type)) {
                    return type.asSubclass(Keyed.class);
                }
            }
        }
        throw new UnsupportedOperationException("型を特定できないレジストリ: " + key);
    }

    /** キーごとに同じダミーを返すだけのレジストリ。 */
    private static final class DummyRegistry<T extends Keyed> implements Registry<T> {

        private final Class<T> type;
        private final Map<NamespacedKey, T> entries = new ConcurrentHashMap<>();

        DummyRegistry(Class<?> type) {
            @SuppressWarnings("unchecked")
            Class<T> cast = (Class<T>) type;
            this.type = cast;
        }

        @Override
        public T get(NamespacedKey key) {
            return entries.computeIfAbsent(key, this::dummy);
        }

        /**
         * ダミーを1件作る。
         *
         * <p>インターフェースは動的プロキシで作る。{@code ItemType} のように Mockito が
         * 扱えない型があるため。抽象クラス ({@code PotionEffectType} など) はプロキシに
         * できないので Mockito に任せる。
         */
        @SuppressWarnings("unchecked")
        private T dummy(NamespacedKey key) {
            if (type.isInterface()) {
                return (T) Proxy.newProxyInstance(
                        type.getClassLoader(), new Class<?>[] {type}, new Dummy(key));
            }
            T entry = org.mockito.Mockito.mock(type);
            org.mockito.Mockito.when(entry.getKey()).thenReturn(key);
            return entry;
        }

        @Override
        public NamespacedKey getKey(T value) {
            return value.getKey();
        }

        @Override
        public boolean hasTag(TagKey<T> key) {
            return false;
        }

        @Override
        public Tag<T> getTag(TagKey<T> key) {
            throw new UnsupportedOperationException("テストではタグを使わない");
        }

        @Override
        public Collection<Tag<T>> getTags() {
            return List.of();
        }

        @Override
        public Stream<T> stream() {
            return entries.values().stream();
        }

        @Override
        public Stream<NamespacedKey> keyStream() {
            return entries.keySet().stream();
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return entries.values().iterator();
        }
    }

    /** ダミー1件の振る舞い。キーを答えるほかは、指定が無ければ既定値を返す。 */
    private record Dummy(NamespacedKey key) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            // Object の基本メソッドは指定表より先に answer する。
            // 表は proxy をキーにした Map なので、ここで先に返さないと
            // hashCode の解決がまた表を引きに来て無限再帰する。
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "Dummy(" + key + ")";
                default:
                    break;
            }
            Map<String, Object> overrides = OVERRIDES.get(proxy);
            if (overrides != null && overrides.containsKey(method.getName())) {
                return overrides.get(method.getName());
            }
            return switch (method.getName()) {
                case "getKey", "getKeyOrThrow" -> key;
                case "key" -> key.key();
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == void.class) {
                return null;
            }
            if (type == double.class) {
                return 0.0d;
            }
            if (type == float.class) {
                return 0.0f;
            }
            if (type == long.class) {
                return 0L;
            }
            return 0;
        }
    }
}
