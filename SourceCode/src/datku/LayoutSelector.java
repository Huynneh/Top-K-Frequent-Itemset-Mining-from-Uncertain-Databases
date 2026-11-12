package datku;

import java.util.ArrayList;
import java.util.List;

/**
 * Chọn bố cục TID cho từng item theo mô hình chi phí có biết ngưỡng θ (θ-aware).
 *
 * Sau khi heap đã được lấp bằng 1- và 2-itemset chính xác, ngưỡng θ1 đã biết. Một phép mở rộng
 * X → X∪{y} chỉ xảy ra khi mọi cặp {x,y} (x ∈ X) có expSup > θ1 (Bổ đề cận theo cặp), nên
 *   e(y) = #{ x ≠ y : pair[x][y] > θ1 }
 * là một ước lượng số tiền tố sẽ được mở rộng bằng y. Mỗi lần mở rộng, cột đặc tiết kiệm khoảng
 * |T(y)| bước merge-join, còn chi phí dựng cột là n phép ghi và 8n byte. Do đó:
 *   dùng cột đặc cho y  ⇔  e(y)·|T(y)| ≥ n   (tức e(y)·ρ(y) ≥ 1),
 * ưu tiên item có lợi ích e(y)·|T(y)| lớn nhất, trong giới hạn bộ nhớ budgetBytes.
 * Khác với ngưỡng cố định ρ(y) ≥ r, quyết định này phụ thuộc cả k (qua θ1) và cấu trúc đồng xuất hiện.
 */
public final class LayoutSelector {

    private LayoutSelector() {}

    /** Trả về số item được chuyển sang cột đặc. */
    public static int apply(VerticalIndex ix, double theta, long budgetBytes) {
        int p = ix.p;
        long n = ix.numTransactions;
        if (n == 0 || ix.pair == null) return 0;
        List<long[]> cand = new ArrayList<>();
        for (int y = 0; y < p; y++) {
            if (ix.dense[y] != null) continue;
            long e = 0;
            double[] row = ix.pair[y];
            for (int x = 0; x < p; x++) if (x != y && row[x] > theta) e++;
            long benefit = e * ix.tids[y].length;
            if (e > 0 && benefit >= n) cand.add(new long[]{benefit, y});
        }
        cand.sort((a, b) -> Long.compare(b[0], a[0]));
        long used = 0;
        int made = 0;
        for (long[] c : cand) {
            if (used + 8 * n > budgetBytes) break;
            ix.makeDense((int) c[1]);
            used += 8 * n;
            made++;
        }
        return made;
    }

    public static long defaultBudget() {
        return Runtime.getRuntime().maxMemory() / 4;
    }
}
