package datku;

import algorithm.Algorithm;
import model.ItemsetResult;
import model.UncertainDatabase;
import util.MemoryProfiler;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.LongAdder;

/**
 * DA-TKU song song đa lõi (bộ nhớ chia sẻ) bằng ForkJoin, với ngưỡng θ CHIA SẺ giữa các luồng.
 *
 *  - Chỉ mục, nâng ngưỡng sớm và chọn bố cục giống DATopK (tuần tự, một lần).
 *  - Mỗi nhánh gốc {a} là một tác vụ; trong nhánh, mỗi nút con 2-itemset {a,y} được tách thành
 *    tác vụ con để work-stealing cân bằng tải (các nhánh có kích thước rất chênh lệch).
 *  - Heap top-k dùng chung, cập nhật trong khối synchronized; θ là biến volatile chỉ tăng,
 *    được ghi sau mỗi lần heap thay đổi. Luồng đọc θ cũ (thấp hơn) chỉ làm cắt tỉa yếu đi,
 *    không bao giờ làm sai kết quả, nên đường đọc θ không cần khóa.
 */
public class ParallelDATopK implements Algorithm {

    private final DATopK.Config cfg;
    private final int threads;

    private VerticalIndex ix;
    private int k;
    private PriorityQueue<ItemsetResult> heap;
    private volatile double theta;
    private volatile boolean full;
    private final LongAdder candidates = new LongAdder();
    private final LongAdder intersections = new LongAdder();
    private final LongAdder abandoned = new LongAdder();
    private final LongAdder pairPruned = new LongAdder();
    private final LongAdder heapUpdates = new LongAdder();

    public ParallelDATopK(DATopK.Config cfg, int threads) {
        this.cfg = cfg;
        this.threads = threads;
    }

    @Override
    public String getName() {
        return cfg.label != null ? cfg.label : "DATKU-PAR" + threads;
    }

    private void offerSerial(int[] ranks, double es) {
        if (es <= 0) return;
        if (heap.size() < k) heap.add(new ItemsetResult(ix.toOriginal(ranks), es));
        else if (es > heap.peek().expSup) { heap.poll(); heap.add(new ItemsetResult(ix.toOriginal(ranks), es)); }
        full = heap.size() >= k;
        theta = full ? heap.peek().expSup : 0.0;
    }

    /** Khóa của các itemset (≥ 3 item) đã được đưa vào heap trong pha khởi động best-first. */
    private Set<String> warmKeys = Collections.emptySet();
    /** Số phép mở rộng tối đa của pha khởi động best-first tuần tự (0 = tắt). */
    private int warmupBudget = -1;

    public ParallelDATopK withWarmup(int budget) { this.warmupBudget = budget; return this; }

    /** true: ma trận cặp dựng song song theo hàng (mặc định); false: dựng tuần tự như bản trước. */
    private boolean parallelIndex = true;
    public ParallelDATopK withParallelIndex(boolean on) { this.parallelIndex = on; return this; }

    /**
     * true (mặc định): khi bộ chọn chọn best-first (V > |P|) và không đặt ngân sách khởi động tường minh,
     * chạy best-first song song theo lô thay vì khởi động tuần tự rồi DFS song song.
     */
    private boolean batchedBestFirst = true;
    public ParallelDATopK withBatchedBestFirst(boolean on) { this.batchedBestFirst = on; return this; }
    private int batchesRun;

    private static String key(int[] ranks) {
        int[] c = ranks.clone(); Arrays.sort(c); return Arrays.toString(c);
    }

    private void offer(int[] ranks, double es) {
        if (es <= 0) return;
        if (full && es <= theta) return; // lọc nhanh không khóa
        if (!warmKeys.isEmpty() && warmKeys.contains(key(ranks))) return; // đã có từ pha khởi động
        synchronized (this) {
            heapUpdates.increment();
            offerSerial(ranks, es);
        }
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.nanoTime();
        this.k = k;
        this.heap = new PriorityQueue<>(k + 1);
        this.theta = 0;
        this.full = false;

        ix = new VerticalIndex(db, k, cfg.autoLayout ? 2.0 : cfg.denseRatio, true, parallelIndex ? threads : 1);
        int p = ix.p;
        for (int r = 0; r < p; r++) { candidates.increment(); offerSerial(new int[]{r}, ix.expSup1[r]); }
        long viablePairs = 0;
        if (cfg.pairInit && p > 1) {
            // Nâng ngưỡng bằng cặp: thay vì đưa cả p(p−1)/2 cặp qua heap tuần tự, tìm giá trị lớn thứ k
            // của (item đơn ∪ cặp) bằng sắp xếp song song, rồi chỉ đưa vào heap các cặp ≥ giá trị đó.
            // Heap thu được giống hệt bản tuần tự (cùng tập giá trị top-k).
            int np = p * (p - 1) / 2;
            double[] all = new double[np + p];
            ForkJoinPool pp = new ForkJoinPool(parallelIndex ? threads : 1);
            try {
                pp.submit(() -> java.util.stream.IntStream.range(0, p - 1).parallel().forEach(a -> {
                    int base = a * (2 * p - a - 1) / 2; // chỉ số của (a, a+1) trong tam giác trên
                    double[] row = ix.pair[a];
                    for (int b = a + 1; b < p; b++) all[base + b - a - 1] = row[b];
                })).join();
                System.arraycopy(ix.expSup1, 0, all, np, p);
                double[] sorted = all.clone();
                Arrays.parallelSort(sorted);
                double kth = sorted.length >= k ? sorted[sorted.length - k] : 0.0;
                final double cut = kth;
                for (int a = 0; a < p - 1; a++) {
                    double[] row = ix.pair[a];
                    for (int b = a + 1; b < p; b++) if (row[b] >= cut && row[b] > 0) offerSerial(new int[]{a, b}, row[b]);
                }
                candidates.add(np);
                // V = số cặp có expSup > θ1 (dùng cho quy tắc bật pha khởi động, như autoSearch)
                int lo = 0, hi = np + p; // vị trí đầu tiên > theta trong sorted
                while (lo < hi) { int mid = (lo + hi) >>> 1; if (sorted[mid] > theta) hi = mid; else lo = mid + 1; }
                long above = np + p - lo, singlesAbove = 0;
                for (int r = 0; r < p; r++) if (ix.expSup1[r] > theta) singlesAbove++;
                viablePairs = above - singlesAbove;
            } finally {
                pp.shutdown();
            }
        }
        if (cfg.autoLayout) LayoutSelector.apply(ix, theta, LayoutSelector.defaultBudget());

        // Pha khởi động best-first tuần tự: mở rộng các nút lớn nhất trước để θ gần giá trị cuối
        // trước khi chia việc cho các luồng DFS (DFS song song không có thứ tự ưu tiên toàn cục).
        // Mặc định (warmupBudget < 0) chỉ bật khi V > |P|, tức khi bộ chọn tuần tự sẽ chọn best-first.
        boolean batched = batchedBestFirst && warmupBudget < 0 && viablePairs > p;
        int budget = batched ? 0 : warmupBudget >= 0 ? warmupBudget : (viablePairs > p ? k : 0);
        boolean finished = budget > 0 && warmup(budget);
        long serialNanos = System.nanoTime() - start; // chỉ mục + nâng ngưỡng sớm + chọn bố cục + khởi động
        ForkJoinPool pool = new ForkJoinPool(threads);
        if (batched) {
            try { parallelBestFirst(pool); } finally { pool.shutdown(); }
        } else if (finished) pool.shutdown();
        else try {
            List<RecursiveAction> roots = new ArrayList<>();
            for (int a = 0; a < p; a++) {
                if (full && ix.expSup1[a] <= theta) break;
                final int fa = a;
                roots.add(new RecursiveAction() {
                    @Override protected void compute() {
                        Node n = new Node(new int[]{fa}, ix.tids[fa], ix.probs[fa], ix.expSup1[fa]);
                        n.sumSq = ix.sumSq1[fa];
                        expand(n, fa + 1, initialPairUb(fa), true);
                    }
                });
            }
            pool.submit(() -> RecursiveAction.invokeAll(roots)).join();
        } finally {
            pool.shutdown();
        }

        List<ItemsetResult> result = new ArrayList<>(heap);
        result.sort((x, y) -> Double.compare(y.expSup, x.expSup));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long memUsed = MemoryProfiler.usedMemoryBytes() - memBefore;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("threads", String.valueOf(threads));
        extra.put("promisingItems", String.valueOf(p));
        extra.put("denseItems", String.valueOf(ix.denseItemCount()));
        extra.put("indexBuildMs", String.valueOf(ix.buildNanos / 1_000_000));
        extra.put("intersections", String.valueOf(intersections.sum()));
        extra.put("earlyAbandoned", String.valueOf(abandoned.sum()));
        extra.put("pairPruned", String.valueOf(pairPruned.sum()));
        extra.put("heapLocks", String.valueOf(heapUpdates.sum()));
        extra.put("serialMs", String.valueOf(serialNanos / 1_000_000));
        extra.put("warmupItemsets", String.valueOf(warmKeys.size()));
        extra.put("finishedInWarmup", String.valueOf(finished));
        extra.put("viablePairs", String.valueOf(viablePairs));
        extra.put("warmupBudget", String.valueOf(budget));
        extra.put("pairMatrixMs", String.valueOf(ix.pairNanos / 1_000_000));
        extra.put("parallelIndex", String.valueOf(parallelIndex));
        extra.put("search", batched ? "BATCHED_BEST_FIRST" : "DFS");
        if (batched) extra.put("batches", String.valueOf(batchesRun));
        return new AlgorithmRun(getName(), db.name, k, result, elapsedMs, candidates.sum(), memUsed, extra);
    }

    /**
     * Best-first song song theo lô. Mỗi vòng lấy tối đa B nút có expSup lớn nhất (và > θ) khỏi hàng đợi,
     * sinh các nút con của chúng song song (mỗi cặp (nút, y) là một tác vụ), rồi đưa các con còn > θ vào
     * hàng đợi. Mỗi itemset vẫn chỉ được sinh đúng một lần từ cha theo thứ tự hạng, nên không cần warmKeys.
     * Dừng khi đỉnh hàng đợi ≤ θ (Bổ đề 4.6): mọi nút còn lại và con cháu của chúng đều ≤ θ.
     * So với DFS song song, thứ tự mở rộng gần với best-first tuần tự (sai khác tối đa một lô), nên θ tăng
     * sớm như bản tuần tự và số phép giao gần bằng bản tuần tự thay vì gấp nhiều lần.
     */
    private void parallelBestFirst(ForkJoinPool pool) {
        PriorityQueue<Object[]> pq = new PriorityQueue<>((a, b) -> Double.compare(((Node) b[0]).expSup, ((Node) a[0]).expSup));
        for (int a = 0; a < ix.p; a++) {
            if (full && ix.expSup1[a] <= theta) break;
            Node n = new Node(new int[]{a}, ix.tids[a], ix.probs[a], ix.expSup1[a]);
            n.sumSq = ix.sumSq1[a];
            pq.add(new Object[]{n, a + 1, initialPairUb(a)});
        }
        // Lô: lấy nút cho tới khi đủ ~WORK cặp (nút, y) hoặc NODES nút. Lô nhỏ chạy ngay trong luồng chính
        // để tránh chi phí điều phối ForkJoin khi phần việc quá ít.
        int maxNodes = Integer.getInteger("datku.batchNodes", 4 * threads);
        int minWork = Integer.getInteger("datku.batchWork", 32 * threads);
        int inlineBelow = 2 * threads;
        List<Object[]> popped = new ArrayList<>();
        List<int[]> work = new ArrayList<>();             // (chỉ số nút trong popped, y)
        while (!pq.isEmpty()) {
            if (full && ((Node) pq.peek()[0]).expSup <= theta) break;
            popped.clear(); work.clear();
            while (popped.size() < maxNodes && work.size() < minWork && !pq.isEmpty()) {
                Object[] top = pq.peek();
                if (full && ((Node) top[0]).expSup <= theta) break;
                popped.add(pq.poll());
                int i = popped.size() - 1;
                Node x = (Node) top[0];
                int from = (Integer) top[1];
                double[] pairUb = (double[]) top[2];
                for (int y = from; y < ix.p; y++) {
                    if (full && upperBound(x, y, pairUb) <= theta) { pairPruned.increment(); continue; }
                    work.add(new int[]{i, y});
                }
            }
            if (work.isEmpty()) continue;
            batchesRun++;
            Object[][] out = new Object[work.size()][];
            java.util.function.IntConsumer task = w -> {
                int[] wy = work.get(w);
                Object[] e = popped.get(wy[0]);
                Node x = (Node) e[0];
                int y = wy[1];
                double[] pairUb = (double[]) e[2];
                if (full && upperBound(x, y, pairUb) <= theta) { pairPruned.increment(); return; } // θ có thể đã tăng
                double[] childUb = childPairUb(pairUb, y);
                int size = x.items.length + 1;
                if (size == 2 && cfg.pairInit && cfg.pairBound
                        && !hasViableExtension(ix.pair[x.items[0]][y], y + 1, childUb)) return;
                candidates.increment();
                Node c = extend(x, y);
                if (c == null || c.expSup <= 0) return;
                if (!(size == 2 && cfg.pairInit)) offer(c.items, c.expSup);
                if (full && c.expSup <= theta) return;
                if (y + 1 < ix.p) out[w] = new Object[]{c, y + 1, childUb};
            };
            if (work.size() < inlineBelow) for (int w = 0; w < work.size(); w++) task.accept(w);
            else pool.submit(() -> java.util.stream.IntStream.range(0, work.size()).parallel().forEach(task)).join();
            for (Object[] o : out)
                if (o != null && (!full || ((Node) o[0]).expSup > theta)) pq.add(o);
        }
    }

    /**
     * Best-first tuần tự với ngân sách phép mở rộng. Trả về true nếu đã khai phá xong
     * (hàng đợi rỗng hoặc đỉnh ≤ θ, Bổ đề 4.6), khi đó không cần pha song song.
     */
    private boolean warmup(int budget) {
        Set<String> keys = new HashSet<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>((a, b) -> Double.compare(((Node) b[0]).expSup, ((Node) a[0]).expSup));
        for (int a = 0; a < ix.p; a++) {
            Node n = new Node(new int[]{a}, ix.tids[a], ix.probs[a], ix.expSup1[a]);
            n.sumSq = ix.sumSq1[a];
            pq.add(new Object[]{n, a + 1, initialPairUb(a)});
        }
        int used = 0;
        while (!pq.isEmpty()) {
            Object[] top = pq.peek();
            Node x = (Node) top[0];
            if (full && x.expSup <= theta) { warmKeys = keys; return true; }
            if (used >= budget) break;
            pq.poll();
            int from = (Integer) top[1];
            double[] pairUb = (double[]) top[2];
            for (int y = from; y < ix.p; y++) {
                if (full && upperBound(x, y, pairUb) <= theta) { pairPruned.increment(); continue; }
                double[] childUb = childPairUb(pairUb, y);
                int size = x.items.length + 1;
                if (size == 2 && cfg.pairInit && cfg.pairBound
                        && !hasViableExtension(ix.pair[x.items[0]][y], y + 1, childUb)) continue;
                candidates.increment();
                used++;
                Node c = extend(x, y);
                if (c == null || c.expSup <= 0) continue;
                if (!(size == 2 && cfg.pairInit) && (!full || c.expSup > theta)) {
                    offerSerial(c.items, c.expSup);
                    keys.add(key(c.items));
                }
                if (full && c.expSup <= theta) continue;
                if (y + 1 < ix.p) pq.add(new Object[]{c, y + 1, childUb});
            }
        }
        warmKeys = keys;
        return pq.isEmpty();
    }

    private static final class Node {
        final int[] items; final int[] tids; final double[] probs; final double expSup;
        double sumSq;
        Node(int[] items, int[] tids, double[] probs, double expSup) {
            this.items = items; this.tids = tids; this.probs = probs; this.expSup = expSup;
        }
    }

    private double[] initialPairUb(int a) {
        double[] ub = new double[ix.p];
        if (cfg.pairBound) System.arraycopy(ix.pair[a], 0, ub, 0, ix.p);
        else Arrays.fill(ub, Double.MAX_VALUE);
        return ub;
    }

    private double[] childPairUb(double[] parentUb, int y) {
        if (!cfg.pairBound) return parentUb;
        double[] ub = new double[ix.p];
        double[] row = ix.pair[y];
        for (int z = y + 1; z < ix.p; z++) ub[z] = Math.min(parentUb[z], row[z]);
        return ub;
    }

    private double upperBound(double es, int y, double[] pairUb) {
        double ub = Math.min(es, pairUb[y]);
        if (cfg.maxProbBound) ub = Math.min(ub, es * ix.maxProb[y]);
        return ub;
    }

    private double upperBound(Node x, int y, double[] pairUb) {
        double ub = upperBound(x.expSup, y, pairUb);
        if (cfg.csBound && x.sumSq > 0) ub = Math.min(ub, Math.sqrt(x.sumSq * ix.sumSq1[y]));
        return ub;
    }

    private boolean hasViableExtension(double es, int from, double[] pairUb) {
        if (!full) return from < ix.p;
        double th = theta;
        for (int z = from; z < ix.p; z++) if (upperBound(es, z, pairUb) > th) return true;
        return false;
    }

    /**
     * Mở rộng nút X với các item y ≥ from. Nếu split = true (X là 1-itemset), mỗi nút con được
     * đẩy thành một tác vụ ForkJoin riêng; sâu hơn thì đệ quy tuần tự trong luồng hiện tại.
     */
    private void expand(Node x, int from, double[] pairUb, boolean split) {
        List<RecursiveAction> subtasks = split ? new ArrayList<>() : null;
        for (int y = from; y < ix.p; y++) {
            if (full && x.expSup <= theta) break;
            if (full && upperBound(x, y, pairUb) <= theta) { pairPruned.increment(); continue; }
            double[] childUb = childPairUb(pairUb, y);
            int size = x.items.length + 1;
            if (size == 2 && cfg.pairInit && cfg.pairBound
                    && !hasViableExtension(ix.pair[x.items[0]][y], y + 1, childUb)) continue;
            if (split) {
                final int fy = y;
                final double ubVal = upperBound(x, y, pairUb);
                subtasks.add(new RecursiveAction() {
                    @Override protected void compute() {
                        if (full && ubVal <= theta) { pairPruned.increment(); return; } // θ có thể đã tăng
                        child(x, fy, childUb, size);
                    }
                });
            } else {
                child(x, y, childUb, size);
            }
        }
        if (split && !subtasks.isEmpty()) RecursiveAction.invokeAll(subtasks);
    }

    private void child(Node x, int y, double[] childUb, int size) {
        candidates.increment();
        Node c = extend(x, y);
        if (c == null || c.expSup <= 0) return;
        if (!(size == 2 && cfg.pairInit)) offer(c.items, c.expSup);
        if (full && c.expSup <= theta) return;
        expand(c, y + 1, childUb, false);
    }

    private Node extend(Node x, int y) {
        intersections.increment();
        double th = full ? theta : -1;
        double maxP = ix.maxProb[y];
        int n = x.tids.length;
        int[] outT = new int[Math.min(n, ix.tids[y].length)];
        double[] outP = new double[outT.length];
        int m = 0;
        double acc = 0, sq = 0, remaining = x.expSup;
        double[] col = ix.dense[y];
        if (col != null) {
            for (int j = 0; j < n; j++) {
                int t = x.tids[j];
                double px = x.probs[j], py = col[t];
                if (py > 0) { double v = px * py; outT[m] = t; outP[m] = v; m++; acc += v; sq += v * v; }
                remaining -= px;
                if (cfg.earlyAbandon && (j & 63) == 63) {
                    if (th < 0 && full) th = theta;
                    if (th >= 0 && acc + remaining * maxP <= th) { abandoned.increment(); return null; }
                }
            }
        } else {
            int[] ty = ix.tids[y];
            double[] py = ix.probs[y];
            int i = 0, j = 0, steps = 0;
            while (i < n && j < ty.length) {
                int a = x.tids[i], b = ty[j];
                if (a == b) { double v = x.probs[i] * py[j]; outT[m] = a; outP[m] = v; m++; acc += v; sq += v * v; remaining -= x.probs[i]; i++; j++; }
                else if (a < b) { remaining -= x.probs[i]; i++; }
                else j++;
                if (cfg.earlyAbandon && (++steps & 63) == 0) {
                    if (th < 0 && full) th = theta;
                    if (th >= 0 && acc + Math.max(0, remaining) * maxP <= th) { abandoned.increment(); return null; }
                }
            }
        }
        int[] items = Arrays.copyOf(x.items, x.items.length + 1);
        items[x.items.length] = y;
        Node res = new Node(items, Arrays.copyOf(outT, m), Arrays.copyOf(outP, m), acc);
        res.sumSq = sq;
        return res;
    }
}
