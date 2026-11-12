package app;

import algorithm.Algorithm;
import datku.DATopK;
import datku.ITUFPBaseline;
import datku.VerticalIndex;
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
 * Kịch bản tương tác (như ITUFP): người dùng hỏi lần lượt nhiều giá trị k trên cùng CSDL.
 * So sánh tổng thời gian của: TUFP chạy lại từ đầu; DA-TKU chạy lại từ đầu; DA-TKU xây chỉ mục
 * một lần cho kMax rồi khai phá mỗi k trên chỉ mục đó.
 *   java -cp out app.BenchInteractive --datasets=... --k=10,50,100,500,1000 --repeats=5 --output=...
 */
public class BenchInteractive {
    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (String a : args) if (a.startsWith("--") && a.contains("=")) o.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
        int[] ks = Arrays.stream(o.getOrDefault("k", "10,50,100,500,1000").split(",")).mapToInt(Integer::parseInt).toArray();
        int repeats = Integer.parseInt(o.getOrDefault("repeats", "3"));
        String out = o.getOrDefault("output", "results/interactive.csv");
        int kMax = Arrays.stream(ks).max().getAsInt();
        try (PrintWriter csv = new PrintWriter(new FileWriter(out, true))) {
            if (new File(out).length() == 0) csv.println("dataset,run,mode,totalMs,buildMs,verify");
            for (String ds : o.getOrDefault("datasets", "").split(",")) {
                if (ds.isBlank()) continue;
                Path path = Paths.get(ds.trim());
                UncertainDatabase db = BenchDATKU.isExplicit(path)
                        ? DatasetReader.readWithExplicitProbabilities(path).database
                        : DatasetReader.read(path, new UniformProbabilityGenerator(0.3, 1.0), 42);
                for (int run = 1; run <= repeats; run++) {
                    // 1) TUFP chạy lại
                    long t = 0;
                    for (int k : ks) t += BenchDATKU.build("TUFP").run(db, k).runtimeMillis;
                    csv.printf(Locale.ROOT, "%s,%d,TUFP-rerun,%d,0,-%n", db.name, run, t);
                    // 1b) ITUFP: UP-List một lần, IMCUP-List giữ lại giữa các k
                    {
                        ITUFPBaseline it = new ITUFPBaseline();
                        t = 0;
                        long b = 0;
                        String iv = "OK";
                        for (int k : ks) {
                            Algorithm.AlgorithmRun r = it.run(db, k);
                            t += r.runtimeMillis;
                            b += Long.parseLong(r.extraInfo.get("buildMs"));
                            if (run == 1) {
                                String v = Verifier.check(db, k, r.topK);
                                if (!v.startsWith("OK")) iv = "k=" + k + ":" + v;
                            }
                        }
                        csv.printf(Locale.ROOT, "%s,%d,ITUFP-interactive,%d,%d,%s%n", db.name, run, t, b, run == 1 ? iv : "-");
                    }
                    // 2) DA-TKU chạy lại
                    t = 0;
                    for (int k : ks) t += BenchDATKU.build("DATKU-full").run(db, k).runtimeMillis;
                    csv.printf(Locale.ROOT, "%s,%d,DATKU-rerun,%d,0,-%n", db.name, run, t);
                    // 3) DA-TKU xây một lần
                    long b0 = System.nanoTime();
                    VerticalIndex ix = new VerticalIndex(db, kMax, 2.0, true);
                    long build = (System.nanoTime() - b0) / 1_000_000;
                    t = build;
                    String ver = "OK";
                    for (int k : ks) {
                        DATopK alg = (DATopK) BenchDATKU.build("DATKU-full");
                        alg.setIndex(ix);
                        Algorithm.AlgorithmRun r = alg.run(db, k);
                        t += r.runtimeMillis;
                        if (run == 1) {
                            String v = Verifier.check(db, k, r.topK);
                            if (!v.startsWith("OK")) ver = "k=" + k + ":" + v;
                        }
                    }
                    csv.printf(Locale.ROOT, "%s,%d,DATKU-buildOnce,%d,%d,%s%n", db.name, run, t, build, run == 1 ? ver : "-");
                    csv.flush();
                    System.out.println(db.name + " run " + run + " xong");
                }
            }
        }
    }
}
