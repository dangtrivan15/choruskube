package com.choruskube.core.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds production code that belongs to the e2e stack: a class in a package named {@code e2e}, or a
 * class or member whose {@code @Profile} names the {@code e2e} profile, negated or not. Such code
 * builds from {@code src/e2e/java} into the add-on jar only the e2e image loads. See {@link #RULE}.
 */
public final class E2eCodeGuard {

    public static final String RULE = "docs/decisions/2026-11-26---01-e2e-beans-ship-outside-the-production-jar.md";
    public static final String RULE_URL = "https://github.com/dangtrivan15/choruskube/blob/main/" + RULE;

    private static final String E2E = "e2e";
    private static final String PROFILE_ANNOTATION = "org.springframework.context.annotation.Profile";
    private static final Pattern PROFILE_EXPRESSION_DELIMITERS = Pattern.compile("[\\s()!&|]+");

    private E2eCodeGuard() {}

    /**
     * Fails on any such code among the classes under {@code rootPackage} in the code source (a
     * directory or jar) that {@code productionAnchor} was loaded from.
     */
    public static void requireNone(Class<?> productionAnchor, String rootPackage) {
        List<String> found = findings(productionAnchor, rootPackage);
        if (!found.isEmpty()) {
            throw new AssertionError("Production code belongs to the e2e stack; move it to src/e2e/java:\n  "
                    + String.join("\n  ", found)
                    + "\nSee " + RULE_URL);
        }
    }

    /** What {@link #requireNone} fails on, one line per class or member, sorted. */
    public static List<String> findings(Class<?> productionAnchor, String rootPackage) {
        String rootPackagePath = "/" + rootPackage.replace('.', '/') + "/";
        ImportOption underRoot = location -> location.contains(rootPackagePath);
        Location codeSource = Location.of(
                productionAnchor.getProtectionDomain().getCodeSource().getLocation());
        JavaClasses classes =
                new ClassFileImporter().withImportOption(underRoot).importLocations(Set.of(codeSource));
        if (classes.isEmpty()) {
            // An anchor outside the root would pass every build while checking nothing.
            throw new IllegalStateException("Imported no class under " + rootPackage + " from " + codeSource);
        }

        List<String> found = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (inE2ePackage(javaClass)) {
                found.add(javaClass.getName() + ": in an e2e package");
            } else if (namesE2eProfile(javaClass.getAnnotations().stream())) {
                found.add(javaClass.getName() + ": @Profile names " + E2E);
            }
            for (JavaMember member : javaClass.getMembers()) {
                if (namesE2eProfile(member.getAnnotations().stream())) {
                    found.add(member.getFullName() + ": @Profile names " + E2E);
                }
            }
        }
        found.sort(null);
        return found;
    }

    private static boolean inE2ePackage(JavaClass javaClass) {
        return Arrays.asList(javaClass.getPackageName().split("\\.")).contains(E2E);
    }

    private static boolean namesE2eProfile(Stream<? extends JavaAnnotation<?>> annotations) {
        return annotations
                .filter(a -> a.getRawType().getName().equals(PROFILE_ANNOTATION))
                .flatMap(a -> Arrays.stream((Object[]) a.get("value").orElse(new Object[0])))
                .flatMap(expression -> PROFILE_EXPRESSION_DELIMITERS.splitAsStream(expression.toString()))
                .anyMatch(E2E::equals);
    }
}
