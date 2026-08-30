package com.resurgent.tev.parser;

import com.resurgent.tev.parser.cli.EnrichCommand;
import com.resurgent.tev.parser.cli.IngestCommand;
import com.resurgent.tev.parser.cli.RedactCommand;
import com.resurgent.tev.parser.enrichment.EnrichmentModelClient;
import com.resurgent.tev.parser.enrichment.OpenAiChatCompletionsClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** tev-parse entry point. */
@Command(name = "tev-parse")
public final class Main {

    private Main() {}

    public static CommandLine commandLine() {
        return commandLine(new OpenAiChatCompletionsClient());
    }

    public static CommandLine commandLine(EnrichmentModelClient modelClient) {
        return new CommandLine(new Main())
                .addSubcommand(new IngestCommand())
                .addSubcommand(new RedactCommand())
                .addSubcommand(new EnrichCommand(modelClient));
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
