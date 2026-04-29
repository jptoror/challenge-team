## Code Review

You are reviewing the following code submitted as part of a task to implement an item cache in a highly concurrent application. The anticipated load includes: thousands of reads per second, hundreds of writes per second, tens of concurrent threads.
Your objective is to identify and explain the issues in the implementation that must be addressed before deploying the code to production. Please provide a clear explanation of each issue and its potential impact on production behaviour.

```java
import java.util.concurrent.ConcurrentHashMap;

public class SimpleCache<K, V> {
    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final long ttlMs = 60000; // 1 minute

    public static class CacheEntry<V> {
        private final V value;
        private final long timestamp;

        public CacheEntry(V value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public V getValue() {
            return value;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() - entry.getTimestamp() < ttlMs) {
                return entry.getValue();
            }
        }
        return null;
    }

    public int size() {
        return cache.size();
    }
}
```

## Issues

The biggest one, and the reason I wouldn't ship this as-is, is that nothing ever evicts expired entries. `get` returns `null` past the TTL but the entry stays in the map, so any key that's written and not read again sits there forever. With hundreds of writes/sec and a key space wider than the working set, the heap just keeps climbing until the JVM dies. The usual fix is to remove on read with the two-arg `cache.remove(key, entry)` (so a fresh write that raced in doesn't get blown away), but on its own that's not enough either: a write-only key never triggers a read and never gets cleaned. So you also want a periodic sweeper, or a bounded map.

Related, there's no maximum size and no eviction policy. Even with a working TTL, nothing prevents the cache from growing without bound during a traffic burst with high key cardinality. For a load like this (thousands of reads/sec, write rate in the hundreds) I'd honestly not roll my own — Caffeine with `maximumSize` + `expireAfterWrite` + `recordStats()` does everything below in a few lines and is what I'd reach for in real code. The rest of the comments assume we're sticking with the `ConcurrentHashMap` skeleton.

`ttlMs = 60000` is a hardcoded private constant. Can't tune per environment, can't override in tests, can't have different TTLs for different entries. Should come in through the constructor as a `Duration`.

The TTL math uses `System.currentTimeMillis()`, which is wall-clock and can move backwards (NTP, leap-second smearing, sysadmin fiddling). When it does, `now - timestamp` goes negative, the `<` check still passes, and entries effectively live longer than they should. After a forward jump everything expires at once and you get a stampede. `System.nanoTime()` is the right primitive for relative durations; better still, take a `Clock` so the test suite can advance time without `Thread.sleep`.

Speaking of stampedes — there's no protection against one. When a hot key expires, every thread reading it sees `null` at roughly the same instant, hits the backend, and writes the same value back. At thousands of reads/sec on a popular key that's a self-inflicted DDoS on whatever the cache is fronting. The standard fix is a loader-based API (`get(K, Function<K,V> loader)` implemented on top of `computeIfAbsent`) so only one thread does the reload; refresh-ahead before expiry helps too if the load is heavy enough to justify it.

`put` is plain `cache.put`, which means concurrent writers race purely on order of arrival — last writer wins regardless of which value was actually computed more recently. In a system where reads from the source of truth take variable time, that can pin a stale value on top of a fresh one. `cache.merge(key, fresh, (old, n) -> n.timestamp() >= old.timestamp() ? n : old)` keeps the newer one.

The read path is technically two non-atomic operations (`cache.get` then `currentTimeMillis`), so a long GC pause between them can flip the verdict. In practice this is mostly noise, but it matters for the eviction fix above: you have to `remove(key, entry)` with the exact entry reference you read, otherwise a write that landed during the pause gets thrown away.

`get` returns `null` for three different things — key never inserted, key expired, value was explicitly null — and the caller can't tell which. That also forecloses on caching nullable values at all. `Optional<V>` would at least separate present-vs-absent; for "expired" you need a richer return.

There's no invalidation. After someone writes to the underlying store, callers have no way to tell the cache "drop this key", so stale data is served for up to a TTL with no escape. In any system where the cache fronts mutable data, an `invalidate(key)` (and probably `invalidateAll`) is non-negotiable.

`size()` returns the raw map size, expired entries included. Useless for monitoring or capacity planning — you can't tell apart "cache is doing its job" from "cache is leaking". Same theme: no hit/miss/eviction counters either, so when latency regresses in prod there's no way to tell if the cache is involved. Wiring it to Micrometer (or just exposing a `stats()` object) is cheap and pays for itself the first time something goes wrong.

A few smaller things, mostly API hygiene:

`CacheEntry` is `public static`. It's an internal data holder that has no business being on the public surface — making it public freezes the `long` epoch-millis representation into the API forever, which makes the `nanoTime` migration above harder than it needs to be. Move it to `private static` and, while you're there, make it a `record`: `private record CacheEntry<V>(V value, long timestamp) {}` is the whole class.

`put(key, null)` silently succeeds because the `null` is wrapped in a `CacheEntry`. Then `get` returns `null` and the caller assumes a miss. Either reject with `Objects.requireNonNull(value)` or commit to supporting null values intentionally (which ties back to the `Optional` discussion). `null` keys do throw, but from deep inside `ConcurrentHashMap` with a generic message — a guard at the top of `put`/`get` makes the error message useful.

The class isn't `final`, has no JavaDoc, and doesn't document its thread-safety contract or what TTL semantics it implements (write-time vs access-time — important difference for callers). None of those are bugs, but for something that goes into a high-traffic path I'd want all three before I let it merge.
