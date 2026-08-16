package app;

import algorithm.Algorithm;
import algorithm.DensityAdaptiveTopK;
import algorithm.NaiveApriori;
import algorithm.UDiffsetTopK;
import algorithm.UEclatTopK;
import density.DensityCalculator;
import io.DatasetReader;
import io.ResultWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import model.UncertainDatabase;
import probability.ProbabilityGenerator;
import probability.UniformProbabilityGenerator;

/**
 * Chương trình chính: đọc nhiều file dataset (định dạng chuẩn SPMF, chưa có xác suất),
 * gắn xác suất giả lập, chạy nhiều thuật toán Top-K Uncertain FIM (tuần tự hoặc song song),
 * xuất kết quả ra file riêng cho từng (dataset, thuật toán), và ghi 1 file CSV tổng hợp
 * so sánh runtime/bộ nhớ theo mật độ dữ liệu của từng dataset.
 *
 * CÁCH DÙNG:
 *   java -cp out app.Main \
 *              --datasets=data/chess.txt,data/retail.txt \
 *              --k=10 --algorithms=all --output=results \
 *              --parallel=true --probMin=0.3 --probMax=1.0 --seed=42
 *
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        if (!opts.containsKey("datasets")) {
            printUsageAndExit();
        }

        List<String> datasetPaths = Arrays.asList(opts.get("datasets").split(","));
        int k = Integer.parseInt(opts.getOrDefault("k", "10"));
        String algorithmsOpt = opts.getOrDefault("algorithms", "all");
        Path outputDir = Paths.get(opts.getOrDefault("output", "results"));
        boolean parallel = Boolean.parseBoolean(opts.getOrDefault("parallel", "false"));
        double probMin = Double.parseDouble(opts.getOrDefault("probMin", "0.3"));
        double probMax = Double.parseDouble(opts.getOrDefault("probMax", "1.0"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "42"));
        double lowThreshold = Double.parseDouble(opts.getOrDefault("lowThreshold", "0.35"));
        double highThreshold = Double.parseDouble(opts.getOrDefault("highThreshold", "0.55"));
        boolean explicitProb = Boolean.parseBoolean(opts.getOrDefault("explicitProb", "false"));

        ProbabilityGenerator probGen = new UniformProbabilityGenerator(probMin, probMax);

        System.out.println("=== CAU HINH CHAY ===");
        System.out.println("Datasets     : " + datasetPaths);
        System.out.println("k            : " + k);
        System.out.println("Algorithms   : " + algorithmsOpt);
        System.out.println("Output dir   : " + outputDir.toAbsolutePath());
        System.out.println("Parallel     : " + parallel);
        System.out.println("ExplicitProb : " + explicitProb
                + (explicitProb ? "" : "  (Prob range: [" + probMin + ", " + probMax + "], seed=" + seed + ")"));
        System.out.println("Density band : sparse < " + lowThreshold + " <= trung binh < " + highThreshold + " <= dense");
        System.out.println("======================\n");

        List<ResultWriter.BenchmarkRow> allRows = new ArrayList<>();

        for (String datasetPathStr : datasetPaths) {
            Path datasetPath = Paths.get(datasetPathStr.trim());
            System.out.println(">> Dang doc dataset: " + datasetPath);

            UncertainDatabase db;
            try {
                if (explicitProb) {
                    db = DatasetReader.readWithExplicitProbabilities(datasetPath).database;
                } else {
                    db = DatasetReader.read(datasetPath, probGen, seed);
                }
            } catch (IOException e) {
                System.err.println("LOI doc file " + datasetPath + ": " + e.getMessage());
                continue;
            }
            System.out.println("   -> So transaction: " + db.size()
                    + " | So item rieng biet: " + db.numDistinctItems);

            DensityCalculator density = new DensityCalculator(db);
            String densityCategory = density.densityScore >= highThreshold ? "DENSE"
                    : density.densityScore < lowThreshold ? "SPARSE" : "MEDIUM";
            System.out.println("   -> " + density + "  => phan loai: " + densityCategory);

            List<Algorithm> algorithms = buildAlgorithms(algorithmsOpt, lowThreshold, highThreshold);

            List<Algorithm.AlgorithmRun> runsForSummary;
            if (parallel) {
                runsForSummary = runParallel(algorithms, db, k);
            } else {
                runsForSummary = runSequential(algorithms, db, k);
            }

            for (Algorithm.AlgorithmRun run : runsForSummary) {
                Path outPath = ResultWriter.write(outputDir, run);
                System.out.println("   -> Da ghi ket qua [" + run.algorithmName + "]: " + outPath
                        + "  (runtime=" + run.runtimeMillis + "ms)");

                double avgExpSup = run.topK.isEmpty() ? 0 :
                        run.topK.stream().mapToDouble(r -> r.expSup).average().orElse(0);
                allRows.add(new ResultWriter.BenchmarkRow(
                        db.name, density.densityScore, densityCategory, run.algorithmName,
                        run.runtimeMillis, run.memoryUsedBytes, run.candidatesGenerated, avgExpSup));
            }
            Path summaryPath = ResultWriter.writeSummary(outputDir, db.name, runsForSummary);
            System.out.println("   -> Da ghi file tong hop so sanh: " + summaryPath + "\n");
        }

        Path csvPath = ResultWriter.writeBenchmarkCsv(outputDir, allRows);
        System.out.println("Da ghi file CSV tong hop toan bo (dung de ve bieu do theo density): " + csvPath);

        System.out.println("HOAN TAT.");
    }

    private static List<Algorithm.AlgorithmRun> runSequential(List<Algorithm> algorithms,
                                                                UncertainDatabase db, int k) {
        List<Algorithm.AlgorithmRun> results = new ArrayList<>();
        for (Algorithm algo : algorithms) {
            System.out.println("   -> Dang chay thuat toan: " + algo.getName() + " ...");
            Algorithm.AlgorithmRun run = algo.run(db, k);
            results.add(run);
        }
        return results;
    }

    private static List<Algorithm.AlgorithmRun> runParallel(List<Algorithm> algorithms,
                                                              UncertainDatabase db, int k) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(algorithms.size(), 4));
        List<Future<Algorithm.AlgorithmRun>> futures = new ArrayList<>();
        for (Algorithm algo : algorithms) {
            futures.add(pool.submit(() -> {
                System.out.println("   -> [song song] Bat dau: " + algo.getName());
                return algo.run(db, k);
            }));
        }
        List<Algorithm.AlgorithmRun> results = new ArrayList<>();
        for (Future<Algorithm.AlgorithmRun> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private static List<Algorithm> buildAlgorithms(String algorithmsOpt, double lowThreshold, double highThreshold) {
        List<Algorithm> list = new ArrayList<>();
        Set<String> requested;
        if (algorithmsOpt.equalsIgnoreCase("all")) {
            requested = new LinkedHashSet<>(Arrays.asList(
                    "NaiveApriori", "UEclatTopK", "UDiffsetTopK", "DensityAdaptiveTopK"));
        } else {
            requested = new LinkedHashSet<>(Arrays.asList(algorithmsOpt.split(",")));
        }
        for (String name : requested) {
            switch (name.trim()) {
                case "NaiveApriori":
                    list.add(new NaiveApriori());
                    break;
                case "UEclatTopK":
                    list.add(new UEclatTopK());
                    break;
                case "UDiffsetTopK":
                    list.add(new UDiffsetTopK());
                    break;
                case "DensityAdaptiveTopK":
                    list.add(new DensityAdaptiveTopK(lowThreshold, highThreshold));
                    break;
                default:
                    System.err.println("Canh bao: khong nhan dien thuat toan '" + name + "', bo qua.");
            }
        }
        return list;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int idx = arg.indexOf('=');
                map.put(arg.substring(2, idx), arg.substring(idx + 1));
            }
        }
        return map;
    }

    private static void printUsageAndExit() {
        System.out.println("Thieu tham so --datasets. Vi du:");
        System.out.println("java -cp out app.Main --datasets=data/chess.txt,data/retail.txt --k=10 --algorithms=all --output=results --parallel=false");
        System.exit(1);
    }
}
