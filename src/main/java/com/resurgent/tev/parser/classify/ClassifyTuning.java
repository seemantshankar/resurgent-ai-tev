package com.resurgent.tev.parser.classify;

/**
 * Speed knobs for the LLM stages. A call costs roughly the same whether it carries one cell or
 * fifteen, so bigger batches and a few calls in flight at once are where the time goes.
 *
 * @param cellBatchSize cells per Layer B classification call
 * @param layerABatchSize candidates per Layer A call
 * @param concurrency calls in flight at once; 1 runs strictly one after another
 */
public record ClassifyTuning(int cellBatchSize, int layerABatchSize, int concurrency) {

    public static final int DEFAULT_CELL_BATCH_SIZE = 15;
    public static final int DEFAULT_LAYER_A_BATCH_SIZE = 10;

    public ClassifyTuning {
        if (cellBatchSize < 1 || layerABatchSize < 1 || concurrency < 1) {
            throw new IllegalArgumentException("batch sizes and concurrency must be >= 1");
        }
    }

    /** One call at a time with the original batch sizes: what library callers and tests get. */
    public static ClassifyTuning sequential() {
        return new ClassifyTuning(DEFAULT_CELL_BATCH_SIZE, DEFAULT_LAYER_A_BATCH_SIZE, 1);
    }
}
