package com.example.exchangerate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Convention checks (Requirements 6.1, 6.3, 6.4, 6.5): no floating point in the public API, no
 * non-concurrent shared state, no ambient clock reads, and a thread-safety statement on every
 * public type.
 *
 * <p>Thread-safety: stateless; thread-safe.
 */
class ConventionsTest {

    private static final String PACKAGE = "com.example.exchangerate";
    private static final Path MAIN = Path.of("src", "main", "java", "com", "example", "exchangerate");
    private static final Pattern FLOATING =
            Pattern.compile("(?<![\\w.])(double|float)(?![\\w])|java\\.lang\\.(Double|Float)\\b");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.list(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String typeName(Path p) {
        var n = p.getFileName().toString();
        return n.substring(0, n.length() - ".java".length());
    }

    private static List<Class<?>> publicTypes() throws Exception {
        var result = new ArrayList<Class<?>>();
        for (var p : sources()) {
            var c = Class.forName(PACKAGE + "." + typeName(p));
            if (Modifier.isPublic(c.getModifiers())) {
                result.add(c);
            }
        }
        return result;
    }

    private static boolean isFloating(Type t) {
        return FLOATING.matcher(t.getTypeName()).find();
    }

    private static boolean isPublicOrProtected(int mods) {
        return Modifier.isPublic(mods) || Modifier.isProtected(mods);
    }

    @Test
    void publicApiHasNoFloatingPoint() throws Exception {
        var violations = new ArrayList<String>();
        for (var c : publicTypes()) {
            for (Field f : c.getDeclaredFields()) {
                if (isPublicOrProtected(f.getModifiers()) && isFloating(f.getGenericType())) {
                    violations.add(c.getSimpleName() + " field " + f.getName());
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!isPublicOrProtected(m.getModifiers()) || m.isSynthetic()) {
                    continue;
                }
                if (isFloating(m.getGenericReturnType())) {
                    violations.add(c.getSimpleName() + " return of " + m.getName());
                }
                checkParams(c, m, violations);
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (isPublicOrProtected(k.getModifiers())) {
                    checkParams(c, k, violations);
                }
            }
            if (c.isRecord()) {
                for (var rc : c.getRecordComponents()) {
                    if (isFloating(rc.getGenericType())) {
                        violations.add(c.getSimpleName() + " component " + rc.getName());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "float/double in public API: " + violations);
    }

    private static void checkParams(Class<?> c, Executable e, List<String> violations) {
        for (Type t : e.getGenericParameterTypes()) {
            if (isFloating(t)) {
                violations.add(c.getSimpleName() + " parameter of " + e.getName() + ": " + t);
            }
        }
    }

    /**
     * Instance and static fields (any visibility) holding collections or maps must use
     * {@code java.util.concurrent} types. Local variables inside methods (such as the temporary
     * {@code ArrayList} built inside {@code compute}) are not shared state and are allowed; the
     * reflection check inspects fields only.
     */
    @Test
    void stateFieldsAreConcurrentTypes() throws Exception {
        var violations = new ArrayList<String>();
        for (var p : sources()) {
            var c = Class.forName(PACKAGE + "." + typeName(p));
            for (Field f : c.getDeclaredFields()) {
                var type = f.getType();
                boolean container =
                        Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
                if (container && !type.getName().startsWith("java.util.concurrent.")) {
                    violations.add(c.getSimpleName() + "." + f.getName() + " : " + type.getName());
                }
            }
        }
        assertTrue(violations.isEmpty(), "non-concurrent collection fields: " + violations);
    }

    @Test
    void sourceHasNoForbiddenConstructs() throws Exception {
        var forbidden = List.of(
                Pattern.compile("\\bHashMap\\b"),
                Pattern.compile("\\bHashtable\\b"),
                Pattern.compile("\\bVector\\b"),
                Pattern.compile("Collections\\s*\\.\\s*synchronized\\w*"),
                Pattern.compile("\\bInstant\\s*\\.\\s*now\\s*\\("),
                Pattern.compile("\\bSystem\\s*\\.\\s*currentTimeMillis\\s*\\("),
                Pattern.compile("\\bLocalDateTime\\s*\\.\\s*now\\s*\\(\\s*\\)"),
                Pattern.compile("\\bLocalDate\\s*\\.\\s*now\\s*\\(\\s*\\)"));
        var violations = new ArrayList<String>();
        for (var p : sources()) {
            var code = stripComments(Files.readString(p));
            for (var pat : forbidden) {
                // ConcurrentHashMap contains "HashMap" but not as a whole word, so \b is safe.
                if (pat.matcher(code).find()) {
                    violations.add(p.getFileName() + " matches " + pat.pattern());
                }
            }
        }
        assertTrue(violations.isEmpty(), "forbidden constructs: " + violations);
    }

    @Test
    void everyPublicTypeDocumentsThreadSafety() throws Exception {
        var missing = new ArrayList<String>();
        var statement = Pattern.compile("thread[- ]safe", Pattern.CASE_INSENSITIVE);
        for (var c : publicTypes()) {
            var src = Files.readString(MAIN.resolve(c.getSimpleName() + ".java"));
            var decl = Pattern
                    .compile("^public\\s+(?:(?:final|sealed|abstract|static)\\s+)*(?:class|record|interface|enum)\\s+"
                            + c.getSimpleName() + "\\b", Pattern.MULTILINE)
                    .matcher(src);
            if (!decl.find()) {
                missing.add(c.getSimpleName() + " (declaration not found)");
                continue;
            }
            var before = src.substring(0, decl.start());
            int end = before.lastIndexOf("*/");
            int start = before.lastIndexOf("/**");
            // Only annotations or whitespace may sit between the Javadoc and the declaration.
            boolean attached = end >= 0 && start >= 0 && start < end
                    && before.substring(end + 2).replaceAll("@\\w+(\\([^)]*\\))?", "").isBlank();
            if (!attached || !statement.matcher(before.substring(start, end)).find()) {
                missing.add(c.getSimpleName());
            }
        }
        assertTrue(missing.isEmpty(), "public types lacking a thread-safety Javadoc statement: " + missing);
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
