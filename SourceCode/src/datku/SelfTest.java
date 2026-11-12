package datku;

import algorithm.Algorithm;
import model.Transaction;
import model.UncertainDatabase;

import java.util.*;

/**
 * Kiểm thử ngẫu nhiên: sinh nhiều CSDL không chắc chắn nhỏ (có cố ý tạo giá trị bằng nhau
 * bằng xác suất 1.0 và 0.5), chạy mọi thuật toán với nhiều k, đối chiếu bằng Verifier.
 *   java -cp out datku.SelfTest [sốVòng] [seed]
 */
public class SelfTest {

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 7;
        String[] algos = {"TUFP", "UEclatTopK", "DATKU-DFS", "DATKU-BF", "DATKU-noDense", "DATKU-allDense",
                "DATKU-noPairInit", "DATKU-noPairBound", "DATKU-noAbandon", "DATKU-noMaxProb", "DATKU-plain", "DATKU-auto", "DATKU-autoBF", "DATKU-autoAll", "DATKU-cs", "DATKU-full", "DATKU-PAR1", "DATKU-PAR4", "DATKU-PAR2", "DATKU-PAR4-S", "DATKU-PAR4-W0", "DATKU-PAR3-W5", "DATKU-PAR4-D", "DATKU-PAR3-S-D", "UDiffsetTopK", "TUFP-pair", "TUFP"};
        Random rnd = new Random(seed);
        int checks = 0, fails = 0;
        for (int round = 0; round < rounds; round++) {
            int n = 1 + rnd.nextInt(80);
            int items = 1 + rnd.nextInt(14);
            double density = 0.1 + rnd.nextDouble() * 0.8;
            List<Transaction> ts = new ArrayList<>();
            for (int t = 0; t < n; t++) {
                List<Integer> it = new ArrayList<>();
                for (int i = 1; i <= items; i++) if (rnd.nextDouble() < density) it.add(i);
                if (it.isEmpty()) it.add(1 + rnd.nextInt(items));
                int[] a = it.stream().mapToInt(Integer::intValue).toArray();
                double[] p = new double[a.length];
                for (int j = 0; j < a.length; j++) {
                    double u = rnd.nextDouble();
                    p[j] = u < 0.25 ? 1.0 : (u < 0.4 ? 0.5 : 0.05 + 0.95 * rnd.nextDouble());
                }
                ts.add(new Transaction(t, a, p));
            }
            UncertainDatabase db = new UncertainDatabase("rand" + round, ts, items);
            int k = 1 + rnd.nextInt(120);
            // xây một lần cho kMax > k rồi khai phá k (kịch bản tương tác)
            {
                int kMax = k + rnd.nextInt(60);
                VerticalIndex shared = new VerticalIndex(db, kMax, 2.0, true);
                DATopK alg = (DATopK) app.BenchDATKU.build("DATKU-full");
                alg.setIndex(shared);
                String v = Verifier.check(db, k, alg.run(db, k).topK);
                checks++;
                if (!v.startsWith("OK")) { fails++; System.out.println("FAIL buildOnce round=" + round + ": " + v); }
            }
            for (String name : algos) {
                Algorithm alg = app.BenchDATKU.build(name);
                String v = Verifier.check(db, k, alg.run(db, k).topK);
                checks++;
                if (!v.startsWith("OK")) {
                    fails++;
                    if (fails <= 10) System.out.println("FAIL " + name + " round=" + round + " n=" + n + " items=" + items + " k=" + k + ": " + v);
                }
            }
        }
        System.out.println("SelfTest: " + checks + " lan kiem tra, " + fails + " loi");
        if (fails > 0) System.exit(1);
    }
}
