package com.resurgent.tev.doc;

import com.resurgent.tev.doc.cli.DocAssessCommand;
import com.resurgent.tev.doc.cli.DocBindCommand;
import com.resurgent.tev.doc.cli.DocClassifyCommand;
import com.resurgent.tev.doc.cli.DocExtractCommand;
import com.resurgent.tev.doc.cli.DocFactsCommand;
import com.resurgent.tev.doc.cli.DocInduceCommand;
import com.resurgent.tev.doc.cli.DocIngestCommand;
import com.resurgent.tev.doc.cli.DocLayoutCommand;
import com.resurgent.tev.doc.cli.DocParseCommand;
import com.resurgent.tev.doc.cli.DocQaCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** tev-doc entry point (Document Intelligence CLI, Architecture §15). */
@Command(name = "tev-doc", mixinStandardHelpOptions = true, version = "tev-doc 0.1.0-doc-m3",
        subcommands = {
        DocIngestCommand.class, DocLayoutCommand.class, DocParseCommand.class,
        DocClassifyCommand.class, DocExtractCommand.class, DocBindCommand.class,
        DocInduceCommand.class, DocFactsCommand.class, DocAssessCommand.class,
        DocQaCommand.class})
public final class TevDocMain {

    private TevDocMain() {}

    /**
     * Creates a fresh CLI with ingest and layout subcommands without executing it.
     */
    public static CommandLine commandLine() {
        return new CommandLine(new TevDocMain());
    }

    /**
     * Enables headless AWT, executes the CLI arguments, and terminates the JVM
     * with the command's exit code.
     */
    public static void main(String[] args) {
        // PDF page rasters go through Java2D; without headless the AWT toolkit
        // briefly registers a Dock app on macOS.
        System.setProperty("java.awt.headless", "true");
        System.setProperty("apple.awt.UIElement", "true");
        System.exit(commandLine().execute(args));
    }
}
