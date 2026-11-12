package app;

import algorithm.Algorithm;
import algorithm.NaiveApriori;
import algorithm.UDiffsetTopK;
import algorithm.UEclatTopK;
import datku.DATopK;
import datku.TUFPBaseline;
import datku.Verifier;
import io.DatasetReader;
import model.UncertainDatabase;
import probability.UniformProbabilityGenerator;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Chạy đo các thuật toán trong gói datku và baseline trên cùng dữ liệu.
 *
 * Ví dụ:
 *   java -Xmx6g -cp out app.BenchDATKU --datasets=data/uncertain/chess_uncertain.txt \
 *        --k=10,50,100 --algorithms=TUFP,UEclatTopK,DATKU-DFS,DATKU-BF --repeats=3 --verify=true
 *
 * File có dấu ':' (dạng "tid item:prob ...") được đọc với xác suất tường minh;
 * file SPMF thường được gán xác suất uniform [probMin, probMax] với seed cố định.
 * Mỗi thuật toán được chạy riêng, tuần tự (không chạy chồng) để số đo thời gian/bộ nhớ không nhiễu.
 */
public class BenchDATKU {

    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (String a : args) if (a.startsWith("--") && a.contains("=")) o.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
        String[] datasets = o.getOrDefault("datasets", "").split(",");
        int[] ks = Arrays.stream(o.getOrDefault("k", "10").split(",")).mapToInt(Integer::parseInt).toArray();
        String[] algos = o.getOrDefault("algorithms", "TUFP,UEclatTopK,DATKU-DFS,DATKU-BF").split(",");
        int repeats = Integer.parseInt(o.getOrDefault("repeats", "1"));
        boolean verify = Boolean.parseBoolean(o.getOrDefault("verify", "false"));
        double probMin = Double.parseDouble(o.getOrDefault("probMin", "0.3"));
        double probMax = Double.parseDouble(o.getOrDefault("probMax", "1.0"));
        long seed = Long.parseLong(o.getOrDefault("seed", "42"));
        String out = o.getOrDefault("output", "results/datku_bench.csv");
        new File(out).getAbsoluteFile().getParentFile().mkdirs();

        try (PrintWriter csv = new PrintWriter(new FileWriter(out, true))) {
            if (new File(out).length() == 0)
                csv.println("dataset,transactions,items,avgLen,structDensity,algorithm,k,run,runtimeMs,memoryMB,candidates,kthExpSup,verify,extra");
            for (String ds : datasets) {
                if (ds.isBlank()) continue;
                Path path = Paths.get(ds.trim());
                UncertainDatabase db = isExplicit(path)
                        ? DatasetReader.readWithExplicitProbabilities(path).database
                        : DatasetReader.read(path, new UniformProbabilityGenerator(probMin, probMax), seed);
                System.out.printf("== %s: %d giao dich, %d item, ATL=%.2f, mat do=%.4f%%%n", db.name, db.size(),
                        db.numDistinctItems, db.avgTransactionLength(), 100.0 * db.avgTransactionLength() / db.numDistinctItems);
                for (int k : ks) {
                    for (String name : algos) {
                        for (int run = 1; run <= repeats; run++) {
                            Algorithm alg = build(name.trim());
                            Algorithm.AlgorithmRun r = alg.run(db, k);
                            String ver = "-";
                            if (verify && run == 1) {
                                if (name.trim().startsWith("PSO")) {
                                    List<model.ItemsetResult> ex = build("DATKU-full").run(db, k).topK;
                                    ver = String.format(Locale.ROOT, "ACC=%.4f", accuracy(ex, r.topK));
                                } else ver = Verifier.check(db, k, r.topK);
                            }
                            double kth = r.topK.isEmpty() ? 0 : r.topK.get(r.topK.size() - 1).expSup;
                            // phân bố độ dài itemset trong kết quả top-k (1, 2, ≥3 item)
                            int[] len = new int[3];
                            for (model.ItemsetResult it : r.topK) len[Math.min(it.itemset.length, 3) - 1]++;
                            try { r.extraInfo.put("len", len[0] + "/" + len[1] + "/" + len[2]); } catch (UnsupportedOperationException ignored) { }
                            System.out.printf("   k=%-5d %-14s run %d: %8d ms  %9.2f MB  cand=%-10d kth=%.4f  %s %s%n", k, r.algorithmName, run,
                                    r.runtimeMillis, r.memoryUsedBytes / 1048576.0, r.candidatesGenerated, kth, ver, r.extraInfo);
                            csv.printf(Locale.ROOT, "%s,%d,%d,%.3f,%.6f,%s,%d,%d,%d,%.3f,%d,%.6f,%s,\"%s\"%n", db.name, db.size(), db.numDistinctItems,
                                    db.avgTransactionLength(), db.avgTransactionLength() / db.numDistinctItems, r.algorithmName, k, run,
                                    r.runtimeMillis, r.memoryUsedBytes / 1048576.0, r.candidatesGenerated, kth, ver, r.extraInfo.toString().replace("\"", "'"));
                            csv.flush();
                        }
                    }
                }
            }
        }
    }

    /** Tỉ lệ giá trị trong kết quả gần đúng khớp với dãy giá trị top-k chính xác (so khớp tham lam). */
    public static double accuracy(List<model.ItemsetResult> exact, List<model.ItemsetResult> approx) {
        if (exact.isEmpty()) return 1.0;
        int i = 0, j = 0, hit = 0;
        while (i < exact.size() && j < approx.size()) {
            double a = exact.get(i).expSup, b = approx.get(j).expSup;
            if (Math.abs(a - b) <= 1e-6 * Math.max(1, a)) { hit++; i++; j++; }
            else if (a > b) i++; else j++;
        }
        return (double) hit / exact.size();
    }

    public static boolean isExplicit(Path p) throws Exception {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(p.toFile()))) {
            for (int i = 0; i < 3; i++) {
                String l = br.readLine();
                if (l == null) break;
                if (l.contains(":")) return true;
            }
        }
        return false;
    }

    public static Algorithm build(String name) {
        DATopK.Config c = new DATopK.Config();
        switch (name) {
            case "TUFP": return new TUFPBaseline();
            case "TUFP-pair": return new TUFPBaseline(true);
            case "ITUFP": return new datku.ITUFPBaseline();
            case "UEclatTopK": return new UEclatTopK();
            case "UDiffsetTopK": return new UDiffsetTopK();
            case "NaiveApriori": return new NaiveApriori();
            case "DATKU-DFS": c.search = DATopK.Search.DFS; break;
            case "DATKU-BF": c.search = DATopK.Search.BEST_FIRST; break;
            // các biến thể ablation: tắt từng kỹ thuật
            case "DATKU-noDense": c.denseRatio = 2.0; break;
            case "DATKU-allDense": c.denseRatio = 0.0; break;
            case "DATKU-noPairInit": c.pairInit = false; break;
            case "DATKU-noPairBound": c.pairBound = false; break;
            case "DATKU-noAbandon": c.earlyAbandon = false; break;
            case "DATKU-noMaxProb": c.maxProbBound = false; break;
            case "DATKU-auto": c.autoLayout = true; break;
            case "DATKU-autoAll": c.autoLayout = true; c.autoSearch = true; break;
            case "DATKU-cs": c.csBound = true; break;
            case "DATKU-full": c.autoLayout = true; c.autoSearch = true; c.csBound = true; break;
            case "PSO": return new datku.TKUPSOUncertain(20, 2000, 200, 42);
            case "DATKU-autoBF": c.autoLayout = true; c.search = DATopK.Search.BEST_FIRST; break;
            case "DATKU-plain": c.pairInit = false; c.pairBound = false; c.earlyAbandon = false; c.maxProbBound = false; c.denseRatio = 2.0; break;
            default:
                if (name.startsWith("DATKU-PAR")) {
                    // DATKU-PAR<n>: song song n luồng, bố cục tự chọn θ-aware
                    c.autoLayout = true;
                    c.csBound = true;
                    c.label = name;
                    // DATKU-PAR<t>[-W<b>]: b = ngân sách pha khởi động best-first (mặc định k)
                    // DATKU-PAR<t>-S: dựng ma trận cặp tuần tự (để đo riêng lợi ích của bước song song hóa chỉ mục)
                    // DATKU-PAR<t>-D: tắt best-first song song theo lô (khởi động tuần tự rồi DFS song song, bản trước)
                    String rest = name.substring(9);
                    boolean dfsOnly = rest.endsWith("-D");
                    if (dfsOnly) rest = rest.substring(0, rest.length() - 2);
                    boolean serialIndex = rest.endsWith("-S");
                    if (serialIndex) rest = rest.substring(0, rest.length() - 2);
                    int w = -1;
                    if (rest.contains("-W")) { w = Integer.parseInt(rest.substring(rest.indexOf("-W") + 2)); rest = rest.substring(0, rest.indexOf("-W")); }
                    return new datku.ParallelDATopK(c, Integer.parseInt(rest)).withWarmup(w).withParallelIndex(!serialIndex).withBatchedBestFirst(!dfsOnly);
                }
                throw new IllegalArgumentException("Khong biet thuat toan: " + name);
        }
        c.label = name;
        return new DATopK(c);
    }
}
