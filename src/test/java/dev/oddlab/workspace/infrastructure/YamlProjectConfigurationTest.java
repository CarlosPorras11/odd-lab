package dev.oddlab.workspace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.oddlab.policy.domain.RepositoryPolicy;
import dev.oddlab.workspace.domain.ProjectConfiguration;
import dev.oddlab.workspace.domain.ProjectException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YamlProjectConfigurationTest {
    private final YamlProjectConfiguration yaml = new YamlProjectConfiguration();
    private final ProjectConfiguration initial = new ProjectConfiguration(1, UUID.randomUUID(), "Proyecto: gastos", RepositoryPolicy.initial());

    @Test
    void roundTripsIdentityAndConservativePolicy() {
        assertThat(yaml.decode(yaml.encode(initial))).isEqualTo(initial);
        assertThat(initial.policy().allowedProviders()).isEmpty();
        assertThat(initial.policy().networkAllowed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion: 2", "  schemaVersion: 2"})
    void rejectsUnknownVersions(String replacement) {
        String valid = yaml.encode(initial);
        String content = replacement.startsWith(" ") ? valid.replace("  schemaVersion: 1", replacement)
                : valid.replaceFirst("schemaVersion: 1", replacement);
        assertThatThrownBy(() -> yaml.decode(content)).isInstanceOf(ProjectException.class)
                .extracting(exception -> ((ProjectException) exception).code()).isEqualTo("CONFIG_VERSION_UNSUPPORTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion: 1\nschemaVersion: 1\n", "!!java.util.Date {}", "a: &alias [1]\nb: *alias", "", "[]", "policy: ["})
    void rejectsMalformedOrUnsafeDocuments(String content) {
        assertThatThrownBy(() -> yaml.decode(content)).isInstanceOf(ProjectException.class)
                .hasMessageNotContaining(content.isEmpty() ? "stacktrace" : content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  maxActiveSubagents: 3", "  maxActiveSubagents: -1", "  maxActiveSubagents: '2'",
            "  maxCorrectionsPerTask: 3", "  networkAllowed: 'false'", "  allowedProviders: [CODEX]",
            "  allowedProviders: [codex, codex]", "  reservedActions: []"})
    void rejectsInvalidPolicyValues(String replacement) {
        String key = replacement.strip().split(":")[0];
        String content = yaml.encode(initial).replaceAll("(?m)^  " + key + ":.*$", replacement);
        assertThatThrownBy(() -> yaml.decode(content)).isInstanceOf(ProjectException.class);
    }

    @Test
    void rejectsUnknownFieldsWithoutDisclosingTheirContent() {
        assertThatThrownBy(() -> yaml.decode(yaml.encode(initial) + "secret: do-not-print-this\n"))
                .isInstanceOf(ProjectException.class).hasMessageNotContaining("do-not-print-this");
    }
}
