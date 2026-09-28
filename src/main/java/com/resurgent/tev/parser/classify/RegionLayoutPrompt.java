package com.resurgent.tev.parser.classify;

/** Sheet dump sent to the LLM to propose main/helper/scratch regions. */
public record RegionLayoutPrompt(String sheetName, String cellDump) {
}
