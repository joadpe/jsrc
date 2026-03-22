package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.index.BinaryIndexV2Reader;
import com.jsrc.app.output.JsonWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Subcommand: jsrc dump
 * Dumps the binary index as JSON to stdout (debugging tool).
 */
@Command(name = "dump", description = "Dump binary index as JSON to stdout (debugging)")
public class DumpSubcommand extends JsrcSubcommand {

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        // Custom execution — doesn't use the standard Command pattern
        return ctx -> {
            Path indexBin = Path.of(ctx.rootPath()).resolve(".jsrc/index.bin");
            if (!Files.exists(indexBin)) {
                System.err.println("No binary index found at " + indexBin);
                System.err.println("Run 'jsrc index' first.");
                return 0;
            }

            try {
                var data = BinaryIndexV2Reader.read(indexBin);
                Map<String, Object> dump = new LinkedHashMap<>();
                dump.put("format", "jsrc-v2-binary");
                dump.put("entries", data.entries().size());
                dump.put("hasCallGraph", data.callGraph() != null);
                if (data.callGraph() != null) {
                    dump.put("methods", data.callGraph().getAllMethods().size());
                    dump.put("callerIndexSize", data.callGraph().getAllCallerIndexKeys().size());
                }

                int totalEdges = data.entries().stream()
                        .mapToInt(e -> e.callEdges().size()).sum();
                int totalSmells = data.entries().stream()
                        .mapToInt(e -> e.smells().size()).sum();
                dump.put("totalEdges", totalEdges);
                dump.put("totalSmells", totalSmells);
                dump.put("indexSizeBytes", Files.size(indexBin));

                System.out.println(JsonWriter.toJson(dump));
                return 1;
            } catch (Exception e) {
                System.err.println("Error reading binary index: " + e.getMessage());
                return 0;
            }
        };
    }
}
