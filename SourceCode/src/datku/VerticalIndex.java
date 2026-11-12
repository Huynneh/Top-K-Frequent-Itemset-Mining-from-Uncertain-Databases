package datku;

import model.Transaction;
import model.UncertainDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Chỉ mục dọc (vertical index) dựng bằng ĐÚNG MỘT lần quét database — sau bước này
 * không thuật toán nào trong gói datku đọc lại database nữa.
 *
 * Nội dung:
 *  1. expSup của mọi 1-itemset, sắp giảm dần; ngưỡng ban đầu θ0 = expSup lớn thứ k.
 *  2. Tập item triển vọng P = { i : expSup(i) ≥ θ0 } — do tính phản đơn điệu, mọi itemset
 *     thuộc top-k chỉ chứa item trong P. Item được đánh lại chỉ số 0..|P|-1 theo expSup giảm dần.
 *  3. U-tidset (TID tăng dần + xác suất) của từng item trong P.
 *  4. Ma trận expSup CHÍNH XÁC của mọi 2-itemset trong P (tính trong cùng lần quét thứ hai
 *     trên bộ nhớ, không đọc lại file) — dùng để (a) lấp đầy heap top-k sớm, (b) cận trên
 *     expSup(X ∪ {y}) ≤ min_{x∈X} expSup({x,y}).
 *  5. Bố cục theo MẬT ĐỘ của từng item: nếu |T(y)|/n ≥ denseRatio thì item y được lưu thêm
 *     dạng mảng đặc prob[t] (tra cứu O(1)), ngược lại chỉ giữ danh sách (TID, p) để merge-join.
 *     Mảng đặc tốn 8n byte, danh sách tốn 12|T(y)| byte (int + double), nên với
 *     denseRatio = r, bộ nhớ cột đặc tối đa bằng 2/(3r) lần danh sách (r = 2/3 thì không tốn thêm).
 */
public class VerticalIndex {

    public final int numTransactions;
    public final int numDistinctItems;
    public final double avgTransactionLength;

    /** Số item triển vọng |P|. */
    public final int p;
    /** originalItem[r] = id item gốc của item có hạng r (r = 0 là expSup lớn nhất). */
    public final int[] originalItem;
    public final double[] expSup1;
    public final double[] maxProb;
    /** sumSq1[r] = Σ_t p(r,t)², dùng cho cận Cauchy–Schwarz. */
    public final double[] sumSq1;
    public final int[][] tids;
    public final double[][] probs;
    /** dense[r] != null nếu item r dùng bố cục mảng đặc. */
    public final double[][] dense;
    /** pair[a][b] = expSup({a,b}) chính xác, a,b là hạng trong P. */
    public final double[][] pair;

    public final long buildNanos;
    /** Thời gian riêng của bước dựng ma trận cặp (ns). */
    public final long pairNanos;

    public VerticalIndex(UncertainDatabase db, int k, double denseRatio, boolean computePairs) {
        this(db, k, denseRatio, computePairs, 1);
    }

    /**
     * threads > 1: ma trận cặp được dựng song song THEO HÀNG. Mỗi giao dịch được chiếu lên P và lưu
     * dạng CSR với item sắp theo hạng; hàng a chỉ cộng vào pair[a][b] với b > a, duyệt qua đúng các
     * giao dịch chứa a, nên mỗi luồng chỉ ghi vào hàng của mình, không cần khóa. Nửa dưới được chép
     * đối xứng sau đó. Tổng số phép nhân bằng bản tuần tự.
     */
    public VerticalIndex(UncertainDatabase db, int k, double denseRatio, boolean computePairs, int threads) {
        long start = System.nanoTime();
        List<Transaction> trans = db.getTransactions();
        this.numTransactions = trans.size();

        // --- Lần duyệt 1 trên danh sách giao dịch: expSup của từng item gốc ---
        int maxId = 0;
        long totalLen = 0;
        for (Transaction t : trans) {
            totalLen += t.items.length;
            for (int it : t.items) if (it > maxId) maxId = it;
        }
        double[] sup = new double[maxId + 1];
        int[] count = new int[maxId + 1];
        for (Transaction t : trans) {
            for (int i = 0; i < t.items.length; i++) {
                sup[t.items[i]] += t.probs[i];
                count[t.items[i]]++;
            }
        }
        int distinct = 0;
        for (int c : count) if (c > 0) distinct++;
        this.numDistinctItems = distinct;
        this.avgTransactionLength = trans.isEmpty() ? 0 : (double) totalLen / trans.size();

        // Sắp item theo expSup giảm dần, tính θ0 = expSup lớn thứ k
        Integer[] order = new Integer[distinct];
        int w = 0;
        for (int id = 0; id <= maxId; id++) if (count[id] > 0) order[w++] = id;
        Arrays.sort(order, (a, b) -> Double.compare(sup[b], sup[a]));
        double theta0 = distinct >= k ? sup[order[k - 1]] : 0.0;

        int pCount = 0;
        while (pCount < distinct && sup[order[pCount]] >= theta0) pCount++;
        this.p = pCount;
        this.originalItem = new int[p];
        this.expSup1 = new double[p];
        int[] rankOf = new int[maxId + 1];
        Arrays.fill(rankOf, -1);
        for (int r = 0; r < p; r++) {
            originalItem[r] = order[r];
            expSup1[r] = sup[order[r]];
            rankOf[order[r]] = r;
        }

        // --- Lần duyệt 2 (trên bộ nhớ): dựng U-tidset + ma trận cặp ---
        int[] fill = new int[p];
        this.tids = new int[p][];
        this.probs = new double[p][];
        for (int r = 0; r < p; r++) {
            tids[r] = new int[count[order[r]]];
            probs[r] = new double[count[order[r]]];
        }
        this.maxProb = new double[p];
        this.sumSq1 = new double[p];
        this.pair = computePairs ? new double[p][p] : null;
        boolean par = computePairs && threads > 1;
        boolean seqPairs = computePairs && !par;
        // CSR của các giao dịch đã chiếu lên P (chỉ dùng cho bản song song)
        int[] rowStart = par ? new int[numTransactions + 1] : null;
        int total = 0;
        if (par) for (int r = 0; r < p; r++) total += count[order[r]];
        int[] csrR = par ? new int[total] : null;
        double[] csrP = par ? new double[total] : null;
        int[][] posOf = par ? new int[p][] : null;
        if (par) for (int r = 0; r < p; r++) posOf[r] = new int[count[order[r]]];
        int csr = 0;
        int[] rowR = new int[64];
        double[] rowP = new double[64];
        int tid = 0;
        for (Transaction t : trans) {
            int m = 0;
            if (rowR.length < t.items.length) {
                rowR = new int[t.items.length];
                rowP = new double[t.items.length];
            }
            for (int i = 0; i < t.items.length; i++) {
                int r = rankOf[t.items[i]];
                if (r < 0) continue;
                double pr = t.probs[i];
                tids[r][fill[r]] = tid;
                probs[r][fill[r]] = pr;
                fill[r]++;
                if (pr > maxProb[r]) maxProb[r] = pr;
                sumSq1[r] += pr * pr;
                rowR[m] = r;
                rowP[m] = pr;
                m++;
            }
            if (par) {
                rowStart[tid] = csr;
                // sắp chèn theo hạng (giao dịch ngắn)
                for (int a = 1; a < m; a++) {
                    int rr = rowR[a]; double pp = rowP[a]; int b = a - 1;
                    while (b >= 0 && rowR[b] > rr) { rowR[b + 1] = rowR[b]; rowP[b + 1] = rowP[b]; b--; }
                    rowR[b + 1] = rr; rowP[b + 1] = pp;
                }
                for (int a = 0; a < m; a++) {
                    int r = rowR[a];
                    // vị trí của lần xuất hiện này trong danh sách của r: fill[r] đã tăng ở trên, và các
                    // TID của r được thêm theo thứ tự giao dịch, nên đây là phần tử cuối cùng
                    posOf[r][fill[r] - 1] = csr;
                    csrR[csr] = r; csrP[csr] = rowP[a]; csr++;
                }
            }
            if (seqPairs) {
                for (int a = 0; a < m; a++) {
                    double[] rowA = pair[rowR[a]];
                    double pa = rowP[a];
                    for (int b = a + 1; b < m; b++) {
                        double v = pa * rowP[b];
                        rowA[rowR[b]] += v;
                        pair[rowR[b]][rowR[a]] += v;
                    }
                }
            }
            tid++;
        }
        long pairStart = System.nanoTime();
        if (par) {
            rowStart[numTransactions] = csr;
            final int[] fRowStart = rowStart, fCsrR = csrR;
            final double[] fCsrP = csrP;
            final int[][] fPos = posOf;
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                pool.submit(() -> IntStream.range(0, p).parallel().forEach(a -> {
                    double[] rowA = pair[a];
                    int[] ta = tids[a];
                    double[] pa = probs[a];
                    int[] pos = fPos[a];
                    for (int j = 0; j < ta.length; j++) {
                        double v = pa[j];
                        int end = fRowStart[ta[j] + 1];
                        for (int q = pos[j] + 1; q < end; q++) rowA[fCsrR[q]] += v * fCsrP[q];
                    }
                })).join();
                pool.submit(() -> IntStream.range(0, p).parallel().forEach(a -> {
                    double[] rowA = pair[a];
                    for (int b = a + 1; b < p; b++) pair[b][a] = rowA[b];
                })).join();
            } finally {
                pool.shutdown();
            }
        }
        this.pairNanos = System.nanoTime() - pairStart;

        // --- Chọn bố cục theo mật độ của từng item ---
        this.dense = new double[p][];
        for (int r = 0; r < p; r++) {
            if (numTransactions > 0 && (double) tids[r].length / numTransactions >= denseRatio) {
                double[] col = new double[numTransactions];
                for (int j = 0; j < tids[r].length; j++) col[tids[r][j]] = probs[r][j];
                dense[r] = col;
            }
        }
        this.buildNanos = System.nanoTime() - start;
    }

    /** Chuyển item r sang bố cục cột đặc (dùng bởi LayoutSelector). */
    public void makeDense(int r) {
        if (dense[r] != null) return;
        double[] col = new double[numTransactions];
        for (int j = 0; j < tids[r].length; j++) col[tids[r][j]] = probs[r][j];
        dense[r] = col;
    }

    /** Mật độ cấu trúc ATL/|I| (cùng công thức Hybrid-UFHM ở chuyên đề 3). */
    public double structuralDensity() {
        return numDistinctItems == 0 ? 0 : avgTransactionLength / numDistinctItems;
    }

    public int denseItemCount() {
        int c = 0;
        for (double[] d : dense) if (d != null) c++;
        return c;
    }

    public int[] toOriginal(int[] ranks) {
        int[] out = new int[ranks.length];
        for (int i = 0; i < ranks.length; i++) out[i] = originalItem[ranks[i]];
        Arrays.sort(out);
        return out;
    }

    /** Danh sách hạng item, tiện cho vòng lặp. */
    public List<Integer> ranks() {
        List<Integer> l = new ArrayList<>(p);
        for (int r = 0; r < p; r++) l.add(r);
        return l;
    }
}
