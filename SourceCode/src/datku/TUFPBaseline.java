package datku;

import algorithm.Algorithm;
import model.ItemsetResult;
import model.Transaction;
import model.UncertainDatabase;
import util.MemoryProfiler;

import java.util.*;

/**
 * Cài đặt lại TUFP theo mô tả trong bài báo gốc, dùng làm baseline:
 *   T. Le, B. Vo, V.-N. Huynh, N. T. Nguyen, S. W. Baik,
 *   "Mining top-k frequent patterns from uncertain databases", Applied Intelligence 50, 2020.
 *
 * Bám theo bài báo:
 *  - CUP-List (Lee & Yun 2017): tên mẫu, expSup, TEP-List (TID, xác suất), Max của item.
 *  - 1-pattern sắp TĂNG dần theo expSup, tất cả được đưa vào kết quả trước.
 *  - TUFP_Search: chia để trị theo lớp tương đương; phần tử đầu ghép với các phần tử còn lại cùng mức.
 *  - Chiến lược 1: mảng top-k, ngưỡng ξ = expSup nhỏ nhất khi đủ k phần tử.
 *  - Chiến lược 2: không ghép Cu, Cv nếu expSup(Cu) < ξ hoặc expSup(Cv) < ξ.
 *  - Ước lượng trên của LUNA: expSup(X)·Max(Y) < ξ thì bỏ XY.
 * Bản gốc có trong SPMF (Java); bản này viết lại để chạy cùng khung đo, cùng bộ đọc dữ liệu.
 */
public class TUFPBaseline implements Algorithm {

    private long candidates;
    private int k;
    private PriorityQueue<ItemsetResult> heap;

    /** true: TUFP + nâng ngưỡng sớm bằng expSup chính xác của mọi cặp item triển vọng (ablation cho phản biện). */
    private final boolean pairInit;

    public TUFPBaseline() { this(false); }
    public TUFPBaseline(boolean pairInit) { this.pairInit = pairInit; }

    @Override
    public String getName() {
        return pairInit ? "TUFP-pair" : "TUFP";
    }

    private static final class CupList {
        final int[] items;
        final int[] tids;
        final double[] probs;
        final double expSup;
        final double max; // chỉ dùng cho item đơn

        CupList(int[] items, int[] tids, double[] probs, double expSup, double max) {
            this.items = items; this.tids = tids; this.probs = probs; this.expSup = expSup; this.max = max;
        }
    }

    private double xi() {
        return heap.size() >= k ? heap.peek().expSup : 0.0;
    }

    private void insert(CupList c) {
        if (c.expSup <= 0) return;
        int[] sorted = c.items.clone();
        Arrays.sort(sorted);
        if (heap.size() < k) heap.add(new ItemsetResult(sorted, c.expSup));
        else if (c.expSup > heap.peek().expSup) {
            heap.poll();
            heap.add(new ItemsetResult(sorted, c.expSup));
        }
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.nanoTime();
        this.k = k;
        this.heap = new PriorityQueue<>(k + 1);
        candidates = 0;
        singles.clear();

        // UP-List của từng item (một lần quét, mảng nguyên thủy)
        Map<Integer, Integer> cnt = new HashMap<>();
        for (Transaction t : db.getTransactions())
            for (int it : t.items) cnt.merge(it, 1, Integer::sum);
        Map<Integer, int[]> tidArr = new HashMap<>();
        Map<Integer, double[]> probArr = new HashMap<>();
        Map<Integer, Integer> fill = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : cnt.entrySet()) {
            tidArr.put(e.getKey(), new int[e.getValue()]);
            probArr.put(e.getKey(), new double[e.getValue()]);
            fill.put(e.getKey(), 0);
        }
        int tid = 0;
        for (Transaction t : db.getTransactions()) {
            for (int i = 0; i < t.items.length; i++) {
                int it = t.items[i];
                int f = fill.get(it);
                tidArr.get(it)[f] = tid;
                probArr.get(it)[f] = t.probs[i];
                fill.put(it, f + 1);
            }
            tid++;
        }
        List<CupList> level = new ArrayList<>();
        Map<Integer, Double> maxOf = new HashMap<>();
        for (int item : cnt.keySet()) {
            int[] tids = tidArr.get(item);
            double[] probs = probArr.get(item);
            double s = 0, mx = 0;
            for (double pr : probs) {
                s += pr;
                if (pr > mx) mx = pr;
            }
            maxOf.put(item, mx);
            CupList single = new CupList(new int[]{item}, tids, probs, s, mx);
            singles.put(item, single);
            level.add(single);
        }
        level.sort(Comparator.comparingDouble(c -> c.expSup)); // tăng dần theo bài báo
        for (CupList c : level) {
            candidates++;
            insert(c);
        }
        if (pairInit) seedPairs(db, level);
        search(level, maxOf);

        List<ItemsetResult> result = new ArrayList<>(heap);
        result.sort((x, y) -> Double.compare(y.expSup, x.expSup));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long memUsed = MemoryProfiler.usedMemoryBytes() - memBefore;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("mode", "TUFP (CUP-List, re-implementation)");
        return new AlgorithmRun(getName(), db.name, k, result, elapsedMs, candidates, memUsed, extra);
    }

    private void search(List<CupList> level, Map<Integer, Double> maxOf) {
        for (int u = 0; u < level.size(); u++) {
            CupList cu = level.get(u);
            if (cu.expSup < xi()) continue; // chiến lược 2
            List<CupList> next = new ArrayList<>();
            for (int v = u + 1; v < level.size(); v++) {
                CupList cv = level.get(v);
                if (cv.expSup < xi()) continue; // chiến lược 2
                int y = cv.items[cv.items.length - 1];
                if (cu.expSup * maxOf.get(y) < xi()) continue; // ước lượng trên của LUNA
                candidates++;
                CupList c = join(cu, cv);
                if (c.expSup <= xi()) continue;
                if (!(pairInit && c.items.length == 2)) insert(c); // cặp đã có trong heap từ bước nâng ngưỡng
                next.add(c);
            }
            if (!next.isEmpty()) search(next, maxOf);
        }
    }

    /**
     * Ghép hai CUP-List cùng tiền tố P: Cu = P∪{x}, Cv = P∪{y}.
     * P(P∪{x,y}, t) = P(Cu, t) · p(y, t), nên giao TEP-List của Cu với UP-List của item y.
     * (Giao trực tiếp Cu với Cv sẽ nhân xác suất của tiền tố hai lần.)
     */
    private CupList join(CupList cu, CupList cv) {
        int y = cv.items[cv.items.length - 1];
        CupList iy = singles.get(y);
        int n = Math.min(cu.tids.length, iy.tids.length);
        int[] t = new int[n];
        double[] p = new double[n];
        int i = 0, j = 0, m = 0;
        double s = 0;
        while (i < cu.tids.length && j < iy.tids.length) {
            if (cu.tids[i] == iy.tids[j]) {
                t[m] = cu.tids[i];
                p[m] = cu.probs[i] * iy.probs[j];
                s += p[m];
                m++; i++; j++;
            } else if (cu.tids[i] < iy.tids[j]) i++;
            else j++;
        }
        int[] items = Arrays.copyOf(cu.items, cu.items.length + 1);
        items[cu.items.length] = y;
        return new CupList(items, Arrays.copyOf(t, m), Arrays.copyOf(p, m), s, 0);
    }

    private final Map<Integer, CupList> singles = new HashMap<>();

    /** Đưa expSup chính xác của mọi cặp item có expSup ≥ θ0 (= ngưỡng sau khi đưa item đơn) vào heap. */
    private void seedPairs(UncertainDatabase db, List<CupList> level) {
        double theta0 = xi();
        Map<Integer, Integer> rank = new HashMap<>();
        for (CupList c : level) if (c.expSup >= theta0) rank.put(c.items[0], rank.size());
        int p = rank.size();
        int[] item = new int[p];
        for (Map.Entry<Integer, Integer> e : rank.entrySet()) item[e.getValue()] = e.getKey();
        double[][] pair = new double[p][p];
        int[] r = new int[16];
        double[] pr = new double[16];
        for (Transaction t : db.getTransactions()) {
            int m = 0;
            if (r.length < t.items.length) { r = new int[t.items.length]; pr = new double[t.items.length]; }
            for (int i = 0; i < t.items.length; i++) {
                Integer x = rank.get(t.items[i]);
                if (x != null) { r[m] = x; pr[m] = t.probs[i]; m++; }
            }
            for (int a = 0; a < m; a++)
                for (int b = a + 1; b < m; b++) {
                    int lo = Math.min(r[a], r[b]), hi = Math.max(r[a], r[b]);
                    pair[lo][hi] += pr[a] * pr[b];
                }
        }
        for (int a = 0; a < p; a++)
            for (int b = a + 1; b < p; b++) {
                if (pair[a][b] <= 0) continue;
                candidates++;
                insert(new CupList(new int[]{item[a], item[b]}, new int[0], new double[0], pair[a][b], 0));
            }
    }
}
