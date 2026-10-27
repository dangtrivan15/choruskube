package com.choruskube.core.architecture;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.architecture.fixtures.Callers;
import com.choruskube.core.architecture.fixtures.FakeCredentialService;
import com.choruskube.core.architecture.fixtures.FakeGitHubClient;
import com.choruskube.core.architecture.fixtures.ReadsPullRequests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ControlPlaneGitHubCallGuard}'s own test, run over the synthetic fixtures in {@code
 * com.choruskube.core.architecture.fixtures} rather than real production code — {@link
 * ControlPlaneGitHubCallsTest} is what proves the guard against this repo's actual call sites.
 */
class ControlPlaneGitHubCallGuardTest {

    private static final String ROOT_PACKAGE = "com.choruskube.core.architecture.fixtures";
    private static final String REASON =
            "test fixture reason citing docs/decisions/2026-10-13---02-data-plane-credential-rule.md";

    @TempDir
    Path sourceRoot;

    @BeforeEach
    void writeGuardedClassSources() throws IOException {
        writeSource("FakeGitHubClient.java", "public class FakeGitHubClient {}");
        writeSource("FakeCredentialService.java", "public class FakeCredentialService {}");
        writeSource("Clean.java", "public class Clean { String ok = \"nothing interesting here\"; }");
    }

    private void writeSource(String fileName, String body) throws IOException {
        Path dir = sourceRoot.resolve("com/choruskube/core/architecture/fixtures");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), "package com.choruskube.core.architecture.fixtures;\n" + body + "\n");
    }

    private ControlPlaneGitHubCallGuard guard() {
        return ControlPlaneGitHubCallGuard.scanning(Callers.class, ROOT_PACKAGE)
                .guarding(FakeGitHubClient.class, FakeCredentialService.class)
                .sourceRoot(sourceRoot);
    }

    private static Map<String, String> fullAllowlist() {
        Map<String, String> allowed = new LinkedHashMap<>();
        for (String caller : new String[] {
            "Callers$Direct#use",
            "Callers$Lambda#viaLambda",
            "Callers$Reference#viaReference",
            "Callers$Subtype#viaSubtype",
            "Callers$ViaInterface#viaInterface"
        }) {
            allowed.put(caller + " -> FakeGitHubClient#fetch", REASON);
        }
        allowed.put("Callers$Mixed#useService -> FakeCredentialService#check", REASON);
        allowed.put("FakeCredentialService#check -> FakeGitHubClient#fetch", REASON);
        return allowed;
    }

    @Test
    void exactAllowlist_passes() {
        assertThat(guard().callSites())
                .containsExactlyInAnyOrderElementsOf(fullAllowlist().keySet());
        assertThatCode(() -> guard().check(fullAllowlist())).doesNotThrowAnyException();
    }

    @Test
    void unlistedCall_failsNamingTheSiteAndTheRule() {
        Map<String, String> missingDirect = new LinkedHashMap<>(fullAllowlist());
        missingDirect.remove("Callers$Direct#use -> FakeGitHubClient#fetch");

        assertThatThrownBy(() -> guard().check(missingDirect))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Callers$Direct#use -> FakeGitHubClient#fetch")
                .hasMessageContaining("Callers.java:")
                .hasMessageContaining(ControlPlaneGitHubCallGuard.RULE)
                .hasMessageContaining(ControlPlaneGitHubCallGuard.RULE_URL);
    }

    @Test
    void lambdaCall_isAttributedToTheEnclosingMethod() {
        assertThat(guard().callSites()).contains("Callers$Lambda#viaLambda -> FakeGitHubClient#fetch");
    }

    @Test
    void methodReference_counts() {
        assertThat(guard().callSites()).contains("Callers$Reference#viaReference -> FakeGitHubClient#fetch");
    }

    @Test
    void callThroughSubtype_counts() {
        assertThat(guard().callSites()).contains("Callers$Subtype#viaSubtype -> FakeGitHubClient#fetch");
    }

    @Test
    void callThroughImplementedInterface_counts() {
        assertThat(guard().callSites()).contains("Callers$ViaInterface#viaInterface -> FakeGitHubClient#fetch");
    }

    @Test
    void pureMethodOfGuardedClass_isNotACallSite() {
        assertThat(guard().callSites()).noneMatch(key -> key.contains("#parseOnly") || key.endsWith("#parse"));
    }

    @Test
    void storageOnlyMethodOfMixedService_isNotACallSite() {
        assertThat(guard().callSites())
                .noneMatch(key -> key.contains("#storageOnly") || key.endsWith("-> FakeCredentialService#storageOnly"));
    }

    @Test
    void methodReachingAnotherGuardedClass_isGuarded() {
        assertThat(guard().callSites()).contains("Callers$Mixed#useService -> FakeCredentialService#check");
    }

    @Test
    void intraClassCalls_areExempt() {
        // FakeGitHubClient#fetchAll calls FakeGitHubClient#fetch internally — never a call site.
        assertThat(guard().callSites()).noneMatch(key -> key.startsWith("FakeGitHubClient#fetchAll"));
    }

    @Test
    void staleEntry_failsNamingIt() {
        String staleKey = "Callers$Pure#parseOnly -> FakeGitHubClient#fetch"; // parseOnly calls parse, not fetch
        Map<String, String> withStale = new LinkedHashMap<>(fullAllowlist());
        withStale.put(staleKey, REASON);

        assertThatThrownBy(() -> guard().check(withStale))
                .hasMessageContaining(staleKey)
                .hasMessageContaining("delete the entry");
    }

    @Test
    void reasonWithoutDecisionPath_fails() {
        Map<String, String> badReason = new LinkedHashMap<>(fullAllowlist());
        badReason.put("Callers$Direct#use -> FakeGitHubClient#fetch", "no decision path in this reason");

        assertThatThrownBy(() -> guard().check(badReason))
                .hasMessageContaining("Callers$Direct#use -> FakeGitHubClient#fetch");
    }

    @Test
    void malformedKey_fails() {
        Map<String, String> malformed = new LinkedHashMap<>(fullAllowlist());
        malformed.put("this is not a valid key", REASON);

        assertThatThrownBy(() -> guard().check(malformed)).hasMessageContaining("this is not a valid key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"github.api.url", "github_api_url", "api.github.com"})
    void sourceNamingTheApiLocationOutsideGuardedClasses_failsWithFileAndLine(String token) throws IOException {
        writeSource("Leaky.java", "public class Leaky { String x = \"" + token + "\"; }");

        assertThatThrownBy(() -> guard().check(fullAllowlist()))
                .hasMessageContaining("Leaky.java:")
                .hasMessageContaining(ControlPlaneGitHubCallGuard.RULE);
    }

    @Test
    void guardedClassOwnSource_isExempt() throws IOException {
        writeSource("FakeGitHubClient.java", "public class FakeGitHubClient { String x = \"api.github.com\"; }");

        assertThatCode(() -> guard().check(fullAllowlist())).doesNotThrowAnyException();
    }

    @Test
    void noScannedClasses_fails() {
        ControlPlaneGitHubCallGuard guard = ControlPlaneGitHubCallGuard.scanning(Callers.class, "zzz.nonexistent")
                .guarding(FakeGitHubClient.class)
                .sourceRoot(sourceRoot);

        assertThatThrownBy(() -> guard.check(Map.of())).hasMessageContaining("zzz.nonexistent");
    }

    @Test
    void guardedClassWithoutNetworkCall_fails() {
        ControlPlaneGitHubCallGuard guard = ControlPlaneGitHubCallGuard.scanning(Callers.class, ROOT_PACKAGE)
                .guarding(ReadsPullRequests.class)
                .sourceRoot(sourceRoot);

        assertThatThrownBy(() -> guard.check(Map.of())).hasMessageContaining("ReadsPullRequests");
    }

    @Test
    void missingSourceRoot_fails() {
        ControlPlaneGitHubCallGuard guard = ControlPlaneGitHubCallGuard.scanning(Callers.class, ROOT_PACKAGE)
                .guarding(FakeGitHubClient.class, FakeCredentialService.class)
                .sourceRoot(sourceRoot.resolve("does-not-exist"));

        assertThatThrownBy(() -> guard.check(fullAllowlist())).hasMessageContaining("missing");
    }

    @Test
    void sourceRootWithoutJavaFiles_fails(@TempDir Path emptyDir) {
        ControlPlaneGitHubCallGuard guard = ControlPlaneGitHubCallGuard.scanning(Callers.class, ROOT_PACKAGE)
                .guarding(FakeGitHubClient.class, FakeCredentialService.class)
                .sourceRoot(emptyDir);

        assertThatThrownBy(() -> guard.check(fullAllowlist())).hasMessageContaining("no .java file");
    }

    @Test
    void noGuardedClass_fails() {
        ControlPlaneGitHubCallGuard guard = ControlPlaneGitHubCallGuard.scanning(Callers.class, ROOT_PACKAGE)
                .sourceRoot(sourceRoot);

        assertThatThrownBy(() -> guard.check(Map.of())).hasMessageContaining("No guarded class");
    }
}
