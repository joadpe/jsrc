package com.jsrc.app.output;

import com.jsrc.app.cli.BudgetContext;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Wraps each JSON document in the stable protocol v1 envelope. */
public final class VersionedJsonPrintStream extends PrintStream {

    private static final String OUTPUT_TRUNCATED = "OUTPUT_TRUNCATED";
    private static final int MINIMUM_ENVELOPE_BYTES = 320;

    private final PrintStream delegate;
    private final String command;
    private final BudgetContext budgetContext;

    public VersionedJsonPrintStream(
            PrintStream delegate, String command, BudgetContext budgetContext) {
        super(OutputStream.nullOutputStream());
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.command = Objects.requireNonNull(command, "command");
        this.budgetContext = Objects.requireNonNull(budgetContext, "budgetContext");
    }

    @Override
    public void println(String json) {
        Object payload = JsonReader.parse(json);
        delegate.println(JsonWriter.toJson(fitToBudget(envelope(payload))));
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    /** Returns the smallest supported byte budget for protocol v1 envelopes. */
    public static int minimumEnvelopeBytes() {
        return MINIMUM_ENVELOPE_BYTES;
    }

    /** Emits a protocol v1 error envelope. */
    public void printError(DiagnosticCode code, String message) {
        delegate.println(JsonWriter.toJson(fitErrorToBudget(errorEnvelope(code, message))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fitErrorToBudget(Map<String, Object> envelope) {
        int maxBytes = budgetContext.effectiveMaxBytes();
        if (maxBytes < MINIMUM_ENVELOPE_BYTES) {
            return errorEnvelope(
                    DiagnosticCode.INVALID_ARGUMENT,
                    String.valueOf(MINIMUM_ENVELOPE_BYTES));
        }
        if (serializedSize(envelope) <= maxBytes) {
            return envelope;
        }

        List<Map<String, Object>> diagnostics =
                (List<Map<String, Object>>) envelope.get("diagnostics");
        Map<String, Object> diagnostic = diagnostics.getFirst();
        String message = String.valueOf(diagnostic.get("message"));
        while (serializedSize(envelope) > maxBytes && !message.isEmpty()) {
            int excess = serializedSize(envelope) - maxBytes;
            int keep = Math.max(0, message.length() - Math.max(1, excess));
            message = message.substring(0, keep);
            diagnostic.put("message", message);
        }
        return envelope;
    }

    private Map<String, Object> errorEnvelope(DiagnosticCode code, String message) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", code.name());
        diagnostic.put("severity", "error");
        diagnostic.put("message", message);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "urn:jsrc:output:" + command + ":1");
        envelope.put("protocolVersion", 1);
        envelope.put("command", command);
        envelope.put("status", "error");
        envelope.put("data", null);
        envelope.put("diagnostics", List.of(diagnostic));
        envelope.put("meta", metadata(false));
        return envelope;
    }

    private Map<String, Object> envelope(Object originalPayload) {
        Object payload = removeLegacyMetadata(originalPayload);
        boolean truncated = isTruncated(originalPayload);
        boolean ambiguous = originalPayload instanceof Map<?, ?> map
                && Boolean.TRUE.equals(map.get("ambiguous"));
        boolean partial = truncated || ambiguous;
        String status = partial ? "partial" : isEmpty(payload) ? "empty" : "ok";

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "urn:jsrc:output:" + command + ":1");
        envelope.put("protocolVersion", 1);
        envelope.put("command", command);
        envelope.put("status", status);
        envelope.put("data", payload);
        envelope.put("diagnostics", truncated
                ? List.of(truncationDiagnostic())
                : ambiguous ? List.of(unresolvedSymbolDiagnostic()) : List.of());
        envelope.put("meta", metadata(partial, truncated));
        return envelope;
    }

    private Map<String, Object> fitToBudget(Map<String, Object> envelope) {
        int maxBytes = budgetContext.effectiveMaxBytes();
        if (maxBytes < MINIMUM_ENVELOPE_BYTES) {
            return errorEnvelope(
                    DiagnosticCode.INVALID_ARGUMENT,
                    String.valueOf(MINIMUM_ENVELOPE_BYTES));
        }
        if (serializedSize(envelope) <= maxBytes) {
            return envelope;
        }

        envelope.put("status", "partial");
        envelope.put("diagnostics", List.of(truncationDiagnostic()));
        envelope.put("meta", metadata(true));
        Object data = mutablePayload(envelope.get("data"));
        envelope.put("data", data);

        while (serializedSize(envelope) > maxBytes && removeLast(data)) {
            // Remove complete fields or items until the envelope fits.
        }
        if (serializedSize(envelope) > maxBytes) {
            envelope.put("data", null);
        }
        return envelope;
    }

    private Map<String, Object> metadata(boolean truncated) {
        return metadata(truncated, truncated);
    }

    private Map<String, Object> metadata(boolean partialResult, boolean truncated) {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("profile", budgetContext.profile().profileName());
        budget.put("truncated", truncated);
        Map<String, Object> legacyBudget = budgetContext.buildMetadata();
        if (legacyBudget != null) {
            budget.putAll(legacyBudget);
            budget.put("truncated", truncated);
        }

        Map<String, Object> confidence = new LinkedHashMap<>();
        confidence.put("level", partialResult ? "partial" : "exact");
        confidence.put("reasons", partialResult
                ? List.of(truncated ? "truncated" : "unresolved-symbol")
                : List.of());

        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("reasons", partialResult
                ? List.of(truncated ? "truncated" : "unresolved-symbol")
                : List.of());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("budget", budget);
        meta.put("confidence", confidence);
        meta.put("partial", partial);
        return meta;
    }

    private static Map<String, Object> truncationDiagnostic() {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", OUTPUT_TRUNCATED);
        diagnostic.put("severity", "warning");
        diagnostic.put("message", "Output truncated");
        return diagnostic;
    }

    private static Map<String, Object> unresolvedSymbolDiagnostic() {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("code", DiagnosticCode.UNRESOLVED_SYMBOL.name());
        diagnostic.put("severity", "warning");
        diagnostic.put("message", "Symbol resolution is ambiguous");
        return diagnostic;
    }

    private static Object removeLegacyMetadata(Object payload) {
        if (!(payload instanceof Map<?, ?> source)) {
            return payload;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!"_budget".equals(key) && !"_truncated".equals(key)) {
                copy.put(String.valueOf(key), value);
            }
        });
        return copy;
    }

    private boolean isTruncated(Object payload) {
        if (budgetContext.buildMetadata() != null
                && Boolean.TRUE.equals(budgetContext.buildMetadata().get("truncated"))) {
            return true;
        }
        return payload instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("_truncated"));
    }

    private static boolean isEmpty(Object payload) {
        return payload == null
                || payload instanceof Map<?, ?> map && map.isEmpty()
                || payload instanceof Collection<?> collection && collection.isEmpty();
    }

    private static Object mutablePayload(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        if (payload instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return payload;
    }

    private static boolean removeLast(Object payload) {
        if (payload instanceof List<?> list && !list.isEmpty()) {
            list.remove(list.size() - 1);
            return true;
        }
        if (payload instanceof Map<?, ?> map && !map.isEmpty()) {
            Object lastKey = null;
            for (Object key : map.keySet()) {
                lastKey = key;
            }
            map.remove(lastKey);
            return true;
        }
        return false;
    }

    private static int serializedSize(Map<String, Object> envelope) {
        return JsonWriter.toJson(envelope).getBytes(StandardCharsets.UTF_8).length
                + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
    }
}
