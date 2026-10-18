package pt.ulisboa.tecnico.cnv.common.metrics;

public final class MetricBucketer {

    private MetricBucketer() {
        // Utility class, no instantiation
    }

    /**
     * Bucket fractals request parameters.
     * Converts width, height, and iterations into bucketed values.
     * 
     * @param w width in pixels
     * @param h height in pixels
     * @param iterations iteration count
     * @return bucket string like "fractals#w8-h6-i2"
     */
    public static String bucketFractals(int w, int h, int iterations) {
        int wb = Math.max(1, w / 100);
        int hb = Math.max(1, h / 100);
        int ib = Math.max(1, iterations / 50);
        return "fractals#w" + wb + "-h" + hb + "-i" + ib;
    }

    /**
     * Bucket DNA request parameters.
     * Uses sequence lengths, not raw sequences, to avoid storing large data.
     * 
     * @param seq1 first DNA sequence
     * @param seq2 second DNA sequence
     * @param minLength minimum length threshold
     * @param stopOnFirst whether to stop on first match
     * @return bucket string like "dna#l1_1-l2_2-m10-stopfalse"
     */
    public static String bucketDna(String seq1, String seq2, int minLength, boolean stopOnFirst) {
        int l1 = Math.max(1, seq1.length() / 100);
        int l2 = Math.max(1, seq2.length() / 100);
        return "dna#l1_" + l1 + "-l2_" + l2 + "-m" + minLength + "-stop" + stopOnFirst;
    }

    /**
     * Bucket GrayScott request parameters.
     * Converts grid size, iterations, and chemical parameters into bucketed values.
     * 
     * @param size grid size (size x size)
     * @param maxIterations iteration count
     * @param f F parameter (feed rate)
     * @param k K parameter (kill rate)
     * @param stopOnExtinction whether to stop on extinction
     * @param seedMode seeding strategy
     * @return bucket string like "grayscott#s10-i5-f100-k50-stopfalse-seedrandom"
     */
    public static String bucketGrayScott(int size, int maxIterations, double f, double k, 
                                         boolean stopOnExtinction, String seedMode) {
        int sb = Math.max(1, size / 64);
        int ib = Math.max(1, maxIterations / 250);
        int fb = (int) Math.round(f * 1000);
        int kb = (int) Math.round(k * 1000);
        return "grayscott#s" + sb + "-i" + ib + "-f" + fb + "-k" + kb + 
               "-stop" + stopOnExtinction + "-seed" + seedMode;
    }
}
