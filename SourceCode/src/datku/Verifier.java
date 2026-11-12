package datku;

import model.ItemsetResult;
import model.Transaction;
import model.UncertainDatabase;

import java.util.*;

/**
 * Bộ kiểm chứng ĐỘC LẬP với các thuật toán top-k:
 *  1. Tính lại expSup của từng itemset trả về bằng quét ngang trực tiếp (định nghĩa gốc).
 *  2. Khai phá ĐẦY ĐỦ mọi itemset có expSup ≥ ngưỡng bằng Eclat thường (không top-k),
 *     rồi so dãy k giá trị lớn nhất với dãy giá trị thuật toán trả về.
 * So theo giá trị (không theo itemset) để không phụ thuộc cách phá hòa ở vị trí thứ k.
 */
public class Verifier {

    public static final double EPS = 1e-6;

    public static String check(UncertainDatabase db, int k, List<ItemsetResult> got) {
        // (1) expSup trả về phải khớp định nghĩa
        for (ItemsetResult r : got) {
            double direct = directExpSup(db, r.itemset);
            if (Math.abs(direct - r.expSup) > EPS * Math.max(1, direct)) {
                return "SAI expSup của " + r.itemsetString() + ": báo " + r.expSup + ", đúng " + direct;
            }
        }
        if (got.isEmpty()) return "OK (rỗng)";
        // (2) top-k giá trị phải khớp khai phá đầy đủ
        double minGot = got.get(got.size() - 1).expSup;
        List<Double> all = completeMine(db, minGot - EPS);
        all.sort(Collections.reverseOrder());
        int n = Math.min(k, all.size());
        if (got.size() != n) return "SAI số lượng: trả " + got.size() + ", mong đợi " + n;
        for (int i = 0; i < n; i++) {
            if (Math.abs(all.get(i) - got.get(i).expSup) > EPS * Math.max(1, all.get(i))) {
                return "SAI hạng " + (i + 1) + ": trả " + got.get(i).expSup + ", mong đợi " + all.get(i);
            }
        }
        return "OK";
    }

    public static double directExpSup(UncertainDatabase db, int[] itemset) {
        double s = 0;
        for (Transaction t : db.getTransactions()) {
            double p = 1;
            for (int it : itemset) {
                int idx = Arrays.binarySearch(t.items, it);
                if (idx < 0) { p = 0; break; }
                p *= t.probs[idx];
            }
            s += p;
        }
        return s;
    }

    /** Eclat đầy đủ với ngưỡng cố định minSup trên U-tidset; trả về danh sách expSup. */
    public static List<Double> completeMine(UncertainDatabase db, double minSup) {
        Map<Integer, List<double[]>> lists = new TreeMap<>();
        int tid = 0;
        for (Transaction t : db.getTransactions()) {
            for (int i = 0; i < t.items.length; i++)
                lists.computeIfAbsent(t.items[i], z -> new ArrayList<>()).add(new double[]{tid, t.probs[i]});
            tid++;
        }
        List<int[]> tl = new ArrayList<>();
        List<double[]> pl = new ArrayList<>();
        List<Double> out = new ArrayList<>();
        for (List<double[]> l : lists.values()) {
            int[] ts = new int[l.size()];
            double[] ps = new double[l.size()];
            double s = 0;
            for (int j = 0; j < l.size(); j++) { ts[j] = (int) l.get(j)[0]; ps[j] = l.get(j)[1]; s += ps[j]; }
            if (s >= minSup) { tl.add(ts); pl.add(ps); out.add(s); }
        }
        for (int a = 0; a < tl.size(); a++) eclat(tl.get(a), pl.get(a), a, tl, pl, minSup, out);
        return out;
    }

    private static void eclat(int[] t, double[] p, int last, List<int[]> tl, List<double[]> pl,
                              double minSup, List<Double> out) {
        for (int b = last + 1; b < tl.size(); b++) {
            int[] tb = tl.get(b);
            double[] pb = pl.get(b);
            int[] nt = new int[Math.min(t.length, tb.length)];
            double[] np = new double[nt.length];
            int i = 0, j = 0, m = 0;
            double s = 0;
            while (i < t.length && j < tb.length) {
                if (t[i] == tb[j]) { nt[m] = t[i]; np[m] = p[i] * pb[j]; s += np[m]; m++; i++; j++; }
                else if (t[i] < tb[j]) i++;
                else j++;
            }
            if (s >= minSup) {
                out.add(s);
                eclat(Arrays.copyOf(nt, m), Arrays.copyOf(np, m), b, tl, pl, minSup, out);
            }
        }
    }
}
