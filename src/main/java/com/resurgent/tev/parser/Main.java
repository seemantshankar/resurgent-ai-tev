package com.resurgent.tev.parser;

import com.resurgent.tev.parser.cli.IngestCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** tev-parse entry point. */
@Command(name = "tev-parse", subcommands = {IngestCommand.class})
public final class Main {

    private Main() {}

    public static CommandLine commandLine() {
        return new CommandLine(new Main());
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
