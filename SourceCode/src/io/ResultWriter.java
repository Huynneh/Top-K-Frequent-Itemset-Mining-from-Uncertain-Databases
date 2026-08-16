package io;

import algorithm.Algorithm;
import model.ItemsetResult;
import util.MemoryProfiler;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Ghi kết quả 1 lần chạy thuật toán ra file text riêng.
 * Tên file: results_<dataset>_<algorithm>.txt
 */
public class ResultWriter {

    public static Path write(Path outputDir, Algorithm.AlgorithmRun run) throws IOException {
        Files.createDirectories(outputDir);

        String safeDataset = run.datasetName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String fileName = "results_" + safeDataset + "_" + run.algorithmName + ".txt";
        Path outPath = outputDir.resolve(fileName);

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath))) {
            pw.println("========================================================");
            pw.println("THUAT TOAN      : " + run.algorithmName);
            pw.println("DATASET         : " + run.datasetName);
            pw.println("K               : " + run.k);
            pw.println("THOI GIAN CHAY  : " + run.runtimeMillis + " ms");
            pw.println("BO NHO SU DUNG  : " + MemoryProfiler.formatBytes(run.memoryUsedBytes)
                    + "  (xap xi, xem util.MemoryProfiler)");
            pw.println("SO CANDIDATE    : " + run.candidatesGenerated);
            pw.println("THOI DIEM CHAY  : " + LocalDateTime.now());
            if (run.extraInfo != null && !run.extraInfo.isEmpty()) {
                pw.println("--------------------------------------------------------");
                pw.println("THONG TIN BO SUNG:");
                for (Map.Entry<String, String> e : run.extraInfo.entrySet()) {
                    pw.println("  " + e.getKey() + " = " + e.getValue());
                }
            }
            pw.println("========================================================");
            pw.println();
            pw.println("TOP-" + run.k + " ITEMSET (sap xep giam dan theo expected support):");
            pw.println();
            int rank = 1;
            for (ItemsetResult r : run.topK) {
                pw.printf("%3d. %-40s expSup = %.6f%n", rank++, r.itemsetString(), r.expSup);
            }
            if (run.topK.isEmpty()) {
                pw.println("(Khong tim thay itemset nao co expected support > 0)");
            }
        }

        return outPath;
    }

    /** Ghi thêm 1 file tổng hợp so sánh runtime/bộ nhớ giữa các thuật toán trên cùng 1 dataset. */
    public static Path writeSummary(Path outputDir, String datasetName,
                                     Iterable<Algorithm.AlgorithmRun> runs) throws IOException {
        Files.createDirectories(outputDir);
        String safeDataset = datasetName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path outPath = outputDir.resolve("summary_" + safeDataset + ".txt");

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath))) {
            pw.println("SO SANH CAC THUAT TOAN TREN DATASET: " + datasetName);
            pw.println("========================================================");
            pw.printf("%-22s %-14s %-16s %-14s%n", "Thuat toan", "Runtime(ms)", "BoNho(approx)", "SoCandidate");
            pw.println("--------------------------------------------------------------");
            for (Algorithm.AlgorithmRun run : runs) {
                pw.printf("%-22s %-14d %-16s %-14d%n", run.algorithmName, run.runtimeMillis,
                        MemoryProfiler.formatBytes(run.memoryUsedBytes), run.candidatesGenerated);
            }
        }
        return outPath;
    }

    /**
     * Ghi 1 file CSV tổng hợp TOÀN BỘ (mọi dataset x mọi thuật toán) kèm chỉ số mật độ,
     * dùng để vẽ biểu đồ "runtime theo mức density" (biểu đồ "signature" của luận văn)
     * hoặc phân tích thêm bằng Python/Excel.
     *
     * Cột: dataset, densityScore, densityCategory, algorithm, runtimeMs, memoryBytes, candidates, topKAvgExpSup
     */
    public static Path writeBenchmarkCsv(Path outputDir, List<BenchmarkRow> rows) throws IOException {
        Files.createDirectories(outputDir);
        Path outPath = outputDir.resolve("benchmark_summary.csv");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath))) {
            pw.println("dataset,densityScore,densityCategory,algorithm,runtimeMs,memoryBytes,candidates,topKAvgExpSup");
            for (BenchmarkRow r : rows) {
                pw.printf("%s,%.6f,%s,%s,%d,%d,%d,%.6f%n",
                        r.dataset, r.densityScore, r.densityCategory, r.algorithm,
                        r.runtimeMs, r.memoryBytes, r.candidates, r.topKAvgExpSup);
            }
        }
        return outPath;
    }

    /** Một dòng dữ liệu cho file benchmark_summary.csv. */
    public static class BenchmarkRow {
        public final String dataset;
        public final double densityScore;
        public final String densityCategory;
        public final String algorithm;
        public final long runtimeMs;
        public final long memoryBytes;
        public final long candidates;
        public final double topKAvgExpSup;

        public BenchmarkRow(String dataset, double densityScore, String densityCategory, String algorithm,
                             long runtimeMs, long memoryBytes, long candidates, double topKAvgExpSup) {
            this.dataset = dataset;
            this.densityScore = densityScore;
            this.densityCategory = densityCategory;
            this.algorithm = algorithm;
            this.runtimeMs = runtimeMs;
            this.memoryBytes = memoryBytes;
            this.candidates = candidates;
            this.topKAvgExpSup = topKAvgExpSup;
        }
    }
}
