package com.jsrc.app.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime context for budget enforcement.
 * Holds resolved profile and tracks applied transformations.
 */
public class BudgetContext {
    private final BudgetProfile profile;
    private final int effectiveLimit;
    private final int effectiveMaxBytes;
    private final boolean noBudgetMeta;
    private final Set<String> overriddenFields;
    
    private String degradedFrom;
    private final List<String> appliedTransforms = new ArrayList<>();
    private boolean truncated = false;

    public BudgetContext(BudgetProfile profile, Integer limit, Integer maxBytes, 
                         boolean noBudgetMeta, Set<String> overriddenFields) {
        this.profile = profile;
        this.effectiveLimit = limit != null ? limit : profile.defaultLimit();
        this.effectiveMaxBytes = maxBytes != null ? maxBytes : profile.defaultMaxBytes();
        this.noBudgetMeta = noBudgetMeta;
        this.overriddenFields = overriddenFields;
    }

    public BudgetProfile profile() {
        return profile;
    }

    public int effectiveLimit() {
        return effectiveLimit;
    }

    public int effectiveMaxBytes() {
        return effectiveMaxBytes;
    }

    public boolean noBudgetMeta() {
        return noBudgetMeta;
    }

    public void setDegradedFrom(String from) {
        this.degradedFrom = from;
    }

    public void addTransform(String transform) {
        this.appliedTransforms.add(transform);
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    /**
     * Builds _budget metadata object for JSON output.
     * Returns null if noBudgetMeta is true or profile is STANDARD.
     */
    public Map<String, Object> buildMetadata() {
        if (noBudgetMeta || profile == BudgetProfile.STANDARD) {
            return null;
        }
        
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("profile", profile.profileName());
        
        if (degradedFrom != null) {
            meta.put("degradedFrom", degradedFrom);
        }
        
        if (!appliedTransforms.isEmpty()) {
            meta.put("applied", appliedTransforms);
        }
        
        if (truncated) {
            meta.put("truncated", true);
        }
        
        if (overriddenFields != null && !overriddenFields.isEmpty()) {
            meta.put("overrides", List.of("fields:" + String.join(",", overriddenFields)));
        }
        
        return meta;
    }

    /**
     * Returns a budget denial error object.
     */
    public static Map<String, Object> createDenialError(String command, BudgetProfile profile, String suggestion) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "budget_denied");
        error.put("command", command);
        error.put("budget", profile.profileName());
        if (suggestion != null) {
            error.put("suggestion", suggestion);
        }
        error.put("hint", command + " exceeds " + profile.profileName() + " budget");
        return error;
    }
}
