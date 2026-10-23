package dev.oddlab.workspace.infrastructure;

import dev.oddlab.policy.domain.RepositoryPolicy;
import dev.oddlab.workspace.domain.ProjectConfiguration;
import dev.oddlab.workspace.domain.ProjectException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

final class YamlProjectConfiguration {
    ProjectConfiguration decode(String content) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(65536);
        try {
            var document = mapping(new Yaml(new SafeConstructor(options)).load(content),
                    Set.of("schemaVersion", "repositoryId", "name", "policy"));
            var policy = mapping(document.get("policy"), Set.of("schemaVersion", "allowedProviders", "networkAllowed",
                    "maxActiveSubagents", "maxCorrectionsPerTask", "reservedActions"));
            int version = integer(document.get("schemaVersion"));
            int policyVersion = integer(policy.get("schemaVersion"));
            if (version != 1 || policyVersion != 1) {
                throw new ProjectException("CONFIG_VERSION_UNSUPPORTED", "Versión de configuración o política no soportada.", 2);
            }
            return new ProjectConfiguration(version, UUID.fromString(string(document.get("repositoryId"))),
                    string(document.get("name")), new RepositoryPolicy(policyVersion,
                    strings(policy.get("allowedProviders")), bool(policy.get("networkAllowed")),
                    integer(policy.get("maxActiveSubagents")), integer(policy.get("maxCorrectionsPerTask")),
                    strings(policy.get("reservedActions"))));
        } catch (IllegalArgumentException | YAMLException exception) {
            // Do not echo YAML fragments: an invalid descriptor might contain sensitive data.
            throw new ProjectException("CONFIG_INVALID", "Descriptor YAML inválido: revisa campos, tipos y límites del formato v1.", 2);
        }
    }

    String encode(ProjectConfiguration configuration) {
        var policy = new LinkedHashMap<String, Object>();
        policy.put("schemaVersion", configuration.policy().schemaVersion());
        policy.put("allowedProviders", configuration.policy().allowedProviders());
        policy.put("networkAllowed", configuration.policy().networkAllowed());
        policy.put("maxActiveSubagents", configuration.policy().maxActiveSubagents());
        policy.put("maxCorrectionsPerTask", configuration.policy().maxCorrectionsPerTask());
        policy.put("reservedActions", configuration.policy().reservedActions());
        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", configuration.schemaVersion());
        document.put("repositoryId", configuration.repositoryId().toString());
        document.put("name", configuration.name());
        document.put("policy", policy);
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(document);
    }

    private Map<?, ?> mapping(Object value, Set<String> keys) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(keys)) {
            throw new IllegalArgumentException("Campos inválidos");
        }
        return map;
    }

    private String string(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Texto requerido");
        return text;
    }

    private int integer(Object value) {
        if (!(value instanceof Integer number)) throw new IllegalArgumentException("Entero requerido");
        return number;
    }

    private boolean bool(Object value) {
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Booleano requerido");
        return flag;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Lista requerida");
        return list.stream().map(this::string).toList();
    }
}
