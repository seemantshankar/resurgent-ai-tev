package com.resurgent.tev.doc;

import com.resurgent.tev.doc.cli.DocIngestCommand;
import com.resurgent.tev.doc.cli.DocLayoutCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** tev-doc entry point (Document Intelligence CLI, Architecture §15). */
@Command(name = "tev-doc", mixinStandardHelpOptions = true, version = "tev-doc 0.1.0-doc-m1",
        subcommands = {
        DocIngestCommand.class, DocLayoutCommand.class})
public final class TevDocMain {

    private TevDocMain() {}

    public static CommandLine commandLine() {
        return new CommandLine(new TevDocMain());
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
