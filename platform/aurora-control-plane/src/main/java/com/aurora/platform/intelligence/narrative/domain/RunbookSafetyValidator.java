package com.aurora.platform.intelligence.narrative.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Structural and lexical safety validator enforcing ADR-007 Section 7 safety invariants.
 *
 * <p>Runbooks in Phase 3 are strictly informational verification checklists for human SREs.
 * They must NOT contain:
 * <ul>
 *   <li>Executable shell commands or script syntax (e.g. {@code rm}, {@code sudo}, {@code kill}, {@code reboot})</li>
 *   <li>Container / orchestration commands (e.g. {@code kubectl}, {@code helm}, {@code docker})</li>
 *   <li>SQL mutation syntax (e.g. {@code DROP TABLE}, {@code DELETE FROM}, {@code TRUNCATE})</li>
 *   <li>HTTP mutation requests or tool execution (e.g. {@code curl -X POST}, {@code wget})</li>
 *   <li>Shell redirection, piping, or evaluation (e.g. {@code | sh}, {@code > /dev/}, {@code eval(})</li>
 * </ul>
 *
 * <p>Informational natural language (e.g. "Do not restart the service until logs are captured",
 * "Inspect connection pool metrics") is safe and permitted.
 */
public final class RunbookSafetyValidator {

    // Shell execution syntax
    private static final Pattern SHELL_COMMAND_PATTERN = Pattern.compile(
            "(?i)(?:^|[`'\";:&|\\n\\r]|\\bsudo\\s+)\\s*(?:sudo\\s+|rm\\s+-[rf]+|kill\\s+-[0-9]+|systemctl\\s+(?:restart|stop|reload)|service\\s+\\w+\\s+restart|reboot\\b|shutdown\\b|mkfs\\b|dd\\s+if=)"
    );

    // Orchestration command syntax
    private static final Pattern ORCHESTRATION_PATTERN = Pattern.compile(
            "(?i)(?:^|\\s|[`'\";&|])(?:kubectl\\s+(?:delete|drain|cordon|exec|apply|patch|scale|rollout)|helm\\s+(?:uninstall|rollback|delete)|docker\\s+(?:rm|kill|stop|exec))"
    );

    // SQL mutation syntax
    private static final Pattern SQL_MUTATION_PATTERN = Pattern.compile(
            "(?i)\\b(?:DROP\\s+(?:TABLE|DATABASE|SCHEMA|VIEW)|TRUNCATE\\s+(?:TABLE)?|DELETE\\s+FROM|UPDATE\\s+\\w+\\s+SET|ALTER\\s+TABLE|INSERT\\s+INTO)\\b"
    );

    // HTTP / Network mutation requests
    private static final Pattern HTTP_MUTATION_PATTERN = Pattern.compile(
            "(?i)\\b(?:curl|wget)\\b.*(?:-X\\s*(?:POST|DELETE|PUT|PATCH)|--request\\s*(?:POST|DELETE|PUT|PATCH)|-d\\s*['\"{]|--data)"
    );

    // Shell piping / redirection / code evaluation
    private static final Pattern SHELL_PIPING_PATTERN = Pattern.compile(
            "(?i)(?:\\|\\s*(?:ba)?sh\\b|>\\s*/dev/|eval\\s*\\(|base64\\s+-[dD]\\s*\\|)"
    );

    private RunbookSafetyValidator() {
        // Utility class
    }

    /**
     * Validates whether a list of suggested investigation steps is structurally and lexically safe.
     *
     * @param steps List of investigation step strings
     * @return true if all steps are safe informational checklists; false if any step contains executable action syntax
     */
    public static boolean isSafe(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return true;
        }

        for (String step : steps) {
            if (!isSafeStep(step)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a single investigation step or explanation string for unsafe actuation commands.
     *
     * @param step Text string to inspect
     * @return true if safe; false if unsafe command patterns detected
     */
    public static boolean isSafeStep(String step) {
        if (step == null || step.isBlank()) {
            return true;
        }

        if (SHELL_COMMAND_PATTERN.matcher(step).find()) {
            return false;
        }

        if (ORCHESTRATION_PATTERN.matcher(step).find()) {
            return false;
        }

        if (SQL_MUTATION_PATTERN.matcher(step).find()) {
            return false;
        }

        if (HTTP_MUTATION_PATTERN.matcher(step).find()) {
            return false;
        }

        if (SHELL_PIPING_PATTERN.matcher(step).find()) {
            return false;
        }

        return true;
    }
}
