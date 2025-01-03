package com.github.streamshub.console.dependents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.streamshub.console.ReconciliationException;
import com.github.streamshub.console.api.v1alpha1.Console;
import com.github.streamshub.console.api.v1alpha1.spec.ConfigVars;
import com.github.streamshub.console.api.v1alpha1.spec.Credentials;
import com.github.streamshub.console.api.v1alpha1.spec.KafkaCluster;
import com.github.streamshub.console.api.v1alpha1.spec.SchemaRegistry;
import com.github.streamshub.console.api.v1alpha1.spec.TrustStore;
import com.github.streamshub.console.api.v1alpha1.spec.Value;
import com.github.streamshub.console.api.v1alpha1.spec.ValueReference;
import com.github.streamshub.console.api.v1alpha1.spec.metrics.MetricsSource;
import com.github.streamshub.console.api.v1alpha1.spec.metrics.MetricsSource.Type;
import com.github.streamshub.console.config.ConsoleConfig;
import com.github.streamshub.console.config.KafkaClusterConfig;
import com.github.streamshub.console.config.PrometheusConfig;
import com.github.streamshub.console.config.SchemaRegistryConfig;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteIngress;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaStatus;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerConfiguration;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerConfigurationBootstrap;
import io.strimzi.api.kafka.model.kafka.listener.ListenerStatus;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserStatus;

import static com.github.streamshub.console.support.StringSupport.replaceNonAlphanumeric;
import static com.github.streamshub.console.support.StringSupport.toEnv;

@ApplicationScoped
@KubernetesDependent(labelSelector = ConsoleResource.MANAGEMENT_SELECTOR)
public class ConsoleSecret extends CRUDKubernetesDependentResource<Secret, Console> implements ConsoleResource {

    public static final String NAME = "console-secret";

    private static final String EMBEDDED_METRICS_NAME = "streamshub.console.embedded-prometheus";
    private static final String METRICS_TRUST_PREFIX = "metrics-source-truststore.";
    private static final String REGISTRY_TRUST_PREFIX = "schema-registry-truststore.";
    private static final Random RANDOM = new SecureRandom();

    @Inject
    ObjectMapper objectMapper;

    @Inject
    PrometheusService prometheusService;

    public ConsoleSecret() {
        super(Secret.class);
    }

    @Override
    public String resourceName() {
        return NAME;
    }

    @Override
    protected Secret desired(Console primary, Context<Console> context) {
        var nextAuth = context.getSecondaryResource(Secret.class).map(s -> s.getData().get("NEXTAUTH_SECRET"));
        Map<String, String> data = new LinkedHashMap<>(2);

        var nextAuthSecret = nextAuth.orElseGet(() -> encodeString(base64String(32)));
        data.put("NEXTAUTH_SECRET", nextAuthSecret);

        var consoleConfig = buildConfig(primary, context);

        try {
            var yaml = objectMapper.copyWith(new YAMLFactory());
            data.put("console-config.yaml", encodeString(yaml.writeValueAsString(consoleConfig)));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }

        buildTrustStores(primary, context, data);

        updateDigest(context, "console-digest", data);

        return new SecretBuilder()
                .withNewMetadata()
                    .withName(instanceName(primary))
                    .withNamespace(primary.getMetadata().getNamespace())
                    .withLabels(commonLabels("console"))
                .endMetadata()
                .withData(data)
                .build();
    }

    /**
     * Generate additional entries in the secret for metric source trust stores. Also, this
     * method will add to the context the resources to be added to the console deployment to
     * access the secret entries.
     */
    private void buildTrustStores(Console primary, Context<Console> context, Map<String, String> data) {
        Map<Class<?>, List<?>> deploymentResources = new HashMap<>();

        for (var metricsSource : Optional.ofNullable(primary.getSpec().getMetricsSources())
                .orElse(Collections.emptyList())) {
            var truststore = metricsSource.getTrustStore();

            if (truststore != null) {
                reconcileTrustStore(primary, context, data, metricsSource.getName(), METRICS_TRUST_PREFIX, truststore, "metrics-source", deploymentResources);
            }
        }

        for (var registry : Optional.ofNullable(primary.getSpec().getSchemaRegistries())
                .orElse(Collections.emptyList())) {
            var truststore = registry.getTrustStore();

            if (truststore != null) {
                reconcileTrustStore(primary, context, data, registry.getName(), REGISTRY_TRUST_PREFIX, truststore, "schema-registry", deploymentResources);
            }
        }

        context.managedDependentResourceContext().put("TrustStoreResources", deploymentResources);
    }

    @SuppressWarnings("java:S107") // Ignore Sonar warning for too many args
    private void reconcileTrustStore(
            Console primary,
            Context<Console> context,
            Map<String, String> data,
            String sourceName,
            String sourcePrefix,
            TrustStore truststore,
            String bucketPrefix,
            Map<Class<?>, List<?>> deploymentResources) {

        String namespace = primary.getMetadata().getNamespace();
        String secretName = instanceName(primary);
        String typeCode = truststore.getType().toString();
        String volumeName = replaceNonAlphanumeric(sourcePrefix + sourceName, '-');
        String fileName = sourcePrefix + sourceName + "." + typeCode;

        @SuppressWarnings("unchecked")
        List<Volume> volumes = (List<Volume>) deploymentResources.computeIfAbsent(Volume.class, k -> new ArrayList<>());

        volumes.add(new VolumeBuilder()
                .withName(volumeName)
                .withNewSecret()
                    .withSecretName(secretName)
                    .addNewItem()
                        .withKey(sourcePrefix + sourceName + ".content")
                        .withPath(fileName)
                    .endItem()
                    .withDefaultMode(420)
                .endSecret()
                .build());

        @SuppressWarnings("unchecked")
        List<VolumeMount> mounts = (List<VolumeMount>) deploymentResources.computeIfAbsent(VolumeMount.class, k -> new ArrayList<>());

        mounts.add(new VolumeMountBuilder()
                .withName(volumeName)
                .withMountPath("/etc/ssl/" + fileName)
                .withSubPath(fileName)
                .build());

        String configTemplate = "quarkus.tls.\"" + bucketPrefix + "-%s\".trust-store.%s.%s";

        @SuppressWarnings("unchecked")
        List<EnvVar> vars = (List<EnvVar>) deploymentResources.computeIfAbsent(EnvVar.class, k -> new ArrayList<>());

        if (putMetricsTrustStoreValue(data, sourceName, "content", getValue(context, namespace, truststore.getContent()))) {
            String pathKey = switch (truststore.getType()) {
                case JKS, PKCS12 -> "path";
                case PEM -> "certs";
            };

            vars.add(new EnvVarBuilder()
                    .withName(toEnv(configTemplate.formatted(sourceName, typeCode, pathKey)))
                    .withValue("/etc/ssl/" + fileName)
                    .build());
        }

        if (putMetricsTrustStoreValue(data, sourceName, "password", getValue(context, namespace, truststore.getPassword()))) {
            vars.add(new EnvVarBuilder()
                    .withName(toEnv(configTemplate.formatted(sourceName, typeCode, "password")))
                    .withNewValueFrom()
                        .withNewSecretKeyRef(sourcePrefix + sourceName + ".password", secretName, false)
                    .endValueFrom()
                    .build());
        }

        if (putMetricsTrustStoreValue(data, sourceName, "alias", getValue(context, namespace, Value.of(truststore.getAlias())))) {
            vars.add(new EnvVarBuilder()
                    .withName(toEnv(configTemplate.formatted(sourceName, typeCode, "alias")))
                    .withNewValueFrom()
                        .withNewSecretKeyRef(sourcePrefix + sourceName + ".alias", secretName, false)
                    .endValueFrom()
                    .build());
        }
    }

    private boolean putMetricsTrustStoreValue(Map<String, String> data, String sourceName, String key, String value) {
        if (value != null) {
            data.put(METRICS_TRUST_PREFIX + sourceName + "." + key, value);
            return true;
        }
        return false;
    }

    private static String base64String(int length) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OutputStream out = Base64.getEncoder().wrap(buffer);

        RANDOM.ints().limit(length).forEach(value -> {
            try {
                out.write(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        return new String(buffer.toByteArray()).substring(0, length);
    }

    private ConsoleConfig buildConfig(Console primary, Context<Console> context) {
        ConsoleConfig config = new ConsoleConfig();

        addMetricsSources(primary, config, context);
        addSchemaRegistries(primary, config);

        for (var kafkaRef : primary.getSpec().getKafkaClusters()) {
            addConfig(primary, context, config, kafkaRef);
        }

        return config;
    }

    private void addMetricsSources(Console primary, ConsoleConfig config, Context<Console> context) {
        var metricsSources = coalesce(primary.getSpec().getMetricsSources(), Collections::emptyList);

        if (metricsSources.isEmpty()) {
            var prometheusConfig = new PrometheusConfig();
            prometheusConfig.setName(EMBEDDED_METRICS_NAME);
            prometheusConfig.setUrl(prometheusService.getUrl(primary, context));
            config.getMetricsSources().add(prometheusConfig);
            return;
        }

        for (MetricsSource metricsSource : metricsSources) {
            var prometheusConfig = new PrometheusConfig();
            prometheusConfig.setName(metricsSource.getName());

            if (metricsSource.getType() == Type.OPENSHIFT_MONITORING) {
                prometheusConfig.setType(PrometheusConfig.Type.OPENSHIFT_MONITORING);
                prometheusConfig.setUrl(getOpenShiftMonitoringUrl(context));
            } else {
                // embedded Prometheus used like standalone by console
                prometheusConfig.setType(PrometheusConfig.Type.STANDALONE);

                if (metricsSource.getType() == Type.EMBEDDED) {
                    prometheusConfig.setUrl(prometheusService.getUrl(primary, context));
                } else {
                    prometheusConfig.setUrl(metricsSource.getUrl());
                }
            }

            var metricsAuthn = metricsSource.getAuthentication();

            if (metricsAuthn != null) {
                if (metricsAuthn.getToken() == null) {
                    var basicConfig = new PrometheusConfig.Basic();
                    basicConfig.setUsername(metricsAuthn.getUsername());
                    basicConfig.setPassword(metricsAuthn.getPassword());
                    prometheusConfig.setAuthentication(basicConfig);
                } else {
                    var bearerConfig = new PrometheusConfig.Bearer();
                    bearerConfig.setToken(metricsAuthn.getToken());
                    prometheusConfig.setAuthentication(bearerConfig);
                }
            }

            config.getMetricsSources().add(prometheusConfig);
        }
    }

    private String getOpenShiftMonitoringUrl(Context<Console> context) {
        Route thanosQuerier = getResource(context, Route.class, "openshift-monitoring", "thanos-querier");

        String host = thanosQuerier.getStatus()
                .getIngress()
                .stream()
                .map(RouteIngress::getHost)
                .findFirst()
                .orElseThrow(() -> new ReconciliationException(
                        "Ingress host not found on openshift-monitoring/thanos-querier route"));

        return "https://" + host;
    }

    private void addSchemaRegistries(Console primary, ConsoleConfig config) {
        for (SchemaRegistry registry : coalesce(primary.getSpec().getSchemaRegistries(), Collections::emptyList)) {
            var registryConfig = new SchemaRegistryConfig();
            registryConfig.setName(registry.getName());
            registryConfig.setUrl(registry.getUrl());
            config.getSchemaRegistries().add(registryConfig);
        }
    }

    private void addConfig(Console primary, Context<Console> context, ConsoleConfig config, KafkaCluster kafkaRef) {
        String namespace = kafkaRef.getNamespace();
        String name = kafkaRef.getName();
        String listenerName = kafkaRef.getListener();

        KafkaClusterConfig kcConfig = new KafkaClusterConfig();
        kcConfig.setId(kafkaRef.getId());
        kcConfig.setNamespace(namespace);
        kcConfig.setName(name);
        kcConfig.setListener(listenerName);
        kcConfig.setSchemaRegistry(kafkaRef.getSchemaRegistry());

        if (kafkaRef.getMetricsSource() == null) {
            if (config.getMetricsSources().stream().anyMatch(src -> src.getName().equals(EMBEDDED_METRICS_NAME))) {
                kcConfig.setMetricsSource(EMBEDDED_METRICS_NAME);
            }
        } else {
            kcConfig.setMetricsSource(kafkaRef.getMetricsSource());
        }

        config.getKubernetes().setEnabled(Objects.nonNull(namespace));
        config.getKafka().getClusters().add(kcConfig);

        setConfigVars(primary, context, kcConfig.getProperties(), kafkaRef.getProperties());
        setConfigVars(primary, context, kcConfig.getAdminProperties(), kafkaRef.getAdminProperties());
        setConfigVars(primary, context, kcConfig.getConsumerProperties(), kafkaRef.getConsumerProperties());
        setConfigVars(primary, context, kcConfig.getProducerProperties(), kafkaRef.getProducerProperties());

        if (namespace != null && listenerName != null) {
            // Changes in the Kafka resource picked up during periodic reconciliation
            Kafka kafka = getResource(context, Kafka.class, namespace, name);
            setListenerConfig(kcConfig.getProperties(), kafka, listenerName);
        }

        if (!kcConfig.getProperties().containsKey(SaslConfigs.SASL_JAAS_CONFIG)) {
            Optional.ofNullable(kafkaRef.getCredentials())
                .map(Credentials::getKafkaUser)
                .ifPresent(user -> {
                    String userNs = Optional.ofNullable(user.getNamespace()).orElse(namespace);
                    setKafkaUserConfig(
                            context,
                            getResource(context, KafkaUser.class, userNs, user.getName()),
                            kcConfig.getProperties());
                });
        }
    }

    void setListenerConfig(Map<String, String> properties, Kafka kafka, String listenerName) {
        GenericKafkaListener listenerSpec = kafka.getSpec()
                .getKafka()
                .getListeners()
                .stream()
                .filter(l -> l.getName().equals(listenerName))
                .findFirst()
                .orElseThrow(() -> new ReconciliationException("Listener '%s' not found on Kafka %s/%s"
                        .formatted(listenerName, kafka.getMetadata().getNamespace(), kafka.getMetadata().getName())));

        StringBuilder protocol = new StringBuilder();
        String mechanism = null;

        if (listenerSpec.getAuth() != null) {
            protocol.append("SASL_");

            var auth = listenerSpec.getAuth();
            switch (auth.getType()) {
                case "oauth":
                    mechanism = "OAUTHBEARER";
                    break;
                case "scram-sha-512":
                    mechanism = "SCRAM-SHA-512";
                    break;
                case "tls", "custom":
                default:
                    // Nothing yet
                    break;
            }
        }

        if (listenerSpec.isTls()) {
            protocol.append("SSL");
        } else {
            protocol.append("PLAINTEXT");
        }

        properties.putIfAbsent(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol.toString());

        if (mechanism != null) {
            properties.putIfAbsent(SaslConfigs.SASL_MECHANISM, mechanism);
        }

        Optional<ListenerStatus> listenerStatus = Optional.ofNullable(kafka.getStatus())
                .map(KafkaStatus::getListeners)
                .orElseGet(Collections::emptyList)
                .stream()
                .filter(l -> l.getName().equals(listenerName))
                .findFirst();

        properties.computeIfAbsent(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
                key -> listenerStatus.map(ListenerStatus::getBootstrapServers)
                    .or(() -> Optional.ofNullable(listenerSpec.getConfiguration())
                            .map(GenericKafkaListenerConfiguration::getBootstrap)
                            .map(GenericKafkaListenerConfigurationBootstrap::getHost))
                    .orElseThrow(() -> new ReconciliationException("""
                            Bootstrap servers could not be found for listener '%s' on Kafka %s/%s \
                            and no configuration was given in the Console resource"""
                            .formatted(listenerName, kafka.getMetadata().getNamespace(), kafka.getMetadata().getName()))));

        if (!properties.containsKey(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG)
                && !properties.containsKey(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG)) {
            listenerStatus.map(ListenerStatus::getCertificates)
                    .filter(Objects::nonNull)
                    .filter(Predicate.not(Collection::isEmpty))
                    .map(certificates -> String.join("\n", certificates).trim())
                    .ifPresent(certificates -> {
                        properties.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
                        properties.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, certificates);
                    });
        }
    }

    void setKafkaUserConfig(Context<Console> context, KafkaUser user, Map<String, String> properties) {
        // Changes in the KafkaUser resource and referenced Secret picked up during periodic reconciliation
        var secretName = Optional.ofNullable(user.getStatus())
                .map(KafkaUserStatus::getSecret)
                .orElseThrow(() -> new ReconciliationException("KafkaUser %s/%s missing .status.secret"
                        .formatted(user.getMetadata().getNamespace(), user.getMetadata().getName())));

        String secretNs = user.getMetadata().getNamespace();
        Secret userSecret = getResource(context, Secret.class, secretNs, secretName);
        String jaasConfig = userSecret.getData().get(SaslConfigs.SASL_JAAS_CONFIG);

        if (jaasConfig == null) {
            throw new ReconciliationException("Secret %s/%s missing key '%s'"
                    .formatted(secretNs, secretName, SaslConfigs.SASL_JAAS_CONFIG));
        }

        properties.put(SaslConfigs.SASL_JAAS_CONFIG, decodeString(jaasConfig));
    }

    void setConfigVars(Console primary, Context<Console> context, Map<String, String> target, ConfigVars source) {
        String namespace = primary.getMetadata().getNamespace();

        source.getValuesFrom().stream().forEach(fromSource -> {
            String prefix = fromSource.getPrefix();
            var configMapRef = fromSource.getConfigMapRef();
            var secretRef = fromSource.getSecretRef();

            if (configMapRef != null) {
                copyData(context, target, ConfigMap.class, namespace, configMapRef.getName(), prefix, configMapRef.getOptional(), ConfigMap::getData);
            }

            if (secretRef != null) {
                copyData(context, target, Secret.class, namespace, secretRef.getName(), prefix, secretRef.getOptional(), Secret::getData);
            }
        });

        source.getValues().forEach(configVar -> target.put(configVar.getName(), configVar.getValue()));
    }

    @SuppressWarnings("java:S107") // Ignore Sonar warning for too many args
    <S extends HasMetadata> void copyData(Context<Console> context,
            Map<String, String> target,
            Class<S> sourceType,
            String namespace,
            String name,
            String prefix,
            Boolean optional,
            Function<S, Map<String, String>> dataProvider) {

        S source = getResource(context, sourceType, namespace, name, Boolean.TRUE.equals(optional));

        if (source != null) {
            copyData(target, dataProvider.apply(source), prefix, Secret.class.equals(sourceType));
        }
    }

    void copyData(Map<String, String> target, Map<String, String> source, String prefix, boolean decode) {
        source.forEach((key, value) -> {
            if (prefix != null) {
                key = prefix + key;
            }
            target.put(key, decode ? decodeString(value) : value);
        });
    }

    /**
     * Fetch the string value from the given valueSpec. The return string
     * will be encoded for use in the Console secret data map.
     */
    String getValue(Context<Console> context, String namespace, Value valueSpec) {
        if (valueSpec == null) {
            return null;
        }

        return Optional.ofNullable(valueSpec.getValue())
                .map(this::encodeString)
            .or(() -> Optional.ofNullable(valueSpec.getValueFrom())
                    .map(ValueReference::getConfigMapKeyRef)
                    .flatMap(ref -> getValue(context, ConfigMap.class, namespace, ref.getName(), ref.getKey(), ref.getOptional(), ConfigMap::getData)
                            .map(this::encodeString)
                        .or(() -> getValue(context, ConfigMap.class, namespace, ref.getName(), ref.getKey(), ref.getOptional(), ConfigMap::getBinaryData))))
            .or(() -> Optional.ofNullable(valueSpec.getValueFrom())
                    .map(ValueReference::getSecretKeyRef)
                    .flatMap(ref -> getValue(context, Secret.class, namespace, ref.getName(), ref.getKey(), ref.getOptional(), Secret::getData))
                    /* No need to call encodeString, the value is already encoded from Secret */)
            .orElse(null);
    }

    <S extends HasMetadata> Optional<String> getValue(Context<Console> context,
            Class<S> sourceType,
            String namespace,
            String name,
            String key,
            Boolean optional,
            Function<S, Map<String, String>> dataProvider) {

        S source = getResource(context, sourceType, namespace, name, Boolean.TRUE.equals(optional));

        if (source != null) {
            return Optional.ofNullable(dataProvider.apply(source).get(key));
        }

        return Optional.empty();
    }

    static <T extends HasMetadata> T getResource(
            Context<Console> context, Class<T> resourceType, String namespace, String name) {
        return getResource(context, resourceType, namespace, name, false);
    }

    static <T extends HasMetadata> T getResource(
            Context<Console> context, Class<T> resourceType, String namespace, String name, boolean optional) {

        T resource;

        try {
            resource = context.getClient()
                .resources(resourceType)
                .inNamespace(namespace)
                .withName(name)
                .get();
        } catch (KubernetesClientException e) {
            throw new ReconciliationException("Failed to retrieve %s resource: %s/%s. Message: %s"
                    .formatted(resourceType.getSimpleName(), namespace, name, e.getMessage()));
        }

        if (resource == null && !optional) {
            throw new ReconciliationException("No such %s resource: %s/%s".formatted(resourceType.getSimpleName(), namespace, name));
        }

        return resource;
    }
}
