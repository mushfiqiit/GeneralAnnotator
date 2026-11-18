package generalannotator.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** A small multi-file project for context extraction tests (written under {@code root/src}). */
final class ContextFixture {

    static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("p/Lib.java", """
                package p;

                import java.util.Map;

                public class Lib {
                    private String name;
                    private Map<String, String> cache;
                    static String shared = Boolean.getBoolean("x") ? "s" : null;

                    public Lib(String name) {
                        this.name = name;
                    }

                    public Lib() {
                        this(null);
                    }

                    public String find(String key) {
                        if (cache == null) {
                            return null;
                        }
                        return cache.get(key);
                    }

                    public String find(String key, String fallback) {
                        String v = find(key);
                        return v != null ? v : fallback;
                    }

                    String describe(String prefix) {
                        if (prefix == null) {
                            return name;
                        }
                        String name = prefix + "x";
                        return name.trim() + this.name.length();
                    }

                    public void setName(String n) {
                        name = n;
                    }
                }
                """);
        FILES.put("q/Client.java", """
                package q;

                import p.Lib;

                class Client {
                    String name;

                    int use(Lib lib, String k) {
                        String r = lib.find(k);
                        if (r != null) {
                            return r.length();
                        }
                        lib.find(k, "d").trim();
                        String local = lib.find(null);
                        this.name = "c";
                        return local.length() + find(k) + name.length();
                    }

                    int find(String s) {
                        return 0;
                    }

                    void make() {
                        Lib a = new Lib("x");
                        Lib b = new Lib();
                        Runnable run = () -> a.setName(null);
                        java.util.function.Function<String, String> f = a::find;
                        String multi = a.find(
                                "first",
                                "second"
                                        + "third");
                    }
                }
                """);
        FILES.put("q/Hierarchy.java", """
                package q;

                class Base {
                    Base() {
                    }

                    String value() {
                        return "base";
                    }
                }

                class Derived extends Base {
                    Derived(int x) {
                    }

                    @Override
                    String value() {
                        return null;
                    }
                }

                class Derived2 extends Base {
                }

                class Box<T> {
                    Box(T v) {
                    }
                }

                class Makers {
                    Object make() {
                        Base anon = new Base() {
                        };
                        return new Box<>("s");
                    }
                }
                """);
        FILES.put("q/Broken.java", """
                package q;

                import missing.Dep;

                class Broken {
                    String go(Dep d) {
                        return d.find("k");
                    }

                    String go2(p.Lib lib) {
                        return lib.find(unknownVariable);
                    }
                }

                class Overloaded {
                    void take(String s) {
                    }

                    void take(Integer i) {
                    }

                    void use() {
                        take("known");
                        take(unknownThing);
                    }
                }
                """);
    }

    private ContextFixture() {}

    /** Writes all fixture files (or only those whose name does not start with an excluded prefix). */
    static Path write(Path root, String... excluded) throws IOException {
        for (Map.Entry<String, String> f : FILES.entrySet()) {
            boolean skip = false;
            for (String x : excluded) {
                skip |= f.getKey().startsWith(x);
            }
            if (!skip) {
                Path file = root.resolve("src").resolve(f.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, f.getValue());
            }
        }
        return root;
    }

    /** 1-based line of the first line containing {@code needle} in fixture file {@code file}. */
    static int line(String file, String needle) {
        String[] lines = FILES.get(file).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(needle + " not in " + file);
    }
}
