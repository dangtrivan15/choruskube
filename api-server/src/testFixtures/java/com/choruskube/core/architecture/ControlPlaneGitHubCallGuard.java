package com.choruskube.core.architecture;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds every production call into a guarded GitHub-calling method and fails the build on a call
 * site an allowlist does not name, on a listed call site that no longer exists, or on production
 * source outside a guarded class that names the GitHub API location. See
 * {@link #RULE} and {@code docs/decisions/2026-10-24---01-control-plane-github-calls-are-allowlisted.md}.
 *
 * <p>A guarded class's GitHub-calling methods are derived from its bytecode, never hand-listed:
 * see {@link #guarding}. Each repo that uses this guard scans only its own production classes
 * with its own allowlist.
 */
public final class ControlPlaneGitHubCallGuard {

    public static final String RULE = "docs/decisions/2026-10-13---02-data-plane-credential-rule.md";
    public static final String RULE_URL = "https://github.com/dangtrivan15/choruskube/blob/main/" + RULE;

    private static final String HTTP_CLIENT_CLASS = "java.net.http.HttpClient";
    private static final Set<String> HTTP_SEND_METHOD_NAMES = Set.of("send", "sendAsync");

    private static final Pattern KEY_PATTERN = Pattern.compile("^[\\w.$]+#[\\w$<>]+ -> [\\w$]+#[\\w$]+$");

    private final Class<?> productionAnchor;
    private final String rootPackage;
    private final List<Class<?>> guardedClasses = new ArrayList<>();
    private Path productionSources;

    private boolean computed = false;
    private SortedSet<String> foundCallSites;
    private Map<String, String> callSiteFileLine;
    private List<String> vacuityProblems;
    private List<String> bypassFindings;

    private ControlPlaneGitHubCallGuard(Class<?> productionAnchor, String rootPackage) {
        this.productionAnchor = productionAnchor;
        this.rootPackage = rootPackage;
    }

    /**
     * {@code productionAnchor} names the code source (a directory or jar) this guard scans for
     * call sites; {@code rootPackage} keeps the import to that code base's own classes.
     */
    public static ControlPlaneGitHubCallGuard scanning(Class<?> productionAnchor, String rootPackage) {
        return new ControlPlaneGitHubCallGuard(productionAnchor, rootPackage);
    }

    /** The classes whose GitHub-calling methods — derived from their own bytecode — are guarded. */
    public ControlPlaneGitHubCallGuard guarding(Class<?>... guardedClasses) {
        this.guardedClasses.addAll(List.of(guardedClasses));
        return this;
    }

    /** The production source tree the bypass scan walks, e.g. {@code Path.of("src/main/java")}. */
    public ControlPlaneGitHubCallGuard sourceRoot(Path productionSources) {
        this.productionSources = productionSources;
        return this;
    }

    /**
     * The call sites this guard finds, each keyed {@code <caller class, less rootPackage>#<caller
     * method> -> <guarded class simple name>#<guarded method>} — the key format an allowlist uses.
     */
    public SortedSet<String> callSites() {
        compute();
        return Collections.unmodifiableSortedSet(foundCallSites);
    }

    /**
     * Fails with one {@link AssertionError} covering every violation: a call site {@code
     * allowlist} does not name, a listed site with no call, a malformed entry, or the GitHub API
     * location named outside a guarded class.
     */
    public void check(Map<String, String> allowlist) {
        compute();

        List<String> problems = new ArrayList<>(vacuityProblems);
        SortedSet<String> wellFormedKeys = new TreeSet<>();
        for (Map.Entry<String, String> entry : allowlist.entrySet()) {
            String key = entry.getKey();
            String reason = entry.getValue();
            boolean keyOk = key != null && KEY_PATTERN.matcher(key).matches();
            boolean reasonOk = reason != null && !reason.isBlank() && reason.contains("docs/decisions/");
            if (!keyOk) {
                problems.add("Malformed allowlist key: " + key);
            }
            if (!reasonOk) {
                problems.add("Allowlist entry's reason does not cite docs/decisions/: " + key);
            }
            if (keyOk && reasonOk) {
                wellFormedKeys.add(key);
            }
        }

        SortedSet<String> unlisted = new TreeSet<>(foundCallSites);
        unlisted.removeAll(wellFormedKeys);
        SortedSet<String> stale = new TreeSet<>(wellFormedKeys);
        stale.removeAll(foundCallSites);

        StringBuilder message = new StringBuilder();
        if (!unlisted.isEmpty()) {
            message.append(
                    "New control-plane GitHub call — the API server makes no new GitHub call; put it in a Worker step instead:\n");
            for (String key : unlisted) {
                message.append(key)
                        .append("  (")
                        .append(callSiteFileLine.get(key))
                        .append(")\n");
            }
            appendRulePointer(message);
        }
        if (!stale.isEmpty()) {
            message.append("Allowlisted GitHub call no longer exists — delete the entry; the list only shrinks:\n");
            for (String key : stale) {
                message.append(key).append('\n');
            }
            appendRulePointer(message);
        }
        if (!bypassFindings.isEmpty()) {
            message.append(
                    "GitHub API location named outside a guarded class — route the call through a guarded class or move it to the Worker:\n");
            for (String finding : bypassFindings) {
                message.append(finding).append('\n');
            }
            appendRulePointer(message);
        }
        if (!problems.isEmpty()) {
            message.append("Problems:\n");
            for (String problem : new TreeSet<>(problems)) {
                message.append(problem).append('\n');
            }
            appendRulePointer(message);
        }

        if (message.length() > 0) {
            throw new AssertionError(message.toString().strip());
        }
    }

    private static void appendRulePointer(StringBuilder message) {
        message.append("See ").append(RULE).append(" (").append(RULE_URL).append(").\n\n");
    }

    private void compute() {
        if (computed) {
            return;
        }

        List<String> problems = new ArrayList<>();
        String rootPackagePath = rootPackage.replace('.', '/');
        ImportOption rootFilter = location -> location.contains(rootPackagePath);
        Map<Location, JavaClasses> importCache = new LinkedHashMap<>();

        JavaClasses scanned = importCache.computeIfAbsent(
                locationOf(productionAnchor),
                loc -> new ClassFileImporter().withImportOption(rootFilter).importLocations(Set.of(loc)));
        if (guardedClasses.isEmpty()) {
            problems.add("No guarded class: guarding(...) was never called.");
        }
        if (scanned.isEmpty()) {
            problems.add("No class was found under root package '" + rootPackage + "'; the scan would pass vacuously.");
        }

        Map<Class<?>, List<JavaClass>> ownersByGuardedClass = new LinkedHashMap<>();
        List<JavaClass> allGuardedOwners = new ArrayList<>();
        for (Class<?> guardedClass : guardedClasses) {
            JavaClasses imported = importCache.computeIfAbsent(
                    locationOf(guardedClass),
                    loc -> new ClassFileImporter().withImportOption(rootFilter).importLocations(Set.of(loc)));
            if (!imported.contain(guardedClass.getName())) {
                // rootPackage excluded the guarded class's own code — same shape as "no guarded
                // method", so it is reported there rather than crashing the import lookup below.
                ownersByGuardedClass.put(guardedClass, List.of());
                continue;
            }
            JavaClass topLevel = imported.get(guardedClass.getName());
            List<JavaClass> owners = new ArrayList<>();
            for (JavaClass candidate : imported) {
                if (topLevelAncestorOf(candidate).getFullName().equals(topLevel.getFullName())) {
                    owners.add(candidate);
                }
            }
            ownersByGuardedClass.put(guardedClass, owners);
            allGuardedOwners.addAll(owners);
        }

        Map<String, String> aliasToOwnerMethod = computeAliases(allGuardedOwners);

        Map<String, GuardedMethod> guardedMethods = new LinkedHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (JavaClass owner : allGuardedOwners) {
                for (JavaMethod method : owner.getMethods()) {
                    String fullName = method.getFullName();
                    if (guardedMethods.containsKey(fullName)) {
                        continue;
                    }
                    if (isGuardedByItsOwnCalls(method, aliasToOwnerMethod, guardedMethods.keySet())) {
                        guardedMethods.put(fullName, new GuardedMethod(topLevelAncestorOf(owner), method.getName()));
                        changed = true;
                    }
                }
            }
        }

        for (Map.Entry<Class<?>, List<JavaClass>> entry : ownersByGuardedClass.entrySet()) {
            String topLevelFullName = entry.getKey().getName();
            boolean anyGuardedMethod = guardedMethods.values().stream()
                    .anyMatch(gm -> gm.topLevelOwner().getFullName().equals(topLevelFullName));
            if (!anyGuardedMethod) {
                problems.add(
                        "Guarded class " + entry.getKey().getSimpleName() + " has no derived GitHub-calling method.");
            }
        }

        Map<String, String> fileLineByKey = new TreeMap<>();
        for (JavaClass scannedClass : scanned) {
            for (JavaAccess<?> access : scannedClass.getAccessesFromSelf()) {
                if (!(access instanceof JavaMethodCall) && !(access instanceof JavaMethodReference)) {
                    continue;
                }
                AccessTarget target = access.getTarget();
                String resolved = resolvedFullNameOf(target);
                String canonical = aliasToOwnerMethod.getOrDefault(resolved, resolved);
                GuardedMethod guardedMethod = guardedMethods.get(canonical);
                if (guardedMethod == null) {
                    continue;
                }
                JavaClass originTopLevel = topLevelAncestorOf(access.getOriginOwner());
                if (originTopLevel
                        .getFullName()
                        .equals(guardedMethod.topLevelOwner().getFullName())) {
                    continue; // intra-class call into a method of the same guarded class — exempt
                }
                String originOwner = stripRootPrefix(access.getOriginOwner().getName());
                String key = originOwner + "#" + access.getOrigin().getName() + " -> "
                        + guardedMethod.topLevelOwner().getSimpleName() + "#" + guardedMethod.methodName();
                String fileLine = access.getSourceCodeLocation().getSourceFileName() + ":"
                        + access.getSourceCodeLocation().getLineNumber();
                fileLineByKey.putIfAbsent(key, fileLine);
            }
        }

        this.vacuityProblems = problems;
        this.foundCallSites = new TreeSet<>(fileLineByKey.keySet());
        this.callSiteFileLine = fileLineByKey;
        this.bypassFindings = scanBypass();
        this.vacuityProblems.addAll(sourceRootProblems());
        this.computed = true;
    }

    /** A method is guarded if any of its own calls reach the HTTP client or an already-guarded method. */
    private static boolean isGuardedByItsOwnCalls(
            JavaMethod method, Map<String, String> aliasToOwnerMethod, Set<String> guardedFullNames) {
        for (JavaAccess<?> access : method.getAccessesFromSelf()) {
            if (!(access instanceof JavaMethodCall) && !(access instanceof JavaMethodReference)) {
                continue;
            }
            AccessTarget target = access.getTarget();
            if (isHttpSend(target)) {
                return true;
            }
            String resolved = resolvedFullNameOf(target);
            String canonical = aliasToOwnerMethod.getOrDefault(resolved, resolved);
            if (guardedFullNames.contains(canonical)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttpSend(AccessTarget target) {
        return HTTP_CLIENT_CLASS.equals(target.getOwner().getFullName())
                && HTTP_SEND_METHOD_NAMES.contains(target.getName());
    }

    private static String resolvedFullNameOf(AccessTarget target) {
        return target.resolveMember().map(member -> member.getFullName()).orElseGet(target::getFullName);
    }

    /**
     * Any method with the same name and raw parameter types declared on a raw superclass or
     * interface of a guarded owner, direct or inherited (excluding {@link Object}), aliases that
     * owner's method — a call resolved against the supertype (an interface call never resolves down
     * to the implementation) counts as a call to the owner's method.
     */
    private static Map<String, String> computeAliases(Collection<JavaClass> guardedOwners) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (JavaClass owner : guardedOwners) {
            for (JavaMethod method : owner.getMethods()) {
                String[] rawParamNames = method.getRawParameterTypes().stream()
                        .map(JavaClass::getName)
                        .toArray(String[]::new);
                for (JavaClass supertype : rawSupertypesExcludingObject(owner)) {
                    supertype.tryGetMethod(method.getName(), rawParamNames).ifPresent(aliasMethod -> {
                        if (!aliasMethod.getFullName().equals(method.getFullName())) {
                            aliases.put(aliasMethod.getFullName(), method.getFullName());
                        }
                    });
                }
            }
        }
        return aliases;
    }

    private static List<JavaClass> rawSupertypesExcludingObject(JavaClass owner) {
        List<JavaClass> supertypes = new ArrayList<>();
        owner.getAllRawSuperclasses().stream()
                .filter(superclass -> !superclass.getFullName().equals(Object.class.getName()))
                .forEach(supertypes::add);
        supertypes.addAll(owner.getAllRawInterfaces());
        return supertypes;
    }

    private static JavaClass topLevelAncestorOf(JavaClass javaClass) {
        JavaClass current = javaClass;
        Optional<JavaClass> enclosing = current.getEnclosingClass();
        while (enclosing.isPresent()) {
            current = enclosing.get();
            enclosing = current.getEnclosingClass();
        }
        return current;
    }

    private String stripRootPrefix(String fullyQualifiedName) {
        String prefix = rootPackage + ".";
        return fullyQualifiedName.startsWith(prefix)
                ? fullyQualifiedName.substring(prefix.length())
                : fullyQualifiedName;
    }

    private static Location locationOf(Class<?> clazz) {
        URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
        return Location.of(url);
    }

    private List<String> sourceRootProblems() {
        List<String> problems = new ArrayList<>();
        if (productionSources == null || !Files.exists(productionSources)) {
            problems.add("Production source root is missing: " + productionSources);
            return problems;
        }
        if (!Files.isDirectory(productionSources)) {
            problems.add("Production source root is not a directory: " + productionSources);
            return problems;
        }
        try (Stream<Path> walk = Files.walk(productionSources)) {
            long javaFileCount =
                    walk.filter(path -> path.toString().endsWith(".java")).count();
            if (javaFileCount == 0) {
                problems.add("Production source root contains no .java file: " + productionSources);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return problems;
    }

    /**
     * Every line in {@code productionSources} naming the GitHub API location outside a guarded
     * class's own source — the side door a fresh HTTP client could use to reach GitHub unguarded.
     */
    private List<String> scanBypass() {
        if (productionSources == null || !Files.isDirectory(productionSources)) {
            return List.of();
        }
        Set<String> guardedClassOwnFiles = guardedClasses.stream()
                .map(guardedClass -> guardedClass.getName().replace('.', '/') + ".java")
                .collect(Collectors.toSet());

        List<String> findings = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(productionSources)) {
            List<Path> javaFiles = walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path file : javaFiles) {
                String relative = productionSources.relativize(file).toString().replace('\\', '/');
                if (guardedClassOwnFiles.contains(relative)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String lower = lines.get(i).toLowerCase(Locale.ROOT);
                    if (lower.contains("github.api.url")
                            || lower.contains("github_api_url")
                            || lower.contains("api.github.com")) {
                        findings.add(relative + ":" + (i + 1));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return findings;
    }

    private record GuardedMethod(JavaClass topLevelOwner, String methodName) {}
}
