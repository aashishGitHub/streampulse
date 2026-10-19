/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/** A fake store that logs every call and can be told to fail or corrupt at exact points. */
final class InMemoryArchiveStore implements ArchiveStore {

    record Entry(byte[] bytes, Instant lastModified) {}

    private final Map<String, Entry> objects = new TreeMap<>();
    private final Clock clock;
    final List<String> calls = new ArrayList<>();

    private boolean corruptNextDataPut;
    private boolean failNextPutText;
    private int deleteFailsAfter = -1;
    private Predicate<String> failOpenFor = key -> false;

    InMemoryArchiveStore(Clock clock) {
        this.clock = clock;
    }

    // ---- arrange ----------------------------------------------------------------------

    void addRaw(String key, Instant lastModified, String... lines) {
        objects.put(key, new Entry(Gz.ndjson(lines), lastModified));
    }

    void addObject(String key, byte[] bytes, Instant lastModified) {
        objects.put(key, new Entry(bytes, lastModified));
    }

    void addText(String key, String text, Instant lastModified) {
        objects.put(key, new Entry(text.getBytes(StandardCharsets.UTF_8), lastModified));
    }

    void corruptNextDataPut() {
        corruptNextDataPut = true;
    }

    void failNextPutText() {
        failNextPutText = true;
    }

    /** The next delete call removes its first {@code n} keys, then throws, like a crash part-way through. */
    void deleteFailsAfter(int n) {
        deleteFailsAfter = n;
    }

    void failOpenFor(Predicate<String> keys) {
        failOpenFor = keys;
    }

    // ---- inspect ----------------------------------------------------------------------

    List<String> keys() {
        return List.copyOf(objects.keySet());
    }

    List<String> keysMatching(String regex) {
        return objects.keySet().stream().filter(k -> k.matches(regex)).toList();
    }

    long recordsIn(String key) {
        String text = Gz.gunzip(objects.get(key).bytes());
        return text.lines().filter(l -> !l.isEmpty()).count();
    }

    String contentOf(String key) {
        return Gz.gunzip(objects.get(key).bytes());
    }

    String textOf(String key) {
        return new String(objects.get(key).bytes(), StandardCharsets.UTF_8);
    }

    Map<String, Entry> snapshot() {
        var copy = new TreeMap<String, Entry>();
        objects.forEach((k, v) -> copy.put(k, new Entry(v.bytes().clone(), v.lastModified())));
        return copy;
    }

    boolean sameAs(Map<String, Entry> before) {
        if (!before.keySet().equals(objects.keySet())) {
            return false;
        }
        return before.entrySet().stream()
                .allMatch(e -> java.util.Arrays.equals(
                        e.getValue().bytes(), objects.get(e.getKey()).bytes()));
    }

    long mutatingCalls() {
        return calls.stream()
                .filter(c -> c.startsWith("put:") || c.startsWith("putText:") || c.startsWith("delete:"))
                .count();
    }

    int indexOfCall(String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // ---- ArchiveStore -----------------------------------------------------------------

    @Override
    public List<ArchiveObject> list(String prefix) {
        calls.add("list:" + prefix);
        return objects.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> new ArchiveObject(
                        e.getKey(), e.getValue().lastModified(), e.getValue().bytes().length))
                .toList();
    }

    @Override
    public InputStream open(String key) {
        calls.add("open:" + key);
        if (failOpenFor.test(key)) {
            throw new UncheckedIOException(new IOException("injected open failure for " + key));
        }
        return new ByteArrayInputStream(objects.get(key).bytes());
    }

    @Override
    public String readText(String key) {
        calls.add("readText:" + key);
        return new String(objects.get(key).bytes(), StandardCharsets.UTF_8);
    }

    @Override
    public void put(String key, Path file) {
        calls.add("put:" + key);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (corruptNextDataPut) {
                corruptNextDataPut = false;
                List<String> lines = Gz.gunzip(bytes).lines().toList();
                bytes = Gz.ndjson(lines.subList(0, lines.size() - 1).toArray(String[]::new));
            }
            objects.put(key, new Entry(bytes, clock.instant()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void putText(String key, String text) {
        calls.add("putText:" + key);
        if (failNextPutText) {
            failNextPutText = false;
            throw new UncheckedIOException(new IOException("injected putText failure"));
        }
        objects.put(key, new Entry(text.getBytes(StandardCharsets.UTF_8), clock.instant()));
    }

    @Override
    public void delete(Collection<String> keys) {
        calls.add("delete:" + String.join(",", keys));
        int removed = 0;
        for (String key : keys) {
            if (deleteFailsAfter >= 0 && removed == deleteFailsAfter) {
                deleteFailsAfter = -1;
                throw new UncheckedIOException(new IOException("injected crash during delete"));
            }
            objects.remove(key);
            removed++;
        }
    }
}
