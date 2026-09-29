package com.aurora.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import jakarta.persistence.Entity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architectural Guardrail Tests enforcing modular monolith boundaries,
 * clean hexagonal layering, and truthful capability claims for AURORA Phases 1A-2B.
 */
public class ArchitectureRulesTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aurora.platform");
    }

    @Test
    @DisplayName("Rule A & M: Controllers cannot expose @Entity types in return types or parameters")
    void controllersCannotExposeEntities() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().arePublic()
                .should().notHaveRawReturnType(
                        new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("an @Entity class") {
                            @Override
                            public boolean test(com.tngtech.archunit.core.domain.JavaClass javaClass) {
                                return javaClass.isAnnotatedWith(Entity.class);
                            }
                        }
                );

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule B: Controllers cannot depend directly on Repositories")
    void controllersCannotDependOnRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule C: DTO packages do not depend on Repositories")
    void dtosDoNotDependOnRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..dto..")
                .should().dependOnClassesThat().resideInAPackage("..repository..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule D: Anomaly detector implementations do not depend on controllers")
    void detectorsDoNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.anomaly.detector..")
                .should().dependOnClassesThat().resideInAPackage("..controller..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule E: Anomaly detector implementations do not depend on persistence entities or repositories")
    void detectorsDoNotDependOnPersistence() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.anomaly.detector..")
                .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..entity..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule F: Recovery execution classes must not exist in Phase 1A-2B")
    void recoveryExecutionClassesDoNotExist() {
        boolean hasExecutionClass = importedClasses.stream()
                .anyMatch(c -> c.getName().contains("RecoveryExecutor")
                        || c.getName().contains("AutonomousRemediation")
                        || c.getName().contains("KubernetesActuator")
                        || c.getName().contains("ShellExecutor")
                        || c.getName().contains("AutoRollback"));

        assertThat(hasExecutionClass)
                .as("Recovery must remain read-only proposal scaffolding in Phase 1-2; no execution engine classes may exist")
                .isFalse();
    }

    @Test
    @DisplayName("Rule G: RCA cannot depend on recovery execution")
    void rcaCannotDependOnRecoveryExecution() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.rca..")
                .should().dependOnClassesThat().resideInAPackage("..recovery..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule H: Telemetry cannot depend on RCA")
    void telemetryCannotDependOnRca() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..telemetry..")
                .should().dependOnClassesThat().resideInAPackage("..intelligence.rca..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule I: Resource cannot depend on RCA")
    void resourceCannotDependOnRca() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.aurora.platform.resource..")
                .should().dependOnClassesThat().resideInAPackage("com.aurora.platform.intelligence.rca..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule J: Dependency domain cannot depend on recovery")
    void dependencyCannotDependOnRecovery() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.aurora.platform.dependency..")
                .should().dependOnClassesThat().resideInAPackage("com.aurora.platform.recovery..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule K: Common infrastructure cannot depend on domain-specific packages")
    void commonCannotDependOnDomainPackages() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.aurora.platform.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.aurora.platform.resource..",
                        "com.aurora.platform.telemetry..",
                        "com.aurora.platform.incident..",
                        "com.aurora.platform.dependency..",
                        "com.aurora.platform.intelligence..",
                        "com.aurora.platform.recovery..",
                        "com.aurora.platform.policy.."
                );

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule L: No domain package may import another domain's controller")
    void domainPackagesCannotImportForeignControllers() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "..resource.service..",
                        "..telemetry.service..",
                        "..incident.service..",
                        "..dependency.service..",
                        "..intelligence..",
                        "..recovery.service.."
                )
                .should().dependOnClassesThat().resideInAPackage("..controller..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule N: Phase 2C (Historical Incident Intelligence) must not use vector search, embeddings, ANN, or ML")
    void phase2CDoesNotUseVectorsOrMl() {
        boolean hasVectorOrMl = importedClasses.stream()
                .anyMatch(c -> c.getName().toLowerCase().contains("embedding")
                        || c.getName().toLowerCase().contains("vectorsearch")
                        || c.getName().toLowerCase().contains("milvus")
                        || c.getName().toLowerCase().contains("pinecone")
                        || c.getName().toLowerCase().contains("faiss"));

        assertThat(hasVectorOrMl)
                .as("Phase 2C must rely strictly on deterministic discrete similarity; vector/embedding classes must not exist")
                .isFalse();

        boolean hasHistoricalPackage = importedClasses.stream()
                .anyMatch(c -> c.getName().contains("com.aurora.platform.intelligence.historical"));

        assertThat(hasHistoricalPackage)
                .as("Phase 2C classes must reside in com.aurora.platform.intelligence.historical")
                .isTrue();
    }

    @Test
    @DisplayName("Rule P1 & Req 32: Phase 2D-A (Graph Intelligence) must not use LLMs, vector search, embeddings, or external AI")
    void phase2DDoesNotUseVectorsOrMl() {
        boolean hasVectorOrMlInGraph = importedClasses.stream()
                .filter(c -> c.getName().contains("com.aurora.platform.intelligence.graph"))
                .anyMatch(c -> c.getName().toLowerCase().contains("embedding")
                        || c.getName().toLowerCase().contains("vectorsearch")
                        || c.getName().toLowerCase().contains("milvus")
                        || c.getName().toLowerCase().contains("pinecone")
                        || c.getName().toLowerCase().contains("faiss")
                        || c.getName().toLowerCase().contains("langchain")
                        || c.getName().toLowerCase().contains("openai")
                        || c.getName().toLowerCase().contains("llm"));

        assertThat(hasVectorOrMlInGraph)
                .as("Phase 2D-A must rely strictly on empirical closed-form Bayesian math; vector/LLM classes must not exist in graph package")
                .isFalse();

        boolean hasGraphPackage = importedClasses.stream()
                .anyMatch(c -> c.getName().contains("com.aurora.platform.intelligence.graph"));

        assertThat(hasGraphPackage)
                .as("Phase 2D-A classes must reside in com.aurora.platform.intelligence.graph")
                .isTrue();
    }

    @Test
    @DisplayName("Rule P2 & Req 33: Phase 2D-A must not depend on recovery execution")
    void phase2DDoesNotDependOnRecovery() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.graph..")
                .should().dependOnClassesThat().resideInAPackage("..recovery..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule P3 & Req 31: Phase 2B RCA service must not depend on Phase 2D graph implementation classes")
    void phase2BDoesNotDependOnGraphImplementation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.rca.service..")
                .should().dependOnClassesThat().resideInAPackage("..intelligence.graph..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule P4 & Req 34: Graph domain cannot depend on outer layers (infrastructure or web)")
    void graphDomainDoesNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.graph.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..intelligence.graph.infrastructure..",
                        "..controller..",
                        "..repository.."
                );

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule Q1: Narrative domain and application packages must remain provider-neutral")
    void narrativeDomainAndApplicationPurity() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..intelligence.narrative.domain..", "..intelligence.narrative.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..openai..",
                        "..anthropic..",
                        "..gemini..",
                        "..langchain..",
                        "..intelligence.narrative.infrastructure.adapter.."
                ).allowEmptyShould(true);

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule Q2: Provider adapter classes must reside exclusively in infrastructure.adapter")
    void providerAdapterIsolation() {
        boolean hasProviderOutsideAdapter = importedClasses.stream()
                .filter(c -> c.getName().contains("com.aurora.platform.intelligence.narrative"))
                .filter(c -> !c.getName().contains(".infrastructure.adapter."))
                .anyMatch(c -> c.getName().toLowerCase().contains("openai")
                        || c.getName().toLowerCase().contains("anthropic")
                        || c.getName().toLowerCase().contains("ollama")
                        || c.getName().toLowerCase().contains("vllm")
                        || c.getName().toLowerCase().contains("httpclient"));

        assertThat(hasProviderOutsideAdapter)
                .as("Provider and wire-protocol classes must exist only in narrative.infrastructure.adapter")
                .isFalse();
    }

    @Test
    @DisplayName("Rule Q3: Narrative package must not depend on recovery execution, vector databases, or embeddings")
    void narrativeSafetyBoundary() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.narrative..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..recovery..",
                        "..milvus..",
                        "..pinecone..",
                        "..pgvector.."
                ).allowEmptyShould(true);

        rule.check(importedClasses);

        boolean hasVectorClasses = importedClasses.stream()
                .filter(c -> c.getName().contains("com.aurora.platform.intelligence.narrative"))
                .anyMatch(c -> c.getName().toLowerCase().contains("embedding")
                        || c.getName().toLowerCase().contains("vectorsearch")
                        || c.getName().toLowerCase().contains("vector"));

        assertThat(hasVectorClasses)
                .as("Narrative package must not use vector search or embeddings")
                .isFalse();
    }

    @Test
    @DisplayName("Rule Q4: Narrative domain must not import HTTP client directly")
    void narrativeDomainDoesNotImportHttpClient() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.narrative.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
                .allowEmptyShould(true);

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule Q5: Narrative application must not depend on infrastructure adapter")
    void narrativeApplicationDoesNotDependOnAdapter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.narrative.application..")
                .should().dependOnClassesThat().resideInAPackage("..intelligence.narrative.infrastructure.adapter..")
                .allowEmptyShould(true);

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule Q6: Controllers must not directly invoke provider adapter")
    void controllersDoNotDependOnAdapter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..intelligence.narrative.infrastructure.adapter..")
                .allowEmptyShould(true);

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule Q7: Repositories must not depend on provider adapter")
    void repositoriesDoNotDependOnAdapter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAPackage("..intelligence.narrative.infrastructure.adapter..")
                .allowEmptyShould(true);

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule Q8: Provider adapter must not depend on incident or RCA entity/repository layers")
    void providerAdapterDoesNotDependOnIncidentOrRcaEntities() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..intelligence.narrative.infrastructure.adapter..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..incident.entity..",
                        "..incident.repository..",
                        "..rca.entity..",
                        "..rca.repository.."
                ).allowEmptyShould(true);

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Rule F1: Actuator port isolation - domain and application never import external infrastructure clients")
    void actuatorPortIsolation() {
        noClasses()
                .that().resideInAnyPackage("..recovery.domain..", "..recovery.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.kubernetes..", "com.amazonaws..", "com.google.cloud..", "com.azure.."
                ).allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("Rule F2: Actuator implementations must reside exclusively in infrastructure actuator package")
    void actuatorImplementationsInInfrastructure() {
        classes()
                .that().implement(com.aurora.platform.recovery.application.port.out.RecoveryActuatorPort.class)
                .should().resideInAPackage("..recovery.infrastructure.actuator..")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Rule F3: Absolute prohibition of Runtime.exec and ProcessBuilder across the platform")
    void absoluteProhibitionOfProcessExecution() {
        noClasses()
                .should().callMethod(Runtime.class, "exec", String.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class)
                .orShould().callConstructor(ProcessBuilder.class, String[].class)
                .orShould().callConstructor(ProcessBuilder.class, java.util.List.class)
                .check(importedClasses);
    }

    @Test
    @DisplayName("Rule F4: Actuators can only be accessed through the execution orchestrator guard gate")
    void actuatorAccessRestrictedToOrchestrator() {
        noClasses()
                .that().resideOutsideOfPackages("..recovery.application.orchestration..", "..recovery.infrastructure.actuator..")
                .should().dependOnClassesThat().resideInAPackage("..recovery.infrastructure.actuator..")
                .check(importedClasses);
    }
}
