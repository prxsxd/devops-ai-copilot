package com.analyzer.devopsaicopilot.service;

import com.analyzer.devopsaicopilot.model.FailureAnalysis;
import com.analyzer.devopsaicopilot.model.FailureType;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class LogExtractionService {

    /**
     * Maximum number of characters sent downstream to the LLM.
     * Failures are usually near the end, so we keep the tail when capping.
     */
    private static final int MAX_OUTPUT_CHARS = 8000;

    /**
     * Number of context lines kept before/after each matching line.
     */
    private static final int CONTEXT_WINDOW = 3;

    public FailureAnalysis analyze(String logs) {

        if (logs == null || logs.isBlank()) {
            return new FailureAnalysis(FailureType.UNKNOWN, "");
        }

        String lower = logs.toLowerCase();

        // ---- Maven compilation -------------------------------------------
        if (lower.contains("compilation error")) {
            return result(
                    FailureType.MAVEN_COMPILATION,
                    extractBetween(logs, "COMPILATION ERROR", "BUILD FAILURE"));
        }

        // ---- Dependency resolution ---------------------------------------
        if (lower.contains("could not resolve dependencies")
                || lower.contains("non-resolvable parent pom")
                || lower.contains("failed to read artifact descriptor")
                || lower.contains("could not find artifact")) {
            return result(
                    FailureType.DEPENDENCY_RESOLUTION,
                    extractContaining(logs, "resolve", "artifact", "dependencies"));
        }

        // ---- Unit tests (only real failures, not "Failures: 0") ----------
        if (lower.contains("tests run:")
                && (lower.contains("there were failing tests")
                || hasNonZero(lower, "failures: ")
                || hasNonZero(lower, "errors: "))) {
            return result(
                    FailureType.UNIT_TEST,
                    extractContaining(logs, "Tests run:", "FAILED", "<<< ERROR"));
        }

        // ---- Kubernetes crash --------------------------------------------
        if (lower.contains("crashloopbackoff")
                || lower.contains("liveness probe failed")
                || lower.contains("failedscheduling")
                || lower.contains("insufficient cpu")
                || lower.contains("insufficient memory")) {
            return result(
                    FailureType.KUBERNETES_CRASH,
                    extractContaining(logs, "CrashLoopBackOff", "probe failed",
                            "FailedScheduling", "Insufficient"));
        }

        // ---- Image pull --------------------------------------------------
        if (lower.contains("imagepullbackoff")
                || lower.contains("errimagepull")) {
            return result(
                    FailureType.IMAGE_PULL,
                    extractContaining(logs, "ImagePullBackOff", "ErrImagePull"));
        }

        // ---- OOM ---------------------------------------------------------
        if (lower.contains("oomkilled")
                || lower.contains("out of memory")
                || lower.contains("java.lang.outofmemoryerror")) {
            return result(
                    FailureType.OOM_KILLED,
                    extractContaining(logs, "OOMKilled", "OutOfMemory", "out of memory"));
        }

        // ---- Disk space --------------------------------------------------
        if (lower.contains("no space left on device")) {
            return result(
                    FailureType.DISK_SPACE,
                    extractContaining(logs, "no space left on device"));
        }

        // ---- Network / timeout -------------------------------------------
        if (lower.contains("connection timed out")
                || lower.contains("unknownhostexception")
                || lower.contains("read timed out")
                || lower.contains("connection refused")) {
            return result(
                    FailureType.NETWORK_TIMEOUT,
                    extractContaining(logs, "timed out", "UnknownHostException",
                            "Connection refused"));
        }

        // ---- Git checkout ------------------------------------------------
        if (lower.contains("fatal: could not read")
                || lower.contains("authentication failed")
                || lower.contains("could not resolve host")
                || lower.contains("repository not found")) {
            return result(
                    FailureType.GIT_CHECKOUT,
                    extractContaining(logs, "fatal:", "Authentication failed",
                            "Repository not found"));
        }

        // ---- Docker build ------------------------------------------------
        if (lower.contains("docker build")
                || lower.contains("docker build failed")
                || lower.contains("error response from daemon")) {
            return result(
                    FailureType.DOCKER_BUILD,
                    extractContaining(logs, "docker"));
        }

        // ---- Permission denied (generic, lower priority) -----------------
        if (lower.contains("permission denied")
                || lower.contains("accessdenied")
                || lower.contains("403 forbidden")) {
            return result(
                    FailureType.PERMISSION_DENIED,
                    extractContaining(logs, "permission denied", "AccessDenied",
                            "403 Forbidden"));
        }

        // ---- Fallback ----------------------------------------------------
        return result(FailureType.UNKNOWN, genericExtraction(logs));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private FailureAnalysis result(FailureType type, String extracted) {
        return new FailureAnalysis(type, capSize(extracted));
    }

    /**
     * Returns true when a numeric counter (e.g. "failures: ") has a non-zero value.
     */
    private boolean hasNonZero(String lowerLogs, String marker) {
        int idx = lowerLogs.indexOf(marker);
        while (idx != -1) {
            int valueStart = idx + marker.length();
            int valueEnd = valueStart;
            while (valueEnd < lowerLogs.length()
                    && Character.isDigit(lowerLogs.charAt(valueEnd))) {
                valueEnd++;
            }
            if (valueEnd > valueStart) {
                String number = lowerLogs.substring(valueStart, valueEnd);
                try {
                    if (Integer.parseInt(number) > 0) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // not a number, skip
                }
            }
            idx = lowerLogs.indexOf(marker, idx + marker.length());
        }
        return false;
    }

    private String extractBetween(String logs, String start, String end) {

        int startIndex = logs.indexOf(start);
        int endIndex = logs.indexOf(end);

        if (startIndex != -1
                && endIndex != -1
                && endIndex > startIndex) {
            return logs.substring(startIndex, endIndex + end.length());
        }

        return logs;
    }

    /**
     * Extracts every line matching any keyword (case-insensitive), together with
     * {@link #CONTEXT_WINDOW} lines of surrounding context. Duplicate lines are
     * removed to keep the prompt compact.
     */
    private String extractContaining(String logs, String... keywords) {

        String[] lines = logs.split("\n");
        Set<Integer> keep = new TreeSet<>();

        for (int i = 0; i < lines.length; i++) {
            String lineLower = lines[i].toLowerCase();
            boolean matched = false;
            for (String keyword : keywords) {
                if (lineLower.contains(keyword.toLowerCase())) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                int from = Math.max(0, i - CONTEXT_WINDOW);
                int to = Math.min(lines.length - 1, i + CONTEXT_WINDOW);
                for (int j = from; j <= to; j++) {
                    keep.add(j);
                }
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        for (Integer index : keep) {
            seen.add(lines[index]);
        }

        String result = String.join("\n", seen);
        return result.isBlank() ? logs : result;
    }

    private String genericExtraction(String logs) {

        List<String> matched = Arrays.stream(logs.split("\n"))
                .filter(line -> {
                    String lineLower = line.toLowerCase();
                    return lineLower.contains("error")
                            || lineLower.contains("exception")
                            || lineLower.contains("caused by")
                            || lineLower.contains("failed");
                })
                .distinct()
                .collect(Collectors.toList());

        String result = String.join("\n", matched);
        return result.isBlank() ? logs : result;
    }

    /**
     * Keeps the output within {@link #MAX_OUTPUT_CHARS}. Failures usually appear
     * at the end, so the tail is retained when truncating.
     */
    private String capSize(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return "... [truncated] ...\n"
                + text.substring(text.length() - MAX_OUTPUT_CHARS);
    }
}
