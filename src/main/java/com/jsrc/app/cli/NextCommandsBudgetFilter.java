package com.jsrc.app.cli;

import com.jsrc.app.model.CommandHint;

import java.util.ArrayList;
import java.util.List;

/**
 * Filter for nextCommands hints under budget constraints.
 * Enforces DENY filtering, budgetSurface compliance, and caps per profile.
 * 
 * Single enforcement point for budget-aware hints (issue #11 slice B).
 */
public class NextCommandsBudgetFilter {

    /**
     * Apply budget-aware filtering and capping to hints.
     * 
     * @param hints raw hints from command
     * @param ctx budget context with profile and flags
     * @return filtered and capped hints, or null if suppressed
     */
    public static List<CommandHint> apply(List<CommandHint> hints, BudgetContext ctx) {
        if (hints == null || hints.isEmpty()) {
            return null;
        }

        // B6: --no-next-commands suppresses all hints
        if (ctx.noNextCommands()) {
            return null;
        }

        // B3: standard profile strips hints entirely
        if (ctx.profile() == BudgetProfile.STANDARD) {
            return null;
        }

        // Filter hints by DENY and budgetSurface
        List<CommandHint> filtered = new ArrayList<>();
        for (CommandHint hint : hints) {
            String commandToken = extractCommandToken(hint.command());
            
            // B4: Filter out DENY commands
            BudgetPolicy.Action action = BudgetPolicy.getAction(commandToken, ctx.profile());
            if (action == BudgetPolicy.Action.DENY) {
                continue;
            }

            // B5: Filter out hints whose leading token is not in budgetSurface
            // when surface is non-null (standard profile has null surface)
            var surface = BudgetPolicy.budgetSurface(ctx.profile());
            if (surface != null && !surface.contains(commandToken)) {
                continue;
            }

            // Keep if action is ALLOW or DEGRADE and in surface
            filtered.add(hint);
        }

        // Cap based on profile (B1, B2)
        int cap = getCapForProfile(ctx.profile());
        if (filtered.size() > cap) {
            filtered = filtered.subList(0, cap);
        }

        return filtered.isEmpty() ? null : filtered;
    }

    /**
     * Extract the leading command token from a hint command string.
     * E.g., "read Foo.bar" → "read", "call-chain method" → "call-chain"
     */
    private static String extractCommandToken(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        return command.trim().split("\\s+")[0];
    }

    /**
     * Get hint cap for the given profile.
     * B1: tiny ≤2, B2: small ≤3
     */
    private static int getCapForProfile(BudgetProfile profile) {
        return switch (profile) {
            case TINY -> 2;
            case SMALL -> 3;
            case STANDARD -> Integer.MAX_VALUE;
        };
    }
}
